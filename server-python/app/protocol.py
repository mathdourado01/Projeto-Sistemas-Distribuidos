import time
import uuid

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
    channels: list[str] | None = None,
    request_id: str = "",
) -> Envelope:
    msg = Envelope()
    msg.type = msg_type
    msg.timestamp = now_timestamp()
    msg.request_id = request_id or new_request_id()
    msg.username = username
    msg.channel_name = channel_name
    msg.success = success
    msg.error_message = error_message

    if channels:
        msg.channels.extend(channels)

    return msg