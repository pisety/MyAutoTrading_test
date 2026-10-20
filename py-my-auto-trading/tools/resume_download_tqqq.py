"""Resume downloading TQQQ 1-minute bars for 2021-2026 with automatic retries."""
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

OUT_CSV = "data/TQQQ_1min_2020_2026.csv"

def resume_tqqq():
    dfs = []
    # If 2020 was downloaded, read it
    if os.path.exists(OUT_CSV):
        print("Reading existing TQQQ data...", flush=True)
        df_old = pd.read_csv(OUT_CSV)
        print(f"Existing rows: {len(df_old)}", flush=True)
        dfs.append(df_old)
    
    for year in range(2021, 2027):
        start = datetime(year, 1, 1)
        end = datetime(year, 12, 31, 23, 59)
        if year == 2026:
            end = datetime(2026, 9, 30, 23, 59)
            
        success = False
        for attempt in range(1, 6):
            print(f"Fetching TQQQ {year} (attempt {attempt}/5)...", flush=True)
            t0 = time.time()
            try:
                req = StockBarsRequest(
                    symbol_or_symbols="TQQQ",
                    timeframe=TimeFrame.Minute,
                    start=start,
                    end=end,
                    adjustment=Adjustment.SPLIT
                )
                bars = client.get_stock_bars(req)
                df = bars.df.reset_index()
                dfs.append(df)
                print(f"  -> OK: {len(df)} rows in {time.time()-t0:.1f}s", flush=True)
                success = True
                break
            except Exception as e:
                print(f"  -> Error: {e}, waiting 5s...", flush=True)
                time.sleep(5)
                
        if not success:
            print(f"FAILED to download TQQQ {year} after 5 attempts!", flush=True)
            return False
            
    final_df = pd.concat(dfs, ignore_index=True)
    # Deduplicate if needed
    if 'timestamp' in final_df.columns:
        final_df = final_df.drop_duplicates(subset=['symbol', 'timestamp'])
    final_df.to_csv(OUT_CSV, index=False)
    print(f"\nTQQQ COMPLETE: Total {len(final_df)} rows saved to {OUT_CSV}", flush=True)
    return True

if __name__ == "__main__":
    resume_tqqq()
