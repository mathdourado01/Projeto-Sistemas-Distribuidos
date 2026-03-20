package bbs;

import chat.EnvelopeOuterClass.Envelope;

import java.util.List;
import java.util.UUID;

public class ProtocolUtil {

    public static long nowTimestamp() {
        return System.currentTimeMillis() / 1000;
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
            String requestId
    ) {
        Envelope.Builder builder = Envelope.newBuilder()
                .setType(msgType)
                .setTimestamp(nowTimestamp())
                .setRequestId((requestId == null || requestId.isBlank()) ? newRequestId() : requestId)
                .setUsername(username == null ? "" : username)
                .setChannelName(channelName == null ? "" : channelName)
                .setSuccess(success)
                .setErrorMessage(errorMessage == null ? "" : errorMessage);

        if (channels != null) {
            builder.addAllChannels(channels);
        }

        return builder.build();
    }
}