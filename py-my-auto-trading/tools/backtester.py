import pandas as pd
from datetime import datetime
import pytz
import time

def run_backtest():
    print("데이터 로딩 중...")
    df = pd.read_csv('data/SOXL_1min_2020_2026.csv')
    
    # Parse timestamp and convert to NY time
    df['timestamp'] = pd.to_datetime(df['timestamp'])
    df.set_index('timestamp', inplace=True)
    df = df.tz_convert('America/New_York')
    df = df.sort_index()

    # Drop duplicate index if any
    df = df[~df.index.duplicated(keep='first')]
    
    print("백테스트 시뮬레이션 시작...")
    
    cash = 100000.0
    shares = 0.0
    reference_equity = 100000.0
    
    # To track monthly equity
    monthly_equity = {}
    
    prev_close = None
    today_close = None
    
    # State flags
    bought_2pct = False
    bought_3pct = False
    
    bought_ah_1pct = False
    bought_ah_2pct = False
    
    sold_ah_rule1 = False
    sold_ah_rule2 = False
    sold_ah_rule3 = False
    sold_ah_rule4 = False
    
    is_down_8 = False
    is_up_8 = False
    
    current_date = None
    
    start_time = time.time()
    
    timestamps = df.index
    closes = df['close'].values
    
    for i in range(len(timestamps)):
        ts = timestamps[i]
        price = closes[i]
        
        date_str = ts.strftime('%Y-%m-%d')
        month_str = ts.strftime('%Y-%m')
        
        if current_date != date_str:
            # New day!
            current_date = date_str
            bought_2pct = False
            bought_3pct = False
            bought_ah_1pct = False
            bought_ah_2pct = False
            sold_ah_rule1 = False
            sold_ah_rule2 = False
            sold_ah_rule3 = False
            sold_ah_rule4 = False
            
            # We don't know today_close yet
            today_close = None
            is_down_8 = False
            is_up_8 = False
            
        time_str = ts.strftime('%H:%M:%S')
        total_equity = cash + shares * price
        
        # Track end of month equity
        monthly_equity[month_str] = total_equity
        
        # Stop loss check
        if shares > 0 and total_equity < reference_equity * 0.95:
            cash += shares * price
            shares = 0
            continue # skip other rules for this minute
            
        # 16:00 is Regular Close
        if time_str == '16:00:00':
            today_close = price
            if prev_close is not None:
                if today_close <= prev_close * 0.92:
                    is_down_8 = True
                if today_close >= prev_close * 1.08:
                    is_up_8 = True
            # Update prev_close for tomorrow
            prev_close = today_close
            
        # Pre-market & Regular Market (04:00:00 ~ 16:00:00)
        if time_str <= '16:00:00':
            if prev_close is not None:
                # Buy Rule 2
                if price <= prev_close * 0.98 and not bought_2pct:
                    bought_2pct = True
                    reference_equity = total_equity
                    pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                    buy_amt = total_equity * pct
                    if cash >= buy_amt:
                        shares += buy_amt / price
                        cash -= buy_amt
                
                if price <= prev_close * 0.97 and not bought_3pct:
                    bought_3pct = True
                    reference_equity = total_equity
                    pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                    buy_amt = total_equity * pct
                    if cash >= buy_amt:
                        shares += buy_amt / price
                        cash -= buy_amt
                        
        # After-hours Market (16:00:00+ ~ 20:00:00)
        elif time_str > '16:00:00' and today_close is not None:
            # Buy Rule 1 (if down 8% during regular)
            if is_down_8:
                if price <= today_close * 0.99 and not bought_ah_1pct:
                    bought_ah_1pct = True
                    reference_equity = total_equity
                    buy_amt = total_equity * 0.20
                    if cash >= buy_amt:
                        shares += buy_amt / price
                        cash -= buy_amt
                if price <= today_close * 0.98 and not bought_ah_2pct:
                    bought_ah_2pct = True
                    reference_equity = total_equity
                    buy_amt = total_equity * 0.10
                    if cash >= buy_amt:
                        shares += buy_amt / price
                        cash -= buy_amt
                        
            # Sell Rule 1 & 2 (if up 8% during regular)
            if is_up_8 and shares > 0:
                if price >= today_close * 1.01 and not sold_ah_rule1:
                    sold_ah_rule1 = True
                    sell_shares = shares * 0.5
                    cash += sell_shares * price
                    shares -= sell_shares
                    
                if sold_ah_rule1 and price >= today_close * 1.03 and not sold_ah_rule2:
                    sold_ah_rule2 = True
                    cash += shares * price
                    shares = 0
                    
            # Sell Rule 3 & 4 (any time AH)
            if shares > 0:
                if price >= today_close * 1.08 and not sold_ah_rule3:
                    sold_ah_rule3 = True
                    sell_shares = shares * 0.5
                    cash += sell_shares * price
                    shares -= sell_shares
                    
                if sold_ah_rule3 and price >= today_close * 1.12 and not sold_ah_rule4:
                    sold_ah_rule4 = True
                    cash += shares * price
                    shares = 0
                    
    print(f"시뮬레이션 완료! (소요 시간: {time.time() - start_time:.2f}초)")
    
    # Save monthly report
    report = []
    for month, eq in monthly_equity.items():
        ret = ((eq / 100000.0) - 1.0) * 100.0
        report.append({'Month': month, 'Equity': f"", 'Return(%)': f"{ret:.2f}%"})
        
    report_df = pd.DataFrame(report)
    report_df.to_csv('data/backtest_report.csv', index=False)
    
    print("\n[연도별 마지막 달 성과 요약]")
    # Print only December of each year, plus the very last month (Sep 2026)
    summary_df = report_df[report_df['Month'].str.endswith('-12') | (report_df['Month'] == report_df['Month'].iloc[-1])]
    print(summary_df.to_string(index=False))

if __name__ == "__main__":
    run_backtest()
