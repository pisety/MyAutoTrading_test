import pandas as pd
import matplotlib.pyplot as plt
from datetime import datetime
import os

def run_dual_stats(E_threshold):
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
    
    monthly_data = {}
    
    state = {
        'SOXL': {'prev_close': None, 'today_close': None, 'is_down_E': False, 'is_up_8': False, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False, 's_ah_1': False, 's_ah_2': False, 's_ah_3': False, 's_ah_4': False},
        'SOXS': {'prev_close': None, 'today_close': None, 'is_down_E': False, 'is_up_8': False, 'b_3pct': False, 'b_4pct': False, 'b_ah_1pct': False, 'b_ah_2pct': False, 's_ah_1': False, 's_ah_2': False, 's_ah_3': False, 's_ah_4': False}
    }
    
    current_date = None
    timestamps = df.index
    soxl_prices = df['SOXL'].values
    soxs_prices = df['SOXS'].values
    
    for i in range(len(timestamps)):
        ts = timestamps[i]
        prices = {'SOXL': soxl_prices[i], 'SOXS': soxs_prices[i]}
        date_str = ts.strftime('%Y-%m-%d')
        month_str = ts.strftime('%Y-%m')
        time_str = ts.strftime('%H:%M:%S')
        
        if month_str not in monthly_data:
            monthly_data[month_str] = {'Equity': 0.0, 'Buys': 0, 'Sells': 0}
            
        if current_date != date_str:
            current_date = date_str
            for sym in ['SOXL', 'SOXS']:
                state[sym]['b_3pct'] = False; state[sym]['b_4pct'] = False
                state[sym]['b_ah_1pct'] = False; state[sym]['b_ah_2pct'] = False
                state[sym]['s_ah_1'] = False; state[sym]['s_ah_2'] = False
                state[sym]['s_ah_3'] = False; state[sym]['s_ah_4'] = False
                state[sym]['today_close'] = None
                state[sym]['is_down_E'] = False; state[sym]['is_up_8'] = False

        active_price = soxl_prices[i] if active_ticker == 'SOXL' else (soxs_prices[i] if active_ticker == 'SOXS' else None)
        total_equity = cash + shares * active_price if active_ticker else cash
        monthly_data[month_str]['Equity'] = total_equity
        
        # Stop loss
        if shares > 0 and total_equity < reference_equity * 0.95:
            cash += shares * active_price; shares = 0; avg_price = 0.0; active_ticker = None
            monthly_data[month_str]['Sells'] += 1
            continue
            
        # 15% Take profit
        if shares > 0 and active_price >= avg_price * 1.15:
            cash += shares * active_price; shares = 0; avg_price = 0.0; active_ticker = None
            monthly_data[month_str]['Sells'] += 1
            continue
            
        if time_str == '16:00:00':
            for sym in ['SOXL', 'SOXS']:
                state[sym]['today_close'] = prices[sym]
                if state[sym]['prev_close'] is not None:
                    if state[sym]['today_close'] <= state[sym]['prev_close'] * (1 + E_threshold/100.0): state[sym]['is_down_E'] = True
                    if state[sym]['today_close'] >= state[sym]['prev_close'] * 1.08: state[sym]['is_up_8'] = True
                state[sym]['prev_close'] = state[sym]['today_close']
                
        eval_order = [active_ticker] if active_ticker else ['SOXL', 'SOXS']
        for sym in eval_order:
            if active_ticker is not None and active_ticker != sym: continue
            p = prices[sym]
            s = state[sym]
            
            if time_str <= '16:00:00':
                if s['prev_close'] is not None:
                    if p <= s['prev_close'] * 0.97 and not s['b_3pct']:
                        s['b_3pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                        buy_amt = total_equity * pct
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                            monthly_data[month_str]['Buys'] += 1
                    if p <= s['prev_close'] * 0.96 and not s['b_4pct']:
                        s['b_4pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        pct = 0.05 if cash <= total_equity * 0.20 else 0.10
                        buy_amt = total_equity * pct
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                            monthly_data[month_str]['Buys'] += 1
            elif time_str > '16:00:00' and s['today_close'] is not None:
                if s['is_down_E']:
                    if p <= s['today_close'] * 0.99 and not s['b_ah_1pct']:
                        s['b_ah_1pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        buy_amt = total_equity * 0.20
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                            monthly_data[month_str]['Buys'] += 1
                    if p <= s['today_close'] * 0.98 and not s['b_ah_2pct']:
                        s['b_ah_2pct'] = True
                        if active_ticker is None: active_ticker = sym
                        reference_equity = total_equity
                        buy_amt = total_equity * 0.10
                        if cash >= buy_amt:
                            new_shares = buy_amt / p; avg_price = (avg_price * shares + p * new_shares) / (shares + new_shares)
                            shares += new_shares; cash -= buy_amt
                            monthly_data[month_str]['Buys'] += 1
                if s['is_up_8'] and shares > 0 and active_ticker == sym:
                    if p >= s['today_close'] * 1.01 and not s['s_ah_1']:
                        s['s_ah_1'] = True; sell_shares = shares * 0.5; cash += sell_shares * p; shares -= sell_shares
                        monthly_data[month_str]['Sells'] += 1
                    if s['s_ah_1'] and p >= s['today_close'] * 1.03 and not s['s_ah_2']:
                        s['s_ah_2'] = True; cash += shares * p; shares = 0; avg_price = 0.0; active_ticker = None
                        monthly_data[month_str]['Sells'] += 1
                if shares > 0 and active_ticker == sym:
                    if p >= s['today_close'] * 1.08 and not s['s_ah_3']:
                        s['s_ah_3'] = True; sell_shares = shares * 0.5; cash += sell_shares * p; shares -= sell_shares
                        monthly_data[month_str]['Sells'] += 1
                    if s['s_ah_3'] and p >= s['today_close'] * 1.12 and not s['s_ah_4']:
                        s['s_ah_4'] = True; cash += shares * p; shares = 0; avg_price = 0.0; active_ticker = None
                        monthly_data[month_str]['Sells'] += 1
                        
    return monthly_data

data = run_dual_stats(-7)

months = list(data.keys())
equities = [data[m]['Equity'] for m in months]
buys = [data[m]['Buys'] for m in months]
sells = [data[m]['Sells'] for m in months]

df = pd.DataFrame({
    'Month': months,
    'Equity ($)': equities,
    'Buy_Count': buys,
    'Sell_Count': sells,
    'Total_Trades': [b+s for b, s in zip(buys, sells)]
})

# Save to CSV for the user to analyze in Excel
out_dir = r'C:\Users\piset\.gemini\antigravity\brain\2178f1df-4c49-440f-94ca-b50c2e62a824'
csv_path = os.path.join(out_dir, 'dual_switching_E7_monthly.csv')
df.to_csv(csv_path, index=False)

# Plot
plt.style.use('dark_background')
fig, ax1 = plt.subplots(figsize=(14, 7))

ax1.set_xlabel('Month')
ax1.set_ylabel('Equity ($) - Log Scale', color='cyan')
ax1.plot(months, equities, color='cyan', marker='.', linewidth=2, label='Equity')
ax1.set_yscale('log')
ax1.tick_params(axis='y', labelcolor='cyan')
ax1.set_xticks(months[::6])
ax1.set_xticklabels(months[::6], rotation=45)

ax2 = ax1.twinx()
ax2.set_ylabel('Trade Count (Buys + Sells)', color='orange')
width = 0.4
ax2.bar(months, buys, width, label='Buys', color='lime', alpha=0.6)
ax2.bar(months, sells, width, bottom=buys, label='Sells', color='red', alpha=0.6)
ax2.tick_params(axis='y', labelcolor='orange')

fig.suptitle('Dual Switching (E=-7%) Monthly Performance & Trades', fontsize=16)
fig.tight_layout()
png_path = os.path.join(out_dir, 'dual_switching_chart.png')
plt.savefig(png_path, dpi=300)
print(f"Chart saved to {png_path}")
print(f"CSV saved to {csv_path}")

