package com.example.telegrambot.domain;

import java.time.Instant;

public record ShoppingItem(long id, long chatId, String name, long addedByUserId, Instant addedAt) {}
