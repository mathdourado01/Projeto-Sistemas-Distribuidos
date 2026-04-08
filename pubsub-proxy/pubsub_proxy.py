import os
import zmq


def main() -> None:
    xsub_bind = os.getenv("PUBSUB_XSUB_BIND", "tcp://0.0.0.0:5557")
    xpub_bind = os.getenv("PUBSUB_XPUB_BIND", "tcp://0.0.0.0:5558")

    context = zmq.Context()

    xsub_socket = context.socket(zmq.XSUB)
    xsub_socket.bind(xsub_bind)

    xpub_socket = context.socket(zmq.XPUB)
    xpub_socket.bind(xpub_bind)

    print(f"Proxy Pub/Sub aguardando publishers em {xsub_bind}")
    print(f"Proxy Pub/Sub aguardando subscribers em {xpub_bind}")

    try:
        zmq.proxy(xsub_socket, xpub_socket)
    except KeyboardInterrupt:
        print("Proxy Pub/Sub encerrado.")
    finally:
        xsub_socket.close()
        xpub_socket.close()
        context.term()


if __name__ == "__main__":
    main()