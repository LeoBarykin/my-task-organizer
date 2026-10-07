package com.example.telegrambot.bot;

import com.example.telegrambot.service.TaskService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Translates incoming Telegram updates into messages using {@link TaskService}. */
public final class TelegramBot implements LongPollingUpdateConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramBot.class);
    private final TelegramClient client;
    private final TaskService tasks;
    private final java.util.Set<Long> allowedChats;

    public TelegramBot(TelegramClient client, TaskService tasks) {
        this(client, tasks, java.util.Set.of());
    }

    public TelegramBot(TelegramClient client, TaskService tasks, java.util.Set<Long> allowedChats) { 
        this.client = client; 
        this.tasks = tasks; 
        this.allowedChats = allowedChats;
        this.tasks.setGroupAdminsProvider(this::getChatAdministrators);
    }

    @Override public void consume(List<Update> updates) {
        for (Update update : updates) {
            long chatId = getChatId(update);
            if (!allowedChats.isEmpty() && !allowedChats.contains(chatId)) {
                LOG.warn("🔒 Blocked unauthorized access attempt from Chat ID: {}", chatId);
                continue;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                respond(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                respondCallback(update.getCallbackQuery());
            }
        }
    }

    private long getChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        } else if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return 0;
    }

    private void respond(Message incoming) {
        if (incoming.getFrom() == null) return;
        
        BotReply reply = tasks.replyTo(
                incoming.getChatId(), 
                incoming.getFrom().getId(),
                incoming.getFrom().getFirstName(), 
                incoming.getFrom().getUserName(), 
                incoming.getText()
        );
        
        SendMessage.SendMessageBuilder messageBuilder = SendMessage.builder()
                .chatId(incoming.getChatId())
                .text(reply.text());

        if (reply.inlineKeyboard().isEmpty()) {
            if (reply.buttons().isEmpty()) {
                messageBuilder.replyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build());
            } else {
                messageBuilder.replyMarkup(buildKeyboard(reply.buttons()));
            }
        } else {
            messageBuilder.replyMarkup(buildInlineKeyboard(reply.inlineKeyboard()));
        }

        try { 
            client.execute(messageBuilder.build()); 
        } catch (Exception exception) { 
            LOG.error("Could not send a reply to chat {}", incoming.getChatId(), exception); 
        }
    }

    private void respondCallback(CallbackQuery callbackQuery) {
        if (callbackQuery.getFrom() == null) return;
        
        long chatId = callbackQuery.getMessage().getChatId();
        long userId = callbackQuery.getFrom().getId();
        String firstName = callbackQuery.getFrom().getFirstName();
        String username = callbackQuery.getFrom().getUserName();
        String data = callbackQuery.getData();
        
        BotReply reply = tasks.handleCallback(chatId, userId, firstName, username, data);
        
        if (reply.editCurrentMessage()) {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(callbackQuery.getMessage().getMessageId())
                    .text(reply.text())
                    .parseMode("Markdown")
                    .replyMarkup(reply.inlineKeyboard().isEmpty() ? null : buildInlineKeyboard(reply.inlineKeyboard()))
                    .build();
            try {
                client.execute(edit);
                
                AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackQuery.getId())
                        .build();
                client.execute(answer);
            } catch (Exception exception) {
                LOG.error("Could not edit message in chat {}", chatId, exception);
            }
            return;
        }

        SendMessage.SendMessageBuilder messageBuilder = SendMessage.builder()
                .chatId(chatId)
                .text(reply.text());

        if (reply.inlineKeyboard().isEmpty()) {
            if (reply.buttons().isEmpty()) {
                messageBuilder.replyMarkup(ReplyKeyboardRemove.builder().removeKeyboard(true).build());
            } else {
                messageBuilder.replyMarkup(buildKeyboard(reply.buttons()));
            }
        } else {
            messageBuilder.replyMarkup(buildInlineKeyboard(reply.inlineKeyboard()));
        }

        try { 
            client.execute(messageBuilder.build()); 
            
            AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .build();
            client.execute(answer);
        } catch (Exception exception) { 
            LOG.error("Could not send callback reply or answer callback query to chat {}", chatId, exception); 
        }
    }

    private List<String> getChatAdministrators(long chatId) {
        try {
            GetChatAdministrators getAdmins = GetChatAdministrators.builder()
                    .chatId(chatId)
                    .build();
            List<ChatMember> admins = client.execute(getAdmins);
            List<String> usernames = new java.util.ArrayList<>();
            for (ChatMember admin : admins) {
                if (admin.getUser() != null) {
                    String username = admin.getUser().getUserName();
                    if (username != null && !username.isBlank()) {
                        usernames.add("@" + username);
                    } else {
                        String firstName = admin.getUser().getFirstName();
                        if (firstName != null && !firstName.isBlank()) {
                            usernames.add(firstName);
                        }
                    }
                }
            }
            return usernames;
        } catch (Exception e) {
            LOG.error("Failed to get chat administrators for chat {}", chatId, e);
            return List.of();
        }
    }

    private ReplyKeyboardMarkup buildKeyboard(List<String> buttons) {
        ReplyKeyboardMarkup.ReplyKeyboardMarkupBuilder builder = ReplyKeyboardMarkup.builder()
                .resizeKeyboard(true)
                .oneTimeKeyboard(false);

        // Group buttons into rows of max 2 buttons per row
        KeyboardRow currentRow = new KeyboardRow();
        for (String button : buttons) {
            currentRow.add(button);
            if (currentRow.size() == 2) {
                builder.keyboardRow(currentRow);
                currentRow = new KeyboardRow();
            }
        }
        if (!currentRow.isEmpty()) {
            builder.keyboardRow(currentRow);
        }
        return builder.build();
    }

    private InlineKeyboardMarkup buildInlineKeyboard(List<List<InlineButton>> inlineKeyboard) {
        List<InlineKeyboardRow> rows = new java.util.ArrayList<>();
        for (List<InlineButton> rowButtons : inlineKeyboard) {
            List<InlineKeyboardButton> row = new java.util.ArrayList<>();
            for (InlineButton btn : rowButtons) {
                row.add(InlineKeyboardButton.builder()
                        .text(btn.text())
                        .callbackData(btn.callbackData())
                        .build());
            }
            rows.add(new InlineKeyboardRow(row));
        }
        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }
}
