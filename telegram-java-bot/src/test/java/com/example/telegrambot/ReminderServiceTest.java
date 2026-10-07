package com.example.telegrambot;

import com.example.telegrambot.domain.Task;
import com.example.telegrambot.domain.TaskStatus;
import com.example.telegrambot.domain.TaskCategory;
import com.example.telegrambot.repository.TaskRepository;
import com.example.telegrambot.service.ReminderService;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

class ReminderServiceTest {
    private InMemoryTaskRepository repository;
    private List<SendMessage> sentMessages;
    private TelegramClient fakeClient;
    private Clock fixedClock;
    private ReminderService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskRepository();
        sentMessages = new ArrayList<>();
        
        // Dynamic proxy to capture client.execute calls
        fakeClient = (TelegramClient) Proxy.newProxyInstance(
            TelegramClient.class.getClassLoader(),
            new Class<?>[]{TelegramClient.class},
            (proxy, method, args) -> {
                if (method.getName().equals("execute") && args.length > 0 && args[0] instanceof SendMessage) {
                    sentMessages.add((SendMessage) args[0]);
                }
                return null;
            }
        );

        fixedClock = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        service = new ReminderService(fakeClient, repository, fixedClock);
    }

    @Test
    void testRemindersOnlyTriggeredWithin24Hours() {
        // 1. Create a task with deadline far in future (e.g. 48 hours)
        repository.create(101L, "Future Task", 42L, "Alice", "@bobby", Instant.parse("2026-09-12T12:00:00Z"), TaskCategory.CHORES);
        
        // 2. Create a task with deadline in 12 hours (needs reminder)
        repository.create(101L, "Soon Task", 42L, "Alice", "@bobby", Instant.parse("2026-09-11T00:00:00Z"), TaskCategory.CHORES);
        
        // 3. Create a task with deadline in past (overdue, shouldn't trigger reminder)
        repository.create(101L, "Past Task", 42L, "Alice", "@bobby", Instant.parse("2026-09-09T12:00:00Z"), TaskCategory.CHORES);

        // Run sendReminders
        service.sendReminders();

        // Check that only "Soon Task" triggered a reminder
        assertEquals(1, sentMessages.size());
        SendMessage msg = sentMessages.get(0);
        assertEquals("101", msg.getChatId());
        assertTrue(msg.getText().contains("Soon Task"));
        assertTrue(msg.getText().contains("@bobby"));

        // Check that task is marked as reminder sent
        List<Task> needingReminders = repository.findTasksNeedingReminder(Instant.parse("2026-09-10T12:00:00Z"), Instant.parse("2026-09-11T12:00:00Z"));
        assertTrue(needingReminders.isEmpty());
        
        // Check that second run does not send duplicate reminders
        sentMessages.clear();
        service.sendReminders();
        assertTrue(sentMessages.isEmpty());
    }

    @Test
    void testWeeklyDigestCalculationAndFormatting() {
        // Clear captured messages
        sentMessages.clear();

        // 1. Create a task that was completed yesterday (needs to be in completed past week)
        Task completedTask = repository.create(102L, "Buy groceries", 42L, "Alice", "@bobby", Instant.parse("2026-09-09T12:00:00Z"), TaskCategory.CHORES);
        // Manually mark it as completed yesterday
        repository.list.set(0, new Task(
            completedTask.id(), completedTask.chatId(), completedTask.description(),
            completedTask.creatorUserId(), completedTask.creatorName(), completedTask.assignee(),
            completedTask.deadline(), TaskStatus.DONE, completedTask.createdAt(),
            Instant.parse("2026-09-09T15:00:00Z"), completedTask.reminderSent(), completedTask.category()
        ));

        // 2. Create an open task due tomorrow (needs to be in upcoming this week)
        repository.create(102L, "Clean garage", 42L, "Alice", "@bobby", Instant.parse("2026-09-11T12:00:00Z"), TaskCategory.CHORES);

        // 3. Create an open task due in 10 days (outside the upcoming week range)
        repository.create(102L, "Paint fence", 42L, "Alice", "@bobby", Instant.parse("2026-09-22T12:00:00Z"), TaskCategory.CHORES);

        // Run sendWeeklyDigest
        service.sendWeeklyDigest();

        // Verify digest sent
        assertEquals(1, sentMessages.size());
        SendMessage msg = sentMessages.get(0);
        assertEquals("102", msg.getChatId());
        
        // Assertions on markdown digest content
        assertTrue(msg.getText().contains("Weekly Family Alignment Digest"));
        assertTrue(msg.getText().contains("Completed This Past Week:"));
        assertTrue(msg.getText().contains("Buy groceries"));
        
        assertTrue(msg.getText().contains("Upcoming This Week:"));
        assertTrue(msg.getText().contains("Clean garage"));
        
        // Ensure paint fence (outside upcoming week range) is NOT in the digest
        assertFalse(msg.getText().contains("Paint fence"));
    }

    @Test
    void testSunday18DelayCalculation() {
        // Wednesday, Sep 9, 2026 12:00:00 UTC
        Clock wednesdayClock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
        ReminderService wednesdayService = new ReminderService(fakeClient, repository, wednesdayClock);
        
        // Next Sunday Sep 13, 2026 18:00:00 UTC
        // Distance is 4 days (Sep 10, 11, 12, 13) and 6 hours
        // 4 days * 24h + 6h = 102 hours = 102 * 60 * 60 * 1000 = 367,200,000 ms
        assertEquals(367200000L, wednesdayService.calculateSunday18DelayMs());

        // Sunday, Sep 13, 2026 18:30:00 UTC (after Sunday 18:00)
        // Next Sunday Sep 20, 2026 18:00:00 UTC
        // Distance is 6 days, 23 hours, 30 minutes
        Clock sundayPostClock = Clock.fixed(Instant.parse("2026-09-13T18:30:00Z"), ZoneOffset.UTC);
        ReminderService sundayPostService = new ReminderService(fakeClient, repository, sundayPostClock);
        
        // 6 days * 24h + 23.5h = 167.5 hours = 167.5 * 3,600,000 = 603,000,000 ms
        assertEquals(603000000L, sundayPostService.calculateSunday18DelayMs());
    }

    private static class InMemoryTaskRepository implements TaskRepository {
        final List<Task> list = new ArrayList<>();
        private long idCounter = 1;

        @Override
        public Task create(long chatId, String description, long creatorUserId, String creatorName, String assignee, Instant deadline, TaskCategory category) {
            Task task = new Task(idCounter++, chatId, description, creatorUserId, creatorName, assignee, deadline, TaskStatus.OPEN, Instant.now(), null, false, category);
            list.add(task);
            return task;
        }

        @Override
        public List<Task> findOpen(long chatId) {
            return list.stream().filter(t -> t.chatId() == chatId && t.status() == TaskStatus.OPEN).toList();
        }

        @Override
        public List<Task> findOpenDueBetween(long chatId, Instant from, Instant to) {
            return list.stream().filter(t -> t.chatId() == chatId && t.status() == TaskStatus.OPEN && t.deadline() != null && !t.deadline().isBefore(from) && t.deadline().isBefore(to)).toList();
        }

        @Override
        public List<Task> findOverdue(long chatId, Instant now) {
            return list.stream().filter(t -> t.chatId() == chatId && t.status() == TaskStatus.OPEN && t.deadline() != null && t.deadline().isBefore(now)).toList();
        }

        @Override
        public Optional<Task> markDone(long chatId, long id, Instant completedAt) {
            return Optional.empty();
        }

        @Override
        public Optional<Task> cancel(long chatId, long id, Instant cancelledAt) {
            return Optional.empty();
        }

        @Override
        public Optional<Task> delete(long chatId, long id) {
            return Optional.empty();
        }

        @Override
        public Optional<Task> assign(long chatId, long id, String assignee) {
            return Optional.empty();
        }

        @Override
        public Optional<Task> edit(long chatId, long id, String description, Instant deadline, TaskCategory category) {
            return Optional.empty();
        }

        @Override
        public List<Task> findTasksNeedingReminder(Instant now, Instant limit) {
            return list.stream().filter(t -> t.status() == TaskStatus.OPEN && !t.reminderSent() && t.deadline() != null && t.deadline().isAfter(now) && !t.deadline().isAfter(limit)).toList();
        }

        @Override
        public void markReminderSent(long chatId, long id) {
            for (int i = 0; i < list.size(); i++) {
                Task t = list.get(i);
                if (t.chatId() == chatId && t.id() == id) {
                    list.set(i, new Task(t.id(), t.chatId(), t.description(), t.creatorUserId(), t.creatorName(), t.assignee(), t.deadline(), t.status(), t.createdAt(), t.completedAt(), true, t.category()));
                    return;
                }
            }
        }

        @Override
        public List<Task> findCompletedBetween(long chatId, Instant fromInclusive, Instant toExclusive) {
            return list.stream()
                .filter(t -> t.chatId() == chatId && t.status() == TaskStatus.DONE && t.completedAt() != null && !t.completedAt().isBefore(fromInclusive) && t.completedAt().isBefore(toExclusive))
                .toList();
        }

        @Override
        public List<Long> findAllChatIds() {
            return list.stream().map(Task::chatId).distinct().toList();
        }
    }
}
