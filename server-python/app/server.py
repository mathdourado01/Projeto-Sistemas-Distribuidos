import os
import threading
import time
from typing import Optional

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


def request_server_list(reference_socket) -> list:
    req = make_message(msg_type="LIST_SERVERS_REQ")
    rep = send_reference_request(reference_socket, req)

    if not rep.success:
        print(f"Erro ao listar servidores: {rep.error_message}")
        return []

    return list(rep.servers)


def send_heartbeat(reference_socket, server_name: str) -> None:
    req = make_message(
        msg_type="HEARTBEAT_REQ",
        server_name=server_name,
    )

    rep = send_reference_request(reference_socket, req)

    if not rep.success:
        print(f"Erro no heartbeat: {rep.error_message}")
        return

    print(f"Heartbeat OK | servidor={server_name}")


def parse_known_servers(raw_value: str) -> dict:
    servers = {}

    if not raw_value:
        return servers

    pairs = raw_value.split(",")

    for pair in pairs:
        if "=" not in pair:
            continue

        name, address = pair.split("=", 1)
        servers[name.strip()] = address.strip()

    return servers


def parse_rank_list(server_list: list) -> dict:
    result = {}

    for item in server_list:
        if ":" not in item:
            continue

        name, rank = item.split(":", 1)

        try:
            result[name] = int(rank)
        except ValueError:
            continue

    return result


def choose_coordinator_by_rank(ranks: dict) -> str:
    if not ranks:
        return ""

    return min(ranks.items(), key=lambda item: item[1])[0]


def send_direct_request(address: str, msg: Envelope, timeout_ms: int = 3000) -> Optional[Envelope]:
    context = zmq.Context.instance()
    socket = context.socket(zmq.REQ)
    socket.connect(address)
    socket.setsockopt(zmq.RCVTIMEO, timeout_ms)
    socket.setsockopt(zmq.SNDTIMEO, timeout_ms)

    try:
        socket.send(msg.SerializeToString())
        raw_response = socket.recv()

        response = Envelope()
        response.ParseFromString(raw_response)

        return response

    except zmq.error.Again:
        return None

    finally:
        socket.close()


def request_time_from_coordinator(
    coordinator_name: str,
    known_servers: dict,
    server_name: str,
) -> Optional[int]:
    coordinator_address = known_servers.get(coordinator_name)

    if not coordinator_address:
        print(f"Endereço do coordenador '{coordinator_name}' não encontrado.")
        return None

    req = make_message(
        msg_type="TIME_REQ",
        server_name=server_name,
    )

    rep = send_direct_request(coordinator_address, req)

    if rep is None:
        print(f"Coordenador '{coordinator_name}' não respondeu ao TIME_REQ.")
        return None

    if not rep.success:
        print(f"Erro ao pedir hora ao coordenador: {rep.error_message}")
        return None

    print(
        f"Hora recebida do coordenador {coordinator_name}: "
        f"{rep.physical_time}"
    )

    return rep.physical_time


def publish_coordinator_announcement(pub_socket, coordinator_name: str, server_name: str) -> None:
    msg = make_message(
        msg_type="COORDINATOR_ANNOUNCE",
        server_name=server_name,
        message_text=coordinator_name,
    )

    pub_socket.send_multipart([
        b"servers",
        msg.SerializeToString(),
    ])

    print(f"Coordenador anunciado no tópico 'servers': {coordinator_name}")


def run_election(
    known_servers: dict,
    ranks: dict,
    server_name: str,
    server_rank: int,
    pub_socket,
) -> str:
    print("Iniciando eleição de coordenador...")

    alive_servers = {
        server_name: server_rank
    }

    for other_name, address in known_servers.items():
        if other_name == server_name:
            continue

        req = make_message(
            msg_type="ELECTION_REQ",
            server_name=server_name,
            server_rank=server_rank,
        )

        rep = send_direct_request(address, req, timeout_ms=2000)

        if rep is None:
            print(f"Servidor {other_name} não respondeu à eleição.")
            continue

        if rep.success:
            print(f"Servidor {other_name} respondeu OK à eleição.")
            rank = ranks.get(other_name, rep.server_rank)
            alive_servers[other_name] = rank

    new_coordinator = choose_coordinator_by_rank(alive_servers)

    if not new_coordinator:
        new_coordinator = server_name

    print(f"Novo coordenador eleito: {new_coordinator}")

    publish_coordinator_announcement(
        pub_socket=pub_socket,
        coordinator_name=new_coordinator,
        server_name=server_name,
    )

    return new_coordinator


