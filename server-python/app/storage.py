import os
import sqlite3


class Storage:
    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self._ensure_database()

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.db_path)

    def _ensure_database(self) -> None:
        os.makedirs(os.path.dirname(self.db_path), exist_ok=True)

        with self._connect() as conn:
            cursor = conn.cursor()

            cursor.execute("""
                CREATE TABLE IF NOT EXISTS logins (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    login_timestamp INTEGER NOT NULL
                )
            """)

            cursor.execute("""
                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL
                )
            """)

            conn.commit()

    def save_login(self, username: str, login_timestamp: int) -> None:
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                INSERT INTO logins (username, login_timestamp)
                VALUES (?, ?)
                """,
                (username, login_timestamp),
            )
            conn.commit()

    def channel_exists(self, channel_name: str) -> bool:
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT 1 FROM channels WHERE name = ? LIMIT 1",
                (channel_name,),
            )
            return cursor.fetchone() is not None

    def create_channel(self, channel_name: str, created_at: int) -> bool:
        if self.channel_exists(channel_name):
            return False

        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                INSERT INTO channels (name, created_at)
                VALUES (?, ?)
                """,
                (channel_name, created_at),
            )
            conn.commit()
            return True

    def list_channels(self) -> list[str]:
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name FROM channels ORDER BY name ASC")
            rows = cursor.fetchall()
            return [row[0] for row in rows]