package com.example.telegrambot.service;

import com.example.telegrambot.domain.Task;
import com.example.telegrambot.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public final class ReminderService {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderService.class);
    
    private final TelegramClient client;
    private final TaskRepository repository;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public ReminderService(TelegramClient client, TaskRepository repository) {
        this(client, repository, Clock.systemUTC());
    }

    public ReminderService(TelegramClient client, TaskRepository repository, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "task-reminder-thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        // Run every 15 minutes, with initial delay of 1 minute (to allow bot startup)
        scheduler.scheduleAtFixedRate(this::sendReminders, 1, 15, TimeUnit.MINUTES);
        
        // Schedule weekly digest every Sunday at 18:00 UTC
        long initialDigestDelayMs = calculateSunday18DelayMs();
        scheduler.scheduleAtFixedRate(this::sendWeeklyDigest, initialDigestDelayMs, 7 * 24 * 60 * 60 * 1000L, TimeUnit.MILLISECONDS);
        
        LOG.info("Task reminder service started. Polling every 15 minutes.");
        LOG.info("Weekly digest scheduled in {} ms (running every 7 days).", initialDigestDelayMs);
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("Task reminder service stopped.");
    }

    public long calculateSunday18DelayMs() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(clock);
        java.time.ZonedDateTime nextSunday18 = now.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
                .withHour(18).withMinute(0).withSecond(0).withNano(0);
        if (now.isAfter(nextSunday18)) {
            nextSunday18 = nextSunday18.plusWeeks(1);
        }
        return java.time.Duration.between(now, nextSunday18).toMillis();
    }

    public void sendWeeklyDigest() {
        try {
            Instant now = Instant.now(clock);
            Instant oneWeekAgo = now.minus(7, ChronoUnit.DAYS);
            Instant oneWeekAhead = now.plus(7, ChronoUnit.DAYS);
            
            List<Long> chatIds = repository.findAllChatIds();
            for (long chatId : chatIds) {
                List<Task> completed = repository.findCompletedBetween(chatId, oneWeekAgo, now);
                List<Task> upcoming = repository.findOpenDueBetween(chatId, now, oneWeekAhead);
                
                String message = buildDigestMessage(completed, upcoming);
                
                SendMessage send = SendMessage.builder()
                        .chatId(chatId)
                        .text(message)
                        .parseMode("Markdown")
                        .build();
                        
                client.execute(send);
                LOG.info("Sent weekly alignment digest to chat {}", chatId);
            }
        } catch (Exception e) {
            LOG.error("Failed to send weekly alignment digests", e);
        }
    }

    public String buildDigestMessage(List<Task> completed, List<Task> upcoming) {
        StringBuilder builder = new StringBuilder();
        builder.append("📊 *Weekly Family Alignment Digest*\n\n");
        
        builder.append("✅ *Completed This Past Week:*\n");
        if (completed.isEmpty()) {
            builder.append("No tasks completed this week.\n");
        } else {
            for (Task task : completed) {
                String assignee = task.assignee() != null ? " (" + task.assignee() + ")" : "";
                builder.append("- #").append(task.id()).append(": ").append(escapeMarkdown(task.description())).append(assignee).append("\n");
            }
        }
        
        builder.append("\n📅 *Upcoming This Week:*\n");
        if (upcoming.isEmpty()) {
            builder.append("No tasks scheduled for the upcoming week.\n");
        } else {
            for (Task task : upcoming) {
                String assignee = task.assignee() != null ? " " + task.assignee() : "";
                java.time.LocalDate localDate = java.time.LocalDate.ofInstant(task.deadline(), java.time.ZoneOffset.UTC);
                builder.append("- #").append(task.id()).append(": ").append(escapeMarkdown(task.description())).append(" (Due: ").append(localDate).append(")").append(assignee).append("\n");
            }
        }
        
        return builder.toString();
    }

    public void sendReminders() {
        try {
            Instant now = Instant.now(clock);
            Instant limit = now.plus(24, ChronoUnit.HOURS);
            List<Task> tasks = repository.findTasksNeedingReminder(now, limit);
            for (Task task : tasks) {
                String mention = task.assignee() != null ? " " + task.assignee() : "";
                String message = "🔔 *Reminder:* Task #" + task.id() + " - \"" + escapeMarkdown(task.description()) + "\" is due in less than 24 hours!" + mention;
                
                SendMessage send = SendMessage.builder()
                        .chatId(task.chatId())
                        .text(message)
                        .parseMode("Markdown")
                        .build();
                        
                client.execute(send);
                repository.markReminderSent(task.chatId(), task.id());
                LOG.info("Sent deadline reminder for task #{}", task.id());
            }
        } catch (Exception e) {
            LOG.error("Failed to process task deadline reminders", e);
        }
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("*", "\\*")
                   .replace("_", "\\_")
                   .replace("`", "\\`")
                   .replace("[", "\\[");
    }
}
