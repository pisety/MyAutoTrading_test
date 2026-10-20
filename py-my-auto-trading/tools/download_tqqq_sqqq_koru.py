"""Download TQQQ, SQQQ, KORU 1-minute bars (2020~2026) with Adjustment.SPLIT
Also downloads QQQ and EWY daily bars for benchmark indicators (200MA, RSI).
"""
import os, time, sys
from datetime import datetime
from alpaca.data.historical import StockHistoricalDataClient
from alpaca.data.requests import StockBarsRequest
from alpaca.data.timeframe import TimeFrame
from alpaca.data.enums import Adjustment
import pandas as pd

sys.stdout.reconfigure(encoding='utf-8')

API_KEY = "PKUNFZE5G2L7YJ637NY4QZLBID"
SECRET_KEY = "6mYKfact4N3WaAv6p7hkjpap71aey8fNUT6Goqtd6AR"
client = StockHistoricalDataClient(API_KEY, SECRET_KEY)

OUT_DIR = "data"
os.makedirs(OUT_DIR, exist_ok=True)

def download_daily_benchmarks():
    print("--- [1] Downloading Daily Benchmarks (QQQ, EWY) ---", flush=True)
    for sym in ['QQQ', 'EWY']:
        out_csv = os.path.join(OUT_DIR, f"{sym}_daily_2019_2026.csv")
        req = StockBarsRequest(
            symbol_or_symbols=sym,
            timeframe=TimeFrame.Day,
            start=datetime(2019, 1, 1),
            end=datetime(2026, 10, 1),
            adjustment=Adjustment.SPLIT
        )
        try:
            bars = client.get_stock_bars(req)
            df = bars.df
            df.to_csv(out_csv)
            print(f"  {sym} daily saved: {len(df)} rows -> {out_csv}", flush=True)
        except Exception as e:
            print(f"  Error downloading {sym}: {e}", flush=True)

def download_minute_ticker(symbol):
    out_csv = os.path.join(OUT_DIR, f"{symbol}_1min_2020_2026.csv")
    if os.path.exists(out_csv) and os.path.getsize(out_csv) > 10000000:
        print(f"  {symbol} already downloaded ({os.path.getsize(out_csv)} bytes). Skipping.", flush=True)
        return
        
    print(f"\n--- [2] Downloading {symbol} 1-minute bars (2020-2026) ---", flush=True)
    all_dfs = []
    t_start = time.time()
    
    for year in range(2020, 2027):
        start = datetime(year, 1, 1)
        end = datetime(year, 12, 31, 23, 59)
        if year == 2026:
            end = datetime(2026, 9, 30, 23, 59)
            
        print(f"  Fetching {symbol} {year}...", end=" ", flush=True)
        t_yr = time.time()
        req = StockBarsRequest(
            symbol_or_symbols=symbol,
            timeframe=TimeFrame.Minute,
            start=start,
            end=end,
            adjustment=Adjustment.SPLIT
        )
        try:
            bars = client.get_stock_bars(req)
            df = bars.df
            all_dfs.append(df)
            print(f"OK ({len(df)} rows in {time.time()-t_yr:.1f}s)", flush=True)
        except Exception as e:
            print(f"Error: {e}", flush=True)
            
    if all_dfs:
        final_df = pd.concat(all_dfs)
        final_df.to_csv(out_csv)
        print(f"  {symbol} COMPLETE: {len(final_df)} rows saved to {out_csv} ({time.time()-t_start:.1f}s)", flush=True)
    else:
        print(f"  {symbol} FAILED to download any data.", flush=True)

if __name__ == "__main__":
    download_daily_benchmarks()
    # Download KORU first (fastest, ~1.5 min), then SQQQ (~4 min), then TQQQ (~4 min)
    download_minute_ticker("KORU")
    download_minute_ticker("SQQQ")
    download_minute_ticker("TQQQ")
    print("\nALL DOWNLOADS FINISHED SUCCESSFULLY!", flush=True)
