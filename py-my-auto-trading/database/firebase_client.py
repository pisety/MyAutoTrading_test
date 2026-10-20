import asyncio
import os
import time
import logging
from typing import Callable, Dict, Any
import firebase_admin
from firebase_admin import credentials, db, messaging

logger = logging.getLogger("FirebaseClient")

class FirebaseClient:
    def __init__(self, db_url: str, cert_path: str):
        self.db_url = db_url
        self.cert_path = cert_path
        self._app = None
        self._listeners: Dict[str, Any] = {}

    def initialize(self):
        if not os.path.exists(self.cert_path):
            logger.warning(f"Firebase cert_path {self.cert_path} not found. Attempting default credentials.")
            cred = credentials.ApplicationDefault()
        else:
            cred = credentials.Certificate(self.cert_path)
            
        try:
            self._app = firebase_admin.initialize_app(cred, {
                'databaseURL': self.db_url
            })
            logger.info("Firebase SDK Initialized successfully.")
        except Exception as e:
            if "already exists" in str(e):
                logger.info("Firebase app already initialized.")
            else:
                logger.error(f"Firebase initialization failed: {e}")
                raise

    def get_data(self, path: str) -> Any:
        try:
            return db.reference(path).get()
        except Exception as e:
            logger.error(f"Error getting data from {path}: {e}")
            return None

    def set_data(self, path: str, data: Any):
        try:
            db.reference(path).set(data)
        except Exception as e:
            logger.error(f"Error setting data to {path}: {e}")

    def update_data(self, path: str, data: dict):
        try:
            db.reference(path).update(data)
        except Exception as e:
            logger.error(f"Error updating data at {path}: {e}")

    def delete_data(self, path: str):
        try:
            db.reference(path).delete()
        except Exception as e:
            logger.error(f"Error deleting data at {path}: {e}")

    def push_data(self, path: str, data: Any):
        try:
            return db.reference(path).push(data)
        except Exception as e:
            logger.error(f"Error pushing data to {path}: {e}")
            return None

    async def async_get_data(self, path: str) -> Any:
        return await asyncio.to_thread(self.get_data, path)

    async def async_set_data(self, path: str, data: Any):
        await asyncio.to_thread(self.set_data, path, data)

    async def async_update_data(self, path: str, data: dict):
        await asyncio.to_thread(self.update_data, path, data)

    async def async_delete_data(self, path: str):
        await asyncio.to_thread(self.delete_data, path)

    async def async_push_data(self, path: str, data: Any):
        return await asyncio.to_thread(self.push_data, path, data)

    def listen(self, path: str, callback: Callable[[Any], None]):
        """
        Firebase 리스너 등록. callback은 이벤트 발생 시 호출됩니다.
        """
        def wrapper(event):
            callback(event)

        try:
            ref = db.reference(path)
            listener = ref.listen(wrapper)
            self._listeners[path] = listener
            logger.info(f"Started listening to Firebase path: {path}")
        except Exception as e:
            logger.error(f"Failed to start listener on {path}: {e}")

    def remove_listener(self, path: str):
        listener = self._listeners.pop(path, None)
        if listener:
            try:
                listener.close()
                logger.info(f"Removed listener for path: {path}")
            except Exception as e:
                logger.error(f"Error closing listener for {path}: {e}")

    async def async_send_push_notification(self, user_id: str, title: str, body: str):
        await asyncio.to_thread(self.send_push_notification, user_id, title, body)

    def send_push_notification(self, user_id: str, title: str, body: str):
        try:
            # 1. FCM Token 조회
            token = self.get_data(f"users/{user_id}/fcm_token")
            if not token:
                logger.warning(f"No FCM token found for user {user_id}. Cannot send push notification.")
                return

            # 프로필 이름이 설정되어 있는 경우 알림 제목에 [프로필명] 접두어 자동 부착
            try:
                pname = self.get_data(f"users/{user_id}/profile_name")
                if pname and not title.startswith(f"[{pname}]"):
                    title = f"[{pname}] {title}"
            except Exception:
                pass

            # 2. 메시지 생성 및 전송 (data 페이로드만 사용하여 백그라운드에서도 onMessageReceived 강제 호출)
            message = messaging.Message(
                data={
                    "title": title,
                    "body": body
                },
                token=token
            )
            response = messaging.send(message)
            logger.info(f"Successfully sent push notification to {user_id}: {response}")
            
            # 3. 알림 이력 저장
            try:
                timestamp = int(time.time() * 1000)
                notif_data = {
                    "title": title,
                    "body": body,
                    "timestamp": timestamp,
                    "read": False
                }
                from firebase_admin import db
                db.reference(f"users/{user_id}/notifications").push(notif_data)
            except Exception as e:
                logger.error(f"Failed to save push notification history to DB: {e}")
                
        except Exception as e:
            logger.error(f"Failed to send push notification to {user_id}: {e}")
