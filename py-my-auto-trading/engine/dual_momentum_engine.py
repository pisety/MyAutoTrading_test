"""
Dual Momentum SOXL/SOXS Quant Swing Engine (v2.1 Final Production)
Implements BT-SPEC-02 specification:
- Single-Account Mutual Exclusion (SOXL or SOXS)
- Bull / Bear regime by SOXX vs 200MA (Bull: SOXL +18% / SOXS +15%, Bear: +12%)
- RSI Filter: SOXX RSI >= 65 blocks SOXL, SOXX RSI <= 38 blocks SOXS (EXP-10 optimal)
- 4-tier Entry: C2 (regular -2%/-3% or -3%/-4%), C1 (extended -9% 20%, -10% 10%)
- Stop Loss: Pure Loss <= -5.0% (2-bar confirm) or -6.5% instant safety pin
- Trailing Take Profit: 50% exit at target, Trailing Gap 0.7%, +1% step-up 10% sell, 19:50 ET EOD close
"""

import math
import logging
from dataclasses import dataclass, field, asdict
from typing import List, Dict, Any, Optional, Tuple

logger = logging.getLogger("DualMomentumEngine")

@dataclass
class DualMomentumConfig:
    fee_rate: float = 0.0005              # 0.05%
    sl_pct: float = 0.05                  # -5.0% Stop Loss
    sl_pin_extra: float = 0.015           # -6.5% Instant Safety Pin
    trail_gap: float = 0.7                # 0.7% Trailing Gap (EXP-10 confirmed optimal)
    rsi_hi: float = 65.0                  # SOXL new entry upper bound
    rsi_lo: float = 38.0                  # SOXS new entry lower bound (EXP-10 confirmed optimal)
    tp_bull_soxl: float = 18.0            # Bull market SOXL TP start (+18%)
    tp_bull_soxs: float = 15.0            # Bull market SOXS TP start (+15%)
    tp_bear: float = 12.0                 # Bear market common TP start (+12%)
    decay_start: int = 5                  # T-Decay start day
    decay_rate: float = 1.0               # 1.0%p per day decay
    decay_floor: float = 12.0             # Floor for decayed TP (+12%)
    c1_levels: Tuple[Tuple[float, float], ...] = ((9.0, 0.20), (10.0, 0.10))

@dataclass
class DualMomentumState:
    active_ticker: int = 0                # 0: None, 1: SOXL, 2: SOXS
    shares: float = 0.0
    avg_price: float = 0.0                # Accounting average price (including fee)
    pure_avg_price: float = 0.0           # Decision pure execution price
    reference_equity: float = 0.0         # Portfolio equity when cycle started
    days_held: int = 0
    tp_activated: bool = False
    tp_trailing_stop: float = 0.0
    initial_shares_for_tp: float = 0.0
    next_target_pct: float = 0.0
    sl_breach_count: int = 0
    day_done: bool = False
    bought_L: List[bool] = field(default_factory=lambda: [False, False, False, False])
    bought_S: List[bool] = field(default_factory=lambda: [False, False, False, False])
    is_bull: bool = True
    cur_rsi: float = 50.0
    prev_close_L: float = 0.0
    prev_close_S: float = 0.0
    last_trade_pnl: float = 0.0
    total_trades: int = 0
    win_trades: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'DualMomentumState':
        state = cls()
        if not data:
            return state
        for k, v in data.items():
            if hasattr(state, k):
                setattr(state, k, v)
        
        # Firebase 필드 호환성 복원
        if data.get("active_symbol") == "SOXL" or data.get("active_ticker") == 1:
            state.active_ticker = 1
            if "bought_levels" in data and isinstance(data["bought_levels"], list):
                state.bought_L = list(data["bought_levels"])
        elif data.get("active_symbol") == "SOXS" or data.get("active_ticker") == 2:
            state.active_ticker = 2
            if "bought_levels" in data and isinstance(data["bought_levels"], list):
                state.bought_S = list(data["bought_levels"])

        if not state.pure_avg_price and state.avg_price:
            state.pure_avg_price = state.avg_price
        return state

