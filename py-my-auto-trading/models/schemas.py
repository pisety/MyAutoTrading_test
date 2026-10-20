from pydantic import BaseModel, Field, field_validator
from typing import Dict, Optional, List

class SymbolSurgeConfig(BaseModel):
    time_window_seconds: int = 60
    surge_percent_threshold: float = 3.0
    buy_quantity: float = 0.0

class SurgeTradingConfig(BaseModel):
    enabled: bool = False
    allow_extended_hours: bool = True
    per_symbol_config: Dict[str, List[SymbolSurgeConfig]] = Field(default_factory=dict)

    @field_validator('per_symbol_config', mode='before')
    @classmethod
    def convert_legacy_config(cls, v):
        if not isinstance(v, dict):
            return v
        new_v = {}
        for sym, cfg in v.items():
            if isinstance(cfg, dict):
                new_v[sym] = [cfg]
            else:
                new_v[sym] = cfg
        return new_v

class SymbolConfig(BaseModel):
    enabled: bool = False

class Settings(BaseModel):
    is_auto_trading: bool = False
    target_profit: float = 10.0
    watch_symbols: Dict[str, SymbolConfig] = Field(default_factory=dict)
    surge_trading: SurgeTradingConfig = Field(default_factory=SurgeTradingConfig)
    memo: Optional[str] = ""

class AccountInfo(BaseModel):
    account_number: str = ""
    app_key: str = ""
    app_secret: str = ""
    broker: str = "KIS"
    is_mock: bool = Field(default=False)
    is_overseas: bool = Field(default=True)
    last_used_timestamp: Optional[str] = None
    
    model_config = {
        "populate_by_name": True
    }
    
    # KIS API 특화 설정
    @property
    def is_kis_mock(self) -> bool:
        return self.is_mock

class AccountData(BaseModel):
    active_account_id: str = ""
    saved_accounts: Dict[str, AccountInfo] = Field(default_factory=dict)

class OrderCommand(BaseModel):
    command: str = "NONE"
    symbol: Optional[str] = None
    quantity: Optional[float] = 1.0
    timestamp: Optional[str] = None

class ConditionalOrder(BaseModel):
    symbol: str
    side: str
    quantity: float
    target_price: float
    allow_extended_hours: bool = False
    status: str = "PENDING"
    timestamp: Optional[str] = None
    executed_at: Optional[str] = None
    executed_price: Optional[float] = None

class StopLossOrder(BaseModel):
    symbol: str
    trigger_price: float
    volume_percent: int = 100
    allow_extended_hours: bool = False
    status: str = "PENDING"
    timestamp: Optional[str] = None
    executed_at: Optional[str] = None
    executed_price: Optional[float] = None

class UserStatus(BaseModel):
    engine_running: bool = False
    connected: bool = False
    total_balance: float = 0.0
    current_yield: float = 0.0
    stock_prices: Dict[str, float] = Field(default_factory=dict)
    last_updated: str = ""
    last_seen: str = ""
