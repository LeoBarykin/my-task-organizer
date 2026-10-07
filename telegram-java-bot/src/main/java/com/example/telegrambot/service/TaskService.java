package com.example.telegrambot.service;

import com.example.telegrambot.domain.Task;
import com.example.telegrambot.domain.TaskStatus;
import com.example.telegrambot.domain.TaskCategory;
import com.example.telegrambot.domain.UserSession;
import com.example.telegrambot.domain.UserState;
import com.example.telegrambot.repository.TaskRepository;
import com.example.telegrambot.repository.UserSessionRepository;
import com.example.telegrambot.bot.BotReply;
import com.example.telegrambot.bot.InlineButton;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Command-level task workflow with state machine for interactive button clicks. */
public final class TaskService {
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    
    public static final List<String> MAIN_MENU_BUTTONS = List.of(
        "📋 Tasks", "🛒 Cart", "ℹ️ Help"
    );

    public static final List<String> TASKS_MENU_BUTTONS = List.of(
        "➕ Add Task", "📋 List Tasks", "📅 Today", "📅 Week",
        "⚠️ Overdue", "⬅️ Main Menu"
    );

    public static final List<String> CART_MENU_BUTTONS = List.of(
        "📋 View Cart", "➕ Add to Cart", "⬅️ Main Menu"
    );
    
    private static final List<String> CANCEL_BUTTON = List.of("❌ Cancel");

    private final TaskRepository repository;
    private final UserSessionRepository sessionRepository;
    private final Clock clock;
    private java.util.function.Function<Long, List<String>> groupAdminsProvider;
    private ShoppingService shoppingService;

    public void setGroupAdminsProvider(java.util.function.Function<Long, List<String>> provider) {
        this.groupAdminsProvider = provider;
    }

    public void setShoppingService(ShoppingService shoppingService) {
        this.shoppingService = shoppingService;
    }

    public TaskService(TaskRepository repository, UserSessionRepository sessionRepository) {
        this(repository, sessionRepository, Clock.systemUTC());
    }
    
    public TaskService(TaskRepository repository, UserSessionRepository sessionRepository, Clock clock) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    public BotReply replyTo(long chatId, long userId, String firstName, String username, String text) {
        String input = text == null ? "" : text.trim();
        
        // Handle cancel universally if the user sends "❌ Cancel" or "/cancel"
        if (input.equalsIgnoreCase("❌ Cancel") || input.equalsIgnoreCase("/cancel")) {
            UserSession session = sessionRepository.getSession(userId);
            List<String> returnButtons = MAIN_MENU_BUTTONS;
            if (session.state() == UserState.AWAITING_CART_ITEM) {
                returnButtons = CART_MENU_BUTTONS;
            } else if (session.state() != UserState.IDLE) {
                returnButtons = TASKS_MENU_BUTTONS;
            }
            sessionRepository.clearSession(userId);
            return new BotReply("Operation cancelled.", returnButtons);
        }

        UserSession session = sessionRepository.getSession(userId);
        
        try {
            if (session.state() != UserState.IDLE) {
                return handleStateFlow(chatId, userId, firstName, username, session, input);
            }
            return handleIdleState(chatId, userId, firstName, input);
        } catch (IllegalArgumentException exception) {
            // Keep user in their current state, but return the error message with appropriate state buttons
            List<String> buttons = (session.state() == UserState.IDLE) ? MAIN_MENU_BUTTONS : getButtonsForState(session.state());
            return new BotReply("⚠️ " + exception.getMessage(), buttons);
        } catch (IllegalStateException exception) {
            System.err.println("TaskService IllegalStateException encountered:");
            exception.printStackTrace();
            return new BotReply("⚠️ I couldn't save the task right now. Please try again.", MAIN_MENU_BUTTONS);
        }
    }

    private List<String> getButtonsForState(UserState state) {
        return switch (state) {
            case AWAITING_ASSIGNEE -> List.of("-", "❌ Cancel");
            default -> CANCEL_BUTTON;
        };
    }

