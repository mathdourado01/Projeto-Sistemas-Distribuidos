package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
                "RANK_REQ",
                "",
                "",
                false,
                "",
                null,
                null,
                "",
                0,
                serverName,
                0,
                null,
                0
        );

        Envelope rep = sendReferenceRequest(referenceSocket, req);

        if (!rep.getSuccess()) {
            System.out.println("Erro ao obter rank Java: " + rep.getErrorMessage());
            return 0;
        }

        System.out.println("Servidor Java recebeu rank " + rep.getServerRank());
        return rep.getServerRank();
    }

    private static void sendHeartbeat(ZMQ.Socket referenceSocket, String serverName) throws Exception {
        Envelope req = ProtocolUtil.makeMessage(
                "HEARTBEAT_REQ",
                "",
                "",
                false,
                "",
                null,
                null,
                "",
                0,
                serverName,
                0,
                null,
                0
        );

        Envelope rep = sendReferenceRequest(referenceSocket, req);

        if (!rep.getSuccess()) {
            System.out.println("Erro no heartbeat Java: " + rep.getErrorMessage());
            return;
        }

        System.out.println("Heartbeat Java OK | servidor=" + serverName);
    }

    private static Map<String, String> parseKnownServers(String rawValue) {
        Map<String, String> servers = new HashMap<>();

        if (rawValue == null || rawValue.isBlank()) {
            return servers;
        }

        String[] pairs = rawValue.split(",");

        for (String pair : pairs) {
            if (!pair.contains("=")) {
                continue;
            }

            String[] parts = pair.split("=", 2);
            servers.put(parts[0].trim(), parts[1].trim());
        }

        return servers;
    }

    private static Map<String, Integer> parseRankList(Envelope listResponse) {
        Map<String, Integer> ranks = new HashMap<>();

        for (String item : listResponse.getServersList()) {
            if (!item.contains(":")) {
                continue;
            }

            String[] parts = item.split(":", 2);

            try {
                ranks.put(parts[0], Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }

        return ranks;
    }

    private static Envelope requestServerList(ZMQ.Socket referenceSocket) throws Exception {
        Envelope req = ProtocolUtil.makeMessage("LIST_SERVERS_REQ");
        return sendReferenceRequest(referenceSocket, req);
    }

    private static String chooseCoordinatorByRank(Map<String, Integer> ranks) {
        String coordinator = "";
        int bestRank = Integer.MAX_VALUE;

        for (Map.Entry<String, Integer> entry : ranks.entrySet()) {
            if (entry.getValue() < bestRank) {
                bestRank = entry.getValue();
                coordinator = entry.getKey();
            }
        }

        return coordinator;
    }

    private static Envelope sendDirectRequest(String address, Envelope msg, int timeoutMs) {
        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REQ);
            socket.connect(address);
            socket.setReceiveTimeOut(timeoutMs);
            socket.setSendTimeOut(timeoutMs);

            socket.send(msg.toByteArray(), 0);

            byte[] rawResponse = socket.recv(0);

            if (rawResponse == null) {
                return null;
            }

            return Envelope.parseFrom(rawResponse);

        } catch (Exception e) {
            return null;
        }
    }

    private static Long requestTimeFromCoordinator(
            String coordinatorName,
            Map<String, String> knownServers,
            String serverName
    ) {
        String coordinatorAddress = knownServers.get(coordinatorName);

        if (coordinatorAddress == null || coordinatorAddress.isBlank()) {
            System.out.println("Endereço do coordenador Java não encontrado: " + coordinatorName);
            return null;
        }

        Envelope req = ProtocolUtil.makeMessage(
                "TIME_REQ",
                "",
                "",
                false,
                "",
                null,
                null,
                "",
                0,
                serverName,
                0,
                null,
                0
        );

        Envelope rep = sendDirectRequest(coordinatorAddress, req, 3000);

        if (rep == null) {
            System.out.println("Coordenador '" + coordinatorName + "' não respondeu ao TIME_REQ Java.");
            return null;
        }

        if (!rep.getSuccess()) {
            System.out.println("Erro ao pedir hora ao coordenador Java: " + rep.getErrorMessage());
            return null;
        }

        System.out.println(
                "Hora recebida do coordenador " + coordinatorName +
                        " no Java: " + rep.getPhysicalTime()
        );

        return rep.getPhysicalTime();
    }

    private static void publishCoordinatorAnnouncement(
            ZMQ.Socket pubSocket,
            String coordinatorName,
            String serverName
    ) {
        Envelope msg = ProtocolUtil.makeMessage(
                "COORDINATOR_ANNOUNCE",
                "",
                "",
                true,
                "",
                null,
                null,
                coordinatorName,
                0,
                serverName,
                0,
                null,
                0
        );

        pubSocket.sendMore("servers");
        pubSocket.send(msg.toByteArray(), 0);

        System.out.println("Coordenador anunciado no tópico 'servers' pelo Java: " + coordinatorName);
    }

    private static String runElection(
            Map<String, String> knownServers,
            Map<String, Integer> ranks,
            String serverName,
            int serverRank,
            ZMQ.Socket pubSocket
    ) {
        System.out.println("Java iniciando eleição de coordenador...");

        Map<String, Integer> aliveServers = new HashMap<>();
        aliveServers.put(serverName, serverRank);

        for (Map.Entry<String, String> entry : knownServers.entrySet()) {
            String otherName = entry.getKey();
            String address = entry.getValue();

            if (otherName.equals(serverName)) {
                continue;
            }

            Envelope req = ProtocolUtil.makeMessage(
                    "ELECTION_REQ",
                    "",
                    "",
                    false,
                    "",
                    null,
                    null,
                    "",
                    0,
                    serverName,
                    serverRank,
                    null,
                    0
            );

            Envelope rep = sendDirectRequest(address, req, 2000);

            if (rep == null) {
                System.out.println("Servidor " + otherName + " não respondeu à eleição Java.");
                continue;
            }

            if (rep.getSuccess()) {
                System.out.println("Servidor " + otherName + " respondeu OK à eleição Java.");
                int rank = ranks.getOrDefault(otherName, rep.getServerRank());
                aliveServers.put(otherName, rank);
            }
        }

        String newCoordinator = chooseCoordinatorByRank(aliveServers);

        if (newCoordinator.isBlank()) {
            newCoordinator = serverName;
        }

        System.out.println("Novo coordenador eleito pelo Java: " + newCoordinator);

        publishCoordinatorAnnouncement(pubSocket, newCoordinator, serverName);

        return newCoordinator;
    }

    private static Thread startSyncListener(
            String bindAddress,
            String serverName,
            int serverRank,
            LogicalClock logicalClock,
            AtomicLong physicalClockOffset,
            AtomicBoolean running
    ) {
        Thread thread = new Thread(() -> {
            try (ZContext context = new ZContext()) {
                ZMQ.Socket socket = context.createSocket(SocketType.REP);
                socket.bind(bindAddress);
                socket.setReceiveTimeOut(500);

                System.out.println("Servidor Java escutando sincronização em " + bindAddress);

                while (running.get()) {
                    byte[] rawMessage = socket.recv(0);

                    if (rawMessage == null) {
                        continue;
                    }

                    Envelope incoming = Envelope.parseFrom(rawMessage);

                    logicalClock.update(incoming.getLogicalClock());

                    System.out.println("============================================================");
                    System.out.println("[SYNC JAVA RECEBIDA]");
                    System.out.println("type        : " + incoming.getType());
                    System.out.println("server_name : " + incoming.getServerName());
                    System.out.println("============================================================");

                    Envelope response;

                    if (incoming.getType().equals("TIME_REQ")) {
                        logicalClock.tick();

                        response = ProtocolUtil.makeMessage(
                                "TIME_REP",
                                "",
                                "",
                                true,
                                "",
                                null,
                                incoming.getRequestId(),
                                "",
                                logicalClock.getValue(),
                                serverName,
                                serverRank,
                                null,
                                getAdjustedPhysicalTime(physicalClockOffset.get())
                        );

                    } else if (incoming.getType().equals("ELECTION_REQ")) {
                        logicalClock.tick();

                        response = ProtocolUtil.makeMessage(
                                "ELECTION_REP",
                                "",
                                "",
                                true,
                                "",
                                null,
                                incoming.getRequestId(),
                                "",
                                logicalClock.getValue(),
                                serverName,
                                serverRank,
                                null,
                                getAdjustedPhysicalTime(physicalClockOffset.get())
                        );

                    } else {
                        logicalClock.tick();

                        response = ProtocolUtil.makeMessage(
                                "ERROR_REP",
                                "",
                                "",
                                false,
                                "Tipo desconhecido na sincronização Java: " + incoming.getType(),
                                null,
                                incoming.getRequestId(),
                                "",
                                logicalClock.getValue(),
                                serverName,
                                serverRank,
                                null,
                                getAdjustedPhysicalTime(physicalClockOffset.get())
                        );
                    }

                    System.out.println("============================================================");
                    System.out.println("[SYNC JAVA ENVIADA]");
                    System.out.println("type          : " + response.getType());
                    System.out.println("server_name   : " + response.getServerName());
                    System.out.println("server_rank   : " + response.getServerRank());
                    System.out.println("physical_time : " + response.getPhysicalTime());
                    System.out.println("============================================================");

                    socket.send(response.toByteArray(), 0);
                }

                socket.close();

            } catch (Exception e) {
                System.out.println("Erro no listener de sincronização Java: " + e.getMessage());
            }
        });

        thread.setDaemon(true);
        thread.start();

        return thread;
    }

    private static Thread startCoordinatorSubscriptionListener(
            String pubsubProxyAddress,
            AtomicReference<String> currentCoordinator,
            AtomicBoolean running
    ) {
        Thread thread = new Thread(() -> {
            try (ZContext context = new ZContext()) {
                ZMQ.Socket subSocket = context.createSocket(SocketType.SUB);
                subSocket.subscribe("servers".getBytes());
                subSocket.connect(pubsubProxyAddress);
                subSocket.setReceiveTimeOut(500);

                System.out.println("Servidor Java inscrito no tópico 'servers'.");

                while (running.get()) {
                    byte[] topicBytes = subSocket.recv(0);

                    if (topicBytes == null) {
                        continue;
                    }

                    byte[] rawMessage = subSocket.recv(0);

                    if (rawMessage == null) {
                        continue;
                    }

                    Envelope incoming = Envelope.parseFrom(rawMessage);

                    if (incoming.getType().equals("COORDINATOR_ANNOUNCE")) {
                        currentCoordinator.set(incoming.getMessageText());
                        System.out.println(
                                "Coordenador atualizado via Pub/Sub no Java: "
                                        + incoming.getMessageText()
                        );
                    }
                }

                subSocket.close();

            } catch (Exception e) {
                System.out.println("Erro no listener de coordenador Java: " + e.getMessage());
            }
        });

        thread.setDaemon(true);
        thread.start();

        return thread;
    }

    public static void main(String[] args) throws Exception {
        String brokerAddress = System.getenv().getOrDefault("BROKER_ADDRESS", "tcp://broker:5555");
        String pubsubAddress = System.getenv().getOrDefault("PUBSUB_ADDRESS", "tcp://pubsub-proxy:5557");
        String pubsubSubAddress = System.getenv().getOrDefault("PUBSUB_PROXY_ADDRESS", "tcp://pubsub-proxy:5558");
        String referenceAddress = System.getenv().getOrDefault("REFERENCE_ADDRESS", "tcp://reference-service:5560");
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "server-java");
        String dbPath = System.getenv().getOrDefault("SERVER_DB_PATH", "data/server-java.db");
        String syncBind = System.getenv().getOrDefault("SERVER_SYNC_BIND", "tcp://0.0.0.0:5572");
        String knownServersRaw = System.getenv().getOrDefault("KNOWN_SERVERS", "");

        Map<String, String> knownServers = parseKnownServers(knownServersRaw);

        Storage storage = new Storage(dbPath);
        LogicalClock logicalClock = new LogicalClock();

        int clientMessageCount = 0;
        AtomicLong physicalClockOffset = new AtomicLong(0);
        AtomicReference<String> currentCoordinator = new AtomicReference<>("");
        AtomicBoolean running = new AtomicBoolean(true);

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.connect(brokerAddress);

            ZMQ.Socket pubSocket = context.createSocket(SocketType.PUB);
            pubSocket.connect(pubsubAddress);

            ZMQ.Socket referenceSocket = context.createSocket(SocketType.REQ);
            referenceSocket.connect(referenceAddress);

            int serverRank = requestRank(referenceSocket, serverName);

            Envelope serverListResponse = requestServerList(referenceSocket);
            Map<String, Integer> ranks = parseRankList(serverListResponse);

            if (!ranks.containsKey(serverName)) {
                ranks.put(serverName, serverRank);
            }

            String initialCoordinator = chooseCoordinatorByRank(ranks);

            if (initialCoordinator.isBlank()) {
                initialCoordinator = serverName;
            }

            currentCoordinator.set(initialCoordinator);

            startSyncListener(
                    syncBind,
                    serverName,
                    serverRank,
                    logicalClock,
                    physicalClockOffset,
                    running
            );

            startCoordinatorSubscriptionListener(
                    pubsubSubAddress,
                    currentCoordinator,
                    running
            );

            Thread.sleep(1000);

            if (currentCoordinator.get().equals(serverName)) {
                publishCoordinatorAnnouncement(pubSocket, serverName, serverName);
            }

            System.out.println("Servidor Java conectado ao broker em " + brokerAddress);
            System.out.println("Servidor Java conectado ao proxy Pub/Sub em " + pubsubAddress);
            System.out.println("Servidor Java conectado ao serviço de referência em " + referenceAddress);
            System.out.println("Servidor Java " + serverName + " com rank " + serverRank);
            System.out.println("Coordenador atual no Java: " + currentCoordinator.get());
            System.out.println("Banco Java em " + dbPath);

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = socket.recv(0);

                Envelope incoming = Envelope.parseFrom(raw);

                logicalClock.update(incoming.getLogicalClock());
                clientMessageCount++;

                Printer.printMessage("RECEBIDA", incoming);
                System.out.println("Relógio lógico local do servidor Java após receber: " + logicalClock.getValue());
                System.out.println("Relógio físico ajustado Java: " + getAdjustedPhysicalTime(physicalClockOffset.get()));

                if (clientMessageCount % 10 == 0) {
                    sendHeartbeat(referenceSocket, serverName);
                }

                if (clientMessageCount % 15 == 0) {
                    String coordinatorName = currentCoordinator.get();

                    if (coordinatorName.equals(serverName)) {
                        System.out.println("Este servidor Java é o coordenador. Não precisa pedir hora.");
                    } else {
                        Long coordinatorTime = requestTimeFromCoordinator(
                                coordinatorName,
                                knownServers,
                                serverName
                        );

                        if (coordinatorTime == null) {
                            String newCoordinator = runElection(
                                    knownServers,
                                    ranks,
                                    serverName,
                                    serverRank,
                                    pubSocket
                            );

                            currentCoordinator.set(newCoordinator);
                        } else {
                            long localTime = nowTimestamp();
                            physicalClockOffset.set(coordinatorTime - localTime);

                            System.out.println(
                                    "Relógio físico Java sincronizado com coordenador "
                                            + coordinatorName
                                            + ". offset="
                                            + physicalClockOffset.get()
                            );
                        }
                    }
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
                        .setPhysicalTime(getAdjustedPhysicalTime(physicalClockOffset.get()))
                        .build();

                Printer.printMessage("ENVIADA", response);
                System.out.println("Relógio lógico local do servidor Java após enviar: " + logicalClock.getValue());

                socket.send(response.toByteArray(), 0);
            }

            running.set(false);
        }
    }
}