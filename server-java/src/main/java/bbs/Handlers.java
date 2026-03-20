package bbs;

import chat.EnvelopeOuterClass.Envelope;

import java.util.List;

public class Handlers {

    public static Envelope handleLogin(Envelope msg, Storage storage) {
        String username = msg.getUsername().trim();

        if (username.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "LOGIN_REP",
                    "",
                    "",
                    false,
                    "Nome de usuário inválido.",
                    null,
                    msg.getRequestId()
            );
        }

        storage.saveLogin(username, msg.getTimestamp());

        return ProtocolUtil.makeMessage(
                "LOGIN_REP",
                username,
                "",
                true,
                "",
                null,
                msg.getRequestId()
        );
    }

    public static Envelope handleListChannels(Envelope msg, Storage storage) {
        List<String> channels = storage.listChannels();

        return ProtocolUtil.makeMessage(
                "LIST_CHANNELS_REP",
                "",
                "",
                true,
                "",
                channels,
                msg.getRequestId()
        );
    }

    public static Envelope handleCreateChannel(Envelope msg, Storage storage) {
        String channelName = msg.getChannelName().trim();

        if (channelName.isBlank()) {
            return ProtocolUtil.makeMessage(
                    "CREATE_CHANNEL_REP",
                    "",
                    "",
                    false,
                    "Nome do canal inválido.",
                    null,
                    msg.getRequestId()
            );
        }

        if (!channelName.matches("^[a-zA-Z0-9_-]+$")) {
            return ProtocolUtil.makeMessage(
                    "CREATE_CHANNEL_REP",
                    "",
                    "",
                    false,
                    "Nome do canal deve conter apenas letras, números, '_' ou '-'.",
                    null,
                    msg.getRequestId()
            );
        }

        boolean created = storage.createChannel(channelName, msg.getTimestamp());

        if (!created) {
            return ProtocolUtil.makeMessage(
                    "CREATE_CHANNEL_REP",
                    "",
                    channelName,
                    false,
                    "Canal já existe.",
                    null,
                    msg.getRequestId()
            );
        }

        return ProtocolUtil.makeMessage(
                "CREATE_CHANNEL_REP",
                "",
                channelName,
                true,
                "",
                null,
                msg.getRequestId()
        );
    }

    public static Envelope handleUnknown(Envelope msg) {
        return ProtocolUtil.makeMessage(
                "ERROR_REP",
                "",
                "",
                false,
                "Tipo de mensagem desconhecido: " + msg.getType(),
                null,
                msg.getRequestId()
        );
    }
}