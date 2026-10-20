import asyncio
import logging
from engine.laore_calculator import calculate_daily_orders
from engine.laore_scheduler import LaoreScheduler
from engine.laore_execution import LaoreExecutionTracker

logger = logging.getLogger("LaoreMain")

class LaoreEngineManager:
    def __init__(self, db, uid, get_price_fn, execute_order_fn):
        self.db = db
        self.uid = uid
        self.get_price_fn = get_price_fn
        self.execute_order_fn = execute_order_fn
        self.tracker = LaoreExecutionTracker(db.reference(f'users/{uid}/stock/laore_orders'))
        def _sync_wrapper(market_type):
            asyncio.create_task(self.execute_scheduled_orders(market_type))
        self.scheduler = LaoreScheduler(_sync_wrapper)
        self.scheduler.schedule_tasks()
        logger.info(f"LaoreEngineManager initialized for UID: {uid}")

    async def execute_scheduled_orders(self, market_type):
        logger.info(f"Triggered Laore Execution for {market_type} market")
        settings_ref = self.db.reference(f'users/{self.uid}/stock/laore_settings')
        settings_map = settings_ref.get()
        if not settings_map or not isinstance(settings_map, dict):
            logger.info("Laore settings empty or invalid. Skipping.")
            return
            
        status_ref = self.db.reference(f'users/{self.uid}/stock/laore_status')
        status_map = status_ref.get() or {}
        
        for symbol, settings in settings_map.items():
            if not isinstance(settings, dict) or not settings.get('enabled', False):
                continue
                
            status = status_map.get(symbol, {})
            current_price = self.get_price_fn(symbol)
            if current_price is None or current_price <= 0:
                logger.error(f"Failed to fetch price for {symbol}")
                continue
                
            orders = calculate_daily_orders(settings, status, current_price)
            logger.info(f"Calculated orders for {symbol}: {orders}")
            
            # 34 is KIS code for LOC (Limit On Close) for overseas.
            loc_code = "34" 
            
            for buy_ord in orders['buy']:
                self.execute_order_fn(symbol, "BUY", buy_ord['qty'], buy_ord['price'], ord_dvsn=loc_code)
                await asyncio.sleep(0.3)
                
            for sell_ord in orders['sell']:
                self.execute_order_fn(symbol, "SELL", sell_ord['qty'], sell_ord['price'], ord_dvsn=loc_code)
                await asyncio.sleep(0.3)
                
            # T값 및 회차 업데이트
            status['current_round'] = status.get('current_round', 0) + 1
            status['t_value'] = status.get('t_value', 0.0) + 1.0
            status['status_message'] = f"LOC 주문 예약 완료 (기준가: {current_price})"
            status_map[symbol] = status
            
        # 모든 종목의 상태 업데이트
        status_ref.set(status_map)
        logger.info("Laore Execution cycle completed and status updated.")

    def run_pending(self):
        self.scheduler.run_pending()
