import os
import asyncio
import logging
from dotenv import load_dotenv

# Load env vars before importing other modules
load_dotenv()

from database.firebase_client import FirebaseClient
from engine.user_manager import UserManager
from engine.reservation_manager import ReservationManager

from logging.handlers import RotatingFileHandler

# Logging Setup
log_formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')

file_handler = RotatingFileHandler("engine.log", maxBytes=10*1024*1024, backupCount=5, encoding='utf-8')
file_handler.setFormatter(log_formatter)

console_handler = logging.StreamHandler()
console_handler.setFormatter(log_formatter)

logging.basicConfig(level=logging.INFO, handlers=[file_handler, console_handler])
logger = logging.getLogger("Main")

async def main():
    logger.info("Starting Global Quant Trading Multi-User Engine")
    load_dotenv()
    
    db_url = os.getenv("FIREBASE_DB_URL")
    cert_path = os.getenv("FIREBASE_CREDENTIALS_PATH", "serviceAccountKey.json")
    
    if not db_url:
        logger.error("FIREBASE_DB_URL not found in environment.")
        return

    # 1. Initialize Firebase Client
    fb_client = FirebaseClient(db_url, cert_path)
    fb_client.initialize()

    # 2. Initialize UserManager
    user_manager = UserManager(fb_client)
    user_manager.start_listening()

    # 3. Initialize ReservationManager
    resv_manager = ReservationManager(fb_client, user_manager)
    await resv_manager.start()

    # 4. Keep Main Loop Running
    try:
        while True:
            await asyncio.sleep(3600)
    except asyncio.CancelledError:
        logger.info("Engine shutting down...")
    finally:
        await resv_manager.stop()
        await user_manager.stop_all()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Engine stopped by user.")