    private BotReply handleIdleState(long chatId, long userId, String firstName, String input) {
        String cleaned = input.trim().toLowerCase(Locale.ROOT);
        
        // Root Menu Navigations
        if (cleaned.equals("📋 tasks")) {
            return new BotReply("📋 Tasks Menu opened. Choose an option below:", TASKS_MENU_BUTTONS);
        }
        if (cleaned.equals("🛒 cart")) {
            return shoppingService != null ? shoppingService.cart(chatId) : new BotReply("⚠️ Shopping service not available.");
        }
        if (cleaned.equals("⬅️ main menu")) {
            return new BotReply("Main Menu opened:", MAIN_MENU_BUTTONS);
        }

        // Cart Sub-Menu Actions
        if (cleaned.equals("📋 view cart")) {
            return shoppingService != null ? shoppingService.cart(chatId) : new BotReply("⚠️ Shopping service not available.");
        }
        if (cleaned.equals("➕ add to cart")) {
            sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_CART_ITEM, null, null, null, null));
            return new BotReply("🛒 Please enter the item(s) you want to add to your shopping cart (separated by commas):", CANCEL_BUTTON);
        }

        // Explicitly match exact buttons first
        if (cleaned.equals("➕ add task")) {
            sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_CATEGORY, null, null, null, null));
            List<List<InlineButton>> inline = new java.util.ArrayList<>();
            TaskCategory[] cats = TaskCategory.values();
            for (int i = 0; i < cats.length; i += 2) {
                inline.add(List.of(
                    new InlineButton(cats[i].displayName(), "category:" + cats[i].name()),
                    new InlineButton(cats[i + 1].displayName(), "category:" + cats[i + 1].name())
                ));
            }
            return new BotReply("🏷️ Select a category tag for the new task:", CANCEL_BUTTON, inline);
        }
        if (cleaned.equals("🛒 shopping cart")) {
            return shoppingService != null ? shoppingService.cart(chatId) : new BotReply("⚠️ Shopping service not available.");
        }
        if (cleaned.equals("📋 list tasks")) {
            return list("Open tasks", repository.findOpen(chatId));
        }
        if (cleaned.equals("📅 today")) {
            return today(chatId);
        }
        if (cleaned.equals("📅 week")) {
            return week(chatId);
        }
        if (cleaned.equals("⚠️ overdue")) {
            return list("Overdue tasks", repository.findOverdue(chatId, Instant.now(clock)));
        }
        if (cleaned.equals("✅ done task")) {
            sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_DONE_ID, null, null, null, null));
            return new BotReply("Please enter the ID of the task to mark as done:", CANCEL_BUTTON);
        }
        if (cleaned.equals("🗑 delete task")) {
            sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_DELETE_ID, null, null, null, null));
            return new BotReply("Please enter the ID of the task to delete:", CANCEL_BUTTON);
        }
        if (cleaned.equals("ℹ️ help") || cleaned.equals("help")) {
            return new BotReply(help(), MAIN_MENU_BUTTONS);
        }

        // Map general text commands or fallback slash commands
        String[] words = input.split("\\s+", 2);
        String command = words[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        String arguments = words.length == 2 ? words[1].trim() : "";

        return switch (command) {
            case "/start", "/help", "help" -> new BotReply(help(), MAIN_MENU_BUTTONS);
            case "/buy" -> {
                yield shoppingService != null ? shoppingService.handleBuy(chatId, userId, arguments) : new BotReply("⚠️ Shopping service not available.");
            }
            case "/cart" -> {
                yield shoppingService != null ? shoppingService.cart(chatId) : new BotReply("⚠️ Shopping service not available.");
            }
            case "/add" -> {
                if (!arguments.isEmpty()) {
                    // Support legacy inline creation: /add description | deadline | @assignee
                    yield addLegacy(chatId, userId, firstName, arguments);
                }
                sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_CATEGORY, null, null, null, null));
                List<List<InlineButton>> inline = new java.util.ArrayList<>();
                TaskCategory[] cats = TaskCategory.values();
                for (int i = 0; i < cats.length; i += 2) {
                    inline.add(List.of(
                        new InlineButton(cats[i].displayName(), "category:" + cats[i].name()),
                        new InlineButton(cats[i + 1].displayName(), "category:" + cats[i + 1].name())
                    ));
                }
                yield new BotReply("🏷️ Select a category tag for the new task:", CANCEL_BUTTON, inline);
            }
            case "/tasks" -> list("Open tasks", repository.findOpen(chatId));
            case "/today" -> today(chatId);
            case "/week" -> week(chatId);
            case "/overdue" -> list("Overdue tasks", repository.findOverdue(chatId, Instant.now(clock)));
            case "/done" -> {
                if (!arguments.isEmpty()) {
                    yield doneLegacy(chatId, arguments);
                }
                sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_DONE_ID, null, null, null, null));
                yield new BotReply("Please enter the ID of the task to mark as done:", CANCEL_BUTTON);
            }
            case "/delete" -> {
                if (!arguments.isEmpty()) {
                    yield deleteLegacy(chatId, arguments);
                }
                sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_DELETE_ID, null, null, null, null));
                yield new BotReply("Please enter the ID of the task to delete:", CANCEL_BUTTON);
            }
            case "/assign" -> assignLegacy(chatId, arguments);
            case "/edit" -> editLegacy(chatId, arguments);
            default -> new BotReply("I manage family tasks. Use the buttons below or type /help.", MAIN_MENU_BUTTONS);
        };
    }

    private BotReply handleStateFlow(long chatId, long userId, String firstName, String username, UserSession session, String input) {
        return switch (session.state()) {
            case AWAITING_CATEGORY -> {
                List<List<InlineButton>> inline = new java.util.ArrayList<>();
                TaskCategory[] cats = TaskCategory.values();
                for (int i = 0; i < cats.length; i += 2) {
                    inline.add(List.of(
                        new InlineButton(cats[i].displayName(), "category:" + cats[i].name()),
                        new InlineButton(cats[i + 1].displayName(), "category:" + cats[i + 1].name())
                    ));
                }
                yield new BotReply("⚠️ Please select a category tag by clicking one of the buttons below:", CANCEL_BUTTON, inline);
            }
            case AWAITING_DESCRIPTION -> {
                String description = requiredDescription(input);
                sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_DEADLINE, session.tempCategory(), description, null, null));
                List<List<InlineButton>> inline = List.of(
                    List.of(
                        new InlineButton("Today 18:00", "deadline:today_18"),
                        new InlineButton("Tomorrow 12:00", "deadline:tomorrow_12")
                    ),
                    List.of(
                        new InlineButton("Tomorrow 18:00", "deadline:tomorrow_18"),
                        new InlineButton("In 1 Week", "deadline:next_week_12")
                    )
                );
                yield new BotReply("Enter the deadline (e.g., 2026-09-15 18:00):", CANCEL_BUTTON, inline);
            }
            case AWAITING_DEADLINE -> {
                Instant deadline = deadline(input); // Validate deadline parses correctly
                sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_ASSIGNEE, session.tempCategory(), session.tempDescription(), input, null));
                
                List<List<InlineButton>> inline = new java.util.ArrayList<>();
                String myName = (username != null && !username.isBlank()) ? "@" + username : firstName;
                inline.add(List.of(new InlineButton("👤 Me (" + myName + ")", "assign:me")));
                if (chatId < 0) {
                    inline.add(List.of(new InlineButton("👥 Choose from Group", "assign:group")));
                }
                inline.add(List.of(
                    new InlineButton("🤷 Nobody", "assign:none"),
                    new InlineButton("❌ Cancel", "cancel_add")
                ));
                yield new BotReply("Enter assignee's Telegram username (e.g., @alex) or choose below:", List.of("-", "❌ Cancel"), inline);
            }
            case AWAITING_ASSIGNEE -> {
                String assignee = optionalAssignee(input);
                Instant deadline = deadline(session.tempDeadline());
                Task task = repository.create(chatId, session.tempDescription(), userId, safeName(firstName), assignee, deadline, session.tempCategory());
                sessionRepository.clearSession(userId);
                yield new BotReply("✅ Added #" + task.id() + ": " + task.description() + "\nDue: " + formatDeadline(deadline) + assigneeLine(assignee), TASKS_MENU_BUTTONS);
            }
            case AWAITING_DONE_ID -> {
                long id = id(input, "Please enter a valid numeric task ID.");
                String message = repository.markDone(chatId, id, Instant.now(clock))
                        .map(t -> "✅ Completed #" + t.id() + ": " + t.description())
                        .orElse("No open task #" + id + " in this chat.");
                sessionRepository.clearSession(userId);
                yield new BotReply(message, TASKS_MENU_BUTTONS);
            }
            case AWAITING_DELETE_ID -> {
                long id = id(input, "Please enter a valid numeric task ID.");
                String message = repository.delete(chatId, id)
                        .map(t -> "🗑 Deleted #" + t.id() + ": " + t.description())
                        .orElse("No task #" + id + " in this chat.");
                sessionRepository.clearSession(userId);
                yield new BotReply(message, TASKS_MENU_BUTTONS);
            }
            case AWAITING_CART_ITEM -> {
                BotReply reply = shoppingService != null ? shoppingService.handleBuy(chatId, userId, input) : new BotReply("⚠️ Shopping service not available.");
                sessionRepository.clearSession(userId);
                yield reply;
            }
            default -> {
                sessionRepository.clearSession(userId);
                yield new BotReply("Something went wrong. Resetting back to start.", TASKS_MENU_BUTTONS);
            }
        };
    }

    private BotReply addLegacy(long chatId, long userId, String firstName, String arguments) {
        String[] fields = arguments.split("\\|", -1);
        if (fields.length < 2 || fields.length > 3 || fields[0].isBlank() || fields[1].isBlank()) {
            throw new IllegalArgumentException("Usage: /add description | 2026-09-15 18:00 | @assignee");
        }
        String description = requiredDescription(fields[0]);
        Instant deadline = deadline(fields[1]);
        String assignee = fields.length == 3 ? optionalAssignee(fields[2]) : null;
        Task task = repository.create(chatId, description, userId, safeName(firstName), assignee, deadline, TaskCategory.CHORES);
        return new BotReply("✅ Added #" + task.id() + ": " + description + "\nDue: " + formatDeadline(deadline) + assigneeLine(assignee), TASKS_MENU_BUTTONS);
    }

    private BotReply doneLegacy(long chatId, String arguments) {
        long id = id(arguments, "Usage: /done <task number>");
        String text = repository.markDone(chatId, id, Instant.now(clock)).map(t -> "✅ Completed #" + t.id() + ": " + t.description()).orElse("No open task #" + id + " in this chat.");
        return new BotReply(text, TASKS_MENU_BUTTONS);
    }

    private BotReply deleteLegacy(long chatId, String arguments) {
        long id = id(arguments, "Usage: /delete <task number>");
        String text = repository.delete(chatId, id).map(t -> "🗑 Deleted #" + t.id() + ": " + t.description()).orElse("No task #" + id + " in this chat.");
        return new BotReply(text, TASKS_MENU_BUTTONS);
    }

    private BotReply assignLegacy(long chatId, String arguments) {
        String[] fields = arguments.split("\\s+", 2);
        if (fields.length != 2) {
            throw new IllegalArgumentException("Usage: /assign <task number> @username");
        }
        long id = id(fields[0], "Usage: /assign <task number> @username");
        String assignee = optionalAssignee(fields[1]);
        if (assignee == null) {
            throw new IllegalArgumentException("Use a Telegram username, for example @alex.");
        }
        String text = repository.assign(chatId, id, assignee).map(t -> "👤 Assigned #" + t.id() + " to " + assignee).orElse("No open task #" + id + " in this chat.");
        return new BotReply(text, TASKS_MENU_BUTTONS);
    }

    private BotReply editLegacy(long chatId, String arguments) {
        String[] idAndRest = arguments.split("\\s+", 2);
        if (idAndRest.length != 2) {
            throw new IllegalArgumentException("Usage: /edit <number> description | 2026-09-15 18:00");
        }
        long id = id(idAndRest[0], "Usage: /edit <number> description | 2026-09-15 18:00");
        String[] fields = idAndRest[1].split("\\|", -1);
        if (fields.length != 2) {
            throw new IllegalArgumentException("Usage: /edit <number> description | 2026-09-15 18:00");
        }
        String description = requiredDescription(fields[0]);
        Instant deadline = deadline(fields[1]);
        TaskCategory category = repository.findOpen(chatId).stream()
                .filter(t -> t.id() == id)
                .findFirst()
                .map(Task::category)
                .orElse(TaskCategory.CHORES);
        String text = repository.edit(chatId, id, description, deadline, category).map(t -> "✏️ Updated " + taskLine(t)).orElse("No open task #" + id + " in this chat.");
        return new BotReply(text, TASKS_MENU_BUTTONS);
    }

    private BotReply today(long chatId) {
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Instant start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        return list("Due today", repository.findOpenDueBetween(chatId, start, start.plusSeconds(86_400)));
    }

    private BotReply week(long chatId) {
        Instant now = Instant.now(clock);
        return list("Due in the next 7 days", repository.findOpenDueBetween(chatId, now, now.plusSeconds(7 * 86_400)));
    }

    private BotReply list(String title, List<Task> tasks) {
        if (tasks.isEmpty()) {
            return new BotReply(title + ": none 🎉", TASKS_MENU_BUTTONS);
        }
        StringBuilder reply = new StringBuilder(title).append(":\n");
        List<List<InlineButton>> inlineKeyboard = new java.util.ArrayList<>();
        for (Task task : tasks) {
            reply.append(taskLine(task)).append('\n');
            String buttonText = "#" + task.id() + ": " + task.description();
            if (buttonText.length() > 50) {
                buttonText = buttonText.substring(0, 47) + "...";
            }
            inlineKeyboard.add(List.of(new InlineButton(buttonText, "task:" + task.id())));
        }
        return new BotReply(reply.toString().trim(), TASKS_MENU_BUTTONS, inlineKeyboard);
    }

    public BotReply handleCallback(long chatId, long userId, String firstName, String username, String callbackData) {
        String data = callbackData == null ? "" : callbackData.trim();
        if (data.startsWith("cart_")) {
            return shoppingService != null ? shoppingService.handleCallback(chatId, userId, data) : new BotReply("⚠️ Shopping service not available.");
        }
        if (data.startsWith("category:")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_CATEGORY) {
                return new BotReply("⚠️ This category selection is no longer valid.", TASKS_MENU_BUTTONS);
            }
            String categoryStr = data.substring(9);
            TaskCategory category = TaskCategory.valueOf(categoryStr);
            sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_DESCRIPTION, category, null, null, null));
            return new BotReply("Please enter the task description:", CANCEL_BUTTON);
        } else if (data.equals("cancel_add")) {
            sessionRepository.clearSession(userId);
            return new BotReply("Operation cancelled.", TASKS_MENU_BUTTONS);
        } else if (data.equals("assign:me")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_ASSIGNEE) {
                return new BotReply("⚠️ This assignment helper session is no longer active.", TASKS_MENU_BUTTONS);
            }
            String assignee = (username != null && !username.isBlank()) ? "@" + username : firstName;
            Instant deadline = deadline(session.tempDeadline());
            Task task = repository.create(chatId, session.tempDescription(), userId, safeName(firstName), assignee, deadline, session.tempCategory());
            sessionRepository.clearSession(userId);
            return new BotReply("✅ Added #" + task.id() + ": " + task.description() + "\nDue: " + formatDeadline(deadline) + assigneeLine(assignee), TASKS_MENU_BUTTONS);
        } else if (data.equals("assign:none")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_ASSIGNEE) {
                return new BotReply("⚠️ This assignment helper session is no longer active.", TASKS_MENU_BUTTONS);
            }
            Instant deadline = deadline(session.tempDeadline());
            Task task = repository.create(chatId, session.tempDescription(), userId, safeName(firstName), null, deadline, session.tempCategory());
            sessionRepository.clearSession(userId);
            return new BotReply("✅ Added #" + task.id() + ": " + task.description() + "\nDue: " + formatDeadline(deadline) + assigneeLine(null), TASKS_MENU_BUTTONS);
        } else if (data.equals("assign_back")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_ASSIGNEE) {
                return new BotReply("⚠️ This assignment helper session is no longer active.", TASKS_MENU_BUTTONS);
            }
            List<List<InlineButton>> inline = new java.util.ArrayList<>();
            String myName = (username != null && !username.isBlank()) ? "@" + username : firstName;
            inline.add(List.of(new InlineButton("👤 Me (" + myName + ")", "assign:me")));
            if (chatId < 0) {
                inline.add(List.of(new InlineButton("👥 Choose from Group", "assign:group")));
            }
            inline.add(List.of(
                new InlineButton("🤷 Nobody", "assign:none"),
                new InlineButton("❌ Cancel", "cancel_add")
            ));
            return new BotReply("Enter assignee's Telegram username (e.g., @alex) or choose below:", List.of("-", "❌ Cancel"), inline);
        } else if (data.equals("assign:group")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_ASSIGNEE) {
                return new BotReply("⚠️ This assignment helper session is no longer active.", TASKS_MENU_BUTTONS);
            }
            List<String> admins = groupAdminsProvider != null ? groupAdminsProvider.apply(chatId) : List.of();
            if (admins.isEmpty()) {
                return new BotReply("⚠️ Could not fetch group administrators. Please type the assignee's username manually:", List.of("-", "❌ Cancel"));
            }
            List<List<InlineButton>> inline = new java.util.ArrayList<>();
            List<InlineButton> row = new java.util.ArrayList<>();
            for (String admin : admins) {
                row.add(new InlineButton(admin, "assign_to:" + admin));
                if (row.size() == 2) {
                    inline.add(row);
                    row = new java.util.ArrayList<>();
                }
            }
            if (!row.isEmpty()) {
                inline.add(row);
            }
            inline.add(List.of(new InlineButton("⬅️ Back", "assign_back")));
            return new BotReply("👥 Choose an assignee from group administrators:", List.of("-", "❌ Cancel"), inline);
        } else if (data.startsWith("assign_to:")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_ASSIGNEE) {
                return new BotReply("⚠️ This assignment helper session is no longer active.", TASKS_MENU_BUTTONS);
            }
            String assignee = data.substring(10);
            Instant deadline = deadline(session.tempDeadline());
            Task task = repository.create(chatId, session.tempDescription(), userId, safeName(firstName), assignee, deadline, session.tempCategory());
            sessionRepository.clearSession(userId);
            return new BotReply("✅ Added #" + task.id() + ": " + task.description() + "\nDue: " + formatDeadline(deadline) + assigneeLine(assignee), TASKS_MENU_BUTTONS);
        } else if (data.equals("back_to_list")) {
            return list("Open tasks", repository.findOpen(chatId));
        } else if (data.startsWith("task:")) {
            long id = id(data.substring(5), "Invalid task ID.");
            return repository.findOpen(chatId).stream()
                    .filter(t -> t.id() == id)
                    .findFirst()
                    .map(t -> {
                        String text = "Task " + taskLine(t);
                        List<List<InlineButton>> inline = new java.util.ArrayList<>();
                        inline.add(List.of(
                            new InlineButton("✅ Complete", "complete:" + id),
                            new InlineButton("❌ Cancel", "cancel:" + id)
                        ));
                        if (t.assignee() == null || t.assignee().isBlank()) {
                            inline.add(List.of(new InlineButton("👤 Claim Task", "claim:" + id)));
                        }
                        inline.add(List.of(new InlineButton("🗑 Delete Task", "delete:" + id)));
                        inline.add(List.of(new InlineButton("⬅️ Back to List", "back_to_list")));
                        return new BotReply(text, TASKS_MENU_BUTTONS, inline);
                    })
                    .orElseGet(() -> new BotReply("⚠️ Task #" + id + " was not found or is already closed.", TASKS_MENU_BUTTONS));
        } else if (data.startsWith("claim:")) {
            long id = id(data.substring(6), "Invalid task ID.");
            String claimer = (username != null && !username.isBlank()) ? "@" + username : firstName;
            repository.assign(chatId, id, claimer);
            return repository.findOpen(chatId).stream()
                    .filter(t -> t.id() == id)
                    .findFirst()
                    .map(t -> {
                        String text = "Task " + taskLine(t);
                        List<List<InlineButton>> inline = new java.util.ArrayList<>();
                        inline.add(List.of(
                            new InlineButton("✅ Complete", "complete:" + id),
                            new InlineButton("❌ Cancel", "cancel:" + id)
                        ));
                        // Since it's claimed, we omit the [Claim Task] button
                        inline.add(List.of(new InlineButton("🗑 Delete Task", "delete:" + id)));
                        inline.add(List.of(new InlineButton("⬅️ Back to List", "back_to_list")));
                        return new BotReply(text, TASKS_MENU_BUTTONS, inline, true); // editCurrentMessage = true
                    })
                    .orElseGet(() -> new BotReply("⚠️ Task #" + id + " was not found or is already closed.", TASKS_MENU_BUTTONS));
        } else if (data.startsWith("delete:")) {
            long id = id(data.substring(7), "Invalid task ID.");
            String message = repository.delete(chatId, id)
                    .map(t -> "🗑 Deleted #" + t.id() + ": " + t.description())
                    .orElse("No task #" + id + " in this chat.");
            return new BotReply(message, TASKS_MENU_BUTTONS);
        } else if (data.startsWith("complete:")) {
            long id = id(data.substring(9), "Invalid task ID.");
            String message = repository.markDone(chatId, id, Instant.now(clock))
                    .map(t -> "✅ Completed #" + t.id() + ": " + t.description())
                    .orElse("No open task #" + id + " in this chat.");
            return new BotReply(message, TASKS_MENU_BUTTONS);
        } else if (data.startsWith("cancel:")) {
            long id = id(data.substring(7), "Invalid task ID.");
            String message = repository.cancel(chatId, id, Instant.now(clock))
                    .map(t -> "❌ Cancelled #" + t.id() + ": " + t.description())
                    .orElse("No open task #" + id + " in this chat.");
            return new BotReply(message, TASKS_MENU_BUTTONS);
        } else if (data.startsWith("deadline:")) {
            UserSession session = sessionRepository.getSession(userId);
            if (session.state() != UserState.AWAITING_DEADLINE) {
                return new BotReply("⚠️ This shortcut is no longer valid.", TASKS_MENU_BUTTONS);
            }
            String shortcut = data.substring(9);
            LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            String deadlineStr = switch (shortcut) {
                case "today_18" -> date.toString() + " 18:00";
                case "tomorrow_12" -> date.plusDays(1).toString() + " 12:00";
                case "tomorrow_18" -> date.plusDays(1).toString() + " 18:00";
                case "next_week_12" -> date.plusDays(7).toString() + " 12:00";
                default -> throw new IllegalArgumentException("Unknown deadline shortcut: " + shortcut);
            };
            
            Instant deadline = deadline(deadlineStr);
            sessionRepository.saveSession(userId, new UserSession(UserState.AWAITING_ASSIGNEE, session.tempCategory(), session.tempDescription(), deadlineStr, null));
            
            List<List<InlineButton>> inline = new java.util.ArrayList<>();
            String myName = (username != null && !username.isBlank()) ? "@" + username : firstName;
            inline.add(List.of(new InlineButton("👤 Me (" + myName + ")", "assign:me")));
            if (chatId < 0) {
                inline.add(List.of(new InlineButton("👥 Choose from Group", "assign:group")));
            }
            inline.add(List.of(
                new InlineButton("🤷 Nobody", "assign:none"),
                new InlineButton("❌ Cancel", "cancel_add")
            ));
            return new BotReply("Enter assignee's Telegram username (e.g., @alex) or choose below:", List.of("-", "❌ Cancel"), inline);
        }
        return new BotReply("Unknown action.", TASKS_MENU_BUTTONS);
    }

    private String taskLine(Task task) {
        String categoryPrefix = task.category() != null ? " " + task.category().displayName() + " —" : "";
        return "#" + task.id() + categoryPrefix + " " + task.description() + "\n   Due: " + formatDeadline(task.deadline()) + assigneeLine(task.assignee());
    }

    private static String help() {
        return "Family task bot commands (deadlines are UTC):\n/add description | 2026-09-15 18:00 | @assignee\n/tasks — all open tasks\n/today — due today\n/week — due in the next 7 days\n/overdue — past due\n/done 12\n/assign 12 @alex\n/edit 12 new description | 2026-09-15 18:00\n/delete 12";
    }

    private static String requiredDescription(String value) {
        String description = value.trim();
        if (description.isEmpty() || description.length() > 500) {
            throw new IllegalArgumentException("Task description must be between 1 and 500 characters.");
        }
        return description;
    }

    private static String optionalAssignee(String value) {
        String assignee = value.trim();
        if (assignee.isEmpty() || assignee.equals("-") || assignee.equalsIgnoreCase("none")) {
            return null;
        }
        if (!assignee.matches("@[A-Za-z0-9_]{5,32}")) {
            throw new IllegalArgumentException("Assignee must be a Telegram username such as @alex, or - for nobody.");
        }
        return assignee;
    }

    private static long id(String value, String usage) {
        try {
            long id = Long.parseLong(value.trim());
            if (id < 1) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static Instant deadline(String value) {
        try {
            return LocalDateTime.parse(value.trim(), DEADLINE_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Deadline must look like 2026-09-15 18:00 (UTC).");
        }
    }

    private static String formatDeadline(Instant deadline) {
        return DEADLINE_FORMAT.withZone(ZoneOffset.UTC).format(deadline) + " UTC";
    }

    private static String assigneeLine(String assignee) {
        return assignee == null ? "" : "\n   Assigned: " + assignee;
    }

    private static String safeName(String firstName) {
        return firstName == null || firstName.isBlank() ? "Unknown" : firstName;
    }
}
