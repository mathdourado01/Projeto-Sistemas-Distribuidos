import time
import uuid
from typing import List, Optional

from chat_pb2 import Envelope


def now_timestamp() -> int:
    return int(time.time())


def new_request_id() -> str:
    return str(uuid.uuid4())


def make_message(
    msg_type: str,
    username: str = "",
    channel_name: str = "",
    success: bool = False,
    error_message: str = "",
    channels: Optional[List[str]] = None,
    request_id: str = "",
    message_text: str = "",
    logical_clock: int = 0,
    server_name: str = "",
    server_rank: int = 0,
    servers: Optional[List[str]] = None,
    physical_time: int = 0,
) -> Envelope:
    msg = Envelope()
    msg.type = msg_type
    msg.timestamp = now_timestamp()
    msg.request_id = request_id or new_request_id()
    msg.username = username
    msg.channel_name = channel_name
    msg.success = success
    msg.error_message = error_message
    msg.message_text = message_text
    msg.logical_clock = logical_clock
    msg.server_name = server_name
    msg.server_rank = server_rank
    msg.physical_time = physical_time

    if channels:
        msg.channels.extend(channels)

    if servers:
        msg.servers.extend(servers)

    return msg