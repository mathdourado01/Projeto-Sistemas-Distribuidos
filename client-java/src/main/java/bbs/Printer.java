package bbs;

import chat.EnvelopeOuterClass.Envelope;

public class Printer {

    public static void printMessage(String direction, Envelope msg) {
        System.out.println("============================================================");
        System.out.println("[" + direction + "]");
        System.out.println("type         : " + msg.getType());
        System.out.println("timestamp    : " + msg.getTimestamp());
        System.out.println("request_id   : " + msg.getRequestId());
        System.out.println("username     : " + msg.getUsername());
        System.out.println("channel_name : " + msg.getChannelName());
        System.out.println("success      : " + msg.getSuccess());
        System.out.println("error_message: " + msg.getErrorMessage());
        System.out.println("channels     : " + msg.getChannelsList());
        System.out.println("============================================================");
    }
}