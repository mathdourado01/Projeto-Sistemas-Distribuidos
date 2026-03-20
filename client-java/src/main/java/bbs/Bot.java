package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.List;

public class Bot {

    private static Envelope sendAndReceive(ZMQ.Socket socket, Envelope outgoing) throws Exception {
        Printer.printMessage("ENVIADA", outgoing);
        socket.send(outgoing.toByteArray(), 0);

        byte[] rawResponse = socket.recv(0);
        Envelope incoming = Envelope.parseFrom(rawResponse);

        Printer.printMessage("RECEBIDA", incoming);
        return incoming;
    }

    public static void main(String[] args) throws Exception {
        String serverAddress = System.getenv().getOrDefault("SERVER_ADDRESS", "tcp://127.0.0.1:5554");
        String username = System.getenv().getOrDefault("BOT_USERNAME", "bot_java_1");
        String desiredChannel = System.getenv().getOrDefault("BOT_CHANNEL", "java-geral");

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REQ);
            socket.connect(serverAddress);

            socket.setReceiveTimeOut(5000);
            socket.setSendTimeOut(5000);

            System.out.println("Bot Java conectando em " + serverAddress);

            try {
                Envelope loginReq = ProtocolUtil.makeMessage(
                        "LOGIN_REQ",
                        username,
                        "",
                        false,
                        "",
                        null,
                        null
                );

                Envelope loginRep = sendAndReceive(socket, loginReq);

                if (!loginRep.getSuccess()) {
                    System.out.println("Erro no login: " + loginRep.getErrorMessage());
                    return;
                }

                System.out.println("Login realizado com sucesso para '" + username + "'.");

                Envelope listReq1 = ProtocolUtil.makeMessage(
                        "LIST_CHANNELS_REQ",
                        "",
                        "",
                        false,
                        "",
                        null,
                        null
                );

                Envelope listRep1 = sendAndReceive(socket, listReq1);

                if (!listRep1.getSuccess()) {
                    System.out.println("Erro ao listar canais: " + listRep1.getErrorMessage());
                    return;
                }

                List<String> currentChannels = listRep1.getChannelsList();
                System.out.println("Canais antes da criação: " + currentChannels);

                if (!currentChannels.contains(desiredChannel)) {
                    Envelope createReq = ProtocolUtil.makeMessage(
                            "CREATE_CHANNEL_REQ",
                            "",
                            desiredChannel,
                            false,
                            "",
                            null,
                            null
                    );

                    Envelope createRep = sendAndReceive(socket, createReq);

                    if (!createRep.getSuccess()) {
                        System.out.println("Erro ao criar canal: " + createRep.getErrorMessage());
                        return;
                    }

                    System.out.println("Canal '" + desiredChannel + "' criado com sucesso.");
                } else {
                    System.out.println("Canal '" + desiredChannel + "' já existe.");
                }

                Envelope listReq2 = ProtocolUtil.makeMessage(
                        "LIST_CHANNELS_REQ",
                        "",
                        "",
                        false,
                        "",
                        null,
                        null
                );

                Envelope listRep2 = sendAndReceive(socket, listReq2);

                if (!listRep2.getSuccess()) {
                    System.out.println("Erro ao listar canais no final: " + listRep2.getErrorMessage());
                    return;
                }

                System.out.println("Canais depois da criação: " + listRep2.getChannelsList());
                System.out.println("Fluxo do bot Java finalizado.");

            } catch (Exception e) {
                System.out.println("Erro/timeout na comunicação do cliente Java: " + e.getMessage());
            } finally {
                socket.close();
            }
        }
    }
}