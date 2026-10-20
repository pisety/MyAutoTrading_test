import aiohttp
import asyncio
import logging
import json
import time
import os
from .base_broker import BaseBroker

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

    async def _get_access_token(self):
        now = time.time()
        if self.access_token and (self.token_expired_at - now > 3600):
            return self.access_token
            
        token_file = f".kiwoom_token_{self.app_key[:10] if self.app_key else 'default'}.json"
        try:
            if os.path.exists(token_file):
                with open(token_file, 'r') as f:
                    cache = json.load(f)
                    if cache.get("access_token") and (cache.get("token_expires_at", 0) - now > 3600):
                        self.access_token = cache["access_token"]
                        self.token_expired_at = cache["token_expires_at"]
                        return self.access_token
        except Exception:
            pass

        url = f"{self.base_url}/oauth2/token"
        headers = {"Content-Type": "application/json;charset=UTF-8", "User-Agent": "Mozilla/5.0"}
        data = {
            "grant_type": "client_credentials",
            "appkey": self.app_key,
            "secretkey": self.app_secret
        }
        
        try:
            async with aiohttp.ClientSession() as session:
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
                        
                        try:
                            with open(token_file, 'w') as f:
                                json.dump({"access_token": self.access_token, "token_expires_at": self.token_expired_at}, f)
                        except Exception:
                            pass
                            
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

    async def get_balance(self) -> tuple:
        if not await self.ensure_valid_token():
            return None, None, None
            
        # 1. 예수금 조회 (kt00004)
        url_acnt = f"{self.base_url}/api/dostk/acnt"
        headers_04 = self._get_headers(self.access_token, api_id="kt00004")
        body_04 = {"qry_tp": "0", "dmst_stex_tp": "KRX"}
        
        # 2. 계좌평가잔고내역 (kt00018)
        headers_18 = self._get_headers(self.access_token, api_id="kt00018")
        body_18 = {"qry_tp": "1", "dmst_stex_tp": "KRX"}
        
        try:
            async with aiohttp.ClientSession() as session:
                task_04 = session.post(url_acnt, headers=headers_04, json=body_04, timeout=10)
                task_18 = session.post(url_acnt, headers=headers_18, json=body_18, timeout=10)
                resp_04, resp_18 = await asyncio.gather(task_04, task_18)
                
                cash_balance = 0.0
                if resp_04.status == 200:
                    res_04 = await resp_04.json()
                    # entr: 예수금
                    cash_balance = float(res_04.get("entr", 0))
                
                total_yield = 0.0
                holdings = []
                if resp_18.status == 200:
                    res_18 = await resp_18.json()
                    # tot_prft_rt: 총수익률
                    total_yield = float(res_18.get("tot_prft_rt", 0))
                    
                    for item in res_18.get("acnt_evlt_remn_indv_tot", []):
                        stk_cd = item.get("stk_cd", "").replace("A", "").strip()
                        if stk_cd:
                            holdings.append(stk_cd)
                
                return cash_balance, total_yield, holdings
        except Exception as e:
            logger.error(f"Kiwoom get_balance error: {e}")
            return None, None, None

    async def get_holdings(self) -> dict:
        if not await self.ensure_valid_token():
            return {}
            
        url_acnt = f"{self.base_url}/api/dostk/acnt"
        headers_18 = self._get_headers(self.access_token, api_id="kt00018")
        body_18 = {"qry_tp": "1", "dmst_stex_tp": "KRX"}
        
        try:
            async with aiohttp.ClientSession() as session:
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

    async def execute_order(self, symbol: str, side: str, qty: int, price: float, allow_extended_hours: bool) -> tuple:
        if not await self.ensure_valid_token():
            return False, "인증 토큰 발급 실패"
            
        api_id = "kt10000" if side.lower() == "buy" else "kt10001"
        url = f"{self.base_url}/api/dostk/ordr"
        headers = self._get_headers(self.access_token, api_id=api_id)
        
        # 0:보통, 3:시장가. Kiwoom 에서는 "0" 또는 "3"
        trde_tp = "3" if price == 0 else "0"
        
        body = {
            "dmst_stex_tp": "KRX",
            "stk_cd": symbol,
            "ord_qty": str(int(qty)),
            "ord_uv": str(int(price)),
            "trde_tp": trde_tp,
            "cond_uv": ""
        }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(url, headers=headers, json=body, timeout=10) as resp:
                    res = await resp.json()
                    if resp.status == 200 and res.get("return_code") == 0:
                        ord_no = res.get("ord_no")
                        logger.info(f"[Kiwoom] 주문 전송 성공 - {symbol} {side} {qty}주 @ {price} (ord_no: {ord_no})")
                        return True, ""
                    else:
                        msg = res.get("return_msg", "알 수 없는 에러")
                        logger.error(f"[Kiwoom] 주문 전송 실패: {msg}")
                        return False, msg
        except Exception as e:
            logger.error(f"Kiwoom execute_order error: {e}")
            return False, str(e)

    async def fetch_rest_current_prices(self, symbols: list) -> dict:
        if not await self.ensure_valid_token():
            return {}
            
        url = f"{self.base_url}/api/dostk/stkinfo"
        headers = self._get_headers(self.access_token, api_id="ka10100")
        
        async def fetch_single(session, symbol):
            body = {"stk_cd": symbol}
            try:
                async with session.post(url, headers=headers, json=body, timeout=5) as resp:
                    if resp.status == 200:
                        res = await resp.json()
                        price = float(res.get("cur_prc", 0))
                        # 전일대비부호: 1:상한가, 2:상승, 3:보합, 4:하한가, 5:하락
                        sig = res.get("pred_pre_sig", "3")
                        diff = float(res.get("pred_pre", 0))
                        
                        # 하락인 경우 음수 처리
                        if sig in ["4", "5"]:
                            diff = -abs(diff)
                        else:
                            diff = abs(diff)
                            
                        if price > 0:
                            return symbol, {"price": price, "diff": diff}
            except Exception as e:
                logger.error(f"Kiwoom fetch_rest_current_prices error for {symbol}: {e}")
            return symbol, None

        results = {}
        async with aiohttp.ClientSession() as session:
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
            
        ws_url = "wss://api.kiwoom.com:10000" if not self.is_mock else "wss://mockapi.kiwoom.com:10000"
        
        logger.info(f"[Kiwoom] 웹소켓 연결 시작 - 종목: {symbols}")
        try:
            self.ws = await websockets.connect(ws_url, extra_headers={
                "authorization": f"Bearer {self.access_token}",
                "User-Agent": "Mozilla/5.0"
            })
            
            # 주식체결(0B) 구독 요청
            subscribe_payload = {
                "trnm": "REG",
                "grp_no": "1",
                "refresh": "1",
                "data": [
                    {
                        "item": symbols,
                        "type": ["0B"]
                    }
                ]
            }
            
            await self.ws.send(json.dumps(subscribe_payload))
            logger.info("[Kiwoom] 웹소켓 구독 메시지 전송 성공")
            
            async for message in self.ws:
                try:
                    data = json.loads(message)
                    if data.get("trnm") == "REAL" and "data" in data:
                        for d in data["data"]:
                            if d.get("type") == "0B":
                                symbol = d.get("item")
                                values = d.get("values", {})
                                price_str = values.get("10", "0").replace("+", "").replace("-", "")
                                if price_str and symbol:
                                    price = float(price_str)
                                    on_message_callback(symbol, price)
                except Exception as e:
                    logger.error(f"[Kiwoom] WS Parse Error: {e}")
        except Exception as e:
            logger.error(f"[Kiwoom] WS Connection Error: {e}")

    async def close_websocket(self):
        if self.ws:
            await self.ws.close()
            self.ws = None