class DualMomentumEngine:
    def __init__(self, config: Optional[DualMomentumConfig] = None, state: Optional[DualMomentumState] = None):
        self.config = config or DualMomentumConfig()
        self.state = state or DualMomentumState()

    def on_day_start(self, soxx_close: float, soxx_ma200: float, soxx_rsi: float,
                     prev_close_soxl: float, prev_close_soxs: float, is_new_day: bool = True):
        """Called daily before 09:00 ET to initialize market regime and day flags."""
        s = self.state
        s.day_done = False
        s.sl_breach_count = 0

        # 새로운 거래일이 시작되면 당일 분할 매수 레벨 초기화 및 보유 일수 증가
        if is_new_day:
            s.bought_L = [False, False, False, False]
            s.bought_S = [False, False, False, False]
            if s.active_ticker != 0 and s.shares > 0.0:
                s.days_held += 1

        s.is_bull = (soxx_close > soxx_ma200)
        s.cur_rsi = soxx_rsi
        s.prev_close_L = prev_close_soxl
        s.prev_close_S = prev_close_soxs

        logger.info(
            f"[DayStart] Regime: {'BULL' if s.is_bull else 'BEAR'} | SOXX RSI: {s.cur_rsi:.1f} | "
            f"Active: {s.active_ticker} | DaysHeld: {s.days_held} | NewDay: {is_new_day} | "
            f"SOXL Prev: {s.prev_close_L:.2f} | SOXS Prev: {s.prev_close_S:.2f}"
        )

    def evaluate_bar(self, current_time_et: str, price_soxl: float, price_soxs: float,
                     cash: float) -> Tuple[List[Dict[str, Any]], float]:
        """
        Evaluates a 1-minute price bar according to v2.1 Final Production rules.
        Returns:
            orders: list of order dicts to execute [{'action': 'BUY'/'SELL', 'symbol': 'SOXL'/'SOXS', 'qty': ..., 'price': ...}]
            estimated_cash: updated cash balance after simulation/execution
        """
        s = self.state
        cfg = self.config
        orders = []

        p_active = price_soxl if s.active_ticker == 1 else (price_soxs if s.active_ticker == 2 else 0.0)
        total_equity = cash + s.shares * p_active

        # ----------------------------------------------------
        # 1. Stop Loss (2-Bar Confirm + Pin Safety)
        # ----------------------------------------------------
        if s.shares > 0.0 and s.active_ticker != 0:
            signal_eq = s.reference_equity + s.shares * (p_active - s.pure_avg_price)
            loss_pct = (signal_eq - s.reference_equity) / s.reference_equity if s.reference_equity > 0 else 0.0

            if loss_pct <= -cfg.sl_pct:
                s.sl_breach_count += 1
            else:
                s.sl_breach_count = 0

            # 2-bar confirm or instant safety pin (-6.5%)
            if s.sl_breach_count >= 2 or loss_pct <= -(cfg.sl_pct + cfg.sl_pin_extra):
                sym_str = 'SOXL' if s.active_ticker == 1 else 'SOXS'
                sell_sh = s.shares
                orders.append({
                    'action': 'SELL',
                    'symbol': sym_str,
                    'qty': sell_sh,
                    'price': p_active,
                    'order_type': 'MARKET',
                    'reason': f"STOP_LOSS (loss={loss_pct*100:.2f}%, bars={s.sl_breach_count})"
                })
                cash += sell_sh * p_active * (1.0 - cfg.fee_rate)
                s.last_trade_pnl = (cash / s.reference_equity - 1.0) * 100.0 if s.reference_equity > 0 else 0.0
                s.total_trades += 1

                self._reset_position()
                s.day_done = True
                return orders, cash

        # ----------------------------------------------------
        # 2. Take Profit & Trailing Stop (Trail Gap = 0.7%)
        # ----------------------------------------------------
        if s.shares > 0.0 and s.active_ticker != 0:
            gain_pct = (p_active / s.pure_avg_price - 1.0) * 100.0 if s.pure_avg_price > 0 else 0.0

            # Base target by regime + T-Decay
            cur_tp_start = (cfg.tp_bull_soxl if s.active_ticker == 1 else cfg.tp_bull_soxs) if s.is_bull else cfg.tp_bear
            if s.days_held >= cfg.decay_start:
                cur_tp_start = min(cur_tp_start, max(cfg.decay_floor, cur_tp_start - (s.days_held - cfg.decay_start + 1) * cfg.decay_rate))

            # Initial Trigger: 50% partial exit
            if not s.tp_activated and gain_pct >= cur_tp_start:
                s.tp_activated = True
                s.tp_trailing_stop = s.pure_avg_price * (1.0 + (cur_tp_start - cfg.trail_gap) / 100.0)
                s.initial_shares_for_tp = s.shares
                if s.shares >= 1.0:
                    sell_sh = min(s.shares, max(1.0, math.floor(s.initial_shares_for_tp * 0.50)))
                else:
                    sell_sh = min(s.shares, s.initial_shares_for_tp * 0.50)
                if sell_sh > 0:
                    sym_str = 'SOXL' if s.active_ticker == 1 else 'SOXS'
                    orders.append({
                        'action': 'SELL',
                        'symbol': sym_str,
                        'qty': sell_sh,
                        'price': p_active,
                        'order_type': 'MARKET',
                        'reason': f"TP_INIT_50% (gain={gain_pct:.2f}%, stop={s.tp_trailing_stop:.2f})"
                    })
                    cash += sell_sh * p_active * (1.0 - cfg.fee_rate)
                    s.shares -= sell_sh
                s.next_target_pct = float(int(cur_tp_start) + 1.0)

            # Step-up trailing
            if s.tp_activated:
                while s.shares > 0.0 and gain_pct >= s.next_target_pct:
                    if s.shares >= 1.0:
                        sell_sh = min(s.shares, max(1.0, math.floor(s.initial_shares_for_tp * 0.10)))
                    else:
                        sell_sh = min(s.shares, s.initial_shares_for_tp * 0.10)
                    if sell_sh <= 0:
                        sell_sh = s.shares
                    sym_str = 'SOXL' if s.active_ticker == 1 else 'SOXS'
                    orders.append({
                        'action': 'SELL',
                        'symbol': sym_str,
                        'qty': sell_sh,
                        'price': p_active,
                        'order_type': 'MARKET',
                        'reason': f"TP_STEP_10% (target={s.next_target_pct:.1f}%, gain={gain_pct:.2f}%)"
                    })
                    cash += sell_sh * p_active * (1.0 - cfg.fee_rate)
                    s.shares -= sell_sh
                    s.tp_trailing_stop = s.pure_avg_price * (1.0 + (s.next_target_pct - cfg.trail_gap) / 100.0)
                    s.next_target_pct += 1.0

                # If all shares sold via step-up, conclude trade cycle
                if s.shares <= 1e-7:
                    s.last_trade_pnl = (cash / s.reference_equity - 1.0) * 100.0 if s.reference_equity > 0 else 0.0
                    s.total_trades += 1
                    s.win_trades += 1
                    self._reset_position()
                    return orders, cash

                # Final exit on trailing stop breach or EOD (19:50 ET)
                is_eod_tp = (current_time_et >= "19:50:00")
                if s.shares > 0.0 and (p_active <= s.tp_trailing_stop or is_eod_tp):
                    sym_str = 'SOXL' if s.active_ticker == 1 else 'SOXS'
                    orders.append({
                        'action': 'SELL',
                        'symbol': sym_str,
                        'qty': s.shares,
                        'price': p_active,
                        'order_type': 'MARKET',
                        'reason': f"TP_FINAL_EXIT ({'EOD_19:50' if is_eod_tp else 'TRAIL_STOP_HIT'})"
                    })
                    cash += s.shares * p_active * (1.0 - cfg.fee_rate)
                    s.last_trade_pnl = (cash / s.reference_equity - 1.0) * 100.0 if s.reference_equity > 0 else 0.0
                    s.total_trades += 1
                    s.win_trades += 1
                    self._reset_position()
                    return orders, cash

        if s.day_done or s.tp_activated:
            return orders, cash

        # ----------------------------------------------------
        # 3. Buy Entries (Condition 1 & Condition 2)
        # ----------------------------------------------------
        is_regular_market = ("09:30:00" <= current_time_et <= "16:00:00")

        for sym in (1, 2):  # 1: SOXL, 2: SOXS
            p = price_soxl if sym == 1 else price_soxs
            prev_c = s.prev_close_L if sym == 1 else s.prev_close_S
            bought = s.bought_L if sym == 1 else s.bought_S
            if prev_c <= 0.0 or p <= 0.0:
                continue

            # Level drop percentages
            if sym == 1:
                drop_c2_1, drop_c2_2 = (0.98, 0.97) if s.is_bull else (0.97, 0.96)
                allow_c2 = is_regular_market and not (s.active_ticker == 0 and s.cur_rsi >= cfg.rsi_hi)
            else:
                drop_c2_1, drop_c2_2 = (0.97, 0.96)
                allow_c2 = is_regular_market and not (s.active_ticker == 0 and s.cur_rsi <= cfg.rsi_lo)

            lvl1, lvl2 = prev_c * drop_c2_1, prev_c * drop_c2_2
            lvl3, lvl4 = prev_c * 0.91, prev_c * 0.90  # -9.0%, -10.0%

            # Mutual Exclusion & TrackAlways
            if s.active_ticker != 0 and s.active_ticker != sym:
                if p <= lvl4: bought[0] = bought[1] = bought[2] = bought[3] = True
                elif p <= lvl3: bought[0] = bought[1] = bought[2] = True
                elif is_regular_market and p <= lvl2: bought[0] = bought[1] = True
                elif is_regular_market and p <= lvl1: bought[0] = True
                continue

            # Position Sizing
            c2_pct = 0.05 if cash <= total_equity * 0.20 else 0.10
            buy_pct = 0.0
            matched_lvl = ""

            if p <= lvl4 and not bought[3]:
                bought[0] = bought[1] = bought[2] = bought[3] = True
                buy_pct = 0.10
                matched_lvl = "C1_Level4_-10%"
            elif p <= lvl3 and not bought[2]:
                bought[0] = bought[1] = bought[2] = True
                buy_pct = 0.20
                matched_lvl = "C1_Level3_-9%"
            elif allow_c2 and p <= lvl2 and not bought[1]:
                bought[0] = bought[1] = True
                buy_pct = c2_pct
                matched_lvl = "C2_Level2"
            elif allow_c2 and p <= lvl1 and not bought[0]:
                bought[0] = True
                buy_pct = c2_pct
                matched_lvl = "C2_Level1"

            if buy_pct > 0.0 and cash > 10.0:
                buy_amt = min(cash, total_equity * buy_pct)
                eff_p = p * (1.0 + cfg.fee_rate)
                sh_float = buy_amt / eff_p
                new_sh = math.floor(sh_float) if sh_float >= 1.0 else sh_float
                if new_sh > 0.0:
                    if s.active_ticker == 0:
                        s.active_ticker = sym
                        s.reference_equity = total_equity
                        s.days_held = 1
                    real_buy_cost = new_sh * eff_p
                    s.avg_price = (s.avg_price * s.shares + eff_p * new_sh) / (s.shares + new_sh)
                    s.pure_avg_price = (s.pure_avg_price * s.shares + p * new_sh) / (s.shares + new_sh)
                    s.shares += new_sh
                    cash -= real_buy_cost

                    sym_str = 'SOXL' if sym == 1 else 'SOXS'
                    orders.append({
                        'action': 'BUY',
                        'symbol': sym_str,
                        'qty': new_sh,
                        'price': p,
                        'order_type': 'LIMIT',
                        'reason': f"ENTRY_{matched_lvl} (pct={buy_pct*100:.0f}%, cost={real_buy_cost:.2f})"
                    })

        return orders, cash

    def _reset_position(self):
        s = self.state
        s.shares = 0.0
        s.avg_price = 0.0
        s.pure_avg_price = 0.0
        s.active_ticker = 0
        s.tp_activated = False
        s.days_held = 0
        s.sl_breach_count = 0
        s.tp_trailing_stop = 0.0
        s.initial_shares_for_tp = 0.0
        s.next_target_pct = 0.0

