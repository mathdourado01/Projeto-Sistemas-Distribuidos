import os
import zmq


def main() -> None:
    client_bind = os.getenv("BROKER_CLIENT_BIND", "tcp://0.0.0.0:5554")
    server_bind = os.getenv("BROKER_SERVER_BIND", "tcp://0.0.0.0:5555")

    context = zmq.Context()

    frontend = context.socket(zmq.ROUTER)
    frontend.bind(client_bind)

    backend = context.socket(zmq.DEALER)
    backend.bind(server_bind)

    print(f"Broker aguardando clientes em {client_bind}")
    print(f"Broker encaminhando para servidores em {server_bind}")

    try:
        zmq.proxy(frontend, backend)
    except KeyboardInterrupt:
        print("Broker encerrado.")
    finally:
        frontend.close()
        backend.close()
        context.term()


if __name__ == "__main__":
    main()