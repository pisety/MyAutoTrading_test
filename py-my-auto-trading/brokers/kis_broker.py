import os
import time
import json
import logging
import asyncio
import aiohttp
import websockets
from typing import Dict, Optional, Tuple, List, Callable

from brokers.base_broker import BaseBroker

logger = logging.getLogger("KISBroker")

class KISBroker(BaseBroker):
    def __init__(self, app_key: str, app_secret: str, account_number: str, is_mock: bool = False, is_overseas: bool = True):
        super().__init__(app_key, app_secret, account_number, is_mock)
        self.is_overseas = is_overseas
        self.base_url = "https://openapivts.koreainvestment.com:29443" if is_mock else "https://openapi.koreainvestment.com:9443"
        self.ws_url = "ws://ops.koreainvestment.com:31000/tryitout/HDFSCNT0" if self.is_mock else "ws://ops.koreainvestment.com:21000/tryitout/HDFSCNT0"
        self.acnt_prdt_cd = "01"
        self.ws_task = None
        self.ws_connection = None
        self._on_message_callback = None
        self._running_ws = False
        self._valid_tr_keys = []
        self._session: Optional[aiohttp.ClientSession] = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def ensure_valid_token(self) -> bool:
        now = time.time()
        if self.access_token and (self.token_expires_at - now > 3600):
            return True
            
        self._token_cache_filename = f".kis_token_{self.app_key[:10] if self.app_key else 'default'}.json"
        cache = self._load_token_cache()
        if cache and cache.get("access_token") and (cache.get("token_expires_at", 0) - now > 3600):
            self.access_token = cache["access_token"]
            self.token_expires_at = cache["token_expires_at"]
            return True

        headers = {"content-type": "application/json"}
        body = {
            "grant_type": "client_credentials",
            "appkey": self.app_key,
            "appsecret": self.app_secret
        }

        try:
            session = await self._get_session()
            url = f"{self.base_url}/oauth2/tokenP"
            async with session.post(url, headers=headers, json=body, timeout=10) as res:
                    data = await res.json()
                    
                    if "access_token" in data:
                        self.access_token = data["access_token"]
                        expires_in = int(data.get("expires_in", 86400))
                        self.token_expires_at = now + expires_in
                        
                        self._save_token_cache({
                            "access_token": self.access_token,
                            "token_expires_at": self.token_expires_at
                        })
                        
                        logger.info(f"KIS Token issued for account: {self.account_number}")
                        return True
                    else:
                        logger.error(f"Token error: {data}")
        except Exception as e:
            logger.error(f"Network error during token issuance: {e}")
            
        return False

    async def _get_overseas_balance(self) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict]]]:
        tr_id = "VTTS3012R" if self.is_mock else "TTTS3012R"
        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }
        params = {
            "CANO": self.account_number,
            "ACNT_PRDT_CD": self.acnt_prdt_cd,
            "OVRS_EXCG_CD": "NASD",
            "TR_CRCY_CD": "USD",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": ""
        }

        try:
            async with aiohttp.ClientSession() as session:
                url = f"{self.base_url}/uapi/overseas-stock/v1/trading/inquire-balance"
                
                async with session.get(url, headers=headers, params=params, timeout=10) as res:
                    data = await res.json()
                    
                    held_value = 0.0
                    yield_rate = 0.0
                    holdings = []

                    if data.get("rt_cd") == "0":
                        output1 = data.get("output1") or []
                        for item in output1:
                            sym = item.get("ovrs_pdno", "")
                            name = item.get("ovrs_item_name", sym)
                            qty = float(item.get("ovrs_cblc_qty", 0))
                            pchs_avg = float(item.get("pchs_avg_pric", 0))
                            curr_price = float(item.get("now_pric2", 0))
                            pfls_rt = float(item.get("evlu_pfls_rt", 0))
                            pfls_amt = float(item.get("frcr_evlu_pfls_amt", 0))
                                
                            if not sym or qty <= 0: continue
                            
                            holdings.append({
                                "symbol": sym,
                                "name": name,
                                "quantity": qty,
                                "purchase_avg_price": pchs_avg,
                                "current_price": curr_price,
                                "profit_loss_rate": pfls_rt,
                                "profit_loss_amount": pfls_amt
                            })

                        output2 = data.get("output2")
                        if isinstance(output2, list):
                            summary = output2[0] if len(output2) > 0 else {}
                        elif isinstance(output2, dict):
                            summary = output2
                        else:
                            summary = {}

                        if "frcr_pchs_amt1" in summary:
                            held_value = float(summary.get("frcr_pchs_amt1", 0))
                            yield_rate = float(summary.get("tot_pftrt", 0))
                        else:
                            held_value = float(summary.get("tot_evlu_pamt", 0))
                            yield_rate = float(summary.get("evlu_erng_rt1", 0))
                        avail_cash = float(summary.get("frcr_drwg_psbl_amt_1", summary.get("frcr_use_psbl_amt", 0)))
                        
                        # Fallback for integrated margin / unconverted KRW deposit
                        if avail_cash <= 0.0:
                            try:
                                psbl_tr_id = "VTTS3007R" if self.is_mock else "TTTS3007R"
                                psbl_headers = dict(headers)
                                psbl_headers["tr_id"] = psbl_tr_id
                                psbl_params = {
                                    "CANO": self.account_number,
                                    "ACNT_PRDT_CD": self.acnt_prdt_cd,
                                    "OVRS_EXCG_CD": "NASD",
                                    "OVRS_ORD_UNPR": "100",
                                    "ITEM_CD": "SOXL"
                                }
                                psbl_url = f"{self.base_url}/uapi/overseas-stock/v1/trading/inquire-psamount"
                                async with session.get(psbl_url, headers=psbl_headers, params=psbl_params, timeout=5) as psbl_res:
                                    psbl_data = await psbl_res.json()
                                    if psbl_data.get("rt_cd") == "0":
                                        p_out = psbl_data.get("output", {})
                                        ord_frcr = float(p_out.get("ord_psbl_frcr_amt", p_out.get("ovrs_ord_psbl_amt", 0)))
                                        if ord_frcr > 0:
                                            avail_cash = ord_frcr
                            except Exception as e:
                                logger.debug(f"Integrated margin lookup failed: {e}")

                        total_balance = held_value + avail_cash
                    else:
                        logger.error(f"Balance check failed: {data.get('msg1')}")
                        return None, None, None, None
                            
                    return total_balance, yield_rate, avail_cash, holdings
        except Exception as e:
            logger.error(f"Balance check error: {e}")
            return None, None, None, None

    async def _get_domestic_balance(self) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict]]]:
        tr_id = "VTTC8434R" if self.is_mock else "TTTC8434R"
        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }
        params = {
            "CANO": self.account_number,
            "ACNT_PRDT_CD": self.acnt_prdt_cd,
            "AFHR_FLPR_YN": "N",
            "OFL_YN": "",
            "INQR_DVSN": "02",
            "UNPR_DVSN": "01",
            "FUND_STTL_ICLD_YN": "N",
            "FNCG_AMT_AUTO_RDPT_YN": "N",
            "PRCS_DVSN": "00",
            "CTX_AREA_FK100": "",
            "CTX_AREA_NK100": ""
        }

        try:
            async with aiohttp.ClientSession() as session:
                url = f"{self.base_url}/uapi/domestic-stock/v1/trading/inquire-balance"
                
                async with session.get(url, headers=headers, params=params, timeout=10) as res:
                    data = await res.json()
                    
                    yield_rate = 0.0
                    holdings = []

                    if data.get("rt_cd") == "0":
                        output1 = data.get("output1") or []
                        for item in output1:
                            sym = item.get("pdno", "")
                            name = item.get("prdt_name", sym)
                            qty = float(item.get("hldg_qty", 0))
                            pchs_avg = float(item.get("pchs_avg_pric", 0))
                            curr_price = float(item.get("prpr", 0))
                            pfls_rt = float(item.get("evlu_pfls_rt", 0))
                            pfls_amt = float(item.get("evlu_pfls_amt", 0))
                                
                            if not sym or qty <= 0: continue
                            
                            holdings.append({
                                "symbol": sym,
                                "name": name,
                                "quantity": qty,
                                "purchase_avg_price": pchs_avg,
                                "current_price": curr_price,
                                "profit_loss_rate": pfls_rt,
                                "profit_loss_amount": pfls_amt
                            })

                        output2 = data.get("output2")
                        if isinstance(output2, list):
                            summary = output2[0] if len(output2) > 0 else {}
                        elif isinstance(output2, dict):
                            summary = output2
                        else:
                            summary = {}

                        total_balance = float(summary.get("tot_evlu_amt", 0))
                        avail_cash = float(summary.get("dnca_tot_amt", 0))
                        if avail_cash == 0:
                            avail_cash = float(summary.get("prvs_rcdl_excc_amt", 0))
                        if total_balance == 0:
                            total_balance = float(summary.get("scts_evlu_amt", 0)) + avail_cash
                    else:
                        logger.error(f"Balance check failed: {data.get('msg1')}")
                        return None, None, None, None
                            
                    return total_balance, yield_rate, avail_cash, holdings
        except Exception as e:
            logger.error(f"Balance check error: {e}")
            return None, None, None, None

    async def get_balance(self) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict]]]:
        if not await self.ensure_valid_token():
            return None, None, None, None

        if self.is_overseas:
            return await self._get_overseas_balance()
        else:
            return await self._get_domestic_balance()

    async def execute_order(self, symbol: str, side: str, qty: float, price: float, allow_extended_hours: bool = False, ord_dvsn: str = None) -> Tuple[bool, str]:
        if not await self.ensure_valid_token():
            return False, "KIS 인증 토큰 발급 실패"

        if self.is_overseas:
            tr_id = "VTTT1002U" if side == "BUY" else "VTTT1006U"
            if not self.is_mock:
                tr_id = "TTTT1002U" if side == "BUY" else "TTTT1006U"
        else:
            tr_id = "VTTC0802U" if side == "BUY" else "VTTC0801U"
            if not self.is_mock:
                tr_id = "TTTC0802U" if side == "BUY" else "TTTC0801U"

        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }

        # 스마트 롤링 로직: 정규장(00) -> 프리장(34) -> 애프터(32) -> 데이마켓(33)
        # 모의투자는 시간외(34/32/33) 지원 안 되므로 00 지정가만 사용
        if self.is_mock:
            divisions = [ord_dvsn] if ord_dvsn else ["00"]
        else:
            divisions = [ord_dvsn] if ord_dvsn else (["00", "34", "32", "33"] if allow_extended_hours else ["00"])
        last_error = "주문 실패"

        # 해외 거래소 코드 자동 매핑
        sym_clean = symbol.strip().upper()
        if sym_clean in {"SOXL", "SOXS", "SPY", "VOO", "QLD", "SSO", "DIA", "IWM", "UVXY"}:
            ovrs_excg = "AMEX"
        elif sym_clean in {"IONQ", "PLTR", "BABA", "TSM", "NIO", "IBM", "DIS", "JNJ"}:
            ovrs_excg = "NYSE"
        else:
            ovrs_excg = "NASD"

        try:
            session = await self._get_session()
            if self.is_overseas:
                url = f"{self.base_url}/uapi/overseas-stock/v1/trading/order"
                
                for dvsn in divisions:
                    body = {
                        "CANO": self.account_number,
                        "ACNT_PRDT_CD": self.acnt_prdt_cd,
                        "OVRS_EXCG_CD": ovrs_excg,
                        "PDNO": sym_clean,
                        "ORD_QTY": str(int(float(qty))),
                        "OVRS_ORD_UNPR": f"{price:.4f}" if price < 1.0 else f"{price:.2f}",
                        "ORD_SVR_DVSN_CD": "0",
                        "ORD_DVSN": dvsn
                    }
                    
                    async with session.post(url, headers=headers, json=body, timeout=10) as res:
                        data = await res.json()
                        if data.get("rt_cd") == "0":
                            output = data.get("output", {})
                            odno = output.get("ODNO", "") if isinstance(output, dict) else ""
                            logger.info(f"Order success: {side} {symbol} {qty} @ {price} (DVSN: {dvsn}, ODNO: {odno})")
                            return True, f"주문 성공|{odno}" if odno else "주문 성공"
                        else:
                            msg = data.get("msg1", "")
                            logger.warning(f"Order failed with DVSN {dvsn}: {msg}")
                            last_error = msg
                            if "시간" in msg or "불가" in msg or "가능" in msg:
                                continue # 다음 구분 코드로 재시도
                            else:
                                break # 시간 관련 에러가 아니면 즉시 실패 (예: 잔고부족)
            else:
                url = f"{self.base_url}/uapi/domestic-stock/v1/trading/order-cash"
                def _round_to_tick(p_val: float) -> int:
                    p = int(p_val)
                    if p < 2000: return p
                    elif p < 5000: return p - (p % 5)
                    elif p < 20000: return p - (p % 10)
                    elif p < 50000: return p - (p % 50)
                    elif p < 200000: return p - (p % 100)
                    elif p < 500000: return p - (p % 500)
                    else: return p - (p % 1000)

                actual_dvsn = ord_dvsn if ord_dvsn else ("01" if price <= 0 else "00")
                ord_unpr = "0" if actual_dvsn in ["01", "03"] else str(_round_to_tick(price))
                
                body = {
                    "CANO": self.account_number,
                    "ACNT_PRDT_CD": self.acnt_prdt_cd,
                    "PDNO": symbol.strip(),
                    "ORD_DVSN": actual_dvsn,
                    "ORD_QTY": str(int(float(qty))),
                    "ORD_UNPR": ord_unpr
                }
                async with session.post(url, headers=headers, json=body, timeout=10) as res:
                    data = await res.json()
                    if data.get("rt_cd") == "0":
                        output = data.get("output", {})
                        odno = output.get("ODNO", "") if isinstance(output, dict) else ""
                        logger.info(f"Domestic Order success: {side} {symbol} {qty} @ {price} (ODNO: {odno})")
                        return True, f"주문 성공|{odno}" if odno else "주문 성공"
                    else:
                        msg = data.get("msg1", "")
                        logger.warning(f"Domestic Order failed: {msg}")
                        last_error = msg
                                
        except Exception as e:
            logger.error(f"Order exception: {e}")
            last_error = f"시스템 오류: {e}"

        return False, last_error

    async def cancel_all_orders(self, symbol: str) -> bool:
        # KIS broker uses LOC which auto cancels
        return True

    async def cancel_overseas_order(self, order_no: str, symbol: str, qty: int) -> Tuple[bool, str]:
        if not await self.ensure_valid_token():
            return False, "KIS 토큰 발급 실패"

        tr_id = "VTTT1004U" if self.is_mock else "TTTT1004U"
        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }
        url = f"{self.base_url}/uapi/overseas-stock/v1/trading/order-rvsecncl"
        body = {
            "CANO": self.account_number,
            "ACNT_PRDT_CD": self.acnt_prdt_cd,
            "OVRS_EXCG_CD": "NASD",
            "PDNO": symbol.strip(),
            "ORD_DVSN": "00",
            "RVSE_CNCL_DVSN_CD": "02",
            "ORD_QTY": str(int(float(qty))),
            "ORD_UNPR": "0",
            "ORGN_ODNO": str(order_no).strip()
        }
        try:
            session = await self._get_session()
            async with session.post(url, headers=headers, json=body, timeout=10) as res:
                data = await res.json()
                if data.get("rt_cd") == "0":
                    logger.info(f"Overseas Order cancel success: ODNO {order_no} ({symbol} {qty}주)")
                    return True, "취소 성공"
                else:
                    msg = data.get("msg1", "")
                    logger.warning(f"Overseas Order cancel failed for ODNO {order_no}: {msg}")
                    return False, msg
        except Exception as e:
            logger.error(f"Overseas Cancel exception: {e}")
            return False, f"오류: {e}"

    async def cancel_domestic_order(self, order_no: str, symbol: str, qty: int, ord_dvsn: str = "01") -> Tuple[bool, str]:
        if not await self.ensure_valid_token():
            return False, "KIS 토큰 발급 실패"

        tr_id = "VTTC0803U" if self.is_mock else "TTTC0803U"
        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }
        url = f"{self.base_url}/uapi/domestic-stock/v1/trading/order-rvsecncl"
        body = {
            "CANO": self.account_number,
            "ACNT_PRDT_CD": self.acnt_prdt_cd,
            "KRX_FWDG_ORD_ORG_NO": "",
            "ORGN_ODNO": order_no,
            "ORD_DVSN": ord_dvsn,
            "RVSE_CNCL_DVSN_CD": "02",
            "ORD_QTY": "0",
            "ORD_UNPR": "0",
            "QTY_ALL_ORD_YN": "Y"
        }
        try:
            session = await self._get_session()
            async with session.post(url, headers=headers, json=body, timeout=10) as res:
                data = await res.json()
                if data.get("rt_cd") == "0":
                    logger.info(f"Domestic Order cancel success: {order_no} ({symbol})")
                    return True, "취소 성공"
                else:
                    msg = data.get("msg1", "")
                    logger.warning(f"Domestic Order cancel failed: {msg}")
                    return False, msg
        except Exception as e:
            logger.error(f"Cancel exception: {e}")
            return False, f"오류: {e}"

    async def inquire_order_fill_status(self, order_no: str) -> Tuple[int, int]:
        """주문번호로 일별주문체결조회(TTTC8001R)를 호출하여 (체결수량, 미체결잔량) 반환"""
        if not await self.ensure_valid_token():
            return 0, -1

        tr_id = "VTTC8001R" if self.is_mock else "TTTC8001R"
        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret,
            "tr_id": tr_id
        }
        today_str = datetime.now(timezone(timedelta(hours=9))).strftime("%Y%m%d")
        params = {
            "CANO": self.account_number,
            "ACNT_PRDT_CD": self.acnt_prdt_cd,
            "INQR_STRT_DT": today_str,
            "INQR_END_DT": today_str,
            "SLL_BUY_DVSN_CD": "00",
            "INQR_DVSN": "00",
            "PDNO": "",
            "CCLD_DVSN": "00",
            "ORD_GNO_BRNO": "",
            "ODNO": order_no,
            "INQR_DVSN_3": "00",
            "INQR_DVSN_1": "",
            "CTX_AREA_FK100": "",
            "CTX_AREA_NK100": ""
        }
        try:
            session = await self._get_session()
            url = f"{self.base_url}/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
            async with session.get(url, headers=headers, params=params, timeout=10) as res:
                    data = await res.json()
                    if data.get("rt_cd") == "0":
                        output1 = data.get("output1") or []
                        for item in output1:
                            if item.get("odno", "") == order_no or not order_no:
                                tot_ccld_qty = int(float(item.get("tot_ccld_qty", 0)))
                                rmnd_qty = int(float(item.get("rmnd_qty", 0)))
                                return tot_ccld_qty, rmnd_qty
                        if output1:
                            item = output1[0]
                            tot_ccld_qty = int(float(item.get("tot_ccld_qty", 0)))
                            rmnd_qty = int(float(item.get("rmnd_qty", 0)))
                            return tot_ccld_qty, rmnd_qty
                    else:
                        logger.warning(f"Inquire fill status failed for ODNO {order_no}: {data.get('msg1')}")
        except Exception as e:
            logger.error(f"inquire_order_fill_status exception: {e}")
        return 0, -1

    async def fetch_rest_current_prices(self, symbols: List[str]) -> Dict[str, float]:
        if not await self.ensure_valid_token():
            return {}

        headers = {
            "content-type": "application/json",
            "authorization": f"Bearer {self.access_token}",
            "appkey": self.app_key,
            "appsecret": self.app_secret
        }
        if self.is_overseas:
            headers["tr_id"] = "HHDFS00000300"
        else:
            headers["tr_id"] = "FHKST01010100"

        result = {}
        session = await self._get_session()
        for sym in symbols:
            sym_clean = sym.strip()
            if not sym_clean: continue
            
            if self.is_overseas:
                found = False
                for excd in ["NAS", "NYS", "AMS"]:
                    params = {"AUTH": "", "EXCD": excd, "SYMB": sym_clean}
                    try:
                        url = f"{self.base_url}/uapi/overseas-price/v1/quotations/price"
                        async with session.get(url, headers=headers, params=params, timeout=10) as res:
                            data = await res.json()
                            if data.get("rt_cd") == "0":
                                output = data.get("output", {})
                                price_str = output.get("last", "")
                                if price_str:
                                    result[sym] = float(price_str)
                                    found = True
                                    break
                    except Exception as e:
                        logger.debug(f"REST fetch error {sym} on {excd}: {e}")
                    await asyncio.sleep(0.2)
                
                if not found:
                    logger.warning(f"Failed to fetch REST price for {sym} from KIS on all exchanges. Falling back to Yahoo Finance...")
                    try:
                        yf_url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym_clean}"
                        yf_headers = {'User-Agent': 'Mozilla/5.0'}
                        async with session.get(yf_url, headers=yf_headers, timeout=10) as res:
                            if res.status == 200:
                                data = await res.json()
                                if "chart" in data and "result" in data["chart"] and data["chart"]["result"]:
                                    meta = data["chart"]["result"][0]["meta"]
                                    price = meta.get("regularMarketPrice")
                                    prev_close = meta.get("previousClose")
                                    if price:
                                        chg = ((float(price) - float(prev_close)) / float(prev_close) * 100) if prev_close and float(prev_close) > 0 else 0.0
                                        result[sym] = (float(price), chg)
                                        found = True
                                        logger.info(f"Successfully fetched price for {sym} from Yahoo Finance: {price}")
                    except Exception as e:
                        logger.error(f"Yahoo Finance fallback error for {sym}: {e}")
                    
                if not found:
                    logger.warning(f"Failed to fetch REST price for {sym} on all exchanges including fallback.")
            else:
                params = {"FID_COND_MRKT_DIV_CODE": "J", "FID_INPUT_ISCD": sym_clean}
                try:
                    url = f"{self.base_url}/uapi/domestic-stock/v1/quotations/inquire-price"
                    async with session.get(url, headers=headers, params=params, timeout=10) as res:
                        data = await res.json()
                        if data.get("rt_cd") == "0":
                            output = data.get("output", {})
                            price_str = output.get("stck_prpr", "")
                            chg_str = output.get("prdy_ctrt", "0")
                            if price_str:
                                result[sym] = (float(price_str), float(chg_str))
                        else:
                            logger.debug(f"Domestic REST fetch miss {sym}: {data.get('msg1')}")
                except Exception as e:
                    logger.debug(f"Domestic REST fetch error {sym}: {e}")
                await asyncio.sleep(0.2)

        return result

    async def get_websocket_approval_key(self) -> Optional[str]:
        headers = {"content-type": "application/json"}
        body = {
            "grant_type": "client_credentials",
            "appkey": self.app_key,
            "secretkey": self.app_secret
        }
        try:
            session = await self._get_session()
            url = f"{self.base_url}/oauth2/Approval"
            async with session.post(url, headers=headers, json=body, timeout=10) as res:
                    data = await res.json()
                    return data.get("approval_key")
        except Exception as e:
            logger.error(f"WS approval_key error: {e}")
        return None

    async def _send_ws_subscription(self, ws, approval_key, tr_key):
        tr_id = "HDFSCNT0" if self.is_overseas else "H0STCNT0"
        msg = {
            "header": {
                "approval_key": approval_key,
                "custtype": "P",
                "tr_type": "1",
                "content-type": "utf-8"
            },
            "body": {
                "input": {
                    "tr_id": tr_id,
                    "tr_key": tr_key
                }
            }
        }
        await ws.send(json.dumps(msg))
        logger.info(f"Sent subscription for {tr_key}")

    async def connect_websocket(self, symbols: List[str], on_message_callback: Callable[[str, float], None]):
        self._on_message_callback = on_message_callback
        approval_key = await self.get_websocket_approval_key()
        if not approval_key:
            return

        self.ws_url = "ws://ops.koreainvestment.com:31000" if self.is_mock else "ws://ops.koreainvestment.com:21000"

        # 버그 수정: 이전 구독 잔재(Ghost cache) 초기화
        self._valid_tr_keys = []
        self._pending_tr_keys = []
        
        if self.is_overseas:
            KNOWN_MAP = {
                "TSLA": "DNAS", "AAPL": "DNAS", "QQQ": "DNAS", "TQQQ": "DNAS", "SQQQ": "DNAS",
                "NVDA": "DNAS", "MSFT": "DNAS", "AMZN": "DNAS", "GOOGL": "DNAS", "META": "DNAS",
                "SOXS": "DAMS", "SOXL": "DAMS", "SPY": "DAMS", "VOO": "DAMS", "QLD": "DAMS",
                "IONQ": "DNYO", "PLTR": "DNYO", "BABA": "DNYO", "TSM": "DNYO", "NIO": "DNYO"
            }
            for sym in symbols:
                s = sym.strip()
                if not s: continue
                if s in KNOWN_MAP:
                    self._valid_tr_keys.append(f"{KNOWN_MAP[s]}{s}")
                else:
                    self._pending_tr_keys.extend([f"DNAS{s}", f"DNYO{s}", f"DAMS{s}"])
        else:
            for sym in symbols:
                s = sym.strip()
                if not s: continue
                self._valid_tr_keys.append(s)

        self._running_ws = True

        async def _ws_loop():
            while self._running_ws:
                try:
                    async with websockets.connect(self.ws_url, ping_interval=30) as ws:
                        self.ws_connection = ws
                        logger.info("KIS WebSocket connected.")
                        
                        # Subscribe to known valid ones
                        for tr_key in self._valid_tr_keys:
                            await self._send_ws_subscription(ws, approval_key, tr_key)
                            
                        # Discover pending ones safely
                        while self._pending_tr_keys:
                            test_key = self._pending_tr_keys[0]
                            await self._send_ws_subscription(ws, approval_key, test_key)
                            
                            await asyncio.sleep(1.0)
                            
                            if ws.closed:
                                logger.warning(f"WebSocket dropped after {test_key}. Invalid exchange.")
                                self._pending_tr_keys.pop(0)
                                break # Force reconnect
                                
                            logger.info(f"Verified valid exchange: {test_key}")
                            self._valid_tr_keys.append(test_key)
                            self._pending_tr_keys.pop(0)
                            sym_base = test_key[4:]
                            # Remove other prefixes for this symbol
                            self._pending_tr_keys = [k for k in self._pending_tr_keys if k[4:] != sym_base]

                        if ws.closed:
                            continue

                        async for message in ws:
                            if isinstance(message, str):
                                if message.startswith("{"): continue
                                parts = message.split("|")
                                expected_tr_id = "HDFSCNT0" if self.is_overseas else "H0STCNT0"
                                if len(parts) >= 4 and parts[1] == expected_tr_id:
                                    fields = parts[3].split("^")
                                    if self.is_overseas and len(fields) >= 12:
                                        tk = fields[0]
                                        price_str = fields[11]
                                        chg_str = fields[14] if len(fields) > 14 else "0"
                                        sym = tk[4:] if tk.startswith("DNAS") or tk.startswith("DNYO") or tk.startswith("DAMS") else tk
                                        if price_str and self._on_message_callback:
                                            self._on_message_callback(sym, float(price_str), float(chg_str))
                                    elif not self.is_overseas and len(fields) >= 3:
                                        sym = fields[0]
                                        price_str = fields[2]
                                        chg_str = fields[5] if len(fields) > 5 else "0"
                                        if price_str and self._on_message_callback:
                                            self._on_message_callback(sym, float(price_str), float(chg_str))
                except Exception as e:
                    logger.error(f"WebSocket error: {e}")
                    self.ws_connection = None
                    await asyncio.sleep(1.5)

        self.ws_task = asyncio.create_task(_ws_loop())

    async def close_websocket(self):
        self._running_ws = False
        if self.ws_connection:
            await self.ws_connection.close()
        if self.ws_task:
            self.ws_task.cancel()

