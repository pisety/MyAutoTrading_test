import pandas as pd
from datetime import datetime
import time

def run_single(symbol, file_path, E_threshold):
    df = pd.read_csv(file_path)
    df['timestamp'] = pd.to_datetime(df['timestamp'])
    df.set_index('timestamp', inplace=True)
    df = df.tz_convert('America/New_York')
    df = df.sort_index().dropna()
    df = df[~df.index.duplicated(keep='first')]
    
    cash = 100000.0
    shares = 0.0
    avg_price = 0.0
    reference_equity = 100000.0
    monthly_equity = {}
    prev_close = None
    today_close = None
    bought_3pct = False; bought_4pct = False
    bought_ah_1pct = False; bought_ah_2pct = False
    sold_ah_rule1 = False; sold_ah_rule2 = False
    sold_ah_rule3 = False; sold_ah_rule4 = False
    is_down_E = False; is_up_8 = False
    current_date = None
    
    timestamps = df.index
    closes = df['close'].values
    
    for i in range(len(timestamps)):
        ts = timestamps[i]
        price = closes[i]
        date_str = ts.strftime('%Y-%m-%d')
        month_str = ts.strftime('%Y-%m')
        time_str = ts.strftime('%H:%M:%S')
        
        if current_date != date_str:
            current_date = date_str
            bought_3pct = False; bought_4pct = False
            bought_ah_1pct = False; bought_ah_2pct = False
            sold_ah_rule1 = False; sold_ah_rule2 = False
            sold_ah_rule3 = False; sold_ah_rule4 = False
            today_close = None
            is_down_E = False; is_up_8 = False
            
        total_equity = cash + shares * price
        monthly_equity[month_str] = total_equity
        
        if shares > 0 and total_equity < reference_equity * 0.95:
            cash += shares * price; shares = 0; avg_price = 0.0; continue 
            
        if shares > 0 and price >= avg_price * 1.15:
            cash += shares * price; shares = 0; avg_price = 0.0; continue
            
        if time_str == '16:00:00':
            today_close = price
            if prev_close is not None:
                if today_close <= prev_close * (1 + E_threshold/100.0): is_down_E = True
                if today_close >= prev_close * 1.08: is_up_8 = True
            prev_close = today_close
            
        if time_str <= '16:00:00':
            if prev_close is not None:
                if price <= prev_close * 0.97 and not bought_3pct:
                    bought_3pct = True; reference_equity = total_equity
                    pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                    buy_amt = total_equity * pct
                    if cash >= buy_amt:
                        new_shares = buy_amt / price; avg_price = (avg_price * shares + price * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                
                if price <= prev_close * 0.96 and not bought_4pct:
                    bought_4pct = True; reference_equity = total_equity
                    pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                    buy_amt = total_equity * pct
                    if cash >= buy_amt:
                        new_shares = buy_amt / price; avg_price = (avg_price * shares + price * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        
        elif time_str > '16:00:00' and today_close is not None:
            if is_down_E:
                if price <= today_close * 0.99 and not bought_ah_1pct:
                    bought_ah_1pct = True; reference_equity = total_equity; buy_amt = total_equity * 0.20
                    if cash >= buy_amt:
                        new_shares = buy_amt / price; avg_price = (avg_price * shares + price * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                if price <= today_close * 0.98 and not bought_ah_2pct:
                    bought_ah_2pct = True; reference_equity = total_equity; buy_amt = total_equity * 0.10
                    if cash >= buy_amt:
                        new_shares = buy_amt / price; avg_price = (avg_price * shares + price * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        
            if is_up_8 and shares > 0:
                if price >= today_close * 1.01 and not sold_ah_rule1:
                    sold_ah_rule1 = True; sell_shares = shares * 0.5; cash += sell_shares * price; shares -= sell_shares
                if sold_ah_rule1 and price >= today_close * 1.03 and not sold_ah_rule2:
                    sold_ah_rule2 = True; cash += shares * price; shares = 0; avg_price = 0.0
                    
            if shares > 0:
                if price >= today_close * 1.08 and not sold_ah_rule3:
                    sold_ah_rule3 = True; sell_shares = shares * 0.5; cash += sell_shares * price; shares -= sell_shares
                if sold_ah_rule3 and price >= today_close * 1.12 and not sold_ah_rule4:
                    sold_ah_rule4 = True; cash += shares * price; shares = 0; avg_price = 0.0
                    
    return monthly_equity

for e in [-9, -8, -7]:
    res_soxl = run_single("SOXL", 'data/SOXL_1min_2020_2026.csv', e)
    res_soxs = run_single("SOXS", 'data/SOXS_1min_2020_2026.csv', e)
    last_month = list(res_soxl.keys())[-1]
    final_combined = res_soxl[last_month] * 0.5 + res_soxs[last_month] * 0.5
    ret = ((final_combined / 100000.0) - 1.0) * 100.0
    print(f"Indep E={e}% -> Final Combined Equity:  | Return: {ret:.2f}%")

