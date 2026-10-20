from alpaca.data.historical import StockHistoricalDataClient
from alpaca.data.requests import StockBarsRequest
from alpaca.data.timeframe import TimeFrame
from alpaca.data.enums import DataFeed
from datetime import datetime, timedelta
import pandas as pd

API_KEY = "PKUNFZE5G2L7YJ637NY4QZLBID"
SECRET_KEY = "6mYKfact4N3WaAv6p7hkjpap71aey8fNUT6Goqtd6AR"
client = StockHistoricalDataClient(API_KEY, SECRET_KEY)

req = StockBarsRequest(
    symbol_or_symbols="SOXX",
    timeframe=TimeFrame.Day,
    start=datetime(2000, 1, 1),
    end=datetime.now() - timedelta(days=3),
    feed=DataFeed.SIP,
    adjustment='all' # adjust for splits and dividends
)

bars = client.get_stock_bars(req).df
bars.reset_index(inplace=True)
bars['timestamp'] = pd.to_datetime(bars['timestamp']).dt.tz_convert('America/New_York')
bars['year'] = bars['timestamp'].dt.year

annual_data = []
for year, group in bars.groupby('year'):
    group = group.sort_values('timestamp')
    start_close = group.iloc[0]['close']
    end_close = group.iloc[-1]['close']
    ret = ((end_close / start_close) - 1.0) * 100.0
    annual_data.append({'Year': year, 'Return': ret, 'Start_Close': start_close, 'End_Close': end_close})
    
df_annual = pd.DataFrame(annual_data)
print("\n--- SOXX 연간 수익률 (Alpaca SIP 수정종가 기준) ---")
for idx, row in df_annual.iterrows():
    print(f"{row['Year']}년: {row['Return']:>7.2f}% (연초  -> 연말 )")
