package com.lmarena.app;

public class ChatMessage {
    public static final int TYPE_USER = 0;
    public static final int TYPE_ASSISTANT = 1;

    public String text;
    public final int type;

    public ChatMessage(String text, int type) {
        this.text = text;
        this.type = type;
    }
}
