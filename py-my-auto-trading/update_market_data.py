import os
import argparse
import firebase_admin
from firebase_admin import credentials
from firebase_admin import db
import FinanceDataReader as fdr
import requests

def init_firebase():
    """Initialize Firebase app."""
    if not firebase_admin._apps:
        cred_path = os.environ.get('FIREBASE_CREDENTIALS_PATH')
        if not cred_path:
            cred_path = 'serviceAccountKey.json'
            
        if not os.path.exists(cred_path):
            raise FileNotFoundError(f"Firebase credentials not found at {cred_path}")
            
        cred = credentials.Certificate(cred_path)
        db_url = os.environ.get('FIREBASE_DATABASE_URL', 'https://YOUR-FIREBASE-PROJECT-default-rtdb.firebaseio.com/')
        
        firebase_admin.initialize_app(cred, {
            'databaseURL': db_url
        })
        print("Firebase initialized successfully.")

def update_domestic_stocks():
    """Fetch all KOSPI and KOSDAQ stocks and upload to Firebase."""
    print("Fetching KOSPI/KOSDAQ stocks...")
    kospi = fdr.StockListing('KOSPI')
    kosdaq = fdr.StockListing('KOSDAQ')
    
    stocks = {}
    if kospi is not None and not kospi.empty:
        for _, row in kospi.iterrows():
            stocks[str(row['Code'])] = str(row['Name'])
            
    if kosdaq is not None and not kosdaq.empty:
        for _, row in kosdaq.iterrows():
            stocks[str(row['Code'])] = str(row['Name'])

    if stocks:
        print(f"Uploading {len(stocks)} domestic stocks to Firebase...")
        db.reference('/market_data/domestic_stocks').set(stocks)
        print("Domestic stocks updated successfully.")

def update_overseas_stocks():
    """Fetch US stocks (NASDAQ, NYSE, AMEX) and upload to Firebase."""
    print("Fetching US stocks (NASDAQ, NYSE, AMEX)...")
    nasdaq = fdr.StockListing('NASDAQ')
    nyse = fdr.StockListing('NYSE')
    amex = fdr.StockListing('AMEX')
    
    stocks = {}
    for df in [nasdaq, nyse, amex]:
        if df is not None and not df.empty:
            for _, row in df.iterrows():
                # Some symbols might be NaN, so we handle it gracefully
                if 'Symbol' in df.columns and 'Name' in df.columns:
                    stocks[str(row['Symbol'])] = str(row['Name'])
    
    if stocks:
        print(f"Uploading {len(stocks)} overseas stocks to Firebase...")
        db.reference('/market_data/overseas_stocks').set(stocks)
        print("Overseas stocks updated successfully.")

def update_upbit_coins():
    """Fetch all Upbit KRW markets and upload to Firebase."""
    print("Fetching Upbit coins...")
    url = "https://api.upbit.com/v1/market/all?isDetails=false"
    headers = {"accept": "application/json"}
    
    try:
        response = requests.get(url, headers=headers)
        response.raise_for_status()
        markets = response.json()
        
        coins = {}
        for market in markets:
            market_code = market['market']
            if market_code.startswith("KRW-"):
                coins[market_code] = market['korean_name']
                
        if coins:
            print(f"Uploading {len(coins)} Upbit coins to Firebase...")
            db.reference('/market_data/upbit_coins').set(coins)
            print("Upbit coins updated successfully.")
    except Exception as e:
        print(f"Error fetching Upbit coins: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Update market data for trading app")
    parser.add_argument('--target', choices=['kr', 'us', 'all'], default='all', help="Target markets to update")
    args = parser.parse_args()
    
    try:
        init_firebase()
        if args.target in ['kr', 'all']:
            update_domestic_stocks()
            update_upbit_coins()
            
        if args.target in ['us', 'all']:
            update_overseas_stocks()
            
        print("Market data update completed!")
    except Exception as e:
        print(f"Update failed: {e}")
