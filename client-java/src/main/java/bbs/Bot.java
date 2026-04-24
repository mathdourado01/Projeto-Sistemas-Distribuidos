package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bot {

    private static Envelope sendAndReceive(
            ZMQ.Socket socket,
            Envelope outgoing,
            LogicalClock logicalClock
    ) throws Exception {
        logicalClock.tick();

        Envelope messageToSend = outgoing.toBuilder()
                .setLogicalClock(logicalClock.getValue())
                .build();

        Printer.printMessage("ENVIADA", messageToSend);
        socket.send(messageToSend.toByteArray(), 0);

        byte[] rawResponse = socket.recv(0);
        Envelope incoming = Envelope.parseFrom(rawResponse);

        logicalClock.update(incoming.getLogicalClock());

        Printer.printMessage("RECEBIDA", incoming);
        System.out.println("Relógio lógico local do bot Java: " + logicalClock.getValue());

        return incoming;
    }

    private static void listenSubscriptions(
        ZContext context,
        ZMQ.Socket subSocket,
        AtomicBoolean running,
        LogicalClock logicalClock
) {
    ZMQ.Poller poller = context.createPoller(1);
    poller.register(subSocket, ZMQ.Poller.POLLIN);

    while (running.get()) {
        try {
            int events = poller.poll(500);

            if (events == 0) {
                continue;
            }

            byte[] topicBytes = subSocket.recv(0);
            byte[] rawMessage = subSocket.recv(0);

            if (topicBytes == null || rawMessage == null) {
                continue;
            }

            Envelope incoming = Envelope.parseFrom(rawMessage);
            logicalClock.update(incoming.getLogicalClock());

            long receivedTimestamp = Instant.now().getEpochSecond();

            System.out.println();
            System.out.println("--- MENSAGEM RECEBIDA VIA PUB/SUB JAVA ---");
            System.out.println("canal                : " + new String(topicBytes));
            System.out.println("usuario              : " + incoming.getUsername());
            System.out.println("mensagem             : " + incoming.getMessageText());
            System.out.println("timestamp envio      : " + incoming.getTimestamp());
            System.out.println("timestamp recebimento: " + receivedTimestamp);
            System.out.println("relógio lógico local : " + logicalClock.getValue());
            System.out.println("------------------------------------------");
            System.out.println();

        } catch (Exception e) {
            if (running.get()) {
                System.out.println("Erro ao receber mensagem Pub/Sub Java: " + e.getMessage());
            }
            break;
        }
    }

    poller.unregister(subSocket);
}

    public static void main(String[] args) throws Exception {
        String serverAddress = System.getenv().getOrDefault("SERVER_ADDRESS", "tcp://broker:5554");
        String pubsubProxyAddress = System.getenv().getOrDefault("PUBSUB_PROXY_ADDRESS", "tcp://pubsub-proxy:5558");
        String username = System.getenv().getOrDefault("BOT_USERNAME", "bot_java_1");
        String desiredChannel = System.getenv().getOrDefault("BOT_CHANNEL", "java-geral");

        LogicalClock logicalClock = new LogicalClock();
        AtomicBoolean running = new AtomicBoolean(true);

        try (ZContext context = new ZContext()) {
            ZMQ.Socket reqSocket = context.createSocket(SocketType.REQ);
            reqSocket.connect(serverAddress);
            reqSocket.setReceiveTimeOut(5000);
            reqSocket.setSendTimeOut(5000);

            ZMQ.Socket subSocket = context.createSocket(SocketType.SUB);
            subSocket.subscribe(desiredChannel.getBytes());
            subSocket.connect(pubsubProxyAddress);
            subSocket.setReceiveTimeOut(500);

            System.out.println("Bot Java conectando ao servidor em " + serverAddress);
            System.out.println("Bot Java conectando ao proxy Pub/Sub em " + pubsubProxyAddress);

            Envelope loginReq = ProtocolUtil.makeMessage(
                    "LOGIN_REQ",
                    username,
                    "",
                    false,
                    "",
                    null,
                    null,
                    "",
                    0,
                    "",
                    0,
                    null,
                    0
            );

            Envelope loginRep = sendAndReceive(reqSocket, loginReq, logicalClock);

            if (!loginRep.getSuccess()) {
                System.out.println("Erro no login: " + loginRep.getErrorMessage());
                return;
            }

            System.out.println("Login realizado com sucesso para '" + username + "'.");

            Envelope listReq1 = ProtocolUtil.makeMessage("LIST_CHANNELS_REQ");
            Envelope listRep1 = sendAndReceive(reqSocket, listReq1, logicalClock);

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
                        null,
                        "",
                        0,
                        "",
                        0,
                        null,
                        0
                );

                Envelope createRep = sendAndReceive(reqSocket, createReq, logicalClock);

                if (!createRep.getSuccess()) {
                    System.out.println("Erro ao criar canal: " + createRep.getErrorMessage());
                    return;
                }

                System.out.println("Canal '" + desiredChannel + "' criado com sucesso.");
            } else {
                System.out.println("Canal '" + desiredChannel + "' já existe.");
            }

            Envelope listReq2 = ProtocolUtil.makeMessage("LIST_CHANNELS_REQ");
            Envelope listRep2 = sendAndReceive(reqSocket, listReq2, logicalClock);

            System.out.println("Canais depois da criação: " + listRep2.getChannelsList());
            System.out.println("Bot Java inscrito no canal '" + desiredChannel + "'.");

            Thread listenerThread = new Thread(() ->
                    listenSubscriptions(context, subSocket, running, logicalClock)
            );
            listenerThread.setDaemon(true);
            listenerThread.start();

            System.out.println("Aguardando estabilização da inscrição no tópico...");
            Thread.sleep(3000);

            // Garante que o canal existe antes de publicar
            Envelope ensureChannelReq = ProtocolUtil.makeMessage(
                "CREATE_CHANNEL_REQ",
                "",
                desiredChannel,
                false,
                "",
                null,
                null,
                "",
                0,
                "",
                0,
                null,
                0
        );

        Envelope ensureChannelRep = sendAndReceive(reqSocket, ensureChannelReq, logicalClock);

        if (ensureChannelRep.getSuccess()) {
            System.out.println("Canal '" + desiredChannel + "' garantido/criado com sucesso.");
        } else {
            System.out.println("Resposta ao garantir canal: " + ensureChannelRep.getErrorMessage());
        }

        for (int i = 0; i < 2; i++) {
            Envelope publishReq = ProtocolUtil.makeMessage(
                "PUBLISH_REQ",
                username,
                desiredChannel,
                false,
                "",
                null,
                null,
                "Mensagem Java da Parte 3 - envio " + (i + 1) + ".",
                0,
                "",
                0,
                null,
                0
        );

        Envelope publishRep = sendAndReceive(reqSocket, publishReq, logicalClock);

        if (!publishRep.getSuccess()) {
        System.out.println("Erro ao publicar mensagem: " + publishRep.getErrorMessage());
        return;
    }

    System.out.println("Mensagem Java " + (i + 1) + " publicada com sucesso.");
    Thread.sleep(1000);
}

            

            for (int i = 0; i < 2; i++) {
                Envelope publishReq = ProtocolUtil.makeMessage(
                        "PUBLISH_REQ",
                        username,
                        desiredChannel,
                        false,
                        "",
                        null,
                        null,
                        "Mensagem Java da Parte 3 - envio " + (i + 1) + ".",
                        0,
                        "",
                        0,
                        null,
                        0
                );

                Envelope publishRep = sendAndReceive(reqSocket, publishReq, logicalClock);

                if (!publishRep.getSuccess()) {
                    System.out.println("Erro ao publicar mensagem: " + publishRep.getErrorMessage());
                    return;
                }

                System.out.println("Mensagem Java " + (i + 1) + " publicada com sucesso.");
                Thread.sleep(1000);
            }

            System.out.println("Aguardando recebimento via Pub/Sub...");
            Thread.sleep(8000);

            running.set(false);
            subSocket.close();
            reqSocket.close();

            System.out.println("Fluxo Java da Parte 3 finalizado.");
        }
    }
}