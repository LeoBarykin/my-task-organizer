package com.example.telegrambot.bot;

import java.util.List;

public record BotReply(String text, List<String> buttons, List<List<InlineButton>> inlineKeyboard, boolean editCurrentMessage) {
    public BotReply(String text) {
        this(text, List.of(), List.of(), false);
    }

    public BotReply(String text, List<String> buttons) {
        this(text, buttons, List.of(), false);
    }

    public BotReply(String text, List<String> buttons, List<List<InlineButton>> inlineKeyboard) {
        this(text, buttons, inlineKeyboard, false);
    }
}
