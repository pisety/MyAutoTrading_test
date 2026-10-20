import aiohttp
import asyncio
import logging
import json
import time
import os
from .base_broker import BaseBroker
from typing import Tuple, Optional, List, Dict, Any

logger = logging.getLogger(__name__)

class KiwoomBroker(BaseBroker):
    def __init__(self, app_key: str, app_secret: str, account_number: str, is_mock: bool = True, is_overseas: bool = False):
        self.app_key = app_key
        self.app_secret = app_secret
        self.account_number = account_number
        self.is_mock = is_mock
        self.is_overseas = is_overseas
        
        self.base_url = "https://api.kiwoom.com" if not is_mock else "https://mockapi.kiwoom.com"
        
        self.access_token = None
        self.token_expired_at = 0
        
        self.ws = None
        self.ws_session = None
        self._session: Optional[aiohttp.ClientSession] = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def _get_access_token(self):
        now = time.time()
        if self.access_token and (self.token_expired_at - now > 3600):
            return self.access_token
            
        self._token_cache_filename = f".kiwoom_token_{self.app_key[:10] if self.app_key else 'default'}.json"
        cache = self._load_token_cache()
        if cache and cache.get("access_token") and (cache.get("token_expires_at", 0) - now > 3600):
            self.access_token = cache["access_token"]
            self.token_expired_at = cache["token_expires_at"]
            return self.access_token

        url = f"{self.base_url}/oauth2/token"
        headers = {"Content-Type": "application/json;charset=UTF-8", "User-Agent": "Mozilla/5.0"}
        data = {
            "grant_type": "client_credentials",
            "appkey": self.app_key,
            "secretkey": self.app_secret
        }
        
        try:
            session = await self._get_session()
            async with session.post(url, headers=headers, json=data, timeout=10) as resp:
                    if resp.status == 200:
                        res = await resp.json()
                        self.access_token = res.get("token")
                        expires_dt = res.get("expires_dt")
                        
                        if expires_dt:
                            try:
                                from datetime import datetime
                                dt = datetime.strptime(expires_dt, "%Y%m%d%H%M%S")
                                self.token_expired_at = dt.timestamp()
                            except ValueError:
                                self.token_expired_at = now + 86400
                        else:
                            self.token_expired_at = now + 86400
                        
                        self._save_token_cache({
                            "access_token": self.access_token,
                            "token_expires_at": self.token_expired_at
                        })
                            
                        logger.info(f"Kiwoom Access Token 발급 성공: {self.account_number}")
                        return self.access_token
                    else:
                        text = await resp.text()
                        logger.error(f"Kiwoom Token Error: {text}")
                        return None
        except Exception as e:
            logger.error(f"Kiwoom token exception: {e}")
            return None

    def _get_headers(self, token: str, api_id: str = ""):
        return {
            "Content-Type": "application/json;charset=UTF-8",
            "Authorization": f"Bearer {token}",
            "User-Agent": "Mozilla/5.0",
            "api-id": api_id
        }

    async def ensure_valid_token(self) -> bool:
        token = await self._get_access_token()
        return token is not None

    async def get_balance(self) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict[str, Any]]]]:
        if not await self.ensure_valid_token():
            return None, None, None, None
            
        if self.is_overseas:
            url_acnt = f"{self.base_url}/api/ovstk/acnt"
            headers_04 = self._get_headers(self.access_token, api_id="ko00004")
            body_04 = {"qry_tp": "0", "ovrs_stex_tp": "NASD"}
            headers_18 = self._get_headers(self.access_token, api_id="ko00018")
            body_18 = {"qry_tp": "1", "ovrs_stex_tp": "NASD"}
        else:
            url_acnt = f"{self.base_url}/api/dostk/acnt"
            headers_04 = self._get_headers(self.access_token, api_id="kt00004")
            body_04 = {"qry_tp": "0", "dmst_stex_tp": "KRX"}
            headers_18 = self._get_headers(self.access_token, api_id="kt00018")
            body_18 = {"qry_tp": "1", "dmst_stex_tp": "KRX"}
        
        try:
            session = await self._get_session()
            task_04 = session.post(url_acnt, headers=headers_04, json=body_04, timeout=10)
            task_18 = session.post(url_acnt, headers=headers_18, json=body_18, timeout=10)
            resp_04, resp_18 = await asyncio.gather(task_04, task_18)
            
            cash_balance = 0.0
            if resp_04.status == 200:
                res_04 = await resp_04.json()
                cash_balance = float(res_04.get("entr", 0))
            
            total_yield = 0.0
            holdings = []
            total_evlt_amt = 0.0
            if resp_18.status == 200:
                res_18 = await resp_18.json()
                total_yield = float(res_18.get("tot_prft_rt", 0) or 0)
                total_evlt_amt = float(res_18.get("tot_evlt_amt", 0) or 0)
                
                for item in res_18.get("acnt_evlt_remn_indv_tot", []):
                    if not isinstance(item, dict):
                        continue
                    stk_cd = str(item.get("stk_cd", "")).replace("A", "").strip()
                    qty = float(item.get("rmnd_qty") or item.get("hldg_qty") or 0)
                    if not stk_cd or qty <= 0:
                        continue
                    stk_nm = str(item.get("stk_nm", stk_cd)).strip()
                    avg_prc = float(str(item.get("avg_prc") or item.get("pchs_avg_pric") or 0).replace("+", "").replace("-", ""))
                    cur_prc = float(str(item.get("cur_prc") or item.get("prpr") or 0).replace("+", "").replace("-", ""))
                    evlt_prft = float(item.get("evlt_prft") or item.get("evlu_pfls_amt") or ((cur_prc - avg_prc) * qty if avg_prc > 0 else 0))
                    prft_rt = float(item.get("prft_rt") or item.get("evlu_pfls_rt") or (((cur_prc - avg_prc) / avg_prc * 100.0) if avg_prc > 0 else 0))
                    holdings.append({
                        "symbol": stk_cd,
                        "name": stk_nm,
                        "quantity": qty,
                        "purchase_avg_price": avg_prc,
                        "current_price": cur_prc,
                        "profit_loss_amount": evlt_prft,
                        "profit_loss_rate": prft_rt
                    })
            
            total_balance = total_evlt_amt + cash_balance if total_evlt_amt > 0 else cash_balance
            return total_balance, total_yield, cash_balance, holdings
        except Exception as e:
            logger.error(f"Kiwoom get_balance error: {e}")
            return None, None, None, None

    async def get_holdings(self) -> dict:
        if not await self.ensure_valid_token():
            return {}
            
        if self.is_overseas:
            url_acnt = f"{self.base_url}/api/ovstk/acnt"
            headers_18 = self._get_headers(self.access_token, api_id="ko00018")
            body_18 = {"qry_tp": "1", "ovrs_stex_tp": "NASD"}
        else:
            url_acnt = f"{self.base_url}/api/dostk/acnt"
            headers_18 = self._get_headers(self.access_token, api_id="kt00018")
            body_18 = {"qry_tp": "1", "dmst_stex_tp": "KRX"}
        
        try:
            session = await self._get_session()
            async with session.post(url_acnt, headers=headers_18, json=body_18, timeout=10) as resp:
                if resp.status == 200:
                    res = await resp.json()
                    result = {}
                    for item in res.get("acnt_evlt_remn_indv_tot", []):
                        stk_cd = item.get("stk_cd", "").replace("A", "").strip()
                        if stk_cd:
                            result[stk_cd] = {
                                "qty": int(item.get("rmnd_qty", 0)),
                                "avg_price": float(item.get("avg_prc", 0))
                            }
                    return result
                else:
                    logger.error(f"Kiwoom get_holdings failed: {await resp.text()}")
                    return {}
        except Exception as e:
            logger.error(f"Kiwoom get_holdings error: {e}")
            return {}

    async def execute_order(self, symbol: str, side: str, qty: int, price: float, allow_extended_hours: bool = False, ord_dvsn: str = None) -> tuple:
        if not await self.ensure_valid_token():
            return False, "인증 토큰 발급 실패"
            
        if self.is_overseas:
            api_id = "ko10000" if side.lower() == "buy" else "ko10001"
            url = f"{self.base_url}/api/ovstk/ordr"
            headers = self._get_headers(self.access_token, api_id=api_id)
            trde_tp = "0" if price == 0 else "0" 
            body = {
                "ovrs_stex_tp": "NASD",
                "stk_cd": symbol,
                "ord_qty": str(int(qty)),
                "ord_uv": str(price),
                "trde_tp": trde_tp,
                "cond_uv": ""
            }
        else:
            api_id = "kt10000" if side.lower() == "buy" else "kt10001"
            url = f"{self.base_url}/api/dostk/ordr"
            headers = self._get_headers(self.access_token, api_id=api_id)
            trde_tp = "3" if price <= 0 else "0"
            body = {
                "dmst_stex_tp": "KRX",
                "stk_cd": symbol,
                "ord_qty": str(int(qty)),
                "ord_uv": str(int(price)),
                "trde_tp": trde_tp,
                "cond_uv": ""
            }
        
        try:
            session = await self._get_session()
            async with session.post(url, headers=headers, json=body, timeout=10) as resp:
                    res = await resp.json()
                    if resp.status == 200 and res.get("return_code") == 0:
                        ord_no = res.get("ord_no")
                        logger.info(f"[Kiwoom] 주문 전송 성공 - {symbol} {side} {qty}주 @ {price} (ord_no: {ord_no})")
                        return True, f"주문 성공|{ord_no}" if ord_no else "주문 성공"
                    else:
                        msg = res.get("return_msg", "알수없는 에러")
                        logger.error(f"[Kiwoom] 주문 전송 실패: {msg}")
                        return False, msg
        except Exception as e:
            logger.error(f"Kiwoom execute_order error: {e}")
            return False, str(e)

    async def cancel_all_orders(self, symbol: str) -> bool:
        return True

    async def cancel_domestic_order(self, order_no: str, symbol: str, qty: int = 0, ord_dvsn: str = "01") -> Tuple[bool, str]:
        if self.is_overseas:
            return await self.cancel_overseas_order(order_no, symbol, qty)
        if not await self.ensure_valid_token():
            return False, "인증 토큰 발급 실패"

        url = f"{self.base_url}/api/dostk/ordr"
        headers = self._get_headers(self.access_token, api_id="kt10003")
        body = {
            "dmst_stex_tp": "KRX",
            "org_ord_no": str(order_no),
            "stk_cd": symbol.strip(),
            "cncl_qty": str(int(qty)) if qty > 0 else "0"
        }
        try:
            session = await self._get_session()
            async with session.post(url, headers=headers, json=body, timeout=10) as resp:
                res = await resp.json()
                if resp.status == 200 and res.get("return_code") == 0:
                    logger.info(f"[Kiwoom] 주문 취소 성공 - ODNO {order_no} ({symbol})")
                    return True, "취소 성공"
                else:
                    msg = res.get("return_msg", "취소 실패")
                    return False, msg
        except Exception as e:
            logger.error(f"Kiwoom cancel_domestic_order error: {e}")
            return False, str(e)
        
    async def cancel_overseas_order(self, order_no: str, symbol: str, qty: int = 0, ord_dvsn: str = "00") -> Tuple[bool, str]:
        return True, "취소 성공"

    async def fetch_rest_current_prices(self, symbols: list) -> dict:
        if not await self.ensure_valid_token():
            return {}
            
        if self.is_overseas:
            url = f"{self.base_url}/api/ovstk/stkinfo"
            headers = self._get_headers(self.access_token, api_id="ko10100")
        else:
            url = f"{self.base_url}/api/dostk/stkinfo"
            headers = self._get_headers(self.access_token, api_id="ka10100")
        
        async def fetch_single(session, symbol):
            body = {"stk_cd": symbol}
            try:
                async with session.post(url, headers=headers, json=body, timeout=5) as resp:
                    if resp.status == 200:
                        res = await resp.json()
                        raw_prc = str(res.get("cur_prc", "0")).replace("+", "").replace("-", "").strip()
                        price = float(raw_prc) if raw_prc else 0.0
                        flu_rt_str = str(res.get("flu_rt", "")).strip()
                        if flu_rt_str:
                            chg_rate = float(flu_rt_str)
                        else:
                            sig = str(res.get("pred_pre_sig", "3"))
                            diff = float(str(res.get("pred_pre", "0")).replace("+", "").replace("-", "").strip() or 0)
                            if sig in ["4", "5"]:
                                diff = -abs(diff)
                            else:
                                diff = abs(diff)
                            prev_close = price - diff
                            chg_rate = (diff / prev_close * 100.0) if prev_close > 0 else 0.0
                            
                        if price > 0:
                            return symbol, (price, chg_rate)
            except Exception as e:
                logger.error(f"Kiwoom fetch_rest_current_prices error for {symbol}: {e}")
            return symbol, None

        results = {}
        session = await self._get_session()
        tasks = [fetch_single(session, sym) for sym in symbols]
        responses = await asyncio.gather(*tasks)
        for sym, data in responses:
            if data is not None:
                results[sym] = data
        return results

    async def connect_websocket(self, symbols: list, on_message_callback):
        import websockets
        if not await self.ensure_valid_token():
            logger.error("Failed to get token for websocket")
            return
            
        valid_symbols = [s.strip() for s in symbols if s.strip()]
        if not valid_symbols:
            return

        ws_url = "wss://api.kiwoom.com:10000" if not self.is_mock else "wss://mockapi.kiwoom.com:10000"
        self._running_ws = True

        async def _ws_loop():
            while getattr(self, "_running_ws", False):
                try:
                    await self.ensure_valid_token()
                    logger.info(f"[Kiwoom] 웹소켓 연결 시작 - 종목: {valid_symbols}")
                    async with websockets.connect(ws_url, extra_headers={
                        "authorization": f"Bearer {self.access_token}",
                        "User-Agent": "Mozilla/5.0"
                    }, ping_interval=30) as ws:
                        self.ws = ws
                        tr_type = "0B" if not self.is_overseas else "0C"
                        subscribe_payload = {
                            "trnm": "REG",
                            "grp_no": "1",
                            "refresh": "1",
                            "data": [
                                {
                                    "item": valid_symbols,
                                    "type": [tr_type]
                                }
                            ]
                        }
                        await ws.send(json.dumps(subscribe_payload))
                        logger.info("[Kiwoom] 웹소켓 구독 메시지 전송 성공")

                        async for message in ws:
                            try:
                                data = json.loads(message)
                                if data.get("trnm") == "REAL" and "data" in data:
                                    for d in data["data"]:
                                        if d.get("type") in ["0B", "0C"]:
                                            symbol = d.get("item")
                                            values = d.get("values", {})
                                            price_str = str(values.get("10", "0")).replace("+", "").replace("-", "").strip()
                                            chg_str = str(values.get("12", "0")).strip()
                                            if price_str and symbol:
                                                price = float(price_str)
                                                chg = float(chg_str) if chg_str else 0.0
                                                on_message_callback(symbol, price, chg)
                            except Exception as e:
                                logger.error(f"[Kiwoom] WS Parse Error: {e}")
                except Exception as e:
                    logger.error(f"[Kiwoom] WS Connection Error: {e}")
                    self.ws = None
                    await asyncio.sleep(2.0)

        self.ws_task = asyncio.create_task(_ws_loop())

    async def close_websocket(self):
        self._running_ws = False
        if self.ws:
            await self.ws.close()
            self.ws = None
        if getattr(self, "ws_task", None):
            self.ws_task.cancel()
            self.ws_task = None

    async def inquire_order_fill_status(self, order_no: str) -> Tuple[int, int]:
        return 0, -1

