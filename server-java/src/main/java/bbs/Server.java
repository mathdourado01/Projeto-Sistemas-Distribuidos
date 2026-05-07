package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.ZMQ;

import java.util.HashMap;
import java.util.Map;

public class Server {

    private static int clientMessageCount = 0;
    private static long physicalOffset = 0;

    private static final String serverName = System.getenv().getOrDefault("SERVER_NAME", "server-java");
    private static int serverRank = 0;

    private static final Map<String, String> knownServers = parseKnownServers(
            System.getenv().getOrDefault("KNOWN_SERVERS", "")
    );

    private static String currentCoordinator = "";

    private static long getPhysicalTime() {
        return (System.currentTimeMillis() / 1000) + physicalOffset;
    }

    private static Envelope sendRequest(ZMQ.Socket socket, Envelope msg) throws Exception {
        socket.send(msg.toByteArray());
        byte[] reply = socket.recv();
        return Envelope.parseFrom(reply);
    }

    private static int requestRank(ZMQ.Socket refSocket) throws Exception {
        Envelope req = Envelope.newBuilder()
                .setType("RANK_REQ")
                .setServerName(serverName)
                .build();

        Envelope rep = sendRequest(refSocket, req);

        if (!rep.getSuccess()) {
            System.out.println("Erro ao obter rank: " + rep.getErrorMessage());
            return 0;
        }

        return rep.getServerRank();
    }

