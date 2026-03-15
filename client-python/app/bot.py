import os
import zmq

from chat_pb2 import Envelope
from printer import print_message
from protocol import make_message


def send_and_receive(socket, outgoing: Envelope) -> Envelope:
    print_message("ENVIADA", outgoing)
    socket.send(outgoing.SerializeToString())

    raw_response = socket.recv()

    incoming = Envelope()
    incoming.ParseFromString(raw_response)

    print_message("RECEBIDA", incoming)
    return incoming


def main() -> None:
    server_address = os.getenv("SERVER_ADDRESS", "tcp://127.0.0.1:5554")
    username = os.getenv("BOT_USERNAME", "bot_python_1")
    desired_channel = os.getenv("BOT_CHANNEL", "geral")

    context = zmq.Context()
    socket = context.socket(zmq.REQ)
    socket.connect(server_address)

    socket.setsockopt(zmq.RCVTIMEO, 5000)
    socket.setsockopt(zmq.SNDTIMEO, 5000)

    print(f"Bot conectando em {server_address}")

    try:
        # 1. LOGIN
        login_req = make_message(
            msg_type="LOGIN_REQ",
            username=username,
        )
        login_rep = send_and_receive(socket, login_req)

        if not login_rep.success:
            print(f"Erro no login: {login_rep.error_message}")
            return

        print(f"Login realizado com sucesso para '{username}'.")

        # 2. LISTAR CANAIS
        list_req_1 = make_message(msg_type="LIST_CHANNELS_REQ")
        list_rep_1 = send_and_receive(socket, list_req_1)

        if not list_rep_1.success:
            print(f"Erro ao listar canais: {list_rep_1.error_message}")
            return

        current_channels = list(list_rep_1.channels)
        print(f"Canais antes da criação: {current_channels}")

        # 3. CRIAR CANAL, SE NÃO EXISTIR
        if desired_channel not in current_channels:
            create_req = make_message(
                msg_type="CREATE_CHANNEL_REQ",
                channel_name=desired_channel,
            )
            create_rep = send_and_receive(socket, create_req)

            if not create_rep.success:
                print(f"Erro ao criar canal: {create_rep.error_message}")
                return

            print(f"Canal '{desired_channel}' criado com sucesso.")
        else:
            print(f"Canal '{desired_channel}' já existe.")

        # 4. LISTAR DE NOVO
        list_req_2 = make_message(msg_type="LIST_CHANNELS_REQ")
        list_rep_2 = send_and_receive(socket, list_req_2)

        if not list_rep_2.success:
            print(f"Erro ao listar canais no final: {list_rep_2.error_message}")
            return

        updated_channels = list(list_rep_2.channels)
        print(f"Canais depois da criação: {updated_channels}")

        print("Fluxo do bot finalizado.")

    except zmq.error.Again:
        print("Timeout: o cliente não recebeu resposta do servidor.")
    finally:
        socket.close()
        context.term()


if __name__ == "__main__":
    main()