import time
import math
import asyncio
import logging
import aiohttp
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, Optional, List

from engine.dual_momentum_engine import DualMomentumEngine, DualMomentumConfig, DualMomentumState
from database.firebase_client import FirebaseClient

logger = logging.getLogger("DualMomentumController")

class DualMomentumController:
    def __init__(self, uid: str, account_id: str, fb_client: FirebaseClient, session):
        self.uid = uid
        self.account_id = account_id
        self.fb = fb_client
        self.session = session  # Reference to UserSession
        
        self.path = f"users/{uid}/stock/accounts/{account_id}/dual_momentum"
        
        self.engine: Optional[DualMomentumEngine] = None
        self._enabled = True
        self._max_budget = 10000.0
        
        self._last_macro_fetch = 0.0
        self._soxx_close = 500.0
        self._soxx_ma200 = 468.0
        self._soxx_rsi = 50.0
        self._is_bull = True
        
        self._prev_close_soxl = 0.0
        self._prev_close_soxs = 0.0
        self._last_day_str = ""

    def initialize(self):
        """Initializes settings and restores state from Firebase."""
        logger.info(f"[{self.uid}][{self.account_id}] Initializing Dual Momentum Controller...")
        
        # 1. Listen for settings change
        self.fb.listen(f"{self.path}/settings", self._on_settings_change)
        self.fb.listen(f"{self.path}/command", self._on_command)
        
        # Load initial settings
        settings = self.fb.get_data(f"{self.path}/settings") or {}
        if not settings:
            settings = {
                "enabled": False,
                "target_account_id": self.account_id,
                "max_budget": 10000.0,
                "tp_target_percent": 18.0,
                "sl_percent": 5.0
            }
            self.fb.update_data(f"{self.path}/settings", settings)
            
        self._enabled = bool(settings.get("enabled", False))
        self._max_budget = float(settings.get("max_budget", 10000.0))
        
        # Load existing state if available
        status_data = self.fb.get_data(f"{self.path}/status") or {}
        state = DualMomentumState.from_dict(status_data)

        # 브로커 실계좌 잔고(Holdings) 확인 및 포지션 동기화
        try:
            acc_status = self.fb.get_data(f"users/{self.uid}/stock/accounts/{self.account_id}/status") or {}
            holdings = acc_status.get("holdings", {})
            if "SOXL" in holdings and (state.active_ticker == 0 or state.shares <= 0):
                h = holdings["SOXL"]
                qty = float(h.get("quantity", 0))
                avg_p = float(h.get("purchase_avg_price", 0))
                if qty > 0:
                    state.active_ticker = 1
                    state.shares = qty
                    state.avg_price = avg_p
                    state.pure_avg_price = avg_p
                    state.bought_L = [True, True, False, False]
                    state.days_held = max(1, state.days_held)
                    state.reference_equity = self._max_budget
                    logger.info(f"[{self.uid}] Position auto-reconciled from holdings: SOXL {qty} shares @ ${avg_p:.2f}")
            elif "SOXS" in holdings and (state.active_ticker == 0 or state.shares <= 0):
                h = holdings["SOXS"]
                qty = float(h.get("quantity", 0))
                avg_p = float(h.get("purchase_avg_price", 0))
                if qty > 0:
                    state.active_ticker = 2
                    state.shares = qty
                    state.avg_price = avg_p
                    state.pure_avg_price = avg_p
                    state.bought_S = [True, True, False, False]
                    state.days_held = max(1, state.days_held)
                    state.reference_equity = self._max_budget
                    logger.info(f"[{self.uid}] Position auto-reconciled from holdings: SOXS {qty} shares @ ${avg_p:.2f}")
        except Exception as e:
            logger.warning(f"Holdings reconciliation error: {e}")
        
        config = DualMomentumConfig(
            tp_bull_soxl=float(settings.get("tp_target_percent", 18.0)),
            sl_pct=float(settings.get("sl_percent", 5.0)) / 100.0
        )
        self.engine = DualMomentumEngine(config=config, state=state)

    def _on_settings_change(self, event):
        data = event.data
        if isinstance(data, dict):
            self._enabled = bool(data.get("enabled", True))
            self._max_budget = float(data.get("max_budget", 10000.0))
            if self.engine:
                if "tp_target_percent" in data:
                    self.engine.config.tp_bull_soxl = float(data["tp_target_percent"])
                if "sl_percent" in data:
                    self.engine.config.sl_pct = float(data["sl_percent"]) / 100.0
            logger.info(f"[{self.uid}] DualMomentum settings updated: enabled={self._enabled}, budget={self._max_budget}")

    def _on_command(self, event):
        data = event.data
        if isinstance(data, dict) and data.get("action") == "EMERGENCY_CLOSE":
            logger.warning(f"[{self.uid}] EMERGENCY CLOSE requested via Firebase command!")
            asyncio.create_task(self.emergency_close())
            self.fb.delete_data(f"{self.path}/command")

    async def emergency_close(self):
        """Forces immediate market sell of current active position."""
        if not self.engine or not self.session.broker:
            return
        s = self.engine.state
        if s.shares > 0.0 and s.active_ticker in (1, 2):
            sym = "SOXL" if s.active_ticker == 1 else "SOXS"
            qty = s.shares
            cur_p = self.session.price_data.get(sym, s.avg_price)
            # Marketable sell limit price
            ord_p = round(max(0.01, cur_p - 0.03), 2)
            logger.info(f"[{self.uid}] Executing emergency market sell: {sym} {qty} shares @ {ord_p}")
            success, msg = await self.session.broker.execute_order(sym, "SELL", qty, ord_p, allow_extended_hours=True)
            if success:
                self._record_trade("SELL", sym, qty, cur_p, (cur_p / s.avg_price - 1.0) * 100.0, "EMERGENCY_CLOSE")
                await self.fb.async_send_push_notification(self.uid, "🚨 비상 전량 청산 완료", f"[{sym}] {qty}주가 시장가로 비상 청산되었습니다.")
            self.engine._reset_position()
            self._sync_status_to_firebase(cur_p, cur_p)

    async def update_macro_indicators(self):
        """Fetches SOXX 200MA and RSI14 from Yahoo Finance v8 chart API."""
        now = time.time()
        if now - self._last_macro_fetch < 600.0:  # Fetch every 10 mins
            return
        
        try:
            url = "https://query1.finance.yahoo.com/v8/finance/chart/SOXX?range=1y&interval=1d"
            headers = {"User-Agent": "Mozilla/5.0"}
            async with aiohttp.ClientSession() as session:
                async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=5)) as res:
                    data = await res.json()
                    result = data["chart"]["result"][0]
                    timestamps = result["timestamp"]
                    raw_closes = result["indicators"]["quote"][0]["close"]
                    
                    et_tz = timezone(timedelta(hours=-4))
                    today_et = datetime.now(et_tz).strftime("%Y-%m-%d")
                    
                    # Filter bars closed before today to ensure stable daily regime
                    closed_bars = []
                    for ts, c in zip(timestamps, raw_closes):
                        if c is not None:
                            b_date = datetime.fromtimestamp(ts, et_tz).strftime("%Y-%m-%d")
                            if b_date < today_et:
                                closed_bars.append(c)
                                
                    closes = closed_bars if closed_bars else [c for c in raw_closes if c is not None]
                    
                    self._soxx_close = closes[-1]
                    self._soxx_ma200 = sum(closes[-200:]) / 200.0 if len(closes) >= 200 else self._soxx_close
                    
                    diffs = [closes[i] - closes[i-1] for i in range(1, len(closes))]
                    recent_diffs = diffs[-14:]
                    gains = [d for d in recent_diffs if d > 0]
                    losses = [-d for d in recent_diffs if d < 0]
                    avg_gain = sum(gains) / 14.0 if gains else 0.0
                    avg_loss = sum(losses) / 14.0 if losses else 0.0001
                    rs = avg_gain / avg_loss
                    self._soxx_rsi = 100.0 - (100.0 / (1.0 + rs))
                    self._is_bull = (self._soxx_close > self._soxx_ma200)
                    self._last_macro_fetch = now
                    logger.info(f"[SOXX Macro] Close: {self._soxx_close:.2f}, 200MA: {self._soxx_ma200:.2f}, RSI14: {self._soxx_rsi:.1f}, Bull: {self._is_bull}")
        except Exception as e:
            logger.warning(f"Failed to fetch SOXX macro data: {e}")

    async def update_prev_closes(self):
        """Fetches previous regular-session close prices for SOXL and SOXS."""
        et_tz = timezone(timedelta(hours=-4))
        today_et = datetime.now(et_tz).strftime("%Y-%m-%d")
        
        for sym in ("SOXL", "SOXS"):
            try:
                url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?range=5d&interval=1d"
                headers = {"User-Agent": "Mozilla/5.0"}
                async with aiohttp.ClientSession() as session:
                    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=5)) as res:
                        data = await res.json()
                        result = data["chart"]["result"][0]
                        timestamps = result["timestamp"]
                        raw_closes = result["indicators"]["quote"][0]["close"]
                        
                        # Find the most recent bar strictly before today_et
                        prev_close = None
                        for ts, c in reversed(list(zip(timestamps, raw_closes))):
                            if c is not None:
                                b_date = datetime.fromtimestamp(ts, et_tz).strftime("%Y-%m-%d")
                                if b_date < today_et:
                                    prev_close = float(c)
                                    break
                                    
                        if prev_close and prev_close > 0.0:
                            if sym == "SOXL":
                                self._prev_close_soxl = prev_close
                            else:
                                self._prev_close_soxs = prev_close
                            logger.info(f"[{sym}] Confirmed Previous Close: ${prev_close:.2f} (strictly before {today_et})")
            except Exception as e:
                logger.warning(f"Failed to fetch prev close for {sym}: {e}")

    async def evaluate_tick(self):
        """Evaluates real-time price tick and executes dual momentum orders if triggered."""
        if not self.engine:
            return

        # 1. Update Macro data
        await self.update_macro_indicators()
        
        # 2. Check Day start or missing prev closes
        et_now = datetime.now(timezone(timedelta(hours=-4)))  # US Eastern Time (EDT)
        day_str = et_now.strftime("%Y-%m-%d")
        if day_str != self._last_day_str or self._prev_close_soxl <= 0.0 or self._prev_close_soxs <= 0.0:
            is_new_day = (bool(self._last_day_str) and day_str != self._last_day_str)
            await self.update_prev_closes()
            self.engine.on_day_start(
                self._soxx_close, self._soxx_ma200, self._soxx_rsi,
                self._prev_close_soxl, self._prev_close_soxs,
                is_new_day=is_new_day
            )
            self._last_day_str = day_str

        # 3. Read current real-time prices
        p_soxl = self.session.price_data.get("SOXL", 0.0)
        p_soxs = self.session.price_data.get("SOXS", 0.0)
        
        if p_soxl <= 0.0 and self._prev_close_soxl > 0.0:
            p_soxl = self._prev_close_soxl
        if p_soxs <= 0.0 and self._prev_close_soxs > 0.0:
            p_soxs = self._prev_close_soxs
            
        if p_soxl <= 0.0 or p_soxs <= 0.0:
            self._sync_status_to_firebase(p_soxl, p_soxs)
            return

        cur_time_et = et_now.strftime("%H:%M:%S")
        s = self.engine.state
        # 가용 현금: 총 할당 예산에서 현재 보유 포지션 매수원금을 차감한 잔여 현금
        current_invested = s.shares * s.avg_price if (s.shares > 0 and s.active_ticker in (1, 2)) else 0.0
        cash = max(0.0, self._max_budget - current_invested)

        # 4. Evaluate bar via DualMomentumEngine (only if enabled and broker ready)
        # [HARD SAFETY LOCK] 실전 계좌(is_mock == False)는 퀀트스윙 자동 주문 생성을 원천 차단
        is_mock_broker = getattr(self.session.broker, "is_mock", False) if self.session.broker else False
        if not is_mock_broker:
            self._sync_status_to_firebase(p_soxl, p_soxs)
            return

        orders = []
        if self._enabled and self.session.broker:
            orders, _ = self.engine.evaluate_bar(cur_time_et, p_soxl, p_soxs, cash)
        
        # 5. Execute generated orders with Marketable Limit Orders
        for ord_item in orders:
            sym = ord_item["symbol"]
            action = ord_item["action"]
            qty = ord_item["qty"]
            price = ord_item["price"]
            reason = ord_item["reason"]
            
            # Marketable Limit Order Buffer:
            # NBBO 규칙에 의해 매수는 +$0.03으로 지정가를 높여 보내도 최우선 매도호가에 즉시 체결됨
            # 매도는 -$0.03으로 지정가를 낮춰 보내 최우선 매수호가에 즉시 시장가처럼 체결됨
            if action == "BUY":
                order_price = round(price + 0.03, 2)
            else:
                order_price = round(max(0.01, price - 0.03), 2)
            
            logger.info(f"[{self.uid}] DUAL MOMENTUM ORDER TRIGGERED: {action} {sym} {qty}주 @ ${order_price:.2f} (Signal: ${price:.2f}, Reason: {reason})")
            success, msg = await self.session.broker.execute_order(sym, action, qty, order_price, allow_extended_hours=True)
            
            odno = ""
            if success and "|" in msg:
                parts = msg.split("|")
                odno = parts[1].strip()
                
            if success:
                logger.info(f"[{self.uid}] Order accepted by broker. Launching fill monitor for ODNO: {odno}")
                asyncio.create_task(
                    self._monitor_and_reconcile_order(odno, sym, action, qty, price, reason)
                )
            else:
                logger.warning(f"[{self.uid}] Order failed to submit: {msg}")

        # 6. Synchronize status to Firebase
        self._sync_status_to_firebase(p_soxl, p_soxs)

    async def _monitor_and_reconcile_order(self, odno: str, symbol: str, action: str, req_qty: float, signal_price: float, reason: str):
        """
        Monitors fill status after 12s, cancels any unfilled residual, and reconciles state with real holdings.
        """
        logger.info(f"[{self.uid}] Monitoring fill status for {action} {symbol} {req_qty}주 (ODNO: {odno})...")
        
        # Wait 12 seconds for exchange matching and broker ledger update
        await asyncio.sleep(12.0)
        
        if not self.session.broker or not self.engine:
            return
            
        try:
            _, _, _, holdings = await self.session.broker.get_balance()
            actual_holding_qty = 0.0
            actual_avg_price = signal_price
            if holdings:
                for h in holdings:
                    if h.get("symbol") == symbol:
                        actual_holding_qty = float(h.get("quantity", 0.0))
                        actual_avg_price = float(h.get("purchase_avg_price", signal_price))
                        break
            
            s = self.engine.state
            
            if action == "BUY":
                # Check if real holding matches expected state
                if actual_holding_qty < s.shares:
                    unfilled_qty = s.shares - actual_holding_qty
                    logger.warning(f"[{self.uid}] Partial fill/unfilled detected for {symbol}: Expected {s.shares}주, Real {actual_holding_qty}주 (Unfilled: {unfilled_qty}주)")
                    
                    # Cancel residual order if broker supports it
                    if odno and hasattr(self.session.broker, "cancel_overseas_order"):
                        c_ok, c_msg = await self.session.broker.cancel_overseas_order(odno, symbol, unfilled_qty)
                        logger.info(f"[{self.uid}] Auto-cancel unfilled order {odno}: success={c_ok}, msg={c_msg}")
                    
                    if actual_holding_qty <= 0.0:
                        # 0 shares filled (complete unfill) -> revert bought flag to retry on next dip
                        logger.info(f"[{self.uid}] Reverting bought level flags due to complete unfill for {symbol}")
                        self._revert_bought_flag(symbol, reason)
                        if s.shares == req_qty:
                            self.engine._reset_position()
                        else:
                            s.shares = actual_holding_qty
                        
                        await self.fb.async_send_push_notification(
                            self.uid,
                            f"⚠️ 퀀트스윙 [{symbol}] 미체결 자동 취소",
                            f"호가 급변으로 {req_qty:.0f}주 미체결 → 자동 취소 및 신호 복구 완료"
                        )
                    else:
                        # Partially filled -> keep actual filled quantity
                        s.shares = actual_holding_qty
                        s.pure_avg_price = actual_avg_price
                        s.avg_price = actual_avg_price
                        self._record_trade(action, symbol, actual_holding_qty, actual_avg_price, 0.0, f"PARTIAL_{reason}")
                        await self.fb.async_send_push_notification(
                            self.uid,
                            f"⚡ 퀀트스윙 [{symbol}] 부분 체결 ({actual_holding_qty:.0f}/{req_qty:.0f}주)",
                            f"체결된 {actual_holding_qty:.0f}주 정상 반영 / 잔량({unfilled_qty:.0f}주) 자동 취소 완료"
                        )
                else:
                    # 100% full fill verified
                    logger.info(f"[{self.uid}] 100% Full fill verified for {symbol} {req_qty}주 @ ${signal_price:.2f}")
                    pnl = (signal_price / s.pure_avg_price - 1.0) * 100.0 if s.pure_avg_price > 0 else 0.0
                    self._record_trade(action, symbol, req_qty, signal_price, pnl, reason)
                    await self.fb.async_send_push_notification(
                        self.uid,
                        f"⚡ 퀀트 스윙 {action} 체결 완료",
                        f"[{symbol}] {req_qty:.0f}주 100% 전량 체결 (@ ${signal_price:.2f})\n사유: {reason}"
                    )
                    
            elif action == "SELL":
                if actual_holding_qty > 0.0 and s.shares <= 0.0:
                    logger.warning(f"[{self.uid}] Unsold residual detected for {symbol}: Real {actual_holding_qty}주")
                    s.shares = actual_holding_qty
                else:
                    logger.info(f"[{self.uid}] 100% Full sell verified for {symbol} {req_qty}주")
                    pnl = (signal_price / s.pure_avg_price - 1.0) * 100.0 if s.pure_avg_price > 0 else 0.0
                    self._record_trade(action, symbol, req_qty, signal_price, pnl, reason)
                    await self.fb.async_send_push_notification(
                        self.uid,
                        f"⚡ 퀀트 스윙 {action} 체결 완료",
                        f"[{symbol}] {req_qty:.0f}주 매도 완료 (@ ${signal_price:.2f})\n사유: {reason}"
                    )
            
            cur_p_soxl = self.session.price_data.get("SOXL", 0.0)
            cur_p_soxs = self.session.price_data.get("SOXS", 0.0)
            self._sync_status_to_firebase(cur_p_soxl, cur_p_soxs)
            
        except Exception as e:
            logger.error(f"[{self.uid}] Error in _monitor_and_reconcile_order: {e}", exc_info=True)

    def _revert_bought_flag(self, symbol: str, reason: str):
        """Reverts the corresponding bought flag if an order completely failed or was unfilled."""
        if not self.engine:
            return
        s = self.engine.state
        bought = s.bought_L if symbol == "SOXL" else s.bought_S
        if "Level4" in reason:
            bought[3] = False
        elif "Level3" in reason:
            bought[2] = False
        elif "Level2" in reason:
            bought[1] = False
        elif "Level1" in reason:
            bought[0] = False

    def _record_trade(self, action: str, symbol: str, qty: float, price: float, pnl_rate: float, reason: str):
        kst_now = datetime.now(timezone(timedelta(hours=9)))
        trade_data = {
            "timestamp": int(time.time() * 1000),
            "datetime": kst_now.strftime("%m-%d %H:%M:%S"),
            "symbol": symbol,
            "action": action,
            "quantity": float(qty),
            "price": float(price),
            "pnl_rate": float(pnl_rate),
            "reason": str(reason)
        }
        self.fb.push_data(f"{self.path}/trades", trade_data)

    def _sync_status_to_firebase(self, p_soxl: float, p_soxs: float):
        s = self.engine.state
        active_sym = "NONE"
        cur_p = 0.0
        if s.active_ticker == 1:
            active_sym = "SOXL"
            cur_p = p_soxl
        elif s.active_ticker == 2:
            active_sym = "SOXS"
            cur_p = p_soxs

        pnl_pct = (cur_p / s.pure_avg_price - 1.0) * 100.0 if (s.pure_avg_price > 0 and cur_p > 0) else 0.0
        pnl_amt = (cur_p - s.pure_avg_price) * s.shares if (s.pure_avg_price > 0 and cur_p > 0) else 0.0

        bought = s.bought_L if s.active_ticker == 1 else (s.bought_S if s.active_ticker == 2 else [False, False, False, False])
        
        # Calculate Target & Stop Loss Prices
        tp_target_pct = self.engine.config.tp_bull_soxl if s.is_bull else self.engine.config.tp_bear
        tp_price = s.pure_avg_price * (1.0 + tp_target_pct / 100.0) if s.pure_avg_price > 0 else 0.0
        sl_price = s.pure_avg_price * (1.0 - self.engine.config.sl_pct) if s.pure_avg_price > 0 else 0.0

        kst_now = datetime.now(timezone(timedelta(hours=9)))
        status_dict = {
            "engine_running": self._enabled,
            "active_ticker": int(s.active_ticker),
            "active_symbol": active_sym,
            "shares": float(s.shares),
            "avg_price": float(s.pure_avg_price),
            "current_price": float(cur_p),
            "profit_loss_rate": float(round(pnl_pct, 2)),
            "profit_loss_amount": float(round(pnl_amt, 2)),
            "days_held": int(s.days_held),
            "regime": "BULL" if s.is_bull else "BEAR",
            "soxx_rsi": float(round(self._soxx_rsi, 1)),
            "tp_target_price": float(round(tp_price, 2)),
            "tp_trailing_stop": float(round(s.tp_trailing_stop, 2)),
            "sl_price": float(round(sl_price, 2)),
            "bought_levels": [bool(b) for b in bought],
            "last_updated": kst_now.strftime("%Y-%m-%d %H:%M:%S")
        }
        self.fb.update_data(f"{self.path}/status", status_dict)
