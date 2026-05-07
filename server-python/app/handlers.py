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


def handle_create_channel(
    msg,
    storage,
    pub_socket=None,
    logical_clock=None,
    server_name="",
    server_rank=0,
    physical_time=0,
):
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
            channel_name=channel_name,
            error_message="Canal já existe.",
            request_id=msg.request_id,
        )

    if pub_socket is not None and logical_clock is not None:
        replicate_channel(
            channel_name=channel_name,
            request_id=msg.request_id,
            pub_socket=pub_socket,
            logical_clock=logical_clock,
            server_name=server_name,
            server_rank=server_rank,
            physical_time=physical_time,
        )

    return make_message(
        msg_type="CREATE_CHANNEL_REP",
        success=True,
        channel_name=channel_name,
        request_id=msg.request_id,
    )


def handle_publish(
    msg,
    storage,
    pub_socket,
    logical_clock=None,
    server_name="",
    server_rank=0,
    physical_time=0,
):
    username = msg.username.strip()
    channel_name = msg.channel_name.strip()
    message_text = msg.message_text.strip()

    if not username:
        return make_message(
            msg_type="PUBLISH_REP",
            success=False,
            error_message="Usuário inválido.",
            request_id=msg.request_id,
        )

    if not channel_name:
        return make_message(
            msg_type="PUBLISH_REP",
            success=False,
            error_message="Canal inválido.",
            request_id=msg.request_id,
        )

    # Como o broker usa round-robin, a publicação pode cair em um servidor
    # que ainda não recebeu a réplica do canal. Para manter a consistência,
    # criamos o canal localmente antes de salvar a mensagem.
    if not storage.channel_exists(channel_name):
        storage.create_channel(
            channel_name=channel_name,
            created_at=msg.timestamp,
        )

        if logical_clock is not None:
            replicate_channel(
                channel_name=channel_name,
                request_id=msg.request_id,
                pub_socket=pub_socket,
                logical_clock=logical_clock,
                server_name=server_name,
                server_rank=server_rank,
                physical_time=physical_time,
            )

    if not message_text:
        return make_message(
            msg_type="PUBLISH_REP",
            success=False,
            error_message="Mensagem inválida.",
            request_id=msg.request_id,
        )

    storage.save_message(
        username=username,
        channel_name=channel_name,
        message_text=message_text,
        sent_timestamp=msg.timestamp,
        request_id=msg.request_id,
    )

    channel_payload = make_message(
        msg_type="CHANNEL_MESSAGE",
        username=username,
        channel_name=channel_name,
        message_text=message_text,
        logical_clock=msg.logical_clock,
        server_name=server_name,
        server_rank=server_rank,
        physical_time=physical_time,
    )

    pub_socket.send_multipart([
        channel_name.encode("utf-8"),
        channel_payload.SerializeToString(),
    ])

    if logical_clock is not None:
        replicate_message(
            username=username,
            channel_name=channel_name,
            message_text=message_text,
            request_id=msg.request_id,
            sent_timestamp=msg.timestamp,
            pub_socket=pub_socket,
            logical_clock=logical_clock,
            server_name=server_name,
            server_rank=server_rank,
            physical_time=physical_time,
        )

    return make_message(
        msg_type="PUBLISH_REP",
        success=True,
        username=username,
        channel_name=channel_name,
        message_text=message_text,
        request_id=msg.request_id,
    )


def replicate_channel(
    channel_name,
    request_id,
    pub_socket,
    logical_clock,
    server_name,
    server_rank,
    physical_time,
):
    logical_clock.tick()

    replication = make_message(
        msg_type="REPLICATION_CHANNEL",
        success=True,
        channel_name=channel_name,
        request_id=request_id,
        logical_clock=logical_clock.get_value(),
        server_name=server_name,
        server_rank=server_rank,
        physical_time=physical_time,
    )

    pub_socket.send_multipart([
        b"replication",
        replication.SerializeToString(),
    ])

    print(
        "Canal replicado no tópico 'replication' | "
        f"canal={channel_name} | origem={server_name}"
    )


def replicate_message(
    username,
    channel_name,
    message_text,
    request_id,
    sent_timestamp,
    pub_socket,
    logical_clock,
    server_name,
    server_rank,
    physical_time,
):
    logical_clock.tick()

    replication = make_message(
        msg_type="REPLICATION_MESSAGE",
        success=True,
        username=username,
        channel_name=channel_name,
        message_text=message_text,
        request_id=request_id,
        logical_clock=logical_clock.get_value(),
        server_name=server_name,
        server_rank=server_rank,
        physical_time=physical_time,
    )

    replication.timestamp = sent_timestamp

    pub_socket.send_multipart([
        b"replication",
        replication.SerializeToString(),
    ])

    print(
        "Mensagem replicada no tópico 'replication' | "
        f"id={request_id} | canal={channel_name} | origem={server_name}"
    )


def handle_unknown(msg):
    return make_message(
        msg_type="ERROR_REP",
        success=False,
        error_message=f"Tipo de mensagem desconhecido: {msg.type}",
        request_id=msg.request_id,
    )