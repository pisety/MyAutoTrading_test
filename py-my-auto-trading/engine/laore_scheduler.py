import time
import schedule
import logging
from datetime import datetime
import pytz

logger = logging.getLogger("LaoreScheduler")

def is_us_summer_time():
    ny_tz = pytz.timezone('America/New_York')
    now = datetime.now(pytz.utc).astimezone(ny_tz)
    return now.dst() != type(now.dst())(0)

class LaoreScheduler:
    def __init__(self, execution_callback):
        self.execution_callback = execution_callback
        self.is_running = False

    def schedule_tasks(self):
        # 미국 썸머타임 17:40, 동절기 18:40
        summer_time_str = "17:40"
        winter_time_str = "18:40"
        
        us_time_str = summer_time_str if is_us_summer_time() else winter_time_str
        
        # 스케줄 등록
        schedule.every().day.at(us_time_str).do(self.execution_callback, "US")
        
        # 국내 주식: 15:20
        schedule.every().day.at("15:20").do(self.execution_callback, "KR")
        
        logger.info(f"Laore Scheduler initialized. US: {us_time_str}, KR: 15:20")

    def run_pending(self):
        schedule.run_pending()
