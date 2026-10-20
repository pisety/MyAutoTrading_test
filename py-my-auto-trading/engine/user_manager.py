import asyncio
import logging
from typing import Dict, Set
from database.firebase_client import FirebaseClient
from engine.user_session import UserSession

logger = logging.getLogger("UserManager")

class UserManager:
    def __init__(self, fb_client: FirebaseClient):
        self.fb = fb_client
        self.sessions: Dict[str, UserSession] = {}
        self.stopping_uids: Set[str] = set()
        self._loop = asyncio.get_event_loop()

    def start_listening(self):
        logger.info("UserManager started listening for new users.")
        # Listen to the root of 'users' to detect new or removed users dynamically
        self.fb.listen("users", self._on_users_change)

    def _on_users_change(self, event):
        if event.path == "/":
            self._loop.call_soon_threadsafe(self._sync_sessions, event.data)
        else:
            parts = [p for p in event.path.split('/') if p]
            if not parts:
                return
            uid = parts[0]
            if event.data is None:
                # Root user node was deleted
                if len(parts) == 1:
                    logger.info(f"User deleted from Firebase: {uid}")
                    self._loop.call_soon_threadsafe(self._stop_user, uid)
            else:
                # ONLY create new sessions if a root user node was created (len(parts) == 1)
                # Sub-node writes (e.g. status/last_updated) must NEVER trigger new session creation.
                if len(parts) == 1 and uid not in self.stopping_uids:
                    self._loop.call_soon_threadsafe(self._ensure_session, uid)

    def _is_valid_user_data(self, udata) -> bool:
        if not isinstance(udata, dict):
            return False
        # Must contain actual profile info or broker configuration (not just residual status)
        has_direct_info = any(k in udata for k in ("profile_name", "account_data", "settings"))
        has_service_info = any(
            isinstance(udata.get(s), dict) and any(k in udata[s] for k in ("account_data", "settings", "accounts"))
            for s in ("stock", "crypto")
        )
        return has_direct_info or has_service_info

    def _sync_sessions(self, users_data):
        if not isinstance(users_data, dict):
            return

        target_uids = set()
        for uid, udata in users_data.items():
            if uid in self.stopping_uids:
                continue
            if self._is_valid_user_data(udata):
                target_uids.add(uid)

        # Derive set of currently active UIDs from composite session keys
        current_uids = set()
        for key in list(self.sessions.keys()):
            uid = key.rsplit("_", 1)[0]
            current_uids.add(uid)

        # Stop removed sessions
        for uid in current_uids - target_uids:
            logger.info(f"User removed in sync: {uid}")
            self._stop_user(uid)

        # Start new sessions
        for uid in target_uids - current_uids:
            self._ensure_session(uid)

    def _ensure_session(self, uid: str):
        if uid in self.stopping_uids:
            return

        # Double check if user data actually exists in Firebase and is valid
        udata = self.fb.get_data(f"users/{uid}")
        if not self._is_valid_user_data(udata):
            logger.warning(f"Rejecting session creation for '{uid}': not a valid configured user.")
            return

        for stype in ("stock", "crypto"):
            key = f"{uid}_{stype}"
            if key not in self.sessions:
                logger.info(f"New user detected, creating {stype} session: {uid}")
                session = UserSession(uid, self.fb, service_type=stype)
                self.sessions[key] = session
                asyncio.create_task(session.start())

    def _stop_user(self, uid: str):
        self.stopping_uids.add(uid)
        to_stop = []
        for stype in ("stock", "crypto"):
            key = f"{uid}_{stype}"
            session = self.sessions.pop(key, None)
            if session:
                to_stop.append(session)
        if to_stop:
            asyncio.create_task(self._do_stop_sessions(uid, to_stop))
        else:
            self.stopping_uids.discard(uid)

    async def _do_stop_sessions(self, uid: str, sessions: list):
        try:
            logger.info(f"Stopping all sessions for removed user: {uid}")
            for session in sessions:
                session._running = False
                if session._main_task:
                    session._main_task.cancel()
                if session._restart_task:
                    session._restart_task.cancel()
            await asyncio.gather(*(session.stop() for session in sessions), return_exceptions=True)
            # Purge any lingering zombie data from Firebase
            await self.fb.async_delete_data(f"users/{uid}")
            logger.info(f"Successfully stopped and purged user: {uid}")
        except Exception as e:
            logger.error(f"Error while stopping user {uid}: {e}")
        finally:
            self.stopping_uids.discard(uid)

    async def stop_all(self):
        for session in list(self.sessions.values()):
            await session.stop()
        self.sessions.clear()