def start_sync_listener(
    bind_address: str,
    server_name: str,
    server_rank: int,
    logical_clock: LogicalClock,
    physical_clock_offset_ref: dict,
    stop_event: threading.Event,
) -> threading.Thread:
    def listen():
        context = zmq.Context.instance()
        socket = context.socket(zmq.REP)
        socket.bind(bind_address)

        print(f"Servidor escutando sincronização em {bind_address}")

        while not stop_event.is_set():
            try:
                raw_message = socket.recv(flags=zmq.NOBLOCK)
            except zmq.Again:
                time.sleep(0.1)
                continue

            incoming = Envelope()
            incoming.ParseFromString(raw_message)

            logical_clock.update(incoming.logical_clock)

            print("=" * 60)
            print("[SYNC RECEBIDA]")
            print(f"type        : {incoming.type}")
            print(f"server_name : {incoming.server_name}")
            print("=" * 60)

            if incoming.type == "TIME_REQ":
                logical_clock.tick()

                response = make_message(
                    msg_type="TIME_REP",
                    success=True,
                    server_name=server_name,
                    server_rank=server_rank,
                    logical_clock=logical_clock.get_value(),
                    physical_time=get_adjusted_physical_time(
                        physical_clock_offset_ref["offset"]
                    ),
                    request_id=incoming.request_id,
                )

            elif incoming.type == "ELECTION_REQ":
                logical_clock.tick()

                response = make_message(
                    msg_type="ELECTION_REP",
                    success=True,
                    server_name=server_name,
                    server_rank=server_rank,
                    logical_clock=logical_clock.get_value(),
                    request_id=incoming.request_id,
                )

            else:
                logical_clock.tick()

                response = make_message(
                    msg_type="ERROR_REP",
                    success=False,
                    error_message=f"Tipo desconhecido na sincronização: {incoming.type}",
                    server_name=server_name,
                    server_rank=server_rank,
                    logical_clock=logical_clock.get_value(),
                    request_id=incoming.request_id,
                )

            print("=" * 60)
            print("[SYNC ENVIADA]")
            print(f"type          : {response.type}")
            print(f"server_name   : {response.server_name}")
            print(f"server_rank   : {response.server_rank}")
            print(f"physical_time : {response.physical_time}")
            print("=" * 60)

            socket.send(response.SerializeToString())

        socket.close()

    thread = threading.Thread(target=listen, daemon=True)
    thread.start()

    return thread


def start_coordinator_subscription_listener(
    pubsub_proxy_address: str,
    current_coordinator_ref: dict,
    stop_event: threading.Event,
) -> threading.Thread:
    def listen():
        context = zmq.Context.instance()
        sub_socket = context.socket(zmq.SUB)
        sub_socket.connect(pubsub_proxy_address)
        sub_socket.setsockopt_string(zmq.SUBSCRIBE, "servers")

        poller = zmq.Poller()
        poller.register(sub_socket, zmq.POLLIN)

        print("Servidor inscrito no tópico 'servers' para anúncios de coordenador.")

        while not stop_event.is_set():
            events = dict(poller.poll(500))

            if sub_socket not in events:
                continue

            topic, raw_message = sub_socket.recv_multipart()

            incoming = Envelope()
            incoming.ParseFromString(raw_message)

            if incoming.type == "COORDINATOR_ANNOUNCE":
                current_coordinator_ref["name"] = incoming.message_text
                print(f"Coordenador atualizado via Pub/Sub: {incoming.message_text}")

        sub_socket.close()

    thread = threading.Thread(target=listen, daemon=True)
    thread.start()

    return thread


