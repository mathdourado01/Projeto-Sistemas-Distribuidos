import os
import time

import zmq

from chat_pb2 import Envelope
from clock import LogicalClock
from handlers import (
    handle_create_channel,
    handle_list_channels,
    handle_login,
    handle_publish,
    handle_unknown,
)
from printer import print_message
from protocol import make_message
from storage import Storage


def send_reference_request(reference_socket, msg: Envelope) -> Envelope:
    reference_socket.send(msg.SerializeToString())

    raw_response = reference_socket.recv()

    response = Envelope()
    response.ParseFromString(raw_response)

    return response


def get_adjusted_physical_time(offset: int) -> int:
    return int(time.time()) + offset


def request_rank(reference_socket, server_name: str) -> int:
    req = make_message(
        msg_type="RANK_REQ",
        server_name=server_name,
    )

    rep = send_reference_request(reference_socket, req)

    if not rep.success:
        print(f"Erro ao obter rank: {rep.error_message}")
        return 0

    print(f"Servidor recebeu rank {rep.server_rank}")
    return rep.server_rank


def send_heartbeat(reference_socket, server_name: str) -> int:
    req = make_message(
        msg_type="HEARTBEAT_REQ",
        server_name=server_name,
    )

    rep = send_reference_request(reference_socket, req)

    if not rep.success:
        print(f"Erro no heartbeat: {rep.error_message}")
        return 0

    local_time = int(time.time())
    offset = rep.physical_time - local_time

    print(
        f"Heartbeat OK | servidor={server_name} | "
        f"tempo_referencia={rep.physical_time} | "
        f"tempo_local={local_time} | offset={offset}"
    )

    return offset


def main() -> None:
    broker_address = os.getenv("BROKER_ADDRESS", "tcp://broker:5555")
    pubsub_address = os.getenv("PUBSUB_ADDRESS", "tcp://pubsub-proxy:5557")
    reference_address = os.getenv("REFERENCE_ADDRESS", "tcp://reference-service:5560")
    server_name = os.getenv("SERVER_NAME", "server-python")
    db_path = os.getenv("SERVER_DB_PATH", "data/server.db")

    storage = Storage(db_path=db_path)
    logical_clock = LogicalClock()

    client_message_count = 0
    physical_clock_offset = 0

    context = zmq.Context()

    socket = context.socket(zmq.REP)
    socket.connect(broker_address)

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect(pubsub_address)

    reference_socket = context.socket(zmq.REQ)
    reference_socket.connect(reference_address)

    server_rank = request_rank(reference_socket, server_name)

    print(f"Servidor conectado ao broker em {broker_address}")
    print(f"Servidor conectado ao proxy Pub/Sub em {pubsub_address}")
    print(f"Servidor conectado ao serviço de referência em {reference_address}")
    print(f"Servidor {server_name} com rank {server_rank}")
    print(f"Banco de dados em {db_path}")

    while True:
        raw_message = socket.recv()

        incoming = Envelope()
        incoming.ParseFromString(raw_message)

        logical_clock.update(incoming.logical_clock)
        client_message_count += 1

        print_message("RECEBIDA", incoming)
        print(f"Relógio lógico local após receber: {logical_clock.get_value()}")
        print(f"Relógio físico ajustado: {get_adjusted_physical_time(physical_clock_offset)}")

        if client_message_count % 3 == 0:
            physical_clock_offset = send_heartbeat(reference_socket, server_name)

        if incoming.type == "LOGIN_REQ":
            response = handle_login(incoming, storage)

        elif incoming.type == "LIST_CHANNELS_REQ":
            response = handle_list_channels(incoming, storage)

        elif incoming.type == "CREATE_CHANNEL_REQ":
            response = handle_create_channel(incoming, storage)

        elif incoming.type == "PUBLISH_REQ":
            response = handle_publish(incoming, storage, pub_socket)

        else:
            response = handle_unknown(incoming)

        logical_clock.tick()
        response.logical_clock = logical_clock.get_value()
        response.server_name = server_name
        response.server_rank = server_rank
        response.physical_time = get_adjusted_physical_time(physical_clock_offset)

        print_message("ENVIADA", response)
        print(f"Relógio lógico local após enviar: {logical_clock.get_value()}")

        socket.send(response.SerializeToString())


if __name__ == "__main__":
    main()