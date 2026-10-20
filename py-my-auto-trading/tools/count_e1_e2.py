import pandas as pd
import yfinance as yf
import numpy as np

def run_intraday_E(e_val):
    # 1. Fetch Daily SOXX for bull/bear regime
    df_soxx_daily = yf.download('SOXX', start='2019-01-01', end='2026-12-31', progress=False)
    if isinstance(df_soxx_daily.columns, pd.MultiIndex):
        df_soxx_daily.columns = df_soxx_daily.columns.droplevel(1)
    df_soxx_daily = df_soxx_daily[['Close']].rename(columns={'Close': 'SOXX_Close'})
    df_soxx_daily['MA200'] = df_soxx_daily['SOXX_Close'].rolling(window=200).mean()
    df_soxx_daily['Prev_Close'] = df_soxx_daily['SOXX_Close'].shift(1)
    df_soxx_daily['Prev_MA200'] = df_soxx_daily['MA200'].shift(1)
    df_soxx_daily['Is_Bull'] = df_soxx_daily['Prev_Close'] > df_soxx_daily['Prev_MA200']
    df_soxx_daily.index = df_soxx_daily.index.strftime('%Y-%m-%d')
    bull_dict = df_soxx_daily['Is_Bull'].to_dict()

    df_soxl = pd.read_csv('data/SOXL_1min_2020_2026.csv', parse_dates=['timestamp']).set_index('timestamp')
    df_soxl = df_soxl[['close']].rename(columns={'close': 'SOXL'})
    df_soxl = df_soxl[~df_soxl.index.duplicated(keep='first')]
    
    df_soxs = pd.read_csv('data/SOXS_1min_2020_2026.csv', parse_dates=['timestamp']).set_index('timestamp')
    df_soxs = df_soxs[['close']].rename(columns={'close': 'SOXS'})
    df_soxs = df_soxs[~df_soxs.index.duplicated(keep='first')]
    
    df = df_soxl.join(df_soxs, how='outer').ffill().tz_convert('America/New_York').sort_index().dropna()

    cash = 100000.0
    shares = 0.0
    avg_price = 0.0
    reference_equity = 100000.0
    active_ticker = None
    monthly_equity = {}
    
    state = {
        'SOXL': {'prev_close': None, 'b_3pct': False, 'b_4pct': False, 'b_E1': False, 'b_E2': False},
        'SOXS': {'prev_close': None, 'b_3pct': False, 'b_4pct': False, 'b_E1': False, 'b_E2': False}
    }
    
    counts_E1 = {}
    counts_E2 = {}
    
    tp_activated = False
    tp_trailing_stop = 0.0
    initial_shares_for_tp = 0.0
    next_target_pct = 16
    
    current_date = None
    timestamps = df.index
    soxl_prices = df['SOXL'].values
    soxs_prices = df['SOXS'].values
    is_bull_market = True
    
    e1_drop = e_val / 100.0
    e2_drop = (e_val + 1.0) / 100.0
    
    for i in range(len(timestamps)):
        ts = timestamps[i]
        prices = {'SOXL': soxl_prices[i], 'SOXS': soxs_prices[i]}
        date_str = ts.strftime('%Y-%m-%d')
        month_str = ts.strftime('%Y-%m')
        year_str = ts.strftime('%Y')
        time_str = ts.strftime('%H:%M:%S')
        
        if year_str not in counts_E1:
            counts_E1[year_str] = 0
            counts_E2[year_str] = 0
            
        if current_date != date_str:
            current_date = date_str
            regime = bull_dict.get(date_str, True)
            is_bull_market = True if pd.isna(regime) else bool(regime)
            
            for sym in ['SOXL', 'SOXS']:
                state[sym]['b_3pct'] = False
                state[sym]['b_4pct'] = False
                state[sym]['b_E1'] = False
                state[sym]['b_E2'] = False

        active_price = soxl_prices[i] if active_ticker == 'SOXL' else (soxs_prices[i] if active_ticker == 'SOXS' else None)
        total_equity = cash + shares * active_price if active_ticker else cash
        monthly_equity[month_str] = total_equity
        
        # Stop Loss
        if shares > 0 and total_equity < reference_equity * 0.95:
            cash += shares * active_price; shares = 0; avg_price = 0.0; active_ticker = None
            tp_activated = False
            continue
            
        # Trailing Take Profit
        if shares > 0 and active_ticker is not None:
            if not tp_activated and active_price >= avg_price * 1.15:
                tp_activated = True
                tp_trailing_stop = avg_price * 1.145
                initial_shares_for_tp = shares
                
                sell_shares = initial_shares_for_tp * 0.50
                if sell_shares > shares: sell_shares = shares
                cash += sell_shares * active_price
                shares -= sell_shares
                
                next_target_pct = 16
                
            if tp_activated:
                while shares > 0 and active_price >= avg_price * (1 + next_target_pct / 100.0):
                    sell_shares = initial_shares_for_tp * 0.10
                    if sell_shares > shares: sell_shares = shares
                    cash += sell_shares * active_price
                    shares -= sell_shares
                    next_target_pct += 1
                
                if shares > 0 and active_price <= tp_trailing_stop:
                    cash += shares * active_price
                    shares = 0
                    
                if shares > 0 and time_str >= '19:50:00':
                    cash += shares * active_price
                    shares = 0
                    
                if shares <= 0.0001:
                    shares = 0
                    avg_price = 0.0
                    active_ticker = None
                    tp_activated = False
                    continue
            
        # Record daily close at 16:00
        if time_str == '16:00:00':
            for sym in ['SOXL', 'SOXS']:
                state[sym]['prev_close'] = prices[sym]
                
        eval_order = [active_ticker] if active_ticker else ['SOXL', 'SOXS']
        for sym in eval_order:
            if active_ticker is not None and active_ticker != sym: continue
            
            p = prices[sym]
            s = state[sym]
            
            if sym == 'SOXL':
                intra_1, intra_2 = (0.98, 0.97) if is_bull_market else (0.97, 0.96)
            else:
                intra_1, intra_2 = (0.97, 0.96)
            
            if s['prev_close'] is not None:
                if time_str >= '09:30:00' and time_str <= '16:00:00':
                    if p <= s['prev_close'] * intra_1 and not s['b_3pct']:
                        s['b_3pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                        buy_amt = total_equity * pct
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                    if p <= s['prev_close'] * intra_2 and not s['b_4pct']:
                        s['b_4pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                        buy_amt = total_equity * pct
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                
                # Pre-market + Regular + After-market: E1 and E2
                if p <= s['prev_close'] * (1 - e1_drop) and not s['b_E1']:
                    s['b_E1'] = True
                    counts_E1[year_str] += 1
                    if active_ticker is None: active_ticker = sym
                    reference_equity = total_equity
                    buy_amt = total_equity * 0.20
                    if cash >= buy_amt:
                        new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                
                if p <= s['prev_close'] * (1 - e2_drop) and not s['b_E2']:
                    s['b_E2'] = True
                    counts_E2[year_str] += 1
                    if active_ticker is None: active_ticker = sym
                    reference_equity = total_equity
                    buy_amt = total_equity * 0.10
                    if cash >= buy_amt:
                        new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        
    return monthly_equity, counts_E1, counts_E2

def calc_annual(monthly_equity):
    report = [{'Month': m, 'Equity': eq} for m, eq in monthly_equity.items()]
    df_rep = pd.DataFrame(report)
    summary = df_rep[df_rep['Month'].str.endswith('-12') | (df_rep['Month'] == df_rep['Month'].iloc[-1])]
    
    annual_rets = {}
    prev_eq = 100000.0
    for idx, row in summary.iterrows():
        year = row['Month'][:4]
        eq = row['Equity']
        ann_ret = ((eq / prev_eq) - 1.0) * 100.0
        annual_rets[year] = ann_ret
        prev_eq = eq
    final_eq = summary['Equity'].iloc[-1]
    cum_ret = ((final_eq / 100000.0) - 1.0) * 100.0
    annual_rets['Cumulative'] = cum_ret
    return annual_rets

monthly_equity, counts_E1, counts_E2 = run_intraday_E(9.0)
annual_rets = calc_annual(monthly_equity)

print("Year\tReturn(%)\tCondition1(E1=9.0%)\tCondition2(E2=10.0%)")
for y in ['2020', '2021', '2022', '2023', '2024', '2025', '2026']:
    r = annual_rets.get(y, 0)
    c1 = counts_E1.get(y, 0)
    c2 = counts_E2.get(y, 0)
    print(f"{y}\t{r:,.2f}%\t\t{c1}\t\t\t{c2}")
print(f"Total\t{annual_rets.get('Cumulative', 0):,.2f}%\t\t{sum(counts_E1.values())}\t\t\t{sum(counts_E2.values())}")
