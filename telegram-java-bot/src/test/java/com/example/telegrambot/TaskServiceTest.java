package com.example.telegrambot;

import com.example.telegrambot.domain.*;
import com.example.telegrambot.repository.*;
import com.example.telegrambot.service.*;
import com.example.telegrambot.bot.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskServiceTest {
    private InMemoryTaskRepository taskRepository;
    private InMemoryUserSessionRepository sessionRepository;
    private TaskService taskService;
    private static final long CHAT_ID = 1001L;
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository();
        sessionRepository = new InMemoryUserSessionRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        taskService = new TaskService(taskRepository, sessionRepository, clock);
        taskService.setGroupAdminsProvider(chatId -> List.of("@mom", "@dad", "Bobby"));
    }

    @Test
    void testInitialStateAndHelpMenu() {
        BotReply reply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "/help");
        assertTrue(reply.text().contains("Family task bot commands"));
        assertEquals(TaskService.MAIN_MENU_BUTTONS, reply.buttons());
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testHelpButtonInteraction() {
        BotReply reply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "ℹ️ Help");
        assertTrue(reply.text().contains("Family task bot commands"));
        assertEquals(TaskService.MAIN_MENU_BUTTONS, reply.buttons());
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testAddTaskSuccessFlow() {
        // Step 1: User triggers Add Task
        BotReply reply1 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "➕ Add Task");
        assertEquals("🏷️ Select a category tag for the new task:", reply1.text());
        assertEquals(UserState.AWAITING_CATEGORY, sessionRepository.getSession(USER_ID).state());

        // Step 1.5: Select category
        BotReply catReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "category:CHORES");
        assertEquals("Please enter the task description:", catReply.text());
        assertEquals(UserState.AWAITING_DESCRIPTION, sessionRepository.getSession(USER_ID).state());

        // Step 2: User provides description
        BotReply reply2 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "Buy milk");
        assertEquals("Enter the deadline (e.g., 2026-09-15 18:00):", reply2.text());
        assertEquals(UserState.AWAITING_DEADLINE, sessionRepository.getSession(USER_ID).state());
        assertEquals("Buy milk", sessionRepository.getSession(USER_ID).tempDescription());

        // Step 3: User provides deadline
        BotReply reply3 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "2026-09-15 18:00");
        assertEquals("Enter assignee's Telegram username (e.g., @alex) or choose below:", reply3.text());
        assertEquals(UserState.AWAITING_ASSIGNEE, sessionRepository.getSession(USER_ID).state());
        assertEquals("2026-09-15 18:00", sessionRepository.getSession(USER_ID).tempDeadline());

        // Check inline keyboard options (No Group option since CHAT_ID > 0)
        assertEquals(2, reply3.inlineKeyboard().size()); // Me, Nobody/Cancel
        assertEquals("👤 Me (@alice)", reply3.inlineKeyboard().get(0).get(0).text());
        assertEquals("assign:me", reply3.inlineKeyboard().get(0).get(0).callbackData());

        // Step 4: User provides assignee and creates task
        BotReply reply4 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "@bobby");
        System.out.println("DEBUG: reply4.text() = " + reply4.text());
        assertTrue(reply4.text().contains("✅ Added #1: Buy milk"), "Should contain Added #1 description: " + reply4.text());
        assertTrue(reply4.text().contains("Assigned: @bobby"), "Should contain Assignee bobby: " + reply4.text());
        assertEquals(TaskService.TASKS_MENU_BUTTONS, reply4.buttons());
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());

        // Confirm task exists in repository
        List<Task> openTasks = taskRepository.findOpen(CHAT_ID);
        assertEquals(1, openTasks.size());
        assertEquals("Buy milk", openTasks.get(0).description());
        assertEquals("@bobby", openTasks.get(0).assignee());
    }

    @Test
    void testCancelFlow() {
        // Start wizard
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "➕ Add Task");
        assertEquals(UserState.AWAITING_CATEGORY, sessionRepository.getSession(USER_ID).state());

        // Cancel wizard
        BotReply reply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "❌ Cancel");
        assertEquals("Operation cancelled.", reply.text());
        assertEquals(TaskService.TASKS_MENU_BUTTONS, reply.buttons());
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testValidationFailureKeepsState() {
        // Start wizard
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "➕ Add Task");
        taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "category:CHORES");
        assertEquals(UserState.AWAITING_DESCRIPTION, sessionRepository.getSession(USER_ID).state());

        // Send too long/invalid description
        String giantDescription = "a".repeat(600);
        BotReply errorReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", giantDescription);
        assertTrue(errorReply.text().contains("⚠️ Task description must be between 1 and 500 characters."));
        // Verify user is STILL in the same state
        assertEquals(UserState.AWAITING_DESCRIPTION, sessionRepository.getSession(USER_ID).state());

        // Provide correct description
        BotReply successReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "Clean kitchen");
        assertTrue(successReply.text().contains("Enter the deadline"));
        assertEquals(UserState.AWAITING_DEADLINE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testInvalidDeadlineValidationKeepsState() {
        // Progress to deadline state
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "➕ Add Task");
        taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "category:CHORES");
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "Do homework");
        assertEquals(UserState.AWAITING_DEADLINE, sessionRepository.getSession(USER_ID).state());

        // Try invalid deadline format
        BotReply errorReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "tomorrow");
        assertTrue(errorReply.text().contains("⚠️ Deadline must look like 2026-09-15 18:00"));
        assertEquals(UserState.AWAITING_DEADLINE, sessionRepository.getSession(USER_ID).state());

        // Try valid deadline
        BotReply successReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "2026-09-10 14:00");
        assertEquals(UserState.AWAITING_ASSIGNEE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testRelativeDeadlineShortcuts() {
        // Step 1: Start wizard
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "➕ Add Task");
        taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "category:CHORES");
        
        // Step 2: Provide description
        BotReply reply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "Water lawn");
        assertEquals(UserState.AWAITING_DEADLINE, sessionRepository.getSession(USER_ID).state());
        
        // Check that inline shortcuts are included
        assertEquals(2, reply.inlineKeyboard().size());
        assertEquals("Today 18:00", reply.inlineKeyboard().get(0).get(0).text());
        assertEquals("deadline:today_18", reply.inlineKeyboard().get(0).get(0).callbackData());
        assertEquals("Tomorrow 12:00", reply.inlineKeyboard().get(0).get(1).text());
        assertEquals("deadline:tomorrow_12", reply.inlineKeyboard().get(0).get(1).callbackData());
        
        assertEquals("Tomorrow 18:00", reply.inlineKeyboard().get(1).get(0).text());
        assertEquals("deadline:tomorrow_18", reply.inlineKeyboard().get(1).get(0).callbackData());
        assertEquals("In 1 Week", reply.inlineKeyboard().get(1).get(1).text());
        assertEquals("deadline:next_week_12", reply.inlineKeyboard().get(1).get(1).callbackData());

        // Step 3: Trigger Tomorrow 12:00 callback shortcut
        BotReply callbackReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "deadline:tomorrow_12");
        assertEquals(UserState.AWAITING_ASSIGNEE, sessionRepository.getSession(USER_ID).state());
        // Tomorrow for fixed date 2026-09-10 is 2026-09-11
        assertEquals("2026-09-11 12:00", sessionRepository.getSession(USER_ID).tempDeadline());
        assertTrue(callbackReply.text().contains("choose below"));

        // Step 4: Try triggering shortcut when session is in different state
        BotReply invalidCallbackReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "deadline:today_18");
        assertTrue(invalidCallbackReply.text().contains("This shortcut is no longer valid."));
    }

    @Test
    void testAssignmentHelperSelf() {
        // Start wizard and select category + description + deadline
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "➕ Add Task");
        taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "category:CHORES");
        taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "Walk dog");
        taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "deadline:today_18");
        assertEquals(UserState.AWAITING_ASSIGNEE, sessionRepository.getSession(USER_ID).state());

        // Click "[👤 Me (@alice)]"
        BotReply reply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "assign:me");
        assertTrue(reply.text().contains("Added #1: Walk dog"));
        assertTrue(reply.text().contains("Assigned: @alice"));
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testAssignmentHelperGroup() {
        long groupChatId = -2002L; // Negative ID to simulate group chat
        
        // Start wizard and progress to assignee state
        taskService.replyTo(groupChatId, USER_ID, "Alice", "alice", "➕ Add Task");
        taskService.handleCallback(groupChatId, USER_ID, "Alice", "alice", "category:CHORES");
        taskService.replyTo(groupChatId, USER_ID, "Alice", "alice", "Buy snacks");
        BotReply prompt = taskService.handleCallback(groupChatId, USER_ID, "Alice", "alice", "deadline:today_18");
        
        // Retrieve assignee prompt (now has 3 rows because it is a group chat: Me, Group, Nobody/Cancel)
        UserSession session = sessionRepository.getSession(USER_ID);
        assertEquals(UserState.AWAITING_ASSIGNEE, session.state());

        // Verify Choose from Group button exists
        assertEquals(3, prompt.inlineKeyboard().size()); // Me, Group, Nobody/Cancel
        assertEquals("👥 Choose from Group", prompt.inlineKeyboard().get(1).get(0).text());

        // Click "Choose from Group"
        BotReply groupPrompt = taskService.handleCallback(groupChatId, USER_ID, "Alice", "alice", "assign:group");
        assertTrue(groupPrompt.text().contains("Choose an assignee from group administrators"));
        
        // Verify group administrators list is shown (arranged max 2 per row + Back button)
        assertEquals(3, groupPrompt.inlineKeyboard().size()); // [@mom, @dad], [Bobby], [Back]
        assertEquals("@mom", groupPrompt.inlineKeyboard().get(0).get(0).text());
        assertEquals("assign_to:@mom", groupPrompt.inlineKeyboard().get(0).get(0).callbackData());
        assertEquals("@dad", groupPrompt.inlineKeyboard().get(0).get(1).text());
        
        // Click on @dad
        BotReply resultReply = taskService.handleCallback(groupChatId, USER_ID, "Alice", "alice", "assign_to:@dad");
        assertTrue(resultReply.text().contains("Added #1: Buy snacks"));
        assertTrue(resultReply.text().contains("Assigned: @dad"));
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());
    }

    @Test
    void testClaimTaskFlow() {
        // 1. Create an unassigned task (assignee = null)
        Task task = taskRepository.create(CHAT_ID, "Clean fridge", USER_ID, "Alice", null, Instant.parse("2026-09-15T18:00:00Z"), TaskCategory.CHORES);

        // 2. Fetch task detail (task:1)
        BotReply detailReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "task:" + task.id());
        
        // Assertions on detail screen
        assertTrue(detailReply.text().contains("Clean fridge"));
        
        // Symmetrical layout checks: 4 rows because the task is unassigned!
        // Row 0: Complete/Cancel
        // Row 1: Claim Task
        // Row 2: Delete Task
        // Row 3: Back to List
        assertEquals(4, detailReply.inlineKeyboard().size());
        assertEquals("👤 Claim Task", detailReply.inlineKeyboard().get(1).get(0).text());
        assertEquals("claim:1", detailReply.inlineKeyboard().get(1).get(0).callbackData());

        // 3. Trigger "claim:1" callback query
        BotReply claimReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "claim:1");
        
        // Assert that editCurrentMessage is true so it inline edits on the screen
        assertTrue(claimReply.editCurrentMessage());
        assertTrue(claimReply.text().contains("Assigned: @alice"));

        // Verify the claim button has vanished (shrunk back to 3 rows)
        assertEquals(3, claimReply.inlineKeyboard().size());
        assertEquals("🗑 Delete Task", claimReply.inlineKeyboard().get(1).get(0).text());
        assertEquals("⬅️ Back to List", claimReply.inlineKeyboard().get(2).get(0).text());

        // Confirm assignee is saved in memory
        assertEquals("@alice", taskRepository.list.get(0).assignee());
    }

    @Test
    void testMarkDoneWizardSuccess() {
        // Seed database with a task
        Task task = taskRepository.create(CHAT_ID, "Clean dishes", USER_ID, "Alice", null, Instant.parse("2026-09-15T18:00:00Z"), TaskCategory.CHORES);

        // Trigger Done Task
        BotReply reply1 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "✅ Done Task");
        assertEquals("Please enter the ID of the task to mark as done:", reply1.text());
        assertEquals(UserState.AWAITING_DONE_ID, sessionRepository.getSession(USER_ID).state());

        // Enter task ID
        BotReply reply2 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", String.valueOf(task.id()));
        assertTrue(reply2.text().contains("✅ Completed #1: Clean dishes"));
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());

        // Check task is completed
        assertTrue(taskRepository.findOpen(CHAT_ID).isEmpty());
    }

    @Test
    void testDeleteWizardSuccess() {
        // Seed database with a task
        Task task = taskRepository.create(CHAT_ID, "Dust bookshelves", USER_ID, "Alice", null, Instant.parse("2026-09-15T18:00:00Z"), TaskCategory.CHORES);

        // Trigger Delete Task
        BotReply reply1 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "🗑 Delete Task");
        assertEquals("Please enter the ID of the task to delete:", reply1.text());
        assertEquals(UserState.AWAITING_DELETE_ID, sessionRepository.getSession(USER_ID).state());

        // Enter task ID
        BotReply reply2 = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", String.valueOf(task.id()));
        assertTrue(reply2.text().contains("🗑 Deleted #1: Dust bookshelves"));
        assertEquals(UserState.IDLE, sessionRepository.getSession(USER_ID).state());

        // Check task is deleted
        assertTrue(taskRepository.list.isEmpty());
    }

    @Test
    void testTaskCallbackFlows() {
        // 1. Create a task assigned to @alice
        Task task = taskRepository.create(CHAT_ID, "Walk the dog", USER_ID, "Alice", "@alice", Instant.parse("2026-09-15T18:00:00Z"), TaskCategory.CHORES);

        // 2. Request task list
        BotReply listReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "📋 List Tasks");
        assertTrue(listReply.text().contains("Walk the dog"));
        // Check inline keyboard is returned and has 1 row with 1 button
        assertEquals(1, listReply.inlineKeyboard().size());
        assertEquals("#1: Walk the dog", listReply.inlineKeyboard().get(0).get(0).text());
        assertEquals("task:1", listReply.inlineKeyboard().get(0).get(0).callbackData());

        // 3. Trigger callback query for selecting task
        BotReply selectReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "task:1");
        assertTrue(selectReply.text().contains("Task #1"));
        assertTrue(selectReply.text().contains("Walk the dog"));
        // Check inline keyboard for actions (3 rows: Complete/Cancel, Delete, & Back to List)
        assertEquals(3, selectReply.inlineKeyboard().size());
        assertEquals(2, selectReply.inlineKeyboard().get(0).size()); // Complete & Cancel
        
        assertEquals("✅ Complete", selectReply.inlineKeyboard().get(0).get(0).text());
        assertEquals("complete:1", selectReply.inlineKeyboard().get(0).get(0).callbackData());
        assertEquals("❌ Cancel", selectReply.inlineKeyboard().get(0).get(1).text());
        assertEquals("cancel:1", selectReply.inlineKeyboard().get(0).get(1).callbackData());
        
        assertEquals(1, selectReply.inlineKeyboard().get(1).size()); // Delete Task
        assertEquals("🗑 Delete Task", selectReply.inlineKeyboard().get(1).get(0).text());
        assertEquals("delete:1", selectReply.inlineKeyboard().get(1).get(0).callbackData());
        
        assertEquals(1, selectReply.inlineKeyboard().get(2).size()); // Back to List
        assertEquals("⬅️ Back to List", selectReply.inlineKeyboard().get(2).get(0).text());
        assertEquals("back_to_list", selectReply.inlineKeyboard().get(2).get(0).callbackData());

        // Test trigger of Back to List callback
        BotReply backReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "back_to_list");
        assertTrue(backReply.text().contains("Walk the dog"));
        assertEquals(1, backReply.inlineKeyboard().size());

        // 4. Trigger callback query for Cancel
        BotReply cancelActionReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "cancel:1");
        assertTrue(cancelActionReply.text().contains("❌ Cancelled #1: Walk the dog"));
        
        // Check status in repository is CANCELLED
        assertEquals(TaskStatus.CANCELLED, taskRepository.list.get(0).status());

        // Check listing again - should be empty because it is cancelled
        BotReply emptyReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "📋 List Tasks");
        assertTrue(emptyReply.text().contains("Open tasks: none 🎉"));
    }

    @Test
    void testTaskCompleteCallbackFlow() {
        // 1. Create a task
        Task task = taskRepository.create(CHAT_ID, "Water plants", USER_ID, "Alice", null, Instant.parse("2026-09-15T18:00:00Z"), TaskCategory.CHORES);

        // 2. Complete callback
        BotReply completeActionReply = taskService.handleCallback(CHAT_ID, USER_ID, "Alice", "alice", "complete:1");
        assertTrue(completeActionReply.text().contains("✅ Completed #1: Water plants"));

        // Check status is DONE
        assertEquals(TaskStatus.DONE, taskRepository.list.get(0).status());

        // Check list is empty
        BotReply emptyReply = taskService.replyTo(CHAT_ID, USER_ID, "Alice", "alice", "📋 List Tasks");
        assertTrue(emptyReply.text().contains("Open tasks: none 🎉"));
    }

    // Custom in-memory TaskRepository implementation for simple unit testing
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
            for (int i = 0; i < list.size(); i++) {
                Task t = list.get(i);
                if (t.chatId() == chatId && t.id() == id && t.status() == TaskStatus.OPEN) {
                    Task updated = new Task(t.id(), t.chatId(), t.description(), t.creatorUserId(), t.creatorName(), t.assignee(), t.deadline(), TaskStatus.DONE, t.createdAt(), completedAt, t.reminderSent(), t.category());
                    list.set(i, updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Task> cancel(long chatId, long id, Instant cancelledAt) {
            for (int i = 0; i < list.size(); i++) {
                Task t = list.get(i);
                if (t.chatId() == chatId && t.id() == id && t.status() == TaskStatus.OPEN) {
                    Task updated = new Task(t.id(), t.chatId(), t.description(), t.creatorUserId(), t.creatorName(), t.assignee(), t.deadline(), TaskStatus.CANCELLED, t.createdAt(), cancelledAt, t.reminderSent(), t.category());
                    list.set(i, updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Task> delete(long chatId, long id) {
            for (int i = 0; i < list.size(); i++) {
                Task t = list.get(i);
                if (t.chatId() == chatId && t.id() == id) {
                    list.remove(i);
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Task> assign(long chatId, long id, String assignee) {
            for (int i = 0; i < list.size(); i++) {
                Task t = list.get(i);
                if (t.chatId() == chatId && t.id() == id && t.status() == TaskStatus.OPEN) {
                    Task updated = new Task(t.id(), t.chatId(), t.description(), t.creatorUserId(), t.creatorName(), assignee, t.deadline(), t.status(), t.createdAt(), t.completedAt(), t.reminderSent(), t.category());
                    list.set(i, updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Task> edit(long chatId, long id, String description, Instant deadline, TaskCategory category) {
            for (int i = 0; i < list.size(); i++) {
                Task t = list.get(i);
                if (t.chatId() == chatId && t.id() == id && t.status() == TaskStatus.OPEN) {
                    Task updated = new Task(t.id(), t.chatId(), description, t.creatorUserId(), t.creatorName(), t.assignee(), deadline, t.status(), t.createdAt(), t.completedAt(), t.reminderSent(), category);
                    list.set(i, updated);
                    return Optional.of(updated);
                }
            }
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
