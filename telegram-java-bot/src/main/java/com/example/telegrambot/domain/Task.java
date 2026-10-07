package com.example.telegrambot.domain;

import java.time.Instant;

public record Task(long id, long chatId, String description, long creatorUserId, String creatorName,
            String assignee, Instant deadline, TaskStatus status, Instant createdAt, Instant completedAt,
            boolean reminderSent, TaskCategory category) { }
