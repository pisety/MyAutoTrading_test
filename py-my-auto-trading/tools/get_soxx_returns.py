import yfinance as yf

soxx = yf.Ticker('SOXX').history(start='2019-12-30', end='2026-10-01')['Close']
res = soxx.resample('YE').last()
print(res)
returns = res.pct_change() * 100
print(returns)
