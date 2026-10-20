import asyncio
import logging
import urllib.request
import json
import ssl
from concurrent.futures import ThreadPoolExecutor
from typing import List, Tuple, Dict, Optional

logger = logging.getLogger("ETFAnalyzer")

# 주요 국내 상장 해외 ETF별 미국 상위 구성종목 매핑 (상위 10개 혹은 핵심 벤치마크 구성종목)
ETF_CONSTITUENTS_MAP = {
    # TIGER 미국우주테크 (0183J0 / 실제 상장 핵심 구성종목: 레드와이어, 로켓랩, 인튜이티브머신스, 에코스타, ASTS, 플래닛랩스 등)
    # * 스페이스X(약 25%) 및 파이어플라이 등 비상장기업은 야후 파이낸스 실시간 주가가 없으므로 실제 나스닥/NYSE 거래 핵심 우주주 10개로 시황 판단
    "0183J0": ["RDW", "RKLB", "LUNR", "SATS", "ASTS", "PL", "VOYG", "GSAT", "SPIR", "SIDU"],
    "우주": ["RDW", "RKLB", "LUNR", "SATS", "ASTS", "PL", "VOYG", "GSAT", "SPIR", "SIDU"],
    "SPACE": ["RDW", "RKLB", "LUNR", "SATS", "ASTS", "PL", "VOYG", "GSAT", "SPIR", "SIDU"],
    
    # 미국 나스닥 100 (TIGER 133690, KODEX 379800, ACE 367380, 레버리지 409820 등 실제 상위 10개)
    "133690": ["AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "COST", "NFLX"],
    "379800": ["AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "COST", "NFLX"],
    "367380": ["AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "COST", "NFLX"],
    "409820": ["AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "COST", "NFLX"],
    "나스닥": ["AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "COST", "NFLX"],
    "NASDAQ": ["AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "COST", "NFLX"],
    
    # 미국 테크 TOP 10 (TIGER 381170, KODEX AI테크 487100 등 실제 구성종목 10개)
    "381170": ["NVDA", "AAPL", "MSFT", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "AMD", "QCOM"],
    "487100": ["NVDA", "AAPL", "MSFT", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "AMD", "QCOM"],
    "테크": ["NVDA", "AAPL", "MSFT", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "AMD", "QCOM"],
    "TOP10": ["NVDA", "AAPL", "MSFT", "AMZN", "META", "GOOGL", "AVGO", "TSLA", "AMD", "QCOM"],
    "AI": ["NVDA", "MSFT", "GOOGL", "META", "AVGO", "AMD", "TSMC", "PLTR", "QCOM", "INTC"],
    
    # 미국 필라델피아 반도체 (TIGER 381180, KODEX 390390, 레버리지 423920 등 SOX 상위 10개)
    "381180": ["NVDA", "AVGO", "AMD", "QCOM", "TXN", "INTC", "AMAT", "MU", "LRCX", "ADI"],
    "390390": ["NVDA", "AVGO", "AMD", "QCOM", "TXN", "INTC", "AMAT", "MU", "LRCX", "ADI"],
    "423920": ["NVDA", "AVGO", "AMD", "QCOM", "TXN", "INTC", "AMAT", "MU", "LRCX", "ADI"],
    "반도체": ["NVDA", "AVGO", "AMD", "QCOM", "TXN", "INTC", "AMAT", "MU", "LRCX", "ADI"],
    "SOX": ["NVDA", "AVGO", "AMD", "QCOM", "TXN", "INTC", "AMAT", "MU", "LRCX", "ADI"],
    
    # 미국 S&P500 (TIGER 360750, KODEX 379810, ACE 360200, 레버리지 409810 등 실제 상위 10개)
    "360750": ["MSFT", "AAPL", "NVDA", "AMZN", "META", "GOOGL", "BRK-B", "LLY", "AVGO", "JPM"],
    "379810": ["MSFT", "AAPL", "NVDA", "AMZN", "META", "GOOGL", "BRK-B", "LLY", "AVGO", "JPM"],
    "360200": ["MSFT", "AAPL", "NVDA", "AMZN", "META", "GOOGL", "BRK-B", "LLY", "AVGO", "JPM"],
    "409810": ["MSFT", "AAPL", "NVDA", "AMZN", "META", "GOOGL", "BRK-B", "LLY", "AVGO", "JPM"],
    "S&P": ["MSFT", "AAPL", "NVDA", "AMZN", "META", "GOOGL", "BRK-B", "LLY", "AVGO", "JPM"],
    "SP500": ["MSFT", "AAPL", "NVDA", "AMZN", "META", "GOOGL", "BRK-B", "LLY", "AVGO", "JPM"],
    
    # 미국 배당다우존스 SCHD (TIGER 458730, ACE 458720, SOL 446720 등 실제 상위 10개)
    "458730": ["BMY", "ABBV", "HD", "CVX", "MRK", "PEP", "KO", "PFE", "VZ", "AMGN"],
    "458720": ["BMY", "ABBV", "HD", "CVX", "MRK", "PEP", "KO", "PFE", "VZ", "AMGN"],
    "446720": ["BMY", "ABBV", "HD", "CVX", "MRK", "PEP", "KO", "PFE", "VZ", "AMGN"],
    "SCHD": ["BMY", "ABBV", "HD", "CVX", "MRK", "PEP", "KO", "PFE", "VZ", "AMGN"],
    "배당": ["BMY", "ABBV", "HD", "CVX", "MRK", "PEP", "KO", "PFE", "VZ", "AMGN"],
    
    # 기본값 (대표 미국 빅테크 상위 10개)
    "DEFAULT": ["NVDA", "AAPL", "MSFT", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "QCOM"]
}

def get_constituents_for_etf(symbol_or_name: str, custom_tickers: Optional[List[str]] = None, constituents_map: Optional[Dict[str, List[str]]] = None) -> List[str]:
    """
    ETF 종목코드 또는 종목명을 기반으로 상위 구성종목 티커 리스트를 반환합니다.
    """
    if custom_tickers and len(custom_tickers) > 0:
        return custom_tickers
        
    mapping = constituents_map if constituents_map else ETF_CONSTITUENTS_MAP
        
    s = symbol_or_name.upper().strip()
    if s in mapping:
        return mapping[s]
        
    # 키워드 매칭
    for key, tickers in mapping.items():
        if key in s:
            return tickers
            
    logger.info(f"ETF '{symbol_or_name}'에 대한 특정 매핑이 없어 DEFAULT 구성종목을 사용합니다.")
    return mapping.get("DEFAULT", ETF_CONSTITUENTS_MAP["DEFAULT"])

def _fetch_stock_rate_sync(symbol: str, base_price: Optional[float] = None) -> Dict:
    """
    urllib를 이용하여 야후 파이낸스 API에서 특정 미국 종목의 전일 대비 당일 등락률을 산출합니다.
    """
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?includePrePost=true&range=1d&interval=5m"
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
    ssl_context = ssl._create_unverified_context()
    
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, context=ssl_context, timeout=10) as res:
            if res.status == 200:
                data = json.loads(res.read().decode('utf-8'))
                result_obj = data["chart"]["result"][0]
                meta = result_obj["meta"]
                
                # 1. 직전 5분봉(애프터마켓 포함 가장 최근 체결 봉) 종가 조회
                curr = 0.0
                try:
                    quote_list = result_obj.get("indicators", {}).get("quote", [{}])[0].get("close", [])
                    valid_closes = [float(c) for c in quote_list if c is not None and float(c) > 0]
                    if valid_closes:
                        curr = valid_closes[-1]
                except Exception:
                    pass
                
                # 2. 5분봉 데이터가 없을 경우 야후 메타데이터의 postMarketPrice -> regularMarketPrice 순으로 대체
                if curr <= 0:
                    curr = float(meta.get("postMarketPrice", meta.get("regularMarketPrice", 0.0)))
                
                if base_price is not None and base_price > 0:
                    prev = base_price
                else:
                    prev = float(meta.get("chartPreviousClose", meta.get("previousClose", curr)))
                    
                if prev > 0 and curr > 0:
                    rate = ((curr - prev) / prev) * 100.0
                    return {
                        "symbol": symbol,
                        "price": curr,
                        "prev_close": prev,
                        "rate": rate,
                        "success": True
                    }
    except Exception as e:
        logger.warning(f"종목 {symbol} 야후 파이낸스 조회 오류: {e}")
    
    return {"symbol": symbol, "price": 0.0, "prev_close": 0.0, "rate": 0.0, "success": False}

_executor = ThreadPoolExecutor(max_workers=5)

async def fetch_stock_rate(symbol: str, base_price: Optional[float] = None) -> Dict:
    """
    동기 HTTP 호출을 스레드 풀에서 비동기로 실행합니다.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(_executor, _fetch_stock_rate_sync, symbol, base_price)

async def analyze_etf_overnight_return(symbol_or_name: str, custom_tickers: Optional[List[str]] = None, base_prices: Optional[Dict[str, float]] = None, constituents_map: Optional[Dict[str, List[str]]] = None) -> Tuple[float, List[Dict]]:
    """
    해당 ETF를 구성하는 미국 상위 종목들(최대 10개 또는 10개 미만 시 전체)의 밤사이 시세 등락률을
    병렬로 조회한 뒤, 단순 산술평균 등락률과 상세 내역을 반환합니다.
    """
    tickers = get_constituents_for_etf(symbol_or_name, custom_tickers, constituents_map=constituents_map)
    logger.info(f"[{symbol_or_name}] 구성종목 {len(tickers)}개 시세 분석 시작: {tickers}")
    
    tasks = [fetch_stock_rate(t, base_prices.get(t) if base_prices else None) for t in tickers]
    results = await asyncio.gather(*tasks)
        
    valid_rates = [r["rate"] for r in results if r["success"]]
    if not valid_rates:
        logger.error(f"[{symbol_or_name}] 유효한 시세 조회 결과가 없습니다. 평균 0.0%로 처리합니다.")
        return 0.0, results
        
    # 산술평균 계산 (종목 수가 10개 미만이면 해당 개수 전체 평균)
    avg_rate = sum(valid_rates) / len(valid_rates)
    logger.info(f"[{symbol_or_name}] 구성종목 산술평균 등락률: {avg_rate:.2f}% (유효종목: {len(valid_rates)}/{len(tickers)})")
    
    return avg_rate, results

