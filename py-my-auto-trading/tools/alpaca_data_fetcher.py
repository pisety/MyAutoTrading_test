import os
from datetime import datetime
from alpaca.data.historical import StockHistoricalDataClient
from alpaca.data.requests import StockBarsRequest
from alpaca.data.timeframe import TimeFrame
import pandas as pd

API_KEY = "PKUNFZE5G2L7YJ637NY4QZLBID"
SECRET_KEY = "6mYKfact4N3WaAv6p7hkjpap71aey8fNUT6Goqtd6AR"

def fetch_data(symbol="TSLA", start_date=datetime(2021, 1, 1), end_date=datetime(2021, 6, 30)):
    print(f"[{symbol}] 알파카 서버에서 {start_date.date()} ~ {end_date.date()} 1분봉 데이터 다운로드 시작...")
    
    client = StockHistoricalDataClient(API_KEY, SECRET_KEY)
    
    request_params = StockBarsRequest(
        symbol_or_symbols=symbol,
        timeframe=TimeFrame.Minute,
        start=start_date,
        end=end_date
    )
    
    bars = client.get_stock_bars(request_params)
    df = bars.df
    
    os.makedirs('data', exist_ok=True)
    filename = f"data/{symbol}_1min_{start_date.strftime('%Y%m%d')}_{end_date.strftime('%Y%m%d')}.csv"
    
    df.to_csv(filename)
    print(f"다운로드 완료! 총 {len(df)}개의 분봉 데이터가 '{filename}'에 저장되었습니다.")
    
    return df

if __name__ == "__main__":
    df = fetch_data("TSLA", datetime(2021, 1, 1), datetime(2021, 6, 30))
    print("\n[데이터 미리보기]")
    print(df.head())
