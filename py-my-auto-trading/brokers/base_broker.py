from abc import ABC, abstractmethod
from typing import Dict, Optional, Tuple, List, Callable, Any
import os
import logging
import json

logger = logging.getLogger("Broker")

class BaseBroker(ABC):
    def __init__(self, app_key: str, app_secret: str, account_number: str, is_mock: bool = False):
        self.app_key = app_key
        self.app_secret = app_secret
        self.account_number = account_number
        self.is_mock = is_mock
        self.access_token = ""
        self.token_expires_at = 0.0

    def _get_token_cache_path(self) -> str:
        """Returns the path for the token cache file"""
        # Use a class attribute or property that subclasses define
        return getattr(self, '_token_cache_filename', f'.token_cache_{self.__class__.__name__}.json')

    def _save_token_cache(self, token_data: dict):
        """Save token data to cache file"""
        try:
            with open(self._get_token_cache_path(), 'w') as f:
                json.dump(token_data, f)
        except Exception:
            logger.warning(f"Failed to save token cache: {self._get_token_cache_path()}")

    def _load_token_cache(self) -> Optional[dict]:
        """Load token data from cache file"""
        try:
            cache_path = self._get_token_cache_path()
            if os.path.exists(cache_path):
                with open(cache_path, 'r') as f:
                    return json.load(f)
        except Exception:
            logger.warning(f"Failed to load token cache: {self._get_token_cache_path()}")
        return None

    @abstractmethod
    async def ensure_valid_token(self) -> bool:
        """인증 토큰 갱신 또는 유효성 확인"""
        pass

    @abstractmethod
    async def get_balance(self) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[List[Dict[str, Any]]]]:
        """계좌 잔고 조회. (total_balance, current_yield, avail_cash, holdings) 반환"""
        pass

    @abstractmethod
    async def execute_order(self, symbol: str, side: str, qty: float, price: float, allow_extended_hours: bool) -> Tuple[bool, str]:
        """주문 실행 (매수/매도). qty는 float (암호화폐 소수 단위 지원)."""
        pass

    @abstractmethod
    async def fetch_rest_current_prices(self, symbols: List[str]) -> Dict[str, float]:
        """REST API 기반의 시세 조회 (Fallback 용도)"""
        pass
    
    @abstractmethod
    async def cancel_all_orders(self, symbol: str) -> bool:
        """모든 주문 취소"""
        pass
    
    @abstractmethod
    async def connect_websocket(self, symbols: List[str], on_message_callback: Callable[[str, float], None]):
        """웹소켓 연결 및 종목 시세 구독. 콜백으로 (symbol, price) 전달"""
        pass

    @abstractmethod
    async def close_websocket(self):
        """웹소켓 연결 종료"""
        pass

    async def inquire_order_fill_status(self, order_no: str) -> Tuple[int, int]:
        """주문번호로 체결/미체결 상태 조회. (체결수량, 미체결잔량) 반환. 기본적으로 (0, -1) 미지원 반환"""
        return 0, -1