    private static Map<String, Integer> getRanks(ZMQ.Socket refSocket) throws Exception {
        Envelope req = Envelope.newBuilder()
                .setType("LIST_SERVERS_REQ")
                .build();

        Envelope rep = sendRequest(refSocket, req);

        Map<String, Integer> result = new HashMap<>();

        for (String s : rep.getServersList()) {
            String[] parts = s.split(":", 2);

            if (parts.length != 2) {
                continue;
            }

            try {
                result.put(parts[0], Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }

        return result;
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

    private static String chooseCoordinator(Map<String, Integer> ranks) {
        return ranks.entrySet()
                .stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(serverName);
    }

    private static Long requestTimeFromCoordinator(String coordinator, ZMQ.Context ctx) {
        String address = knownServers.get(coordinator);

        if (address == null || address.isBlank()) {
            System.out.println("Endereço do coordenador não encontrado: " + coordinator);
            return null;
        }

        ZMQ.Socket timeSocket = ctx.socket(ZMQ.REQ);
        timeSocket.setReceiveTimeOut(3000);
        timeSocket.setSendTimeOut(3000);
        timeSocket.connect(address);

        try {
            Envelope req = Envelope.newBuilder()
                    .setType("TIME_REQ")
                    .setServerName(serverName)
                    .build();

            timeSocket.send(req.toByteArray());

            byte[] reply = timeSocket.recv();

            if (reply == null) {
                return null;
            }

            Envelope rep = Envelope.parseFrom(reply);

            if (!rep.getSuccess()) {
                return null;
            }

            return rep.getPhysicalTime();

        } catch (Exception e) {
            return null;

        } finally {
            timeSocket.close();
        }
    }

    private static void publishCoordinatorAnnouncement(
            ZMQ.Socket pubSocket,
            String coordinatorName,
            LogicalClock logicalClock
    ) {
        logicalClock.tick();

        Envelope announce = ProtocolUtil.makeMessage(
                "COORDINATOR_ANNOUNCE",
                "",
                "",
                true,
                "",
                null,
                null,
                coordinatorName,
                logicalClock.getValue(),
                serverName,
                serverRank,
                null,
                getPhysicalTime()
        );

        pubSocket.sendMore("servers");
        pubSocket.send(announce.toByteArray(), 0);

        System.out.println("Coordenador anunciado no tópico 'servers': " + coordinatorName);
    }

    private static String runElection(
            ZMQ.Context ctx,
            ZMQ.Socket pubSocket,
            Map<String, Integer> ranks,
            LogicalClock logicalClock
    ) {
        System.out.println("Iniciando eleição de coordenador...");

        Map<String, Integer> alive = new HashMap<>();
        alive.put(serverName, serverRank);

        for (String name : knownServers.keySet()) {
            if (name.equals(serverName)) {
                continue;
            }

            String address = knownServers.get(name);

            ZMQ.Socket electionSocket = ctx.socket(ZMQ.REQ);
            electionSocket.setReceiveTimeOut(2000);
            electionSocket.setSendTimeOut(2000);
            electionSocket.connect(address);

            try {
                logicalClock.tick();

                Envelope req = ProtocolUtil.makeMessage(
                        "ELECTION_REQ",
                        "",
                        "",
                        true,
                        "",
                        null,
                        null,
                        "",
                        logicalClock.getValue(),
                        serverName,
                        serverRank,
                        null,
                        getPhysicalTime()
                );

                electionSocket.send(req.toByteArray());

                byte[] reply = electionSocket.recv();

                if (reply == null) {
                    System.out.println("Servidor " + name + " não respondeu à eleição.");
                    continue;
                }

                Envelope rep = Envelope.parseFrom(reply);

                if (rep.getSuccess()) {
                    int rank = ranks.getOrDefault(name, rep.getServerRank());
                    alive.put(name, rank);
                    System.out.println("Servidor " + name + " respondeu OK à eleição.");
                }

            } catch (Exception ignored) {
            } finally {
                electionSocket.close();
            }
        }

        String newCoordinator = chooseCoordinator(alive);

        if (newCoordinator == null || newCoordinator.isBlank()) {
            newCoordinator = serverName;
        }

        publishCoordinatorAnnouncement(pubSocket, newCoordinator, logicalClock);

        System.out.println("Novo coordenador eleito: " + newCoordinator);

        return newCoordinator;
    }

    private static void startSyncListener(
            String bindAddress,
            LogicalClock logicalClock
    ) {
        Thread thread = new Thread(() -> {
            ZMQ.Context context = ZMQ.context(1);
            ZMQ.Socket syncSocket = context.socket(ZMQ.REP);
            syncSocket.bind(bindAddress);

            System.out.println("Servidor Java escutando sincronização em " + bindAddress);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] rawMessage = syncSocket.recv();

                    if (rawMessage == null) {
                        continue;
                    }

                    Envelope incoming = Envelope.parseFrom(rawMessage);
                    logicalClock.update(incoming.getLogicalClock());

                    System.out.println("============================================================");
                    System.out.println("[SYNC RECEBIDA]");
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
                                getPhysicalTime()
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
                                getPhysicalTime()
                        );

                    } else {
                        logicalClock.tick();

                        response = ProtocolUtil.makeMessage(
                                "ERROR_REP",
                                "",
                                "",
                                false,
                                "Tipo desconhecido na sincronização: " + incoming.getType(),
                                null,
                                incoming.getRequestId(),
                                "",
                                logicalClock.getValue(),
                                serverName,
                                serverRank,
                                null,
                                getPhysicalTime()
                        );
                    }

                    System.out.println("============================================================");
                    System.out.println("[SYNC ENVIADA]");
                    System.out.println("type          : " + response.getType());
                    System.out.println("server_name   : " + response.getServerName());
                    System.out.println("server_rank   : " + response.getServerRank());
                    System.out.println("physical_time : " + response.getPhysicalTime());
                    System.out.println("============================================================");

                    syncSocket.send(response.toByteArray(), 0);

                } catch (Exception e) {
                    System.out.println("Erro no listener de sincronização Java: " + e.getMessage());
                }
            }

            syncSocket.close();
            context.close();
        });

        thread.setDaemon(true);
        thread.start();
    }

    private static void startCoordinatorSubscriptionListener(
            String pubsubProxyAddress
    ) {
        Thread thread = new Thread(() -> {
            ZMQ.Context context = ZMQ.context(1);
            ZMQ.Socket subSocket = context.socket(ZMQ.SUB);

            subSocket.connect(pubsubProxyAddress);
            subSocket.subscribe("servers".getBytes(ZMQ.CHARSET));

            System.out.println("Servidor Java inscrito no tópico 'servers'.");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] topic = subSocket.recv();

                    if (topic == null) {
                        continue;
                    }

                    byte[] rawMessage = subSocket.recv();

                    if (rawMessage == null) {
                        continue;
                    }

                    Envelope incoming = Envelope.parseFrom(rawMessage);

                    if (incoming.getType().equals("COORDINATOR_ANNOUNCE")) {
                        currentCoordinator = incoming.getMessageText();
                        System.out.println("Coordenador atualizado via Pub/Sub no Java: " + currentCoordinator);
                    }

                } catch (Exception e) {
                    System.out.println("Erro no listener de coordenador Java: " + e.getMessage());
                }
            }

            subSocket.close();
            context.close();
        });

        thread.setDaemon(true);
        thread.start();
    }

    private static void startReplicationListener(
            String pubsubProxyAddress,
            Storage storage,
            LogicalClock logicalClock
    ) {
        Thread thread = new Thread(() -> {
            ZMQ.Context context = ZMQ.context(1);
            ZMQ.Socket subSocket = context.socket(ZMQ.SUB);

            subSocket.connect(pubsubProxyAddress);
            subSocket.subscribe("replication".getBytes(ZMQ.CHARSET));

            System.out.println("Servidor Java inscrito no tópico 'replication'.");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] topic = subSocket.recv();

                    if (topic == null) {
                        continue;
                    }

                    byte[] rawMessage = subSocket.recv();

                    if (rawMessage == null) {
                        continue;
                    }

                    Envelope incoming = Envelope.parseFrom(rawMessage);

                    if (incoming.getServerName().equals(serverName)) {
                        continue;
                    }

                    logicalClock.update(incoming.getLogicalClock());

                    if (incoming.getType().equals("REPLICATION_CHANNEL")) {
                        String channelName = incoming.getChannelName().trim();

                        if (!channelName.isBlank()) {
                            storage.createChannel(channelName, incoming.getTimestamp());
                            System.out.println(
                                    "Réplica de canal recebida no Java | canal="
                                            + channelName
                                            + " | origem="
                                            + incoming.getServerName()
                            );
                        }

                    } else if (incoming.getType().equals("REPLICATION_MESSAGE")) {
                        String username = incoming.getUsername().trim();
                        String channelName = incoming.getChannelName().trim();
                        String messageText = incoming.getMessageText().trim();

                        if (!channelName.isBlank() && !storage.channelExists(channelName)) {
                            storage.createChannel(channelName, incoming.getTimestamp());
                        }

                        if (!username.isBlank() && !channelName.isBlank() && !messageText.isBlank()) {
                            storage.saveMessage(
                                    username,
                                    channelName,
                                    messageText,
                                    incoming.getTimestamp(),
                                    incoming.getRequestId()
                            );

                            System.out.println(
                                    "Réplica de mensagem recebida no Java | id="
                                            + incoming.getRequestId()
                                            + " | canal="
                                            + channelName
                                            + " | origem="
                                            + incoming.getServerName()
                            );
                        }
                    }

                } catch (Exception e) {
                    System.out.println("Erro no listener de replicação Java: " + e.getMessage());
                }
            }

            subSocket.close();
            context.close();
        });

        thread.setDaemon(true);
        thread.start();
    }

    public static void main(String[] args) throws Exception {
        String brokerAddress = System.getenv().getOrDefault("BROKER_ADDRESS", "tcp://broker:5555");
        String pubsubAddress = System.getenv().getOrDefault("PUBSUB_ADDRESS", "tcp://pubsub-proxy:5557");
        String pubsubProxyAddress = System.getenv().getOrDefault("PUBSUB_PROXY_ADDRESS", "tcp://pubsub-proxy:5558");
        String referenceAddress = System.getenv().getOrDefault("REFERENCE_ADDRESS", "tcp://reference-service:5560");
        String dbPath = System.getenv().getOrDefault("SERVER_DB_PATH", "data/server-java.db");
        String syncBind = System.getenv().getOrDefault("SERVER_SYNC_BIND", "tcp://0.0.0.0:5572");

        ZMQ.Context context = ZMQ.context(1);

        ZMQ.Socket socket = context.socket(ZMQ.REP);
        socket.connect(brokerAddress);

        ZMQ.Socket pubSocket = context.socket(ZMQ.PUB);
        pubSocket.connect(pubsubAddress);

        ZMQ.Socket refSocket = context.socket(ZMQ.REQ);
        refSocket.connect(referenceAddress);

        Storage storage = new Storage(dbPath);
        LogicalClock logicalClock = new LogicalClock();

        serverRank = requestRank(refSocket);

        Map<String, Integer> ranks = getRanks(refSocket);

        if (!ranks.containsKey(serverName)) {
            ranks.put(serverName, serverRank);
        }

        currentCoordinator = chooseCoordinator(ranks);

        if (currentCoordinator == null || currentCoordinator.isBlank()) {
            currentCoordinator = serverName;
        }

        startSyncListener(syncBind, logicalClock);
        startCoordinatorSubscriptionListener(pubsubProxyAddress);
        startReplicationListener(pubsubProxyAddress, storage, logicalClock);

        Thread.sleep(1000);

        if (currentCoordinator.equals(serverName)) {
            publishCoordinatorAnnouncement(pubSocket, serverName, logicalClock);
        }

        System.out.println("Servidor Java conectado ao broker em " + brokerAddress);
        System.out.println("Servidor Java conectado ao proxy Pub/Sub em " + pubsubAddress);
        System.out.println("Servidor Java conectado ao serviço de referência em " + referenceAddress);
        System.out.println("Servidor Java " + serverName + " com rank " + serverRank);
        System.out.println("Coordenador atual no Java: " + currentCoordinator);
        System.out.println("Banco Java em " + dbPath);

        while (true) {
            byte[] rawMessage = socket.recv();

            Envelope incoming = Envelope.parseFrom(rawMessage);

            logicalClock.update(incoming.getLogicalClock());
            clientMessageCount++;

            Printer.printMessage("RECEBIDA", incoming);
            System.out.println("Relógio lógico local do servidor Java após receber: " + logicalClock.getValue());
            System.out.println("Relógio físico ajustado Java: " + getPhysicalTime());

            if (clientMessageCount % 15 == 0) {
                if (currentCoordinator.equals(serverName)) {
                    System.out.println("Este servidor Java é o coordenador. Não precisa pedir hora.");
                } else {
                    Long coordinatorTime = requestTimeFromCoordinator(currentCoordinator, context);

                    if (coordinatorTime == null) {
                        System.out.println("Coordenador não respondeu. Iniciando eleição no Java...");
                        currentCoordinator = runElection(context, pubSocket, ranks, logicalClock);
                    } else {
                        long localTime = System.currentTimeMillis() / 1000;
                        physicalOffset = coordinatorTime - localTime;
                        System.out.println(
                                "Relógio físico Java sincronizado com coordenador "
                                        + currentCoordinator
                                        + ". offset="
                                        + physicalOffset
                        );
                    }
                }
            }

            Envelope response;

            switch (incoming.getType()) {
                case "LOGIN_REQ" -> response = Handlers.handleLogin(incoming, storage);

                case "LIST_CHANNELS_REQ" -> response = Handlers.handleListChannels(incoming, storage);

                case "CREATE_CHANNEL_REQ" -> response = Handlers.handleCreateChannel(
                        incoming,
                        storage,
                        pubSocket,
                        logicalClock,
                        serverName,
                        serverRank,
                        getPhysicalTime()
                );

                case "PUBLISH_REQ" -> response = Handlers.handlePublish(
                        incoming,
                        storage,
                        pubSocket,
                        logicalClock,
                        serverName,
                        serverRank,
                        getPhysicalTime()
                );

                default -> response = Handlers.handleUnknown(incoming);
            }

            logicalClock.tick();

            response = response.toBuilder()
                    .setLogicalClock(logicalClock.getValue())
                    .setServerName(serverName)
                    .setServerRank(serverRank)
                    .setPhysicalTime(getPhysicalTime())
                    .build();

            Printer.printMessage("ENVIADA", response);
            System.out.println("Relógio lógico local do servidor Java após enviar: " + logicalClock.getValue());

            socket.send(response.toByteArray(), 0);
        }
    }
}