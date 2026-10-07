package com.example.telegrambot;

import com.example.telegrambot.service.CommandService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CommandServiceTest {
    private final CommandService commands = new CommandService(Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));

    @Test void handlesEchoAndBotUsernameSuffix() { assertEquals("hello world", commands.replyTo("/echo@MyBot hello world", "Ada")); }
    @Test void explainsEchoUsageWhenArgumentIsMissing() { assertEquals("Usage: /echo <text>", commands.replyTo("/echo", "Ada")); }
    @Test void formatsTimeDeterministically() { assertEquals("Current UTC time: 2026-09-10T12:00:00Z", commands.replyTo("/time", "Ada")); }
    @Test void repliesToNormalText() { assertEquals("You said: hello\n\nType /help to see commands.", commands.replyTo(" hello ", "Ada")); }
}
