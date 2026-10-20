import os
from datetime import datetime
from alpaca.data.historical import StockHistoricalDataClient
from alpaca.data.requests import StockBarsRequest
from alpaca.data.timeframe import TimeFrame
import pandas as pd
import time

API_KEY = "PKUNFZE5G2L7YJ637NY4QZLBID"
SECRET_KEY = "6mYKfact4N3WaAv6p7hkjpap71aey8fNUT6Goqtd6AR"
client = StockHistoricalDataClient(API_KEY, SECRET_KEY)

def download_soxs():
    os.makedirs('data', exist_ok=True)
    all_dfs = []
    
    for year in range(2020, 2027):
        start = datetime(year, 1, 1)
        end = datetime(year, 12, 31)
        if year == 2026:
            end = datetime(2026, 9, 30)
            
        print(f"Downloading SOXS {year}...")
        request_params = StockBarsRequest(
            symbol_or_symbols="SOXS",
            timeframe=TimeFrame.Minute,
            start=start,
            end=end
        )
        try:
            bars = client.get_stock_bars(request_params)
            df = bars.df
            all_dfs.append(df)
            print(f"  -> {len(df)} rows")
        except Exception as e:
            print(f"  -> Error: {e}")
            
    final_df = pd.concat(all_dfs)
    final_df.to_csv('data/SOXS_1min_2020_2026.csv')
    print(f"Complete! Total rows: {len(final_df)}")

if __name__ == '__main__':
    download_soxs()
