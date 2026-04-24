package bbs;

import chat.EnvelopeOuterClass.Envelope;
import org.zeromq.ZMQ;

import java.util.List;

public class Handlers {

    public static Envelope handleLogin(Envelope msg, Storage storage) {
        String username = msg.getUsername().trim();

        if (username.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "LOGIN_REP", "", "", false,
                    "Nome de usuário inválido.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        storage.saveLogin(username, msg.getTimestamp());

        return ProtocolUtil.makeMessage(
                "LOGIN_REP", username, "", true,
                "", null, msg.getRequestId(), "",
                0, "", 0, null, 0
        );
    }

    public static Envelope handleListChannels(Envelope msg, Storage storage) {
        List<String> channels = storage.listChannels();

        return ProtocolUtil.makeMessage(
                "LIST_CHANNELS_REP", "", "", true,
                "", channels, msg.getRequestId(), "",
                0, "", 0, null, 0
        );
    }

    public static Envelope handleCreateChannel(Envelope msg, Storage storage) {
        String channelName = msg.getChannelName().trim();

        if (channelName.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "CREATE_CHANNEL_REP", "", "", false,
                    "Nome do canal inválido.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        if (!channelName.matches("^[a-zA-Z0-9_-]+$")) {
            return ProtocolUtil.makeMessage(
                    "CREATE_CHANNEL_REP", "", "", false,
                    "Nome do canal deve conter apenas letras, números, '_' ou '-'.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        boolean created = storage.createChannel(channelName, msg.getTimestamp());

        if (!created) {
            return ProtocolUtil.makeMessage(
                    "CREATE_CHANNEL_REP", "", channelName, false,
                    "Canal já existe.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        return ProtocolUtil.makeMessage(
                "CREATE_CHANNEL_REP", "", channelName, true,
                "", null, msg.getRequestId(), "",
                0, "", 0, null, 0
        );
    }

    public static Envelope handlePublish(
            Envelope msg,
            Storage storage,
            ZMQ.Socket pubSocket,
            LogicalClock logicalClock
    ) {
        String username = msg.getUsername().trim();
        String channelName = msg.getChannelName().trim();
        String messageText = msg.getMessageText().trim();

        if (username.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "PUBLISH_REP", "", "", false,
                    "Usuário inválido.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        if (channelName.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "PUBLISH_REP", "", "", false,
                    "Canal inválido.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        if (!storage.channelExists(channelName)) {
            return ProtocolUtil.makeMessage(
                    "PUBLISH_REP", "", channelName, false,
                    "Canal não existe.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        if (messageText.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "PUBLISH_REP", username, channelName, false,
                    "Mensagem inválida.",
                    null, msg.getRequestId(), "",
                    0, "", 0, null, 0
            );
        }

        storage.saveMessage(username, channelName, messageText, msg.getTimestamp());

        logicalClock.tick();

        Envelope payload = ProtocolUtil.makeMessage(
                "CHANNEL_MESSAGE",
                username,
                channelName,
                true,
                "",
                null,
                null,
                messageText,
                logicalClock.getValue(),
                "",
                0,
                null,
                ProtocolUtil.nowTimestamp()
        );

        pubSocket.sendMore(channelName);
        pubSocket.send(payload.toByteArray(), 0);

        return ProtocolUtil.makeMessage(
                "PUBLISH_REP", username, channelName, true,
                "", null, msg.getRequestId(), messageText,
                0, "", 0, null, 0
        );
    }

    public static Envelope handleUnknown(Envelope msg) {
        return ProtocolUtil.makeMessage(
                "ERROR_REP", "", "", false,
                "Tipo de mensagem desconhecido: " + msg.getType(),
                null, msg.getRequestId(), "",
                0, "", 0, null, 0
        );
    }
}