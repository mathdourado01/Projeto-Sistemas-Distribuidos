import os
import time

import zmq

from chat_pb2 import Envelope


HEARTBEAT_TIMEOUT = 30


def now_timestamp() -> int:
    return int(time.time())


class ReferenceService:
    def __init__(self) -> None:
        self.servers = {}
        self.next_rank = 1

    def remove_expired_servers(self) -> None:
        current_time = now_timestamp()

        expired = [
            name for name, info in self.servers.items()
            if current_time - info["last_heartbeat"] > HEARTBEAT_TIMEOUT
        ]

        for name in expired:
            print(f"Removendo servidor inativo: {name}")
            del self.servers[name]

    def get_or_create_rank(self, server_name: str) -> int:
        self.remove_expired_servers()

        if server_name in self.servers:
            self.servers[server_name]["last_heartbeat"] = now_timestamp()
            return self.servers[server_name]["rank"]

        rank = self.next_rank
        self.next_rank += 1

        self.servers[server_name] = {
            "rank": rank,
            "last_heartbeat": now_timestamp(),
        }

        print(f"Servidor cadastrado: {server_name} com rank {rank}")
        return rank

    def heartbeat(self, server_name: str) -> bool:
        self.remove_expired_servers()

        if server_name not in self.servers:
            self.get_or_create_rank(server_name)
        else:
            self.servers[server_name]["last_heartbeat"] = now_timestamp()

        return True

    def list_servers(self) -> list[str]:
        self.remove_expired_servers()

        result = []
        for name, info in self.servers.items():
            result.append(f"{name}:{info['rank']}")

        return result


def build_response(
    request: Envelope,
    msg_type: str,
    success: bool = True,
    error_message: str = "",
    server_name: str = "",
    server_rank: int = 0,
    servers=None,
) -> Envelope:
    response = Envelope()
    response.type = msg_type
    response.timestamp = now_timestamp()
    response.request_id = request.request_id
    response.success = success
    response.error_message = error_message
    response.server_name = server_name
    response.server_rank = server_rank
    response.physical_time = now_timestamp()

    if servers:
        response.servers.extend(servers)

    return response


def main() -> None:
    bind_address = os.getenv("REFERENCE_BIND", "tcp://0.0.0.0:5560")

    service = ReferenceService()

    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.bind(bind_address)

    print(f"Serviço de referência escutando em {bind_address}")

    while True:
        raw_message = socket.recv()

        request = Envelope()
        request.ParseFromString(raw_message)

        print("=" * 60)
        print("[REFERÊNCIA RECEBIDA]")
        print(f"type        : {request.type}")
        print(f"server_name : {request.server_name}")
        print("=" * 60)

        if request.type == "RANK_REQ":
            server_name = request.server_name.strip()

            if not server_name:
                response = build_response(
                    request,
                    msg_type="RANK_REP",
                    success=False,
                    error_message="Nome do servidor inválido.",
                )
            else:
                rank = service.get_or_create_rank(server_name)

                response = build_response(
                    request,
                    msg_type="RANK_REP",
                    success=True,
                    server_name=server_name,
                    server_rank=rank,
                )

        elif request.type == "LIST_SERVERS_REQ":
            servers = service.list_servers()

            response = build_response(
                request,
                msg_type="LIST_SERVERS_REP",
                success=True,
                servers=servers,
            )

        elif request.type == "HEARTBEAT_REQ":
            server_name = request.server_name.strip()

            if not server_name:
                response = build_response(
                    request,
                    msg_type="HEARTBEAT_REP",
                    success=False,
                    error_message="Nome do servidor inválido.",
                )
            else:
                service.heartbeat(server_name)

                response = build_response(
                    request,
                    msg_type="HEARTBEAT_REP",
                    success=True,
                    server_name=server_name,
                    server_rank=service.servers[server_name]["rank"],
                )

        else:
            response = build_response(
                request,
                msg_type="ERROR_REP",
                success=False,
                error_message=f"Tipo desconhecido: {request.type}",
            )

        print("=" * 60)
        print("[REFERÊNCIA ENVIADA]")
        print(f"type          : {response.type}")
        print(f"success       : {response.success}")
        print(f"server_name   : {response.server_name}")
        print(f"server_rank   : {response.server_rank}")
        print(f"physical_time : {response.physical_time}")
        print(f"servers       : {list(response.servers)}")
        print("=" * 60)

        socket.send(response.SerializeToString())


if __name__ == "__main__":
    main()