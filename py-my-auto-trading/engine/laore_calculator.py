import math
import logging

logger = logging.getLogger("LaoreCalculator")

def get_upbit_tick_size(price):
    if price >= 2000000: return 1000.0
    if price >= 1000000: return 500.0
    if price >= 500000: return 100.0
    if price >= 100000: return 50.0
    if price >= 10000: return 10.0
    if price >= 1000: return 1.0
    if price >= 100: return 0.1
    if price >= 10: return 0.01
    if price >= 1: return 0.001
    return 0.0001

def round_price_for_crypto(price):
    tick = get_upbit_tick_size(price)
    return float(round(price / tick) * tick)

def format_crypto_qty(qty):
    return round(float(qty), 8)

def format_stock_qty(qty):
    return int(qty)

def calculate_daily_orders(settings, status, current_price, service_type="stock"):
    """
    Calculate the daily orders based on the Laore Infinite Buying VR rules.
    Supports both stock (LOC) and crypto (LIMIT) markets.
    """
    orders = {'buy': [], 'sell': []}
    
    total_budget = settings.get("total_budget", 0)
    split_count = settings.get("split_count", 40)
    target_yield = settings.get("target_yield_percent", 10.0) / 100.0
    
    current_round = status.get("current_round", 0)
    avg_price = status.get("average_price", 0.0)
    holding_qty = status.get("holding_quantity", 0.0)
    current_mode = status.get("current_mode", "일반 모드(전반전)")
    
    daily_budget = total_budget / split_count if split_count > 0 else 0
    if daily_budget <= 0 or current_price <= 0:
        return orders
        
    is_crypto = (service_type == "crypto")
    order_type = 'LIMIT' if is_crypto else 'LOC'
    
    def _format_price(p):
        if is_crypto:
            return round_price_for_crypto(p)
        return round(p, 2)
        
    def _format_qty(q):
        if is_crypto:
            return format_crypto_qty(q)
        return format_stock_qty(q)
        
    if avg_price > 0:
        target_sell_price = avg_price * (1 + target_yield)
    else:
        target_sell_price = 0.0
        
    if holding_qty > 0 and target_sell_price > 0:
        orders['sell'].append({
            'price': _format_price(target_sell_price),
            'qty': _format_qty(holding_qty),
            'type': order_type
        })
        
    if current_round >= split_count and split_count > 0:
        # 분할 매수 횟수(예: 20회) 100% 도달 시: 추가 자금 소진을 막기 위해 매수 주문은 중단하고 목표 매도(익절) 주문만 반환
        return orders
        
    if is_crypto:
        min_order_value = 5050.0 # 여유분 포함
        
        # 1. 1회차 매수 (또는 평단가 없는 초기상태)
        if current_round == 0 or avg_price <= 0:
            buy_price = current_price * 1.10 if is_crypto else current_price
            buy_value = max(daily_budget, min_order_value)
            orders['buy'].append({
                'price': _format_price(buy_price),
                'qty': _format_qty(buy_value / buy_price),
                'type': order_type
            })
            return orders
            
        if "일반 모드" in current_mode:
            half_value = daily_budget / 2.0
            
            if half_value < min_order_value:
                # 일일 예산이 작아서 2분할 불가: 하나로 합쳐서 진행
                if current_round >= (split_count / 2) and current_price >= avg_price:
                    buy_price = avg_price
                else:
                    buy_price = current_price * 1.10 if current_price <= avg_price else avg_price
                    
                buy_value = max(daily_budget, min_order_value)
                orders['buy'].append({
                    'price': _format_price(buy_price),
                    'qty': _format_qty(buy_value / buy_price),
                    'type': order_type
                })
            else:
                # 2분할 정상 진행
                price_avg = avg_price
                orders['buy'].append({
                    'price': _format_price(price_avg),
                    'qty': _format_qty(half_value / price_avg),
                    'type': order_type
                })
                
                if current_round < (split_count / 2) or current_price < avg_price:
                    price_10 = current_price * 1.10
                    orders['buy'].append({
                        'price': _format_price(price_10),
                        'qty': _format_qty(half_value / price_10),
                        'type': order_type
                    })
                    
            # 폭락 대비 주문 (최소 주문 금액으로 각각 설정)
            price_95 = current_price * 0.95
            orders['buy'].append({
                'price': _format_price(price_95),
                'qty': _format_qty(min_order_value / price_95),
                'type': order_type
            })
            price_90 = current_price * 0.90
            orders['buy'].append({
                'price': _format_price(price_90),
                'qty': _format_qty(min_order_value / price_90),
                'type': order_type
            })
            
        return orders
        
    # === 주식 (Stock) 로직 ===
    base_qty = math.floor(daily_budget / current_price)
    if base_qty < 2:
        base_qty = 2 
    half_qty = base_qty // 2
    crash_qty = 1
    
    if "일반 모드" in current_mode:
        if current_round < (split_count / 2):
            orders['buy'].append({
                'price': _format_price(avg_price if avg_price > 0 else current_price),
                'qty': _format_qty(half_qty),
                'type': order_type
            })
            orders['buy'].append({
                'price': _format_price(current_price * 1.10),
                'qty': _format_qty(base_qty - half_qty),
                'type': order_type
            })
        else:
            orders['buy'].append({
                'price': _format_price(avg_price),
                'qty': _format_qty(half_qty),
                'type': order_type
            })
            if current_price < avg_price:
                orders['buy'].append({
                    'price': _format_price(current_price * 1.10),
                    'qty': _format_qty(base_qty - half_qty),
                    'type': order_type
                })
                
        # 폭락 대비
        orders['buy'].append({
            'price': _format_price(current_price * 0.95),
            'qty': _format_qty(crash_qty),
            'type': order_type
        })
        orders['buy'].append({
            'price': _format_price(current_price * 0.90),
            'qty': _format_qty(crash_qty),
            'type': order_type
        })

    return orders
