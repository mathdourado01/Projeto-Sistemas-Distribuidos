import re

from protocol import make_message


CHANNEL_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9_-]+$")


def handle_login(msg, storage):
    username = msg.username.strip()

    if not username:
        return make_message(
            msg_type="LOGIN_REP",
            success=False,
            error_message="Nome de usuário inválido.",
            request_id=msg.request_id,
        )

    storage.save_login(username=username, login_timestamp=msg.timestamp)

    return make_message(
        msg_type="LOGIN_REP",
        success=True,
        username=username,
        request_id=msg.request_id,
    )


def handle_list_channels(msg, storage):
    channels = storage.list_channels()

    return make_message(
        msg_type="LIST_CHANNELS_REP",
        success=True,
        channels=channels,
        request_id=msg.request_id,
    )


def handle_create_channel(msg, storage):
    channel_name = msg.channel_name.strip()

    if not channel_name:
        return make_message(
            msg_type="CREATE_CHANNEL_REP",
            success=False,
            error_message="Nome do canal inválido.",
            request_id=msg.request_id,
        )

    if not CHANNEL_NAME_PATTERN.match(channel_name):
        return make_message(
            msg_type="CREATE_CHANNEL_REP",
            success=False,
            error_message="Nome do canal deve conter apenas letras, números, '_' ou '-'.",
            request_id=msg.request_id,
        )

    created = storage.create_channel(
        channel_name=channel_name,
        created_at=msg.timestamp,
    )

    if not created:
        return make_message(
            msg_type="CREATE_CHANNEL_REP",
            success=False,
            error_message="Canal já existe.",
            request_id=msg.request_id,
        )

    return make_message(
        msg_type="CREATE_CHANNEL_REP",
        success=True,
        channel_name=channel_name,
        request_id=msg.request_id,
    )


def handle_unknown(msg):
    return make_message(
        msg_type="ERROR_REP",
        success=False,
        error_message=f"Tipo de mensagem desconhecido: {msg.type}",
        request_id=msg.request_id,
    )