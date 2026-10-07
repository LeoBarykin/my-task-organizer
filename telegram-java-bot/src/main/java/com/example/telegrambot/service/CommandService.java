package com.example.telegrambot.service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Pure command logic, kept independent of Telegram classes so it is easy to test and extend. */
public final class CommandService {
    private final Clock clock;

    public CommandService() { this(Clock.systemUTC()); }

    public CommandService(Clock clock) { this.clock = clock; }

    public String replyTo(String text, String firstName) {
        String input = text == null ? "" : text.trim();
        if (!input.startsWith("/")) return "You said: " + input + "\n\nType /help to see commands.";

        String[] parts = input.split("\\s+", 2);
        String command = parts[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        String argument = parts.length == 2 ? parts[1].trim() : "";

        return switch (command) {
            case "/start" -> "Hi " + displayName(firstName) + "! 👋\nUse /help to see what I can do.";
            case "/help" -> "Available commands:\n/start — welcome message\n/help — this help\n/echo <text> — repeat text\n/time — current UTC time";
            case "/echo" -> argument.isEmpty() ? "Usage: /echo <text>" : argument;
            case "/time" -> "Current UTC time: " + DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock));
            default -> "I don't know that command. Type /help.";
        };
    }

    private String displayName(String firstName) { return firstName == null || firstName.isBlank() ? "there" : firstName; }
}
