package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.ZMQ;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Server {

    private static long logicalClock = 0;
    private static int clientMessageCount = 0;
    private static long physicalOffset = 0;

    private static String serverName = System.getenv().getOrDefault("SERVER_NAME", "server-java");
    private static int serverRank = 0;

    private static Map<String, String> knownServers = new HashMap<>();
    private static String currentCoordinator = "";

    private static long tick() {
        return ++logicalClock;
    }

    private static long update(long received) {
        logicalClock = Math.max(logicalClock, received);
        return logicalClock;
    }

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
        return rep.getServerRank();
    }

    private static Map<String, Integer> getRanks(ZMQ.Socket refSocket) throws Exception {
        Envelope req = Envelope.newBuilder()
                .setType("LIST_SERVERS_REQ")
                .build();

        Envelope rep = sendRequest(refSocket, req);

        Map<String, Integer> result = new HashMap<>();

        for (String s : rep.getServersList()) {
            String[] parts = s.split(":");
            result.put(parts[0], Integer.parseInt(parts[1]));
        }

        return result;
    }

    private static String chooseCoordinator(Map<String, Integer> ranks) {
        return ranks.entrySet()
                .stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(serverName);
    }

    private static Long requestTime(String coordinator, ZMQ.Context ctx) {
        String address = knownServers.get(coordinator);

        if (address == null) return null;

        ZMQ.Socket socket = ctx.socket(ZMQ.REQ);
        socket.connect(address);

        Envelope req = Envelope.newBuilder()
                .setType("TIME_REQ")
                .setServerName(serverName)
                .build();

        socket.send(req.toByteArray());

        byte[] reply = socket.recv(ZMQ.DONTWAIT);

        socket.close();

        if (reply == null) {
            return null;
        }

        try {
            Envelope rep = Envelope.parseFrom(reply);
            return rep.getPhysicalTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static void runElection(ZMQ.Context ctx, ZMQ.Socket pubSocket, Map<String, Integer> ranks) throws Exception {

        Map<String, Integer> alive = new HashMap<>();
        alive.put(serverName, serverRank);

        for (String name : knownServers.keySet()) {
            if (name.equals(serverName)) {
                continue;
            }

            ZMQ.Socket electionSocket = ctx.socket(ZMQ.REQ);
            electionSocket.connect(knownServers.get(name));

            Envelope req = Envelope.newBuilder()
                    .setType("ELECTION_REQ")
                    .setServerName(serverName)
                    .setServerRank(serverRank)
                    .build();

            electionSocket.send(req.toByteArray());

            byte[] reply = electionSocket.recv(ZMQ.DONTWAIT);

            electionSocket.close();

            if (reply == null) {
                continue;
            }

            Envelope rep;

            try {
                rep = Envelope.parseFrom(reply);
            } catch (Exception e) {
                continue;
            }

            alive.put(name, rep.getServerRank());
        }

        currentCoordinator = chooseCoordinator(alive);

        Envelope announce = Envelope.newBuilder()
                .setType("COORDINATOR_ANNOUNCE")
                .setMessageText(currentCoordinator)
                .setServerName(serverName)
                .setServerRank(serverRank)
                .build();

        pubSocket.sendMore("servers");
        pubSocket.send(announce.toByteArray());

        System.out.println("Novo coordenador eleito: " + currentCoordinator);
    }

    public static void main(String[] args) throws Exception {

        ZMQ.Context context = ZMQ.context(1);

        ZMQ.Socket socket = context.socket(ZMQ.REP);
        socket.connect("tcp://broker:5555");

        ZMQ.Socket pubSocket = context.socket(ZMQ.PUB);
        pubSocket.connect("tcp://pubsub-proxy:5557");

        ZMQ.Socket refSocket = context.socket(ZMQ.REQ);
        refSocket.connect("tcp://reference-service:5560");

        serverRank = requestRank(refSocket);

        Map<String, Integer> ranks = getRanks(refSocket);
        currentCoordinator = chooseCoordinator(ranks);

        System.out.println("Servidor Java com rank: " + serverRank);
        System.out.println("Coordenador inicial: " + currentCoordinator);

        while (true) {

            byte[] msg = socket.recv();
            Envelope incoming = Envelope.parseFrom(msg);

            update(incoming.getLogicalClock());
            clientMessageCount++;

            if (clientMessageCount % 15 == 0) {

                if (currentCoordinator.equals(serverName)) {
                    System.out.println("Sou o coordenador");
                } else {

                    Long time = requestTime(currentCoordinator, context);

                    if (time == null) {
                        System.out.println("Coordenador não respondeu. Eleição...");
                        runElection(context, pubSocket, ranks);
                    } else {
                        long local = System.currentTimeMillis() / 1000;
                        physicalOffset = time - local;
                        System.out.println("Sincronizado com coordenador");
                    }
                }
            }

            Envelope response = Envelope.newBuilder()
                    .setType("OK")
                    .setSuccess(true)
                    .setLogicalClock(tick())
                    .setServerName(serverName)
                    .setServerRank(serverRank)
                    .setPhysicalTime(getPhysicalTime())
                    .build();

            socket.send(response.toByteArray());
        }
    }
}