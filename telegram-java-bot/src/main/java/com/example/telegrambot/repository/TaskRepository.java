package com.example.telegrambot.repository;

import com.example.telegrambot.domain.Task;
import com.example.telegrambot.domain.TaskCategory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    Task create(long chatId, String description, long creatorUserId, String creatorName, String assignee, Instant deadline, TaskCategory category);
    List<Task> findOpen(long chatId);
    List<Task> findOpenDueBetween(long chatId, Instant fromInclusive, Instant toExclusive);
    List<Task> findOverdue(long chatId, Instant now);
    Optional<Task> markDone(long chatId, long id, Instant completedAt);
    Optional<Task> cancel(long chatId, long id, Instant cancelledAt);
    Optional<Task> delete(long chatId, long id);
    Optional<Task> assign(long chatId, long id, String assignee);
    Optional<Task> edit(long chatId, long id, String description, Instant deadline, TaskCategory category);
    List<Task> findTasksNeedingReminder(Instant now, Instant limit);
    void markReminderSent(long chatId, long id);
    List<Task> findCompletedBetween(long chatId, Instant fromInclusive, Instant toExclusive);
    List<Long> findAllChatIds();
}
