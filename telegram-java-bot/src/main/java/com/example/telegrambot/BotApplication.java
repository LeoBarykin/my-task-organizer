package com.example.telegrambot;

import com.example.telegrambot.repository.TaskRepository;
import com.example.telegrambot.repository.H2TaskRepository;
import com.example.telegrambot.repository.ShoppingRepository;
import com.example.telegrambot.repository.H2ShoppingRepository;
import com.example.telegrambot.repository.UserSessionRepository;
import com.example.telegrambot.repository.InMemoryUserSessionRepository;
import com.example.telegrambot.bot.TelegramBot;
import com.example.telegrambot.service.TaskService;
import com.example.telegrambot.service.ShoppingService;
import com.example.telegrambot.service.ReminderService;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import java.nio.file.Path;

/** Application entry point. The token is intentionally read only from an environment variable. */
public final class BotApplication {
    private BotApplication() { }

    public static void main(String[] args) throws Exception {
        String token = "";
//        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN is required. See README.md for setup.");
        }

        TelegramClient client = new OkHttpTelegramClient(token);
        String databasePath = System.getenv().getOrDefault("TASKS_DB_PATH", "data/family-tasks");
        TaskRepository tasks = new H2TaskRepository(Path.of(databasePath));
        ShoppingRepository shoppingRepository = new H2ShoppingRepository(Path.of(databasePath));
        UserSessionRepository sessionRepository = new InMemoryUserSessionRepository();

        String allowedChatsEnv = System.getenv("ALLOWED_CHATS");
        java.util.Set<Long> allowedChats = new java.util.HashSet<>();
        if (allowedChatsEnv != null && !allowedChatsEnv.isBlank()) {
            for (String idStr : allowedChatsEnv.split(",")) {
                try {
                    allowedChats.add(Long.parseLong(idStr.trim()));
                } catch (NumberFormatException e) {
                    System.err.println("Warning: Invalid Allowed Chat ID skipped: " + idStr);
                }
            }
        }

        TaskService taskService = new TaskService(tasks, sessionRepository);
        ShoppingService shoppingService = new ShoppingService(shoppingRepository);
        taskService.setShoppingService(shoppingService);

        TelegramBot bot = new TelegramBot(client, taskService, allowedChats);
        ReminderService reminderService = new ReminderService(client, tasks);
        reminderService.start();

        try (TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication()) {
            application.registerBot(token, bot);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    reminderService.stop();
                    application.close();
                } catch (Exception e) {
                    System.err.println("Failed to close Telegram bots application: " + e.getMessage());
                }
            }, "telegram-bot-shutdown"));
            System.out.println("Bot is running. Press Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }
}
