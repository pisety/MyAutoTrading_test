import os
import time
import json
import uuid
import base64
import logging
import asyncio
from datetime import datetime, timezone, timedelta
import aiohttp
import websockets
from typing import Dict, Optional, Tuple, List, Callable, Any

from brokers.base_broker import BaseBroker

logger = logging.getLogger("MeritzBroker")

def _get_mac_address() -> str:
    """메리츠 게이트웨이 필수 헤더 (12자리 대문자 헥스)"""
    n = uuid.getnode()
    return "".join(f"{(n >> s) & 0xFF:02X}" for s in range(40, -1, -8))

def _extract_jwt_exp(token: str) -> Optional[float]:
    """JWT access_token 페이로드에서 실제 만료 시각(exp) 추출"""
    if not token or "." not in token:
        return None
    try:
        parts = token.split(".")
        if len(parts) < 2:
            return None
        payload_b64 = parts[1]
        payload_b64 += "=" * (-len(payload_b64) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_b64).decode("utf-8"))
        exp = payload.get("exp")
        return float(exp) if exp else None
    except Exception:
        return None

def _round_to_krx_tick(price: int) -> int:
    """KRX 국내주식 가격대별 호가 단위 보정"""
    if price <= 0:
        return 0
    if price < 2000:
        tick = 1
    elif price < 5000:
        tick = 5
    elif price < 20000:
        tick = 10
    elif price < 50000:
        tick = 50
    elif price < 200000:
        tick = 100
    elif price < 500000:
        tick = 500
    else:
        tick = 1000
    return (price // tick) * tick

# 미국 대표 종목 거래소 벤더 ID (0537: 나스닥 OQ, 0321: 뉴욕 NY, 0066: 아멕스 AX)
KNOWN_US_EXCH_MAP = {
    "TSLA": "0537", "AAPL": "0537", "QQQ": "0537", "TQQQ": "0537", "SQQQ": "0537",
    "NVDA": "0537", "MSFT": "0537", "AMZN": "0537", "GOOGL": "0537", "META": "0537",
    "SOXX": "0537", "AMD": "0537", "NFLX": "0537", "INTC": "0537", "COIN": "0537",
    "MSTR": "0537", "AVGO": "0537", "COST": "0537", "CSCO": "0537", "ADBE": "0537",
    "SOXS": "0066", "SOXL": "0066", "SPY": "0066", "VOO": "0066", "QLD": "0066",
    "DIA": "0066", "IWM": "0066", "GLD": "0066", "SLV": "0066", "UVXY": "0066",
    "IONQ": "0321", "PLTR": "0321", "BABA": "0321", "TSM": "0321", "NIO": "0321",
    "U": "0321", "BA": "0321", "DIS": "0321", "JNJ": "0321", "JPM": "0321"
}

EXCH_ID_TO_CODE = {"0537": "OQ", "0321": "NY", "0066": "AX"}
EXCH_CODE_TO_ID = {"OQ": "0537", "NY": "0321", "AX": "0066"}
TOKEN_ERR_CODES = {"EGW00121", "EGW00123"}
EMPTY_DATA_CODES = {"5820", "5822"}

class MeritzBroker(BaseBroker):
    def __init__(self, app_key: str, app_secret: str, account_number: str = "", is_mock: bool = False, is_overseas: bool = False):
        super().__init__(app_key, app_secret, account_number, is_mock)
        self.is_overseas = is_overseas
        self.base_url = "https://openapi.imeritz.com:9443"
        self.ws_url = "wss://openapi.imeritz.com:29443/websocket"
        self.mac_address = _get_mac_address()
        self._symbol_exch_map: Dict[str, str] = {}  # symbol -> exch_id (0537, 0321, 0066)
        self._token_lock = asyncio.Lock()
        self._token_cache_filename = f".meritz_token_{self.app_key[:10] if self.app_key else 'default'}.json"
        
        self.ws_task = None
        self.ws_connection = None
        self._on_message_callback = None
        self._running_ws = False
        self._session: Optional[aiohttp.ClientSession] = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    def _invalidate_token(self):
        self.access_token = ""
        self.token_expires_at = 0.0
        try:
            cache_path = os.path.join(os.path.expanduser("~"), self._token_cache_filename)
            if os.path.exists(cache_path):
                os.remove(cache_path)
        except Exception as e:
            logger.debug(f"Failed to remove Meritz token cache: {e}")

    @staticmethod
    def _is_token_error(body: Any) -> bool:
        if not isinstance(body, dict):
            return False
        rsp_cd = str(body.get("rsp_cd", "")).strip()
        rsp_msg = str(body.get("rsp_msg", "")).strip()
        return rsp_cd in TOKEN_ERR_CODES or "유효하지 않은 token" in rsp_msg

    async def ensure_valid_token(self, force_refresh: bool = False) -> bool:
        async with self._token_lock:
            now = time.time()
            if force_refresh:
                self._invalidate_token()

            # 메리츠 서버는 기존 JWT가 만료되기 전에 재요청하면 기존 JWT를 그대로 반환하므로,
            # 만료 여유 시간을 60초로 설정하고 반드시 JWT 내부 exp 클레임을 기준으로 검증합니다.
            if not force_refresh and self.access_token:
                jwt_exp = _extract_jwt_exp(self.access_token) or self.token_expires_at
                if jwt_exp - now > 60:
                    self.token_expires_at = jwt_exp
                    return True

            if not force_refresh:
                cache = self._load_token_cache()
                if cache and cache.get("access_token"):
                    cached_token = cache["access_token"]
                    jwt_exp = _extract_jwt_exp(cached_token) or float(cache.get("token_expires_at", 0))
                    if jwt_exp - now > 60:
                        self.access_token = cached_token
                        self.token_expires_at = jwt_exp
                        return True

            url = f"{self.base_url}/oauth2/token"
            headers = {"content-type": "application/x-www-form-urlencoded"}
            data = {
                "grant_type": "client_credentials",
                "client_id": self.app_key,
                "client_secret": self.app_secret,
                "scope": "login"
            }

            try:
                session = await self._get_session()
                async with session.post(url, headers=headers, data=data, timeout=15) as res:
                    if res.status == 200:
                        d = await res.json()
                        new_token = d.get("access_token", "")
                        expires_in = int(d.get("expires_in", 43200))
                        jwt_exp = _extract_jwt_exp(new_token)
                        self.access_token = new_token
                        self.token_expires_at = jwt_exp if (jwt_exp and jwt_exp > now) else (now + expires_in)
                        self._save_token_cache({
                            "access_token": self.access_token,
                            "token_expires_at": self.token_expires_at
                        })
                        logger.info(f"Meritz Access Token issued successfully (valid for {int(self.token_expires_at - now)}s).")
                        return True
                    else:
                        text = await res.text()
                        logger.error(f"Meritz token issuance failed HTTP {res.status}: {text}")
            except Exception as e:
                logger.error(f"Meritz token network exception: {e}")

            return False

    def _get_headers(self) -> Dict[str, str]:
        return {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {self.access_token}",
            "mac_address": self.mac_address
        }

    async def get_balance(self) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict[str, Any]]]]:
        if not await self.ensure_valid_token():
            return None, None, None, None

        if self.is_overseas:
            return await self._get_overseas_balance()
        else:
            return await self._get_domestic_balance()

    async def _get_domestic_balance(self, _retried: bool = False) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict[str, Any]]]]:
        session = await self._get_session()
        headers = self._get_headers()

        avail_cash = 0.0
        # 1. 예수금 조회
        try:
            dep_url = f"{self.base_url}/accounts/v1/me/deposit"
            async with session.get(dep_url, headers=headers, timeout=10) as res:
                body = await res.json(content_type=None)
                if self._is_token_error(body) and not _retried:
                    logger.warning("Meritz token expired during deposit fetch, refreshing...")
                    if await self.ensure_valid_token(force_refresh=True):
                        return await self._get_domestic_balance(_retried=True)
                    return None, None, None, None
                if res.status == 200 and isinstance(body, dict):
                    data = body.get("data")
                    if isinstance(data, dict):
                        avail_cash = float(data.get("drng_able_amt") or data.get("oder_able_cash_amt") or data.get("tfam") or 0.0)
                else:
                    logger.warning(f"Meritz domestic deposit fetch failed HTTP {res.status}: {body}")
        except Exception as e:
            logger.warning(f"Meritz domestic deposit exception: {e}")

        # 2. 국내 보유종목 조회
        holdings = []
        tot_held_value = 0.0
        tot_purchase_value = 0.0

        try:
            holdings_url = f"{self.base_url}/accounts/v1/me/holdings"
            async with session.get(holdings_url, headers=headers, timeout=10) as res:
                body = await res.json(content_type=None)
                if self._is_token_error(body) and not _retried:
                    logger.warning("Meritz token expired during domestic holdings fetch, refreshing...")
                    if await self.ensure_valid_token(force_refresh=True):
                        return await self._get_domestic_balance(_retried=True)
                    return None, None, None, None

                rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                if res.status == 200 or rsp_cd in EMPTY_DATA_CODES:
                    data = body.get("data") if isinstance(body, dict) else None
                    rows = data if isinstance(data, list) else ([data] if isinstance(data, dict) and data else [])

                    for item in rows:
                        if not isinstance(item, dict):
                            continue
                        # 메리츠 /accounts/v1/me/holdings는 해외주식(pros_trgt_cls_code=="J", mksc_cls_name=="해외주식")도
                        # 함께 반환하므로 국내 계좌 모드에서는 해외주식을 제외합니다.
                        pros_cls = str(item.get("pros_trgt_cls_code", "")).strip().upper()
                        mksc_name = str(item.get("mksc_cls_name", "")).strip()
                        if pros_cls == "J" or "해외" in mksc_name:
                            continue

                        raw_iscd = str(item.get("iscd", "")).strip()
                        sym = raw_iscd.lstrip("A")
                        if not sym:
                            continue
                        name = str(item.get("isnm", sym)).strip()
                        qty = float(item.get("rmnd_qty") or item.get("rmnd_dqty") or 0)
                        byng_unpr = float(item.get("byng_unpr", 0))
                        psnt_prc = float(item.get("psnt_prc", 0))
                        vltn_amt = float(item.get("vltn_amt", 0))
                        byng_amt = float(item.get("byng_amt", 0))
                        pfls_amt = float(item.get("vltn_prls_amt", 0))
                        pfls_rt = float(item.get("ert", 0))

                        if qty <= 0:
                            continue

                        tot_held_value += (vltn_amt if vltn_amt > 0 else (psnt_prc * qty))
                        tot_purchase_value += (byng_amt if byng_amt > 0 else (byng_unpr * qty))

                        holdings.append({
                            "symbol": sym,
                            "name": name,
                            "quantity": qty,
                            "purchase_avg_price": byng_unpr,
                            "current_price": psnt_prc,
                            "profit_loss_rate": pfls_rt,
                            "profit_loss_amount": pfls_amt
                        })
                else:
                    logger.warning(f"Meritz domestic holdings fetch failed HTTP {res.status}: {body}")
                    return None, None, None, None
        except Exception as e:
            logger.error(f"Meritz domestic holdings exception: {e}")
            return None, None, None, None

        total_balance = tot_held_value + avail_cash
        current_yield = ((tot_held_value - tot_purchase_value) / tot_purchase_value * 100.0) if tot_purchase_value > 0 else 0.0

        return total_balance, current_yield, avail_cash, holdings

    async def _get_overseas_balance(self, _retried: bool = False) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict[str, Any]]]]:
        session = await self._get_session()
        headers = self._get_headers()

        avail_cash = 0.0
        # 1. 외화 예수금 조회 (USD)
        try:
            curr_url = f"{self.base_url}/accounts/v1/me/overseas/currencies"
            async with session.get(curr_url, headers=headers, timeout=10) as res:
                body = await res.json(content_type=None)
                if self._is_token_error(body) and not _retried:
                    logger.warning("Meritz token expired during overseas currencies fetch, refreshing...")
                    if await self.ensure_valid_token(force_refresh=True):
                        return await self._get_overseas_balance(_retried=True)
                    return None, None, None, None

                rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                if res.status == 200 or rsp_cd in EMPTY_DATA_CODES:
                    data = body.get("data") if isinstance(body, dict) else None
                    rows = data if isinstance(data, list) else ([data] if isinstance(data, dict) and data else [])
                    for c_item in rows:
                        if not isinstance(c_item, dict):
                            continue
                        crcd = str(c_item.get("crcd", "")).upper()
                        if crcd == "USD" or not avail_cash:
                            frcr = float(c_item.get("frcr_amt") or 0.0)
                            estm = float(c_item.get("estm_frcr_amt") or 0.0)
                            amt = frcr if frcr > 0 else estm
                            if amt > 0 or crcd == "USD":
                                avail_cash = amt
                                if crcd == "USD":
                                    break
                else:
                    logger.warning(f"Meritz overseas currencies fetch failed HTTP {res.status}: {body}")
        except Exception as e:
            logger.warning(f"Meritz overseas currencies exception: {e}")

        # 2. 해외 보유종목 조회
        holdings = []
        tot_held_value = 0.0
        tot_purchase_value = 0.0

        try:
            ovs_holdings_url = f"{self.base_url}/accounts/v1/me/overseas/holdings"
            async with session.get(ovs_holdings_url, headers=headers, timeout=10) as res:
                body = await res.json(content_type=None)
                if self._is_token_error(body) and not _retried:
                    logger.warning("Meritz token expired during overseas holdings fetch, refreshing...")
                    if await self.ensure_valid_token(force_refresh=True):
                        return await self._get_overseas_balance(_retried=True)
                    return None, None, None, None

                rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                if res.status == 200 or rsp_cd in EMPTY_DATA_CODES:
                    data = body.get("data") if isinstance(body, dict) else None
                    rows = data if isinstance(data, list) else ([data] if isinstance(data, dict) and data else [])

                    for item in rows:
                        if not isinstance(item, dict):
                            continue
                        raw_sym = str(item.get("ovrs_stck_symb_iscd") or item.get("iscd", "")).split(".")[0].strip().upper()
                        if not raw_sym:
                            continue
                        name = str(item.get("isnm", raw_sym)).strip()
                        qty = float(item.get("rmnd_dqty") or item.get("rmnd_qty") or 0)
                        avrg_unpr = float(item.get("avrg_unpr", 0))
                        psnt_prc = float(item.get("psnt_prc", 0))
                        vltn_amt = float(item.get("frcr_vltn_amt", 0))
                        byov_camt = float(item.get("byov_camt", 0))
                        pfls_amt = float(item.get("vltn_prls_camt", 0))
                        pfls_rt = float(item.get("vltn_ert", 0))
                        excd_id = str(item.get("ovrs_exch_vndr_idno", "")).strip()

                        if excd_id:
                            self._symbol_exch_map[raw_sym] = excd_id

                        if qty <= 0:
                            continue

                        item_held_val = vltn_amt if vltn_amt > 0 else (psnt_prc * qty)
                        item_purch_val = byov_camt if byov_camt > 0 else (avrg_unpr * qty)
                        tot_held_value += item_held_val
                        tot_purchase_value += item_purch_val

                        holdings.append({
                            "symbol": raw_sym,
                            "name": name,
                            "quantity": qty,
                            "purchase_avg_price": avrg_unpr,
                            "current_price": psnt_prc,
                            "profit_loss_rate": pfls_rt,
                            "profit_loss_amount": pfls_amt
                        })
                else:
                    logger.warning(f"Meritz overseas holdings fetch failed HTTP {res.status}: {body}")
                    return None, None, None, None
        except Exception as e:
            logger.error(f"Meritz overseas holdings exception: {e}")
            return None, None, None, None

        total_balance = tot_held_value + avail_cash
        current_yield = ((tot_held_value - tot_purchase_value) / tot_purchase_value * 100.0) if tot_purchase_value > 0 else 0.0

        return total_balance, current_yield, avail_cash, holdings

    def _get_exch_id(self, symbol: str) -> str:
        s = symbol.strip().upper()
        if s in self._symbol_exch_map:
            return self._symbol_exch_map[s]
        if s in KNOWN_US_EXCH_MAP:
            return KNOWN_US_EXCH_MAP[s]
        return "0537"  # Default to NASDAQ

    @staticmethod
    def _map_domestic_order_code(ord_dvsn: Optional[str], price: float) -> Tuple[str, int]:
        """
        엔진 공통(KIS 기준) 주문구분 코드를 메리츠 국내주식 주문구분 코드(oder_cls_code) 및 단가(oder_unpr)로 변환
        - KIS "00"(지정가) / 메리츠 "01"(지정가) -> "01" (호가단위 보정 단가)
        - KIS "01"(시장가) / price <= 0 -> "05"(시장가, 단가 0)
        - KIS "03"(최유리지정가) / 메리츠 "12" -> "12" (단가 0)
        - KIS "05"(장전시간외종가) / 메리츠 "61" -> "61" (단가 0)
        """
        if not ord_dvsn:
            if price <= 0.0:
                return "05", 0
            return "01", _round_to_krx_tick(int(price))

        code = str(ord_dvsn).strip()
        if code in ("05", "61"):
            # 예약주문 매니저 등에서 장전시간외종가("05" 또는 "61")로 요청한 경우
            return "61", 0
        if code in ("03", "12"):
            return "12", 0
        if code == "00":
            return ("05", 0) if price <= 0.0 else ("01", _round_to_krx_tick(int(price)))
        if code == "01":
            # price <= 0 이면 KIS 시장가("01") 의미, price > 0 이면 메리츠 지정가("01") 의미
            return ("05", 0) if price <= 0.0 else ("01", _round_to_krx_tick(int(price)))

        if price <= 0.0:
            return code, 0
        return code, _round_to_krx_tick(int(price))

    async def execute_order(self, symbol: str, side: str, qty: float, price: float, allow_extended_hours: bool = False, ord_dvsn: str = None) -> Tuple[bool, str]:
        if not await self.ensure_valid_token():
            return False, "메리츠 토큰 발급 실패"

        if self.is_overseas:
            return await self._execute_overseas_order(symbol, side, qty, price, allow_extended_hours, ord_dvsn)
        else:
            return await self._execute_domestic_order(symbol, side, qty, price, allow_extended_hours, ord_dvsn)

    async def _execute_domestic_order(self, symbol: str, side: str, qty: float, price: float, allow_extended_hours: bool = False, ord_dvsn: str = None, _retried: bool = False) -> Tuple[bool, str]:
        sym_clean = symbol.strip()
        iscd = f"A{sym_clean}" if not sym_clean.startswith("A") else sym_clean
        url = f"{self.base_url}/trading/v1/orders/{'buy' if side.upper() == 'BUY' else 'sell'}"

        oder_cls_code, oder_unpr = self._map_domestic_order_code(ord_dvsn, price)
        odqt = int(float(qty))

        payload = {
            "iscd": iscd,
            "oder_unpr": oder_unpr,
            "odqt": odqt,
            "oder_cls_code": oder_cls_code,
            "oder_cond_cls_code": "0",
            "exch_kind_code": "01",
            "orgl_oder_no": 0,
            "whol_rctf_cncl_yn": "N",
            "warn_cnfr_yn": "N"
        }

        session = await self._get_session()
        headers = self._get_headers()

        # 경고 시 1회 warn_cnfr_yn="Y" 로 재전송
        for attempt in range(2):
            try:
                async with session.post(url, headers=headers, json=payload, timeout=10) as res:
                    body = await res.json(content_type=None)
                    if self._is_token_error(body) and not _retried:
                        if await self.ensure_valid_token(force_refresh=True):
                            return await self._execute_domestic_order(symbol, side, qty, price, allow_extended_hours, ord_dvsn, _retried=True)

                    rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                    rsp_msg = str(body.get("rsp_msg", "")).strip() if isinstance(body, dict) else ""

                    data = body.get("data") if isinstance(body, dict) else {}
                    if isinstance(data, list) and data:
                        data = data[0]

                    warn_code = str(data.get("warn_cls_code", "")).strip() if isinstance(data, dict) else ""
                    warn_msg = str(data.get("warn_msg", "")).strip() if isinstance(data, dict) else ""

                    if warn_code and warn_code != "0":
                        if attempt == 0 and warn_code in "134679acdfg":
                            logger.info(f"Meritz domestic Order warning {warn_code} ({warn_msg}), resending with warn_cnfr_yn='Y'")
                            payload["warn_cnfr_yn"] = "Y"
                            continue
                        else:
                            return False, f"경고로 주문 미접수: {warn_msg} ({warn_code})"

                    OK_CODES = {"0000", "0001", "5620", "5762", "5766", "5820", "5822"}
                    if rsp_cd in OK_CODES or res.status == 200:
                        odno = str(data.get("oder_no") or data.get("odno") or "").strip() if isinstance(data, dict) else ""
                        logger.info(f"Meritz domestic Order success: {side} {symbol} {qty} @ {oder_unpr} (cls={oder_cls_code}, ODNO: {odno})")
                        return True, f"주문 성공|{odno}" if odno else "주문 성공"
                    else:
                        sub_msg = str(body.get("rsp_sub_msg", "")).strip() if isinstance(body, dict) else ""
                        err = f"{rsp_msg} - {sub_msg}" if sub_msg else rsp_msg
                        return False, err or f"주문 실패 (코드: {rsp_cd})"
            except Exception as e:
                logger.error(f"Meritz domestic order exception: {e}")
                return False, f"시스템 오류: {e}"

        return False, "주문 처리 실패"

    async def _execute_overseas_order(self, symbol: str, side: str, qty: float, price: float, allow_extended_hours: bool = False, ord_dvsn: str = None, _retried: bool = False) -> Tuple[bool, str]:
        sym_clean = symbol.strip().upper()
        exch_id = self._get_exch_id(sym_clean)
        url = f"{self.base_url}/trading/v1/overseas/orders/{'buy' if side.upper() == 'BUY' else 'sell'}"

        # 주문구분 및 조건코드 매핑 (01: 지정가, 05: 시장가, 31: LOO, 32: LOC, 33: MOO, 34: MOC)
        if ord_dvsn in ("32", "34", "LOC"):
            oder_cls_code = "32"       # LOC (장마감지정가)
            oder_cond_cls_code = "7"  # 장마감전
        elif ord_dvsn in ("05", "시장가") or price <= 0.0:
            oder_cls_code = "05"       # 시장가
            oder_cond_cls_code = "0"
        elif ord_dvsn in ("31", "LOO"):
            oder_cls_code = "31"
            oder_cond_cls_code = "2"
        elif ord_dvsn in ("33", "MOO"):
            oder_cls_code = "33"
            oder_cond_cls_code = "2"
        elif ord_dvsn in ("MOC",):
            oder_cls_code = "34"
            oder_cond_cls_code = "7"
        else:
            oder_cls_code = "01"
            oder_cond_cls_code = "0"

        # 시장가/MOC/MOO 계열은 가격을 "0"으로 전송
        if oder_cls_code in ("05", "33", "34", "37", "38"):
            oder_prc_str = "0"
        else:
            oder_prc_str = f"{price:.4f}" if price < 1.0 else f"{price:.2f}"

        odqt_str = str(int(float(qty)))
        candidate_exchanges = [exch_id] + [e for e in ["0537", "0321", "0066"] if e != exch_id]

        session = await self._get_session()
        headers = self._get_headers()

        last_error = "해외주식 주문 실패"
        for current_exch in candidate_exchanges:
            payload = {
                "ovrs_stck_symb_iscd": sym_clean,
                "ovrs_exch_vndr_idno": current_exch,
                "oder_cls_code": oder_cls_code,
                "oder_cond_cls_code": oder_cond_cls_code,
                "odqt": odqt_str,
                "ovrs_stck_oder_prc": oder_prc_str,
                "warn_cnfr_yn": "N"
            }

            for attempt in range(2):
                try:
                    async with session.post(url, headers=headers, json=payload, timeout=12) as res:
                        body = await res.json(content_type=None)
                        if self._is_token_error(body) and not _retried:
                            if await self.ensure_valid_token(force_refresh=True):
                                return await self._execute_overseas_order(symbol, side, qty, price, allow_extended_hours, ord_dvsn, _retried=True)

                        rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                        rsp_msg = str(body.get("rsp_msg", "")).strip() if isinstance(body, dict) else ""

                        data = body.get("data") if isinstance(body, dict) else {}
                        if isinstance(data, list) and data:
                            data = data[0]

                        warn_code = str(data.get("warn_cls_code", "")).strip() if isinstance(data, dict) else ""
                        warn_msg = str(data.get("warn_msg", "")).strip() if isinstance(data, dict) else ""

                        if warn_code and warn_code != "0":
                            if attempt == 0 and warn_code in "134679acdfg":
                                logger.info(f"Meritz overseas Order warning {warn_code} ({warn_msg}), resending with warn_cnfr_yn='Y'")
                                payload["warn_cnfr_yn"] = "Y"
                                continue
                            else:
                                last_error = f"경고로 주문 미접수: {warn_msg} ({warn_code})"
                                break

                        OK_CODES = {"0000", "0001", "5620", "5762", "5766", "5820", "5822"}
                        if rsp_cd in OK_CODES or res.status == 200:
                            odno = str(data.get("oder_no") or data.get("odno") or "").strip() if isinstance(data, dict) else ""
                            self._symbol_exch_map[sym_clean] = current_exch
                            logger.info(f"Meritz overseas Order success: {side} {sym_clean} {qty} @ {price} (Exch: {current_exch}, ODNO: {odno})")
                            return True, f"주문 성공|{odno}" if odno else "주문 성공"
                        else:
                            sub_msg = str(body.get("rsp_sub_msg", "")).strip() if isinstance(body, dict) else ""
                            err = f"{rsp_msg} - {sub_msg}" if sub_msg else rsp_msg
                            last_error = err or f"주문 실패 ({rsp_cd})"
                            # 거래소 불일치 에러일 경우 다음 거래소로 재시도
                            if any(k in err for k in ["거래소", "종목코드", "미등록", "시장구분"]):
                                break
                            else:
                                return False, last_error
                except Exception as e:
                    logger.error(f"Meritz overseas order exception: {e}")
                    last_error = f"시스템 오류: {e}"
                    break

        return False, last_error

    async def cancel_all_orders(self, symbol: str) -> bool:
        return True

    async def cancel_domestic_order(self, order_no: str, symbol: str, qty: int = 0, ord_dvsn: str = "01") -> Tuple[bool, str]:
        if not await self.ensure_valid_token():
            return False, "메리츠 토큰 발급 실패"

        if self.is_overseas:
            return await self.cancel_overseas_order(order_no, symbol, qty)

        sym_clean = symbol.strip()
        iscd = f"A{sym_clean}" if not sym_clean.startswith("A") else sym_clean
        url = f"{self.base_url}/trading/v1/orders/cancel"

        mapped_cls, _ = self._map_domestic_order_code(ord_dvsn, 1.0)
        payload = {
            "iscd": iscd,
            "oder_unpr": 0,
            "odqt": int(qty) if qty > 0 else 0,
            "oder_cls_code": mapped_cls,
            "oder_cond_cls_code": "0",
            "exch_kind_code": "01",
            "orgl_oder_no": int(order_no) if str(order_no).isdigit() else 0,
            "whol_rctf_cncl_yn": "Y" if qty <= 0 else "N",
            "warn_cnfr_yn": "N"
        }

        try:
            session = await self._get_session()
            headers = self._get_headers()
            async with session.post(url, headers=headers, json=payload, timeout=10) as res:
                body = await res.json(content_type=None)
                rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                if rsp_cd in {"0000", "0001"} or res.status == 200:
                    logger.info(f"Meritz domestic Order cancel success: ODNO {order_no} ({symbol})")
                    return True, "취소 성공"
                else:
                    return False, str(body.get("rsp_msg", "취소 실패"))
        except Exception as e:
            logger.error(f"Meritz cancel exception: {e}")
            return False, f"오류: {e}"

    async def cancel_overseas_order(self, order_no: str, symbol: str, qty: int = 0) -> Tuple[bool, str]:
        if not await self.ensure_valid_token():
            return False, "메리츠 토큰 발급 실패"

        url = f"{self.base_url}/trading/v1/overseas/orders/cancel"
        today_str = datetime.now(timezone(timedelta(hours=9))).strftime("%Y%m%d")

        payload = {
            "oder_date": today_str,
            "orgl_oder_no": str(order_no),
            "oder_cls_code": "01",
            "odqt": str(int(qty)) if qty > 0 else "0",
            "warn_cnfr_yn": "N"
        }

        try:
            session = await self._get_session()
            headers = self._get_headers()
            async with session.post(url, headers=headers, json=payload, timeout=10) as res:
                body = await res.json(content_type=None)
                rsp_cd = str(body.get("rsp_cd", "")).strip() if isinstance(body, dict) else ""
                if rsp_cd in {"0000", "0001", "5762", "5766"} or res.status == 200:
                    logger.info(f"Meritz overseas Order cancel success: ODNO {order_no} ({symbol})")
                    return True, "취소 성공"
                else:
                    return False, str(body.get("rsp_msg", "해외주식 취소 실패"))
        except Exception as e:
            logger.error(f"Meritz overseas cancel exception: {e}")
            return False, f"오류: {e}"

    async def inquire_order_fill_status(self, order_no: str) -> Tuple[int, int]:
        """
        특정 주문번호(ODNO)의 당일 총체결수량(tot_cntg_qty)과 잔여미체결수량(rmnd_qty) 조회
        Returns: (tot_ccld_qty, rmnd_qty) - 조회 실패 시 (0, -1) 반환
        """
        if not await self.ensure_valid_token():
            return 0, -1

        url = f"{self.base_url}/trading/v1/orders/today"
        params = {
            "cntg_cls_code": "0",
            "sell_byng_cls_code": "0",
            "iscd": "",
            "oder_no": int(order_no) if str(order_no).isdigit() else 0,
            "inqr_ordr_cls_code": "0",
            "exch_kind_code": "00"
        }
        try:
            session = await self._get_session()
            headers = self._get_headers()
            async with session.get(url, headers=headers, params=params, timeout=10) as res:
                body = await res.json(content_type=None)
                if res.status == 200 and isinstance(body, dict):
                    data = body.get("data")
                    rows = data if isinstance(data, list) else ([data] if isinstance(data, dict) and data else [])
                    for item in rows:
                        if not isinstance(item, dict):
                            continue
                        item_odno = str(item.get("oder_no") or item.get("odno") or "").strip()
                        if item_odno == str(order_no).strip() or not order_no:
                            odqt = int(float(item.get("odqt", 0)))
                            tot_ccld = int(float(item.get("tot_cntg_qty") or item.get("cntg_qty") or 0))
                            rmnd = int(float(item.get("rmnd_qty") or item.get("oder_rmnd_qty") or item.get("uncn_qty") or max(0, odqt - tot_ccld)))
                            return tot_ccld, rmnd
                else:
                    logger.warning(f"Meritz inquire_order_fill_status failed for ODNO {order_no}: {body}")
        except Exception as e:
            logger.error(f"Meritz inquire_order_fill_status exception: {e}")
        return 0, -1

    async def fetch_rest_current_prices(self, symbols: List[str]) -> Dict[str, Any]:
        if not await self.ensure_valid_token():
            return {}

        result = {}
        session = await self._get_session()
        headers = self._get_headers()

        for sym in symbols:
            sym_clean = sym.strip()
            if not sym_clean: continue

            if self.is_overseas:
                sym_upper = sym_clean.upper()
                known_exch_id = self._symbol_exch_map.get(sym_upper) or KNOWN_US_EXCH_MAP.get(sym_upper)
                pref_code = EXCH_ID_TO_CODE.get(known_exch_id, "OQ")
                exchange_codes = [pref_code] + [c for c in ["OQ", "NY", "AX"] if c != pref_code]

                found = False
                for exch in exchange_codes:
                    url = f"{self.base_url}/market/v1/overseas/prices"
                    params = {
                        "mrkt_div_code": "OV",
                        "mrkt_cls_code": exch,
                        "iscd": sym_upper,
                        "dely_rltm_cls_code": "0"  # 실시간 시세
                    }
                    try:
                        async with session.get(url, headers=headers, params=params, timeout=10) as res:
                            body = await res.json(content_type=None)
                            if self._is_token_error(body):
                                if await self.ensure_valid_token(force_refresh=True):
                                    headers = self._get_headers()
                                    async with session.get(url, headers=headers, params=params, timeout=10) as res2:
                                        body = await res2.json(content_type=None)
                                        res = res2
                            if res.status == 200 and isinstance(body, dict):
                                data = body.get("data")
                                if isinstance(data, dict):
                                    prc_str = str(data.get("prpr", "")).strip()
                                    chg_str = str(data.get("prdy_ctrt", "0")).strip()
                                    if prc_str and float(prc_str) > 0:
                                        result[sym] = (float(prc_str), float(chg_str))
                                        self._symbol_exch_map[sym_upper] = EXCH_CODE_TO_ID.get(exch, "0537")
                                        found = True
                                        break
                    except Exception as e:
                        logger.debug(f"Meritz overseas REST price fetch error {sym_upper} on {exch}: {e}")
                    await asyncio.sleep(0.1)

                if not found:
                    # KISBroker와 동일하게 Yahoo Finance Fallback 지원
                    try:
                        yf_url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym_upper}"
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
                                        logger.info(f"Successfully fetched price for {sym_upper} from Yahoo Finance: {price}")
                    except Exception as e:
                        logger.error(f"Yahoo Finance fallback error for {sym_upper}: {e}")

            else:
                sym_code = sym_clean.lstrip("A")
                url = f"{self.base_url}/market/v1/prices"
                params = {"mrkt_div_code": "J", "iscd": sym_code}

                try:
                    async with session.get(url, headers=headers, params=params, timeout=10) as res:
                        body = await res.json(content_type=None)
                        if self._is_token_error(body):
                            if await self.ensure_valid_token(force_refresh=True):
                                headers = self._get_headers()
                                async with session.get(url, headers=headers, params=params, timeout=10) as res2:
                                    body = await res2.json(content_type=None)
                                    res = res2
                        if res.status == 200 and isinstance(body, dict):
                            data = body.get("data")
                            if isinstance(data, dict):
                                prc_str = str(data.get("stck_prpr", "")).strip()
                                chg_str = str(data.get("prdy_ctrt", "0")).strip()
                                if prc_str:
                                    result[sym] = (float(prc_str), float(chg_str))
                except Exception as e:
                    logger.debug(f"Meritz domestic REST price fetch error {sym}: {e}")

                await asyncio.sleep(0.1)

        return result

    async def connect_websocket(self, symbols: List[str], on_message_callback: Callable[[str, float, float], None]):
        self._on_message_callback = on_message_callback
        if not await self.ensure_valid_token():
            return

        valid_symbols = [s.strip() for s in symbols if s.strip()]
        if not valid_symbols:
            return

        self._running_ws = True

        async def _ws_loop():
            while self._running_ws:
                try:
                    await self.ensure_valid_token()
                    async with websockets.connect(self.ws_url, ping_interval=30) as ws:
                        self.ws_connection = ws
                        logger.info(f"Meritz WebSocket connected (Overseas: {self.is_overseas}).")

                        if self.is_overseas:
                            for sym in valid_symbols:
                                sym_upper = sym.upper()
                                exch_id = self._symbol_exch_map.get(sym_upper) or KNOWN_US_EXCH_MAP.get(sym_upper, "0537")
                                exch_code = EXCH_ID_TO_CODE.get(exch_id, "OQ")
                                tr_key = f"{exch_code}@{sym_upper}"

                                sub_msg = {
                                    "header": {
                                        "token": f"Bearer {self.access_token}",
                                        "tr_type": "1"
                                    },
                                    "body": {
                                        "tr_cd": "ohhts_exst_cntg",
                                        "tr_key": tr_key
                                    }
                                }
                                await ws.send(json.dumps(sub_msg))
                                logger.info(f"Meritz overseas WS subscription sent for {tr_key}")
                                await asyncio.sleep(0.05)
                        else:
                            for sym in valid_symbols:
                                sym_code = sym.lstrip("A")
                                sub_msg = {
                                    "header": {
                                        "token": f"Bearer {self.access_token}",
                                        "tr_type": "1"
                                    },
                                    "body": {
                                        "tr_cd": "ohhts_stck_cntg",
                                        "tr_key": sym_code
                                    }
                                }
                                await ws.send(json.dumps(sub_msg))
                                logger.info(f"Meritz domestic WS subscription sent for {sym_code}")
                                await asyncio.sleep(0.05)

                        async for raw in ws:
                            try:
                                msg = json.loads(raw)
                            except Exception:
                                continue

                            head = msg.get("header") or {}
                            if "rsp_cd" in head:
                                # 구독 ACK 응답
                                continue

                            b = msg.get("body") or {}
                            if self.is_overseas:
                                sym = str(b.get("iscd") or head.get("tr_key", "").split("@")[-1]).strip().upper()
                                price_str = b.get("prpr")
                                chg_str = b.get("rsfl_rt") or b.get("prdy_vrss") or "0"
                            else:
                                sym = str(b.get("shrn_iscd") or head.get("tr_key") or "").strip().lstrip("A")
                                price_str = b.get("prpr") or b.get("stck_prpr")
                                chg_str = b.get("prdy_ctrt") or b.get("ctrt") or "0"

                            if sym and price_str and self._on_message_callback:
                                try:
                                    self._on_message_callback(sym, float(price_str), float(chg_str))
                                except Exception as cb_err:
                                    logger.error(f"Meritz WS callback error: {cb_err}")

                except Exception as e:
                    logger.error(f"Meritz WebSocket error: {e}")
                    self.ws_connection = None
                    await asyncio.sleep(2.0)

        self.ws_task = asyncio.create_task(_ws_loop())

    async def close_websocket(self):
        self._running_ws = False
        if self.ws_connection:
            await self.ws_connection.close()
        if self.ws_task:
            self.ws_task.cancel()