if __name__ == '__main__':
    # Self-test to verify logic
    logging.basicConfig(level=logging.INFO)
    engine = DualMomentumEngine()
    print("DualMomentumEngine initialized with config:")
    print(asdict(engine.config))
    
    # Simulate day 1 (Bull market)
    engine.on_day_start(soxx_close=500.0, soxx_ma200=450.0, soxx_rsi=55.0,
                        prev_close_soxl=40.0, prev_close_soxs=20.0)
    
    # Price drop to trigger Level 1 (-2% = 39.20)
    orders, cash = engine.evaluate_bar("10:00:00", 39.10, 20.20, 100000.0)
    print("\nBar 1 (Level 1 triggered):", orders, "Shares:", engine.state.shares, "PureAvg:", engine.state.pure_avg_price)
    
    # Price rises to +18.5% -> TP Init 50%
    target_price = engine.state.pure_avg_price * 1.185
    orders, cash = engine.evaluate_bar("14:00:00", target_price, 18.0, cash)
    print("\nBar 2 (TP Init 50% triggered):", orders, "Remaining Shares:", engine.state.shares, "TrailStop:", engine.state.tp_trailing_stop)
    
    # Price falls below trailing stop (0.7% gap) -> TP Exit
    stop_hit_price = engine.state.tp_trailing_stop - 0.10
    orders, cash = engine.evaluate_bar("15:00:00", stop_hit_price, 18.5, cash)
    print("\nBar 3 (Trail Stop Exit):", orders, "Final Cash:", cash, "Active:", engine.state.active_ticker)
