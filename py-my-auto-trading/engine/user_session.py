import time
import asyncio
import logging
import aiohttp
import re
from collections import deque
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List

from brokers.kis_broker import KISBroker
from brokers.kiwoom_broker import KiwoomBroker
from brokers.upbit_broker import UpbitBroker
from brokers.meritz_broker import MeritzBroker
from database.firebase_client import FirebaseClient
from engine.dual_momentum_controller import DualMomentumController
from models.schemas import Settings, AccountData, OrderCommand, ConditionalOrder, StopLossOrder
import crypto_utils

logger = logging.getLogger("UserSession")

def _task_error_callback(task: asyncio.Task):
    if task.cancelled():
        return
    exc = task.exception()
    if exc:
        logger.error(f"Background task failed: {exc}", exc_info=exc)

class UserSession:
    def __init__(self, uid: str, fb_client: FirebaseClient, service_type: str = "stock"):
        self.uid = uid
        self.service_type = service_type
        self.fb = fb_client
        self.broker = None
        self.dm_controller = None
        self.settings = Settings()
        self.account_data = AccountData()
        self.conditional_orders: Dict[str, ConditionalOrder] = {}
        self.stop_loss_orders: Dict[str, StopLossOrder] = {}
        
        self.stock_names: Dict[str, str] = {}
        
        self.price_data: Dict[str, float] = {}
        self.price_change_data: Dict[str, float] = {}
        self.price_history: Dict[str, deque] = {}
        self.last_surge_buy_time: Dict[str, float] = {}
        
        self._main_task = None
        self._restart_task = None
        self._running = False
        self._loop = asyncio.get_event_loop()
        self._last_rest_price = 0.0
        self.active_account_id = None

    @property
    def account_path(self):
        if self.active_account_id:
            return f"{self.base_path}/accounts/{self.active_account_id}"
        return self.base_path

    @property
    def base_path(self):
        """Firebase 경로 기본 프리픽스: users/{uid}/{service_type}"""
        return f"users/{self.uid}/{self.service_type}"

    def _update_account_listeners(self, old_id, new_id):
        old_path = f"{self.base_path}/accounts/{old_id}" if old_id else self.base_path
        new_path = f"{self.base_path}/accounts/{new_id}" if new_id else self.base_path
        
        paths = ["settings", "manual_orders", "conditional_orders", "stop_loss_orders"]
        for p in paths:
            self.fb.remove_listener(f"{old_path}/{p}")
            
        self.fb.listen(f"{new_path}/settings", self._on_settings_change)
        self.fb.listen(f"{new_path}/manual_orders", self._on_manual_order)
        self.fb.listen(f"{new_path}/conditional_orders", self._on_conditional_orders_change)
        self.fb.listen(f"{new_path}/stop_loss_orders", self._on_stop_loss_orders_change)

    async def start(self):
        logger.info(f"Starting session for user: {self.uid}")
        self._running = True
        
        # Load initial config
        self._load_account_data()
        self.active_account_id = self.account_data.active_account_id
        
        self._load_settings()
        
        # Register firebase listeners
        self.fb.listen(f"{self.base_path}/account_data", self._on_account_change)
        
        new_path = self.account_path
        self.fb.listen(f"{new_path}/settings", self._on_settings_change)
        self.fb.listen(f"{new_path}/manual_orders", self._on_manual_order)
        self.fb.listen(f"{new_path}/conditional_orders", self._on_conditional_orders_change)
        self.fb.listen(f"{new_path}/stop_loss_orders", self._on_stop_loss_orders_change)
        
        self._setup_broker()
        
        # Start async loop
        self._main_task = asyncio.create_task(self._session_loop())
        self._main_task.add_done_callback(_task_error_callback)

    async def _cleanup_broker(self, broker):
        if not broker:
            return
        try:
            await broker.close_websocket()
        except Exception:
            pass
        try:
            if hasattr(broker, "close"):
                await broker.close()
        except Exception:
            pass

    def _remove_all_listeners(self):
        try:
            self.fb.remove_listener(f"{self.base_path}/account_data")
            paths = ["settings", "manual_orders", "conditional_orders", "stop_loss_orders"]
            for p in paths:
                self.fb.remove_listener(f"{self.account_path}/{p}")
        except Exception as e:
            logger.error(f"Error removing listeners for {self.uid}: {e}")

    async def stop(self):
        logger.info(f"Stopping session for user: {self.uid}")
        self._running = False
        if self._main_task:
            self._main_task.cancel()
        if self._restart_task:
            self._restart_task.cancel()
        if self.broker:
            await self._cleanup_broker(self.broker)
        
        await asyncio.to_thread(self._remove_all_listeners)

    def _load_account_data(self):
        data = self.fb.get_data(f"{self.base_path}/account_data")
        if data:
            self.account_data = AccountData(**data)

    def _load_settings(self):
        data = self.fb.get_data(f"{self.account_path}/settings")
        if data:
            self.settings = Settings(**data)

    def _get_active_symbols(self):
        symbols = set(s for s, v in self.settings.watch_symbols.items() if v.enabled)
        laore = self.fb.get_data(f"{self.account_path}/laore_settings") or {}
        if isinstance(laore, dict):
            for k, cfg in laore.items():
                if isinstance(cfg, dict) and cfg.get("enabled", False):
                    parts = k.split("_", 1)
                    sym = parts[1] if len(parts) > 1 else k
                    symbols.add(sym)
        if self.service_type == "stock":
            symbols.add("SOXL")
            symbols.add("SOXS")
        logger.info(f"ACTIVE SYMBOLS: {symbols}")
        return list(symbols)

    def _load_initial_price_data(self):
        status_data = self.fb.get_data(f"{self.account_path}/status")
        if status_data and isinstance(status_data, dict) and "stock_prices" in status_data:
            for sym, p in status_data["stock_prices"].items():
                if sym in self.settings.watch_symbols:
                    self.price_data[sym] = float(p)

    def _setup_broker(self):
        active_id = self.account_data.active_account_id
        if active_id and active_id in self.account_data.saved_accounts:
            acc = self.account_data.saved_accounts[active_id]
            old_broker = self.broker
            if acc.broker in ("KIS", "한국투자", "한국투자증권"):
                if old_broker:
                    self._loop.create_task(self._cleanup_broker(old_broker)).add_done_callback(_task_error_callback)
                    
                is_mock = getattr(acc, "is_mock", getattr(acc, "is_kis_mock", False))
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                self.broker = KISBroker(decrypted_key, decrypted_secret, acc.account_number, is_mock=is_mock, is_overseas=acc.is_overseas)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id}) - Mock: {is_mock}, OS: {acc.is_overseas}")
                
                self._load_initial_price_data()
                        
                self._last_balance_check = 0.0 
                self._restart_websocket()
            elif acc.broker in ("키움", "Kiwoom", "키움증권"):
                if old_broker:
                    self._loop.create_task(self._cleanup_broker(old_broker)).add_done_callback(_task_error_callback)
                is_mock = getattr(acc, "is_mock", True)
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                
                self.broker = KiwoomBroker(decrypted_key, decrypted_secret, acc.account_number, is_mock=is_mock)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id}) - Mock: {is_mock}")
                
                self._load_initial_price_data()
                            
                self._last_balance_check = 0.0
                self._restart_websocket()
            elif acc.broker in ("업비트", "Upbit"):
                if old_broker:
                    self._loop.create_task(self._cleanup_broker(old_broker)).add_done_callback(_task_error_callback)
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                self.broker = UpbitBroker(decrypted_key, decrypted_secret)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id})")

                self._load_initial_price_data()

                self._last_balance_check = 0.0
                self._restart_websocket()
            elif acc.broker in ("메리츠", "Meritz", "메리츠증권"):
                if old_broker:
                    self._loop.create_task(self._cleanup_broker(old_broker)).add_done_callback(_task_error_callback)
                is_mock = getattr(acc, "is_mock", False)
                decrypted_key = crypto_utils.decrypt(acc.app_key).strip() if acc.app_key else ""
                decrypted_secret = crypto_utils.decrypt(acc.app_secret).strip() if acc.app_secret else ""
                self.broker = MeritzBroker(decrypted_key, decrypted_secret, acc.account_number, is_mock=is_mock, is_overseas=acc.is_overseas)
                logger.info(f"[{self.uid}] Broker configured: {acc.broker} ({active_id}) - Mock: {is_mock}, OS: {acc.is_overseas}")

                self._load_initial_price_data()

                self._last_balance_check = 0.0
                self._restart_websocket()
            else:
                logger.info(f"[{self.uid}] Unsupported broker selected: {acc.broker}")
                if old_broker:
                    self._loop.create_task(self._cleanup_broker(old_broker)).add_done_callback(_task_error_callback)
                    self.broker = None
                
                self.price_data.clear()
                
                self.fb.update_data(f"{self.account_path}/status", {
                    "total_balance": 0.0,
                    "current_yield": 0.0,
                    "holdings": {},
                    "stock_prices": {}
                })

        # Dual Momentum(퀀트 스윙)은 오직 pisety 사용자의 모의 해외 계좌(한국투자_MOCK_OS)에서만 가동
        # 실전 계좌(메리츠, 키움, 한투 실전) 및 서브 계정(user_sub1, user_sub2 등)은 원천적으로 컨트롤러 생성 및 실행 차단
        if self.service_type == "stock" and self.uid == "pisety" and active_id == "한국투자_MOCK_OS":
            self.dm_controller = DualMomentumController(self.uid, active_id, self.fb, self)
            self.dm_controller.initialize()
        else:
            self.dm_controller = None

    def _restart_websocket(self):
        if self.broker:
            if self._restart_task and not self._restart_task.done():
                self._restart_task.cancel()
            self._restart_task = self._loop.create_task(self._do_restart_websocket())
            self._restart_task.add_done_callback(_task_error_callback)

    async def _do_restart_websocket(self):
        await self.broker.close_websocket()
        symbols = self._get_active_symbols()
            
        if symbols:
            try:
                initial_prices = await self.broker.fetch_rest_current_prices(symbols)
                logger.info(f"initial_prices for {symbols}: {initial_prices}")
                for sym, price in initial_prices.items():
                    if isinstance(price, (tuple, list)):
                        self._on_ws_price(sym, price[0], price[1])
                    else:
                        self.price_data[sym] = price
            except Exception as e:
                logger.error(f"Failed to fetch initial prices via REST: {e}")
                
            await self.broker.connect_websocket(symbols, self._on_ws_price)

    def _on_ws_price(self, symbol: str, price: float, change_rate: float = None):
        if not symbol: return
        self.price_data[symbol] = price
        if change_rate is not None:
            self.price_change_data[symbol] = round(change_rate, 2)
            
        if symbol not in self.price_history:
            self.price_history[symbol] = deque(maxlen=120)
        self.price_history[symbol].append((time.time(), price))

    def _on_settings_change(self, event):
        if event.data is not None:
            self._loop.call_soon_threadsafe(self._handle_settings_update)
            
    def _handle_settings_update(self):
        self._load_settings()
        
        active_symbols = self._get_active_symbols()
        removed_symbols = [s for s in self.price_data.keys() if s not in active_symbols]
        if removed_symbols:
            logger.info(f"Removing ghost symbols: {removed_symbols} from {list(self.price_data.keys())} vs {active_symbols}")
        for s in removed_symbols:
            self.price_data.pop(s, None)
            self.price_change_data.pop(s, None)
            
        self._last_rest_price = 0.0
        self._restart_websocket()
            
    def _on_account_change(self, event):
        self._loop.call_soon_threadsafe(self._handle_account_update)

    def _handle_account_update(self):
        old_id = getattr(self, "active_account_id", None)
        self._load_account_data()
        new_id = self.account_data.active_account_id
        
        if old_id != new_id:
            logger.info(f"[{self.uid}] Active account changed from {old_id} to {new_id}")
            self._update_account_listeners(old_id, new_id)
            self.active_account_id = new_id
            self._load_settings()
            
            self.price_data.clear()
            self.price_change_data.clear()
            self.price_history.clear()
            self.stock_names.clear()
            
        self._setup_broker()

    def _on_manual_order(self, event):
        data = event.data
        if isinstance(data, dict):
            cmd = data.get("command", "NONE")
            if cmd in ["BUY", "SELL"]:
                sym = data.get("symbol")
                qty = float(data.get("quantity", 1))
                raw_price = data.get("target_price") if data.get("target_price") is not None else data.get("price", 0.0)
                price = float(raw_price) if raw_price else 0.0
                asyncio.run_coroutine_threadsafe(self._execute_manual_order(cmd, sym, qty, price), self._loop)

    async def _execute_manual_order(self, cmd, sym, qty, price):
        if not self.broker:
            self.fb.update_data(f"{self.account_path}/manual_orders", {
                "command": "NONE",
                "status": "FAILED",
                "error_message": "활성화된 증권사/거래소 브로커가 없습니다."
            })
            return

        order_price = float(price) if price and price > 0.0 else 0.0

        # 시장가(price <= 0) 요청 시 브로커별 처리:
        # 1) 국내주식(KIS/메리츠/키움) 및 메리츠 해외주식, 업비트 매도(SELL)는 price=0.0 시장가 주문을 직접 지원
        # 2) KIS 해외주식은 지정가만 지원하므로 현재가를 조회하여 지정가로 전송
        # 3) 업비트 시장가 매수(BUY)는 총 매수금액(qty * current_price)이 필요하므로 현재가 조회
        is_kis_overseas = (self.broker.__class__.__name__ == "KISBroker" and getattr(self.broker, "is_overseas", False))
        is_upbit_market_buy = (self.service_type == "crypto" and cmd == "BUY" and order_price <= 0.0)

        if order_price <= 0.0 and (is_kis_overseas or is_upbit_market_buy):
            cur_p = self.price_data.get(sym)
            if not cur_p or cur_p <= 0.0:
                try:
                    fetched = await self.broker.fetch_rest_current_prices([sym])
                    val = fetched.get(sym)
                    if isinstance(val, (tuple, list)):
                        cur_p = float(val[0])
                    elif val:
                        cur_p = float(val)
                except Exception as e:
                    logger.warning(f"[{self.uid}] Failed to fetch fallback price for manual order {sym}: {e}")

            if not cur_p or cur_p <= 0.0:
                logger.warning(f"[{self.uid}] Cannot execute manual order: No price data for {sym}")
                self.fb.update_data(f"{self.account_path}/manual_orders", {
                    "command": "NONE",
                    "status": "FAILED",
                    "error_message": f"[{sym}] 현재가를 조회할 수 없어 주문을 실행하지 못했습니다."
                })
                return

            if is_upbit_market_buy:
                qty = float(qty) * cur_p
                order_price = 0.0
            else:
                order_price = cur_p

        success, err_msg = await self.broker.execute_order(sym, cmd, qty, order_price, True)
        if success:
            logger.info(f"[{self.uid}] Manual order executed: {cmd} {sym} {qty} @ {order_price}")
            await self.fb.async_send_push_notification(self.uid, "수동 주문 체결", f"[{sym}] {cmd} {qty} 주문이 체결되었습니다.")
            self.fb.update_data(f"{self.account_path}/manual_orders", {
                "command": "NONE",
                "status": "SUCCESS",
                "error_message": ""
            })
        else:
            self.fb.update_data(f"{self.account_path}/manual_orders", {
                "command": "NONE",
                "status": "FAILED",
                "error_message": err_msg
            })

    def _on_conditional_orders_change(self, event):
        self._loop.call_soon_threadsafe(self._handle_conditional_update)

    def _handle_conditional_update(self):
        data = self.fb.get_data(f"{self.account_path}/conditional_orders")
        if isinstance(data, dict):
            self.conditional_orders = {k: ConditionalOrder(**v) for k, v in data.items() if v.get("status") == "PENDING"}

    def _on_stop_loss_orders_change(self, event):
        self._loop.call_soon_threadsafe(self._handle_stop_loss_update)

    def _handle_stop_loss_update(self):
        data = self.fb.get_data(f"{self.account_path}/stop_loss_orders")
        if isinstance(data, dict):
            self.stop_loss_orders = {k: StopLossOrder(**v) for k, v in data.items() if v.get("status") == "PENDING"}

    async def _fetch_stock_name(self, symbol: str) -> str:
        try:
            if "crypto" in self.base_path:
                from brokers.upbit_broker import UpbitBroker
                return UpbitBroker.get_coin_name(symbol)
            else:
                async with aiohttp.ClientSession() as session:
                    url = f"https://finance.naver.com/item/main.naver?code={symbol.strip()}"
                    async with session.get(url, timeout=aiohttp.ClientTimeout(total=3)) as res:
                        raw_bytes = await res.read()
                        try:
                            text = raw_bytes.decode('utf-8')
                        except UnicodeDecodeError:
                            text = raw_bytes.decode('euc-kr', errors='replace')
                            
                        match = re.search(r'<title>(.*?)\s*:\s*(?:\uB124\uC774\uBC84|Npay)', text)
                        if match:
                            return match.group(1).strip()
        except Exception as e:
            logger.debug(f"Failed to fetch stock name for {symbol}: {e}")
        return symbol

    async def _session_loop(self):
        last_sync = 0.0
        self._last_balance_check = 0.0
        
        while self._running:
            try:
                if not self._running:
                    break
                now = time.time()
                if self.dm_controller:
                    try:
                        await self.dm_controller.evaluate_tick()
                    except Exception as e:
                        logger.error(f"[{self.uid}] DualMomentum tick error: {e}", exc_info=True)

                if (self.broker or getattr(self.account_data, "saved_accounts", None)) and (now - last_sync >= 1.0):
                    kst = timezone(timedelta(hours=9))
                    self.fb.set_data(f"{self.account_path}/status/stock_prices", self.price_data)
                    self.fb.set_data(f"{self.account_path}/status/stock_prices_change", self.price_change_data)
                    if self.stock_names:
                        self.fb.update_data(f"{self.account_path}/status/stock_names", self.stock_names)
                    self.fb.set_data(f"{self.account_path}/status/last_updated", datetime.now(kst).isoformat())
                    last_sync = now
                    
                if self.broker and now - self._last_balance_check > 30.0:
                    bal, yld, avail_cash, holdings = await self.broker.get_balance()
                    status_update = {}
                    if bal is not None:
                        status_update["total_balance"] = float(bal)
                        status_update["current_yield"] = float(yld) if yld else 0.0
                        status_update["avail_cash"] = float(avail_cash) if avail_cash else 0.0
                        
                        holdings_data = {}
                        if holdings:
                            for h in holdings:
                                sym = h["symbol"]
                                qty = float(h["quantity"])
                                pchs_avg = float(h["purchase_avg_price"])
                                curr_price = float(h["current_price"])
                                pfls_amt = float(h["profit_loss_amount"])
                                pfls_rt = float(h["profit_loss_rate"])
                                
                                # Use real-time price if available to fix KIS mock delayed prices
                                if sym in self.price_data and self.price_data[sym] > 0:
                                    curr_price = self.price_data[sym]
                                    if pchs_avg > 0:
                                        pfls_amt = (curr_price - pchs_avg) * qty
                                        pfls_rt = (curr_price - pchs_avg) / pchs_avg * 100.0

                                holdings_data[sym] = {
                                    "symbol": sym,
                                    "quantity": qty,
                                    "purchase_avg_price": pchs_avg,
                                    "current_price": curr_price,
                                    "profit_loss_amount": pfls_amt,
                                    "profit_loss_rate": pfls_rt,
                                    "name": h.get("name", "")
                                }
                        status_update["holdings"] = holdings_data
                        status_update["last_updated"] = time.time()
                        status_update["auth_error"] = ""
                    else:
                        # 일시적인 통신 오류나 API 한도 초과일 수 있으므로 기존 잔고를 0으로 초기화하지 않음
                        status_update["last_updated"] = time.time()

                    self.fb.update_data(f"{self.account_path}/status", status_update)
                    self._last_balance_check = now
                    
                if self.broker and (now - self._last_rest_price >= 30.0):
                    symbols = self._get_active_symbols()
                    stale_symbols = []
                    for sym in symbols:
                        hist = self.price_history.get(sym)
                        if not hist or (now - hist[-1][0] > 25):
                            stale_symbols.append(sym)
                    
                    if stale_symbols:
                        rest_prices = await self.broker.fetch_rest_current_prices(stale_symbols)
                        for sym, data in rest_prices.items():
                            if isinstance(data, tuple) and len(data) >= 2:
                                self._on_ws_price(sym, data[0], data[1])
                            else:
                                self._on_ws_price(sym, float(data))
                                
                    self._last_rest_price = now
                        
                    # Sync missing stock names for watch list and surge configs
                    all_symbols_for_names = set(symbols)
                    if self.settings.surge_trading.enabled:
                        all_symbols_for_names.update(self.settings.surge_trading.per_symbol_config.keys())
                    for sym in all_symbols_for_names:
                        if sym not in self.stock_names:
                            name = await self._fetch_stock_name(sym)
                            if name:
                                self.stock_names[sym] = name
                    
                if self.settings.is_auto_trading and self.broker:
                    await self._check_conditional_orders()
                    await self._check_stop_loss()
                    await self._check_surge_trading()

                    await self._check_laore_orders()

            except Exception as e:
                logger.error(f"[{self.base_path}] _session_loop error: {e}", exc_info=True)
                
            await asyncio.sleep(0.5)

    async def _check_conditional_orders(self):
        to_remove = []
        is_kis_overseas = (self.broker.__class__.__name__ == "KISBroker" and getattr(self.broker, "is_overseas", False))
        for oid, order in self.conditional_orders.items():
            price = self.price_data.get(order.symbol)
            if not price: continue
            
            triggered = (order.side == "BUY" and price <= order.target_price) or \
                        (order.side == "SELL" and price >= order.target_price)
                        
            if triggered:
                # 목표가 도달 시 즉시 체결 유도 (단, KIS 해외주식은 지정가만 지원하므로 현재가로 전송)
                exec_qty = order.quantity
                exec_price = price if is_kis_overseas else 0.0
                if self.service_type == "crypto" and order.side == "BUY":
                    exec_qty = float(order.quantity) * float(price)
                    exec_price = 0.0
                success, _ = await self.broker.execute_order(order.symbol, order.side, exec_qty, exec_price, order.allow_extended_hours)
                if success:
                    await self.fb.async_send_push_notification(self.uid, "조건부 주문 체결", f"[{order.symbol}] {order.side} {order.quantity} 주문이 체결되었습니다.")
                    self.fb.update_data(f"{self.account_path}/conditional_orders/{oid}", {
                        "status": "EXECUTED",
                        "executed_at": datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S"),
                        "executed_price": price
                    })
                    to_remove.append(oid)
                    
        for oid in to_remove:
            self.conditional_orders.pop(oid, None)

    async def _check_stop_loss(self):
        to_remove = []
        is_kis_overseas = (self.broker.__class__.__name__ == "KISBroker" and getattr(self.broker, "is_overseas", False))
        for oid, order in self.stop_loss_orders.items():
            price = self.price_data.get(order.symbol)
            if not price: continue
            
            if price <= order.trigger_price:
                qty = 1.0
                status_data = self.fb.get_data(f"{self.account_path}/status")
                if status_data and "holdings" in status_data:
                    holding = status_data["holdings"].get(order.symbol)
                    if holding:
                        total_qty = float(holding["quantity"])
                        qty = total_qty * (order.volume_percent / 100.0)
                        
                if qty > 0:
                    # 손절은 즉시 체결 유도 (단, KIS 해외주식은 지정가만 지원하므로 현재가로 전송)
                    exec_price = price if is_kis_overseas else 0.0
                    success, _ = await self.broker.execute_order(order.symbol, "SELL", qty, exec_price, order.allow_extended_hours)
                    if success:
                        await self.fb.async_send_push_notification(self.uid, "자동 손절매 체결", f"[{order.symbol}] SELL {qty} 주문이 체결되었습니다.")
                        self.fb.update_data(f"{self.account_path}/stop_loss_orders/{oid}", {
                            "status": "EXECUTED",
                            "executed_at": datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S"),
                            "executed_price": price
                        })
                        to_remove.append(oid)
                    
        for oid in to_remove:
            self.stop_loss_orders.pop(oid, None)

    async def _check_surge_trading(self):
        cfg = self.settings.surge_trading
        if not cfg.enabled: return
        
        for sym, hist in self.price_history.items():
            if len(hist) < 2: continue
            
            hist_list = list(hist)
            latest_ts, latest_p = hist_list[-1]
            if sym not in cfg.per_symbol_config: continue
            
            configs = cfg.per_symbol_config[sym]
            if not isinstance(configs, list):
                # Fallback if old data format is somehow loaded
                configs = [configs]
                
            for i, sym_cfg in enumerate(configs):
                if sym_cfg.buy_quantity <= 0: continue
                
                cutoff_ts = latest_ts - sym_cfg.time_window_seconds
                
                past_p = None
                for ts, p in reversed(hist_list):
                    if ts <= cutoff_ts:
                        past_p = p
                        break
                        
                if not past_p: continue
                
                surge_pct = (latest_p - past_p) / past_p * 100.0
                if surge_pct >= sym_cfg.surge_percent_threshold:
                    last_buy = self.last_surge_buy_time.get(sym, 0)
                    if time.time() - last_buy > 60: # 60s cooldown
                        logger.info(f"[{self.uid}] Surge detected on {sym} ({sym_cfg.time_window_seconds}s, {sym_cfg.surge_percent_threshold}%): {surge_pct:.2f}%. Triggering BUY order.")
                        self._loop.create_task(self._execute_surge_buy(sym, sym_cfg.buy_quantity, latest_p, cfg.allow_extended_hours, i)).add_done_callback(_task_error_callback)
                        # Only trigger once per symbol per cycle, break out of config loop to respect cooldown
                        break

    async def _execute_surge_buy(self, symbol, qty, price, allow_extended, config_idx=None):
        success, _ = await self.broker.execute_order(symbol, "BUY", qty, price, allow_extended)
        if success:
            await self.fb.async_send_push_notification(self.uid, "급등주 매수 체결", f"[{symbol}] BUY {qty} 주문이 체결되었습니다.")
            self.last_surge_buy_time[symbol] = time.time()
            
            if config_idx is not None:
                cfg = self.settings.surge_trading
                if symbol in cfg.per_symbol_config:
                    configs = cfg.per_symbol_config[symbol]
                    if 0 <= config_idx < len(configs):
                        del configs[config_idx]
                        dumped = [c.dict() for c in configs]
                        self.fb.set_data(f"{self.account_path}/settings/surge_trading/per_symbol_config/{symbol}", dumped)
                        
            # Wait a bit before checking balance to allow exchange to process the order
            await asyncio.sleep(2.0)
            self._last_balance_check = 0.0

    async def _check_laore_orders(self):
        from engine.laore_calculator import calculate_daily_orders
        
        laore_settings = self.fb.get_data(f"{self.account_path}/laore_settings") or {}
        if not isinstance(laore_settings, dict): return
        
        laore_status_map = await self.fb.async_get_data(f"{self.account_path}/laore_status") or {}
        if not isinstance(laore_status_map, dict):
            laore_status_map = {}
        
        # 타임존 체크
        from datetime import datetime
        import pytz
        ny_tz = pytz.timezone('America/New_York')
        now_ny = datetime.now(pytz.utc).astimezone(ny_tz)
        is_summer = now_ny.dst() != type(now_ny.dst())(0)
        
        kst = pytz.timezone('Asia/Seoul')
        now_kst = datetime.now(pytz.utc).astimezone(kst)
        us_target_hour = 17 if is_summer else 18
        us_target_min = 40
        kr_target_hour = 15
        kr_target_min = 20
        
        is_crypto = (self.service_type == "crypto")
        
        for composite_key, settings in laore_settings.items():
            if not isinstance(settings, dict) or not settings.get("enabled", False):
                continue
            
            real_symbol = composite_key.split("_", 1)[1] if "_" in composite_key else composite_key
            active_acc = self.account_data.saved_accounts.get(self.account_data.active_account_id)
            is_overseas = active_acc.is_overseas if active_acc else False
            is_mock = active_acc.is_mock if active_acc else False
                
            status = laore_status_map.get(composite_key, {})
            current_date_str = now_kst.strftime("%Y-%m-%d")
            
            should_execute = False
            if is_crypto:
                interval_hours = settings.get("interval_hours", 12.0)
                last_exec_ts = status.get("last_exec_timestamp", 0.0)
                if time.time() - last_exec_ts >= (interval_hours * 3600):
                    should_execute = True
            else:
                if is_mock and is_overseas:
                    target_hour = 22 if is_summer else 23
                    target_min = 50
                else:
                    target_hour = us_target_hour if is_overseas else kr_target_hour
                    target_min = us_target_min if is_overseas else kr_target_min
                last_exec_date = status.get("last_exec_date", "")
                if now_kst.hour == target_hour and now_kst.minute >= target_min and last_exec_date != current_date_str:
                    should_execute = True
            
            if should_execute:
                current_price = self.price_data.get(real_symbol)
                if not current_price:
                    try:
                        prices = await self.broker.fetch_rest_current_prices([real_symbol])
                        val = prices.get(real_symbol)
                        if isinstance(val, (tuple, list)):
                            current_price = float(val[0])
                        elif val:
                            current_price = float(val)
                    except Exception:
                        pass
                        
                if not current_price: continue
                
                status = laore_status_map.get(composite_key, {})
                if "average_price" not in status:
                    status["average_price"] = 0.0
                    status["holding_quantity"] = 0.0
                    status["current_round"] = 0
                    status["t_value"] = 0.0
                
                if is_crypto:
                    await self.broker.cancel_all_orders(real_symbol)
                
                bal, yld, avail_cash, holdings = await self.broker.get_balance()
                if holdings is not None:
                    holding = next((h for h in holdings if isinstance(h, dict) and h.get("symbol") == real_symbol), None)
                    if holding:
                        status["holding_quantity"] = float(holding.get("quantity", holding.get("qty", 0.0)))
                        status["average_price"] = float(holding.get("purchase_avg_price", holding.get("avg_price", 0.0)))
                    else:
                        status["holding_quantity"] = 0.0
                        status["average_price"] = 0.0

                split_cnt = settings.get("split_count", 40)
                cur_round = status.get("current_round", 0)
                if status.get("holding_quantity", 0.0) <= 0 or status.get("average_price", 0.0) <= 0:
                    status["current_round"] = 0
                    status["current_mode"] = "일반 모드(전반전)"
                else:
                    if cur_round > split_cnt and split_cnt > 0:
                        cur_round = split_cnt
                        status["current_round"] = cur_round
                    if cur_round >= split_cnt and split_cnt > 0:
                        status["current_mode"] = "원금소진(매도대기)"
                    elif cur_round >= (split_cnt / 2):
                        status["current_mode"] = "일반 모드(후반전)"
                    else:
                        status["current_mode"] = "일반 모드(전반전)"
                
                try:
                    orders = calculate_daily_orders(settings, status, current_price, self.service_type)
                except Exception as e:
                    logger.error(f"Error calculating laore orders for {composite_key}: {e}")
                    continue
                
                if orders['buy'] or orders['sell']:
                    active_acc = self.account_data.saved_accounts.get(self.account_data.active_account_id)
                    is_mock = active_acc.is_mock if active_acc else False
                    
                    ord_dvsn_kw = {}
                    if not is_crypto:
                        ord_dvsn_kw['ord_dvsn'] = "00" if is_mock else ("34" if is_overseas else "03")

                    successful_buy_qty = 0.0
                    successful_sell_qty = 0.0
                    total_buy_cost = 0.0
                    any_success = False
                    critical_error = False

                    if not is_crypto and now_kst.weekday() >= 5:
                        logger.info(f"Skipping Laore for {real_symbol} because today is a weekend.")
                        status['last_exec_date'] = current_date_str
                        self.fb.update_data(f"{self.account_path}/laore_status/{composite_key}", status)
                        continue

                    for b_ord in orders['buy']:
                        success, msg = await self.broker.execute_order(real_symbol, "BUY", b_ord['qty'], b_ord['price'], False, **ord_dvsn_kw)
                        if success:
                            successful_buy_qty += b_ord['qty']
                            total_buy_cost += b_ord['qty'] * b_ord['price']
                            any_success = True
                        else:
                            logger.warning(f"Laore BUY failed for {real_symbol}: {msg}")
                            if "영업일" in msg or "휴장" in msg or "장시작전" in msg or "장시작 전" in msg or "상이" in msg or "모의투자" in msg:
                                critical_error = True
                                await self.fb.async_send_push_notification(self.uid, "무한매수 매수 실패", f"[{real_symbol}] 매수 불가(계좌/시간오류): {msg}")
                                break
                            await self.fb.async_send_push_notification(self.uid, "무한매수 매수 실패", f"[{real_symbol}] 매수 주문 실패: {msg}")
                            if "잔고" in msg or "초과" in msg or "부족" in msg:
                                critical_error = True
                                break
                        import asyncio
                        await asyncio.sleep(1.0 if is_mock else 0.6)
                    if not critical_error:
                        for s_ord in orders['sell']:
                            success, msg = await self.broker.execute_order(real_symbol, "SELL", s_ord['qty'], s_ord['price'], False, **ord_dvsn_kw)
                            if success:
                                successful_sell_qty += s_ord['qty']
                                any_success = True
                            else:
                                logger.warning(f"Laore SELL failed for {real_symbol}: {msg}")
                                if "영업일" in msg or "휴장" in msg or "장시작전" in msg or "장시작 전" in msg or "상이" in msg or "모의투자" in msg:
                                    critical_error = True
                                    await self.fb.async_send_push_notification(self.uid, "무한매수 매도 실패", f"[{real_symbol}] 매도 불가(계좌/시간오류): {msg}")
                                    break
                                await self.fb.async_send_push_notification(self.uid, "무한매수 매도 실패", f"[{real_symbol}] 매도 주문 실패: {msg}")
                                if "잔고" in msg or "초과" in msg or "부족" in msg:
                                    critical_error = True
                                    break
                            import asyncio
                            await asyncio.sleep(1.0 if is_mock else 0.6)
                    
                    if any_success:
                        old_qty = status.get("holding_quantity", 0.0)
                        old_avg = status.get("average_price", 0.0)
                        new_qty = old_qty + successful_buy_qty - successful_sell_qty

                        if successful_sell_qty >= old_qty + successful_buy_qty or new_qty <= 0:
                            status['average_price'] = 0.0
                            status['current_round'] = 0
                            status['t_value'] = 0.0
                            status['holding_quantity'] = 0.0
                            status['current_mode'] = "일반 모드(전반전)"
                        else:
                            if successful_buy_qty > 0:
                                status['average_price'] = ((old_qty * old_avg) + total_buy_cost) / (old_qty + successful_buy_qty)
                                status['current_round'] = status.get("current_round", 0) + 1
                                status['t_value'] = status.get("t_value", 0.0) + 1.0
                            status['holding_quantity'] = new_qty
                            
                            split_cnt = settings.get("split_count", 40)
                            cur_round = status.get("current_round", 0)
                            if cur_round > split_cnt and split_cnt > 0:
                                cur_round = split_cnt
                                status['current_round'] = cur_round
                            if cur_round >= split_cnt and split_cnt > 0:
                                status['current_mode'] = "원금소진(매도대기)"
                            elif cur_round >= (split_cnt / 2):
                                status['current_mode'] = "일반 모드(후반전)"
                            else:
                                status['current_mode'] = "일반 모드(전반전)"
                            
                        status['last_exec_date'] = current_date_str
                        status['last_exec_timestamp'] = time.time()
                        self.fb.update_data(f"{self.account_path}/laore_status/{composite_key}", status)
                    elif critical_error:
                        logger.info(f"Laore skipped updating status for {composite_key} due to critical error, but updating last_exec_date to prevent spam.")
                        status['last_exec_date'] = current_date_str
                        status['last_exec_timestamp'] = time.time()
                        self.fb.update_data(f"{self.account_path}/laore_status/{composite_key}", status)
                    else:
                        if critical_error:
                            status['last_exec_date'] = current_date_str
                            status['last_exec_timestamp'] = time.time()
                            self.fb.update_data(f"{self.account_path}/laore_status/{composite_key}", status)
                            logger.info(f"Laore marked as executed for {composite_key} due to critical error.")
                        else:
                            logger.info(f"Laore skipped updating status for {composite_key} because all orders failed.")