def start_replication_listener(
    pubsub_proxy_address: str,
    storage: Storage,
    server_name: str,
    logical_clock: LogicalClock,
    stop_event: threading.Event,
) -> threading.Thread:
    def listen():
        context = zmq.Context.instance()
        sub_socket = context.socket(zmq.SUB)
        sub_socket.connect(pubsub_proxy_address)
        sub_socket.setsockopt_string(zmq.SUBSCRIBE, "replication")

        poller = zmq.Poller()
        poller.register(sub_socket, zmq.POLLIN)

        print("Servidor inscrito no tópico 'replication' para réplica de dados.")

        while not stop_event.is_set():
            events = dict(poller.poll(500))

            if sub_socket not in events:
                continue

            topic, raw_message = sub_socket.recv_multipart()

            incoming = Envelope()
            incoming.ParseFromString(raw_message)

            if incoming.server_name == server_name:
                continue

            logical_clock.update(incoming.logical_clock)

            if incoming.type == "REPLICATION_CHANNEL":
                channel_name = incoming.channel_name.strip()

                if channel_name:
                    storage.create_channel(
                        channel_name=channel_name,
                        created_at=incoming.timestamp,
                    )

                    print(
                        "Réplica de canal recebida | "
                        f"canal={channel_name} | origem={incoming.server_name}"
                    )

            elif incoming.type == "REPLICATION_MESSAGE":
                username = incoming.username.strip()
                channel_name = incoming.channel_name.strip()
                message_text = incoming.message_text.strip()

                if channel_name and not storage.channel_exists(channel_name):
                    storage.create_channel(
                        channel_name=channel_name,
                        created_at=incoming.timestamp,
                    )

                if username and channel_name and message_text:
                    storage.save_message(
                        username=username,
                        channel_name=channel_name,
                        message_text=message_text,
                        sent_timestamp=incoming.timestamp,
                        request_id=incoming.request_id,
                    )

                    print(
                        "Réplica de mensagem recebida | "
                        f"id={incoming.request_id} | "
                        f"canal={channel_name} | "
                        f"origem={incoming.server_name}"
                    )

        sub_socket.close()

    thread = threading.Thread(target=listen, daemon=True)
    thread.start()

    return thread


