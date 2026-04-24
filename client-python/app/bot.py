import os
import threading
import time

import zmq

from chat_pb2 import Envelope
from printer import print_message
from protocol import make_message
from clock import LogicalClock


def send_and_receive(socket, outgoing: Envelope, logical_clock: LogicalClock) -> Envelope:
    logical_clock.tick()
    outgoing.logical_clock = logical_clock.get_value()

    print_message("ENVIADA", outgoing)
    socket.send(outgoing.SerializeToString())

    raw_response = socket.recv()

    incoming = Envelope()
    incoming.ParseFromString(raw_response)

    logical_clock.update(incoming.logical_clock)

    print_message("RECEBIDA", incoming)
    print(f"Relógio lógico local do bot: {logical_clock.get_value()}")

    return incoming


def listen_subscriptions(sub_socket, stop_event: threading.Event, logical_clock: LogicalClock) -> None:
    poller = zmq.Poller()
    poller.register(sub_socket, zmq.POLLIN)

    while not stop_event.is_set():
        try:
            events = dict(poller.poll(500))
            if sub_socket not in events:
                continue

            topic, raw_message = sub_socket.recv_multipart()

            incoming = Envelope()
            incoming.ParseFromString(raw_message)
            logical_clock.update(incoming.logical_clock)

            received_timestamp = int(time.time())

            print("\n--- MENSAGEM RECEBIDA VIA PUB/SUB ---")
            print(f"canal                : {topic.decode('utf-8')}")
            print(f"usuario              : {incoming.username}")
            print(f"mensagem             : {incoming.message_text}")
            print(f"timestamp envio      : {incoming.timestamp}")
            print(f"timestamp recebimento: {received_timestamp}")
            print(f"relógio lógico local: {logical_clock.get_value()}")
            print("-------------------------------------\n")

        except zmq.error.ZMQError:
            break
        except Exception as e:
            print(f"Erro ao receber mensagem Pub/Sub: {e}")
            break


def main() -> None:
    server_address = os.getenv("SERVER_ADDRESS", "tcp://broker:5554")
    pubsub_proxy_address = os.getenv("PUBSUB_PROXY_ADDRESS", "tcp://pubsub-proxy:5558")
    username = os.getenv("BOT_USERNAME", "bot_python_1")
    desired_channel = os.getenv("BOT_CHANNEL", "geral")

    context = zmq.Context()

    logical_clock = LogicalClock()

    req_socket = context.socket(zmq.REQ)
    req_socket.connect(server_address)
    req_socket.setsockopt(zmq.RCVTIMEO, 5000)
    req_socket.setsockopt(zmq.SNDTIMEO, 5000)

    sub_socket = context.socket(zmq.SUB)
    sub_socket.setsockopt_string(zmq.SUBSCRIBE, desired_channel)
    sub_socket.connect(pubsub_proxy_address)

    stop_event = threading.Event()

    print(f"Bot conectando ao servidor em {server_address}")
    print(f"Bot conectando ao proxy Pub/Sub em {pubsub_proxy_address}")

    try:
        login_req = make_message(
            msg_type="LOGIN_REQ",
            username=username,
        )
        login_rep = send_and_receive(req_socket, login_req, logical_clock)

        if not login_rep.success:
            print(f"Erro no login: {login_rep.error_message}")
            return

        print(f"Login realizado com sucesso para '{username}'.")

        list_req_1 = make_message(msg_type="LIST_CHANNELS_REQ")
        list_rep_1 = send_and_receive(req_socket, list_req_1, logical_clock)

        if not list_rep_1.success:
            print(f"Erro ao listar canais: {list_rep_1.error_message}")
            return

        current_channels = list(list_rep_1.channels)
        print(f"Canais antes da criação: {current_channels}")

        if desired_channel not in current_channels:
            create_req = make_message(
                msg_type="CREATE_CHANNEL_REQ",
                channel_name=desired_channel,
            )
            create_rep = send_and_receive(req_socket, create_req, logical_clock)

            if not create_rep.success:
                print(f"Erro ao criar canal: {create_rep.error_message}")
                return

            print(f"Canal '{desired_channel}' criado com sucesso.")
        else:
            print(f"Canal '{desired_channel}' já existe.")

        list_req_2 = make_message(msg_type="LIST_CHANNELS_REQ")
        list_rep_2 = send_and_receive(req_socket, list_req_2, logical_clock)

        if not list_rep_2.success:
            print(f"Erro ao listar canais no final: {list_rep_2.error_message}")
            return

        updated_channels = list(list_rep_2.channels)
        print(f"Canais depois da criação: {updated_channels}")
        print(f"Bot inscrito no canal '{desired_channel}'.")

        listener_thread = threading.Thread(
            target=listen_subscriptions,
            args=(sub_socket, stop_event, logical_clock),
            daemon=True,
        )
        listener_thread.start()

        # Espera maior para evitar perder a primeira publicação
        print("Aguardando estabilização da inscrição no tópico...")
        time.sleep(3)

        # Publica duas mensagens para facilitar a validação do Pub/Sub
        for i in range(2):
            publish_req = make_message(
                msg_type="PUBLISH_REQ",
                username=username,
                channel_name=desired_channel,
                message_text=f"Mensagem de teste da Parte 2 - envio {i + 1}.",
            )
            publish_rep = send_and_receive(req_socket, publish_req, logical_clock)

            if not publish_rep.success:
                print(f"Erro ao publicar mensagem: {publish_rep.error_message}")
                return

            print(f"Mensagem {i + 1} publicada com sucesso no canal '{desired_channel}'.")
            time.sleep(1)

        print("Aguardando recebimento via Pub/Sub...")
        time.sleep(8)

        print("Fluxo básico da Parte 2 finalizado.")

    except zmq.error.Again:
        print("Timeout: o cliente não recebeu resposta do servidor.")
    finally:
        stop_event.set()
        time.sleep(1)
        req_socket.close()
        sub_socket.close()
        context.term()


if __name__ == "__main__":
    main()