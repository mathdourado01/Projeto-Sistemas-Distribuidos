package bbs;

import chat.EnvelopeOuterClass.Envelope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ProtocolUtil {

    public static long nowTimestamp() {
        return Instant.now().getEpochSecond();
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public static Envelope makeMessage(
            String msgType,
            String username,
            String channelName,
            boolean success,
            String errorMessage,
            List<String> channels,
            String requestId,
            String messageText,
            long logicalClock,
            String serverName,
            int serverRank,
            List<String> servers,
            long physicalTime
    ) {
        Envelope.Builder builder = Envelope.newBuilder()
                .setType(msgType)
                .setTimestamp(nowTimestamp())
                .setRequestId((requestId == null || requestId.isBlank()) ? newRequestId() : requestId)
                .setUsername(username == null ? "" : username)
                .setChannelName(channelName == null ? "" : channelName)
                .setSuccess(success)
                .setErrorMessage(errorMessage == null ? "" : errorMessage)
                .setMessageText(messageText == null ? "" : messageText)
                .setLogicalClock(logicalClock)
                .setServerName(serverName == null ? "" : serverName)
                .setServerRank(serverRank)
                .setPhysicalTime(physicalTime);

        if (channels != null) {
            builder.addAllChannels(channels);
        }

        if (servers != null) {
            builder.addAllServers(servers);
        }

        return builder.build();
    }

    public static Envelope makeMessage(String msgType) {
        return makeMessage(
                msgType,
                "",
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
    }
}