def main() -> None:
    broker_address = os.getenv("BROKER_ADDRESS", "tcp://broker:5555")
    pubsub_address = os.getenv("PUBSUB_ADDRESS", "tcp://pubsub-proxy:5557")
    pubsub_sub_address = os.getenv("PUBSUB_PROXY_ADDRESS", "tcp://pubsub-proxy:5558")
    reference_address = os.getenv("REFERENCE_ADDRESS", "tcp://reference-service:5560")
    server_name = os.getenv("SERVER_NAME", "server-python")
    db_path = os.getenv("SERVER_DB_PATH", "data/server.db")
    sync_bind = os.getenv("SERVER_SYNC_BIND", "tcp://0.0.0.0:5571")
    known_servers_raw = os.getenv("KNOWN_SERVERS", "")

    known_servers = parse_known_servers(known_servers_raw)

    storage = Storage(db_path=db_path)
    logical_clock = LogicalClock()

    client_message_count = 0
    physical_clock_offset_ref = {
        "offset": 0
    }

    current_coordinator_ref = {
        "name": ""
    }

    stop_event = threading.Event()

    context = zmq.Context.instance()

    socket = context.socket(zmq.REP)
    socket.connect(broker_address)

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect(pubsub_address)

    reference_socket = context.socket(zmq.REQ)
    reference_socket.connect(reference_address)

    server_rank = request_rank(reference_socket, server_name)

    server_list = request_server_list(reference_socket)
    ranks = parse_rank_list(server_list)

    if server_name not in ranks:
        ranks[server_name] = server_rank

    current_coordinator_ref["name"] = choose_coordinator_by_rank(ranks)

    if not current_coordinator_ref["name"]:
        current_coordinator_ref["name"] = server_name

    start_sync_listener(
        bind_address=sync_bind,
        server_name=server_name,
        server_rank=server_rank,
        logical_clock=logical_clock,
        physical_clock_offset_ref=physical_clock_offset_ref,
        stop_event=stop_event,
    )

    start_coordinator_subscription_listener(
        pubsub_proxy_address=pubsub_sub_address,
        current_coordinator_ref=current_coordinator_ref,
        stop_event=stop_event,
    )

    start_replication_listener(
        pubsub_proxy_address=pubsub_sub_address,
        storage=storage,
        server_name=server_name,
        logical_clock=logical_clock,
        stop_event=stop_event,
    )

    time.sleep(1)

    if current_coordinator_ref["name"] == server_name:
        publish_coordinator_announcement(
            pub_socket=pub_socket,
            coordinator_name=server_name,
            server_name=server_name,
        )

    print(f"Servidor conectado ao broker em {broker_address}")
    print(f"Servidor conectado ao proxy Pub/Sub em {pubsub_address}")
    print(f"Servidor conectado ao serviço de referência em {reference_address}")
    print(f"Servidor {server_name} com rank {server_rank}")
    print(f"Coordenador atual: {current_coordinator_ref['name']}")
    print(f"Banco de dados em {db_path}")

    while True:
        raw_message = socket.recv()

        incoming = Envelope()
        incoming.ParseFromString(raw_message)

        logical_clock.update(incoming.logical_clock)
        client_message_count += 1

        print_message("RECEBIDA", incoming)
        print(f"Relógio lógico local após receber: {logical_clock.get_value()}")
        print(
            "Relógio físico ajustado: "
            f"{get_adjusted_physical_time(physical_clock_offset_ref['offset'])}"
        )

        if client_message_count % 10 == 0:
            send_heartbeat(reference_socket, server_name)

        if client_message_count % 15 == 0:
            coordinator_name = current_coordinator_ref["name"]

            if coordinator_name == server_name:
                print("Este servidor é o coordenador. Não precisa pedir hora.")
            else:
                coordinator_time = request_time_from_coordinator(
                    coordinator_name=coordinator_name,
                    known_servers=known_servers,
                    server_name=server_name,
                )

                if coordinator_time is None:
                    new_coordinator = run_election(
                        known_servers=known_servers,
                        ranks=ranks,
                        server_name=server_name,
                        server_rank=server_rank,
                        pub_socket=pub_socket,
                    )
                    current_coordinator_ref["name"] = new_coordinator
                else:
                    local_time = int(time.time())
                    physical_clock_offset_ref["offset"] = coordinator_time - local_time

                    print(
                        f"Relógio físico sincronizado com coordenador {coordinator_name}. "
                        f"offset={physical_clock_offset_ref['offset']}"
                    )

        physical_time = get_adjusted_physical_time(
            physical_clock_offset_ref["offset"]
        )

        if incoming.type == "LOGIN_REQ":
            response = handle_login(incoming, storage)

        elif incoming.type == "LIST_CHANNELS_REQ":
            response = handle_list_channels(incoming, storage)

        elif incoming.type == "CREATE_CHANNEL_REQ":
            response = handle_create_channel(
                incoming,
                storage,
                pub_socket=pub_socket,
                logical_clock=logical_clock,
                server_name=server_name,
                server_rank=server_rank,
                physical_time=physical_time,
            )

        elif incoming.type == "PUBLISH_REQ":
            response = handle_publish(
                incoming,
                storage,
                pub_socket,
                logical_clock=logical_clock,
                server_name=server_name,
                server_rank=server_rank,
                physical_time=physical_time,
            )

        else:
            response = handle_unknown(incoming)

        logical_clock.tick()
        response.logical_clock = logical_clock.get_value()
        response.server_name = server_name
        response.server_rank = server_rank
        response.physical_time = get_adjusted_physical_time(
            physical_clock_offset_ref["offset"]
        )

        print_message("ENVIADA", response)
        print(f"Relógio lógico local após enviar: {logical_clock.get_value()}")

        socket.send(response.SerializeToString())


if __name__ == "__main__":
    main()