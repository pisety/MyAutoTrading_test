import pandas as pd
import yfinance as yf

df_soxx_daily = yf.download('SOXX', start='2019-01-01', end='2026-12-31', progress=False)
if isinstance(df_soxx_daily.columns, pd.MultiIndex):
    df_soxx_daily.columns = df_soxx_daily.columns.droplevel(1)
df_soxx_daily = df_soxx_daily[['Close']].rename(columns={'Close': 'SOXX_Close'})
df_soxx_daily['MA200'] = df_soxx_daily['SOXX_Close'].rolling(window=200).mean()
df_soxx_daily['Prev_Close'] = df_soxx_daily['SOXX_Close'].shift(1)
df_soxx_daily['Prev_MA200'] = df_soxx_daily['MA200'].shift(1)
df_soxx_daily['Is_Bull'] = df_soxx_daily['Prev_Close'] > df_soxx_daily['Prev_MA200']
df_soxx_daily['Pct_Change'] = (df_soxx_daily['SOXX_Close'] / df_soxx_daily['Prev_Close'] - 1.0) * 100.0
df_soxx_daily.index = df_soxx_daily.index.strftime('%Y-%m-%d')
bull_dict = df_soxx_daily['Is_Bull'].to_dict()
pct_dict = df_soxx_daily['Pct_Change'].to_dict()

df_soxl = pd.read_csv('data/SOXL_1min_2020_2026.csv')
df_soxl['timestamp'] = pd.to_datetime(df_soxl['timestamp'])
df_soxl.set_index('timestamp', inplace=True)
df_soxl = df_soxl[~df_soxl.index.duplicated(keep='first')]
df_soxl = df_soxl[['close']].rename(columns={'close': 'SOXL'})

df_soxs = pd.read_csv('data/SOXS_1min_2020_2026.csv')
df_soxs['timestamp'] = pd.to_datetime(df_soxs['timestamp'])
df_soxs.set_index('timestamp', inplace=True)
df_soxs = df_soxs[~df_soxs.index.duplicated(keep='first')]
df_soxs = df_soxs[['close']].rename(columns={'close': 'SOXS'})

df = df_soxl.join(df_soxs, how='outer').ffill().tz_convert('America/New_York').sort_index().dropna()

cash = 100000.0
shares = 0.0
avg_price = 0.0
reference_equity = 100000.0
active_ticker = None

state = {
    'SOXL': {'prev_close': None, 'today_close': None, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False},
    'SOXS': {'prev_close': None, 'today_close': None, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False}
}

tp_activated = False
tp_trailing_stop = 0.0
initial_shares_for_tp = 0.0
next_target_pct = 16

current_date = None
timestamps = df.index
soxl_prices = df['SOXL'].values
soxs_prices = df['SOXS'].values
is_bull_market = True
soxx_is_down_E = False

E_val = 7.3

trade_counts = {'INTRA': 0, 'AH': 0, 'TP': 0, 'STOP': 0}

for i in range(len(timestamps)):
    ts = timestamps[i]
    prices = {'SOXL': soxl_prices[i], 'SOXS': soxs_prices[i]}
    date_str = ts.strftime('%Y-%m-%d')
    time_str = ts.strftime('%H:%M:%S')
        
    if current_date != date_str:
        current_date = date_str
        regime = bull_dict.get(date_str, True)
        is_bull_market = True if pd.isna(regime) else bool(regime)
        soxx_pct = pct_dict.get(date_str, 0.0)
        soxx_is_down_E = soxx_pct <= -abs(E_val)
        for sym in ['SOXL', 'SOXS']:
            state[sym]['b_3pct'] = False; state[sym]['b_4pct'] = False
            state[sym]['b_ah_1pct'] = False; state[sym]['b_ah_2pct'] = False
            state[sym]['today_close'] = None

    active_price = soxl_prices[i] if active_ticker == 'SOXL' else (soxs_prices[i] if active_ticker == 'SOXS' else None)
    total_equity = cash + shares * active_price if active_ticker else cash
    
    # Stop Loss
    if shares > 0 and total_equity < reference_equity * 0.95:
        cash += shares * active_price; shares = 0; avg_price = 0.0; active_ticker = None
        tp_activated = False
        trade_counts['STOP'] += 1
        continue
        
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
            trade_counts['TP'] += 1
            
        if tp_activated:
            while shares > 0 and active_price >= avg_price * (1 + next_target_pct / 100.0):
                sell_shares = initial_shares_for_tp * 0.10
                if sell_shares > shares: sell_shares = shares
                cash += sell_shares * active_price
                shares -= sell_shares
                next_target_pct += 1
                trade_counts['TP'] += 1
            
            if shares > 0 and active_price <= tp_trailing_stop:
                cash += shares * active_price
                shares = 0
                trade_counts['TP'] += 1
                
            if shares > 0 and time_str >= '19:50:00':
                cash += shares * active_price
                shares = 0
                trade_counts['TP'] += 1
                
            if shares <= 0.0001:
                shares = 0
                avg_price = 0.0
                active_ticker = None
                tp_activated = False
                continue
        
    if time_str == '16:00:00':
        for sym in ['SOXL', 'SOXS']:
            state[sym]['today_close'] = prices[sym]
            state[sym]['prev_close'] = state[sym]['today_close']
            
    eval_order = [active_ticker] if active_ticker else ['SOXL', 'SOXS']
    for sym in eval_order:
        if active_ticker is not None and active_ticker != sym: continue
        p = prices[sym]
        s = state[sym]
        
        if sym == 'SOXL':
            intra_1, intra_2 = (0.98, 0.97) if is_bull_market else (0.97, 0.96)
        else:
            intra_1, intra_2 = (0.97, 0.96)
        
        if time_str <= '16:00:00':
            if s['prev_close'] is not None:
                if p <= s['prev_close'] * intra_1 and not s['b_3pct']:
                    s['b_3pct'] = True
                    if active_ticker is None: active_ticker = sym
                    reference_equity = total_equity
                    pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                    buy_amt = total_equity * pct
                    if cash >= buy_amt:
                        new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        trade_counts['INTRA'] += 1
                if p <= s['prev_close'] * intra_2 and not s['b_4pct']:
                    s['b_4pct'] = True
                    if active_ticker is None: active_ticker = sym
                    reference_equity = total_equity
                    pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                    buy_amt = total_equity * pct
                    if cash >= buy_amt:
                        new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        trade_counts['INTRA'] += 1
        elif time_str > '16:00:00' and s['today_close'] is not None:
            if soxx_is_down_E:
                if p <= s['today_close'] * 0.99 and not s['b_ah_1pct']:
                    s['b_ah_1pct'] = True
                    if active_ticker is None: active_ticker = sym
                    reference_equity = total_equity
                    buy_amt = total_equity * 0.20
                    if cash >= buy_amt:
                        new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        trade_counts['AH'] += 1
                if p <= s['today_close'] * 0.98 and not s['b_ah_2pct']:
                    s['b_ah_2pct'] = True
                    if active_ticker is None: active_ticker = sym
                    reference_equity = total_equity
                    buy_amt = total_equity * 0.10
                    if cash >= buy_amt:
                        new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                        shares += new_shares; cash -= buy_amt
                        trade_counts['AH'] += 1
                    
print("Trade Counts:", trade_counts)
print("Final Equity:", total_equity)
