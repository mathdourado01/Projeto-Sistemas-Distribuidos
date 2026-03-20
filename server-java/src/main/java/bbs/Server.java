package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

public class Server {

    public static void main(String[] args) throws Exception {
        String brokerAddress = System.getenv().getOrDefault("BROKER_ADDRESS", "tcp://127.0.0.1:5555");
        String dbPath = System.getenv().getOrDefault("SERVER_DB_PATH", "data/server-java.db");

        Storage storage = new Storage(dbPath);

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.connect(brokerAddress);

            System.out.println("Servidor Java conectado ao broker em " + brokerAddress);
            System.out.println("Banco de dados em " + dbPath);

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = socket.recv(0);
                Envelope incoming = Envelope.parseFrom(raw);

                Printer.printMessage("RECEBIDA", incoming);

                Envelope response;
                switch (incoming.getType()) {
                    case "LOGIN_REQ" -> response = Handlers.handleLogin(incoming, storage);
                    case "LIST_CHANNELS_REQ" -> response = Handlers.handleListChannels(incoming, storage);
                    case "CREATE_CHANNEL_REQ" -> response = Handlers.handleCreateChannel(incoming, storage);
                    default -> response = Handlers.handleUnknown(incoming);
                }

                Printer.printMessage("ENVIADA", response);
                socket.send(response.toByteArray(), 0);
            }
        }
    }
}