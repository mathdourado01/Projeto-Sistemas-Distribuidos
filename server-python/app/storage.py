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

            cursor.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT,
                    username TEXT NOT NULL,
                    channel_name TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    sent_timestamp INTEGER NOT NULL
                )
            """)

            self._add_column_if_missing(
                cursor=cursor,
                table_name="messages",
                column_name="request_id",
                column_type="TEXT",
            )

            cursor.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_request_id
                ON messages(request_id)
            """)

            conn.commit()

    def _add_column_if_missing(
        self,
        cursor,
        table_name: str,
        column_name: str,
        column_type: str,
    ) -> None:
        cursor.execute(f"PRAGMA table_info({table_name})")
        columns = [row[1] for row in cursor.fetchall()]

        if column_name not in columns:
            cursor.execute(
                f"ALTER TABLE {table_name} ADD COLUMN {column_name} {column_type}"
            )

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
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                INSERT OR IGNORE INTO channels (name, created_at)
                VALUES (?, ?)
                """,
                (channel_name, created_at),
            )
            conn.commit()
            return cursor.rowcount > 0

    def list_channels(self) -> list[str]:
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name FROM channels ORDER BY name ASC")
            rows = cursor.fetchall()
            return [row[0] for row in rows]

    def save_message(
        self,
        username: str,
        channel_name: str,
        message_text: str,
        sent_timestamp: int,
        request_id=None,
    ) -> None:
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                INSERT OR IGNORE INTO messages (
                    request_id,
                    username,
                    channel_name,
                    message_text,
                    sent_timestamp
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    request_id if request_id else None,
                    username,
                    channel_name,
                    message_text,
                    sent_timestamp,
                ),
            )
            conn.commit()

    def list_messages_by_channel(self, channel_name: str) -> list[tuple]:
        with self._connect() as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT username, channel_name, message_text, sent_timestamp
                FROM messages
                WHERE channel_name = ?
                ORDER BY sent_timestamp ASC, id ASC
                """,
                (channel_name,),
            )
            return cursor.fetchall()