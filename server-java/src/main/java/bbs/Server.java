package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.Instant;

public class Server {

    private static Envelope sendReferenceRequest(ZMQ.Socket referenceSocket, Envelope msg) throws Exception {
        referenceSocket.send(msg.toByteArray(), 0);

        byte[] rawResponse = referenceSocket.recv(0);
        return Envelope.parseFrom(rawResponse);
    }

    private static long nowTimestamp() {
        return Instant.now().getEpochSecond();
    }

    private static long getAdjustedPhysicalTime(long offset) {
        return nowTimestamp() + offset;
    }

    private static int requestRank(ZMQ.Socket referenceSocket, String serverName) throws Exception {
        Envelope req = ProtocolUtil.makeMessage(
                "RANK_REQ", "", "", false,
                "", null, null, "",
                0, serverName, 0, null, 0
        );

        Envelope rep = sendReferenceRequest(referenceSocket, req);

        if (!rep.getSuccess()) {
            System.out.println("Erro ao obter rank: " + rep.getErrorMessage());
            return 0;
        }

        System.out.println("Servidor Java recebeu rank " + rep.getServerRank());
        return rep.getServerRank();
    }

    private static long sendHeartbeat(ZMQ.Socket referenceSocket, String serverName) throws Exception {
        Envelope req = ProtocolUtil.makeMessage(
                "HEARTBEAT_REQ", "", "", false,
                "", null, null, "",
                0, serverName, 0, null, 0
        );

        Envelope rep = sendReferenceRequest(referenceSocket, req);

        if (!rep.getSuccess()) {
            System.out.println("Erro no heartbeat Java: " + rep.getErrorMessage());
            return 0;
        }

        long localTime = nowTimestamp();
        long offset = rep.getPhysicalTime() - localTime;

        System.out.println(
                "Heartbeat Java OK | servidor=" + serverName +
                        " | tempo_referencia=" + rep.getPhysicalTime() +
                        " | tempo_local=" + localTime +
                        " | offset=" + offset
        );

        return offset;
    }

    public static void main(String[] args) throws Exception {
        String brokerAddress = System.getenv().getOrDefault("BROKER_ADDRESS", "tcp://broker:5555");
        String pubsubAddress = System.getenv().getOrDefault("PUBSUB_ADDRESS", "tcp://pubsub-proxy:5557");
        String referenceAddress = System.getenv().getOrDefault("REFERENCE_ADDRESS", "tcp://reference-service:5560");
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "server-java");
        String dbPath = System.getenv().getOrDefault("SERVER_DB_PATH", "data/server-java.db");

        Storage storage = new Storage(dbPath);
        LogicalClock logicalClock = new LogicalClock();

        int clientMessageCount = 0;
        long physicalClockOffset = 0;

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.connect(brokerAddress);

            ZMQ.Socket pubSocket = context.createSocket(SocketType.PUB);
            pubSocket.connect(pubsubAddress);

            ZMQ.Socket referenceSocket = context.createSocket(SocketType.REQ);
            referenceSocket.connect(referenceAddress);

            int serverRank = requestRank(referenceSocket, serverName);

            System.out.println("Servidor Java conectado ao broker em " + brokerAddress);
            System.out.println("Servidor Java conectado ao proxy Pub/Sub em " + pubsubAddress);
            System.out.println("Servidor Java conectado ao serviço de referência em " + referenceAddress);
            System.out.println("Servidor Java " + serverName + " com rank " + serverRank);
            System.out.println("Banco Java em " + dbPath);

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = socket.recv(0);

                Envelope incoming = Envelope.parseFrom(raw);

                logicalClock.update(incoming.getLogicalClock());
                clientMessageCount++;

                Printer.printMessage("RECEBIDA", incoming);
                System.out.println("Relógio lógico local do servidor Java após receber: " + logicalClock.getValue());
                System.out.println("Relógio físico ajustado Java: " + getAdjustedPhysicalTime(physicalClockOffset));

                if (clientMessageCount % 10 == 0) {
                    physicalClockOffset = sendHeartbeat(referenceSocket, serverName);
                }

                Envelope response;

                switch (incoming.getType()) {
                    case "LOGIN_REQ" -> response = Handlers.handleLogin(incoming, storage);
                    case "LIST_CHANNELS_REQ" -> response = Handlers.handleListChannels(incoming, storage);
                    case "CREATE_CHANNEL_REQ" -> response = Handlers.handleCreateChannel(incoming, storage);
                    case "PUBLISH_REQ" -> response = Handlers.handlePublish(incoming, storage, pubSocket, logicalClock);
                    default -> response = Handlers.handleUnknown(incoming);
                }

                logicalClock.tick();

                response = response.toBuilder()
                        .setLogicalClock(logicalClock.getValue())
                        .setServerName(serverName)
                        .setServerRank(serverRank)
                        .setPhysicalTime(getAdjustedPhysicalTime(physicalClockOffset))
                        .build();

                Printer.printMessage("ENVIADA", response);
                System.out.println("Relógio lógico local do servidor Java após enviar: " + logicalClock.getValue());

                socket.send(response.toByteArray(), 0);
            }
        }
    }
}