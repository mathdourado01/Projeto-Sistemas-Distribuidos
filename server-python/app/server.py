import os

import zmq

from chat_pb2 import Envelope
from handlers import (
    handle_create_channel,
    handle_list_channels,
    handle_login,
    handle_publish,
    handle_unknown,
)
from printer import print_message
from storage import Storage


def main() -> None:
    broker_address = os.getenv("BROKER_ADDRESS", "tcp://127.0.0.1:5555")
    pubsub_address = os.getenv("PUBSUB_ADDRESS", "tcp://127.0.0.1:5557")
    db_path = os.getenv("SERVER_DB_PATH", "data/server.db")

    storage = Storage(db_path=db_path)

    context = zmq.Context()

    
    socket = context.socket(zmq.REP)
    socket.connect(broker_address)

    
    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect(pubsub_address)

    print(f"Servidor conectado ao broker em {broker_address}")
    print(f"Servidor conectado ao proxy Pub/Sub em {pubsub_address}")
    print(f"Banco de dados em {db_path}")

    while True:
        raw_message = socket.recv()

        incoming = Envelope()
        incoming.ParseFromString(raw_message)

        print_message("RECEBIDA", incoming)

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

        print_message("ENVIADA", response)
        socket.send(response.SerializeToString())


if __name__ == "__main__":
    main()