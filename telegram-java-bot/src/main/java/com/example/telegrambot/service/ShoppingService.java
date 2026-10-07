package com.example.telegrambot.service;

import com.example.telegrambot.bot.BotReply;
import com.example.telegrambot.bot.InlineButton;
import com.example.telegrambot.domain.ShoppingItem;
import com.example.telegrambot.repository.ShoppingRepository;
import java.util.ArrayList;
import java.util.List;

public final class ShoppingService {
    private final ShoppingRepository repository;

    public ShoppingService(ShoppingRepository repository) {
        this.repository = repository;
    }

    public BotReply handleBuy(long chatId, long userId, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new BotReply("⚠️ Usage: /buy <item1>, <item2>, ... (e.g. /buy Milk, Bread)", TaskService.CART_MENU_BUTTONS);
        }
        String[] items = arguments.split(",");
        List<String> added = new ArrayList<>();
        for (String item : items) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                repository.add(chatId, trimmed, userId);
                added.add(trimmed);
            }
        }
        if (added.isEmpty()) {
            return new BotReply("⚠️ No valid items entered.", TaskService.CART_MENU_BUTTONS);
        }
        return new BotReply("🛒 Added to shopping cart: " + String.join(", ", added), TaskService.CART_MENU_BUTTONS);
    }

    public BotReply cart(long chatId) {
        return cart(chatId, false);
    }

    public BotReply cart(long chatId, boolean editCurrentMessage) {
        List<ShoppingItem> items = repository.list(chatId);
        if (items.isEmpty()) {
            return new BotReply("🛒 Your Family Shopping Cart is currently empty!", TaskService.CART_MENU_BUTTONS, List.of(), editCurrentMessage);
        }

        StringBuilder text = new StringBuilder("🛒 **Family Shopping Cart** (")
                .append(items.size())
                .append(" items):\n")
                .append("Tap any item below to cross it off as you put it in your cart:\n\n");

        List<List<InlineButton>> inlineKeyboard = new ArrayList<>();
        List<InlineButton> currentRow = new ArrayList<>();

        for (ShoppingItem item : items) {
            text.append("- ").append(item.name()).append("\n");
            
            String btnText = item.name();
            if (btnText.length() > 20) {
                btnText = btnText.substring(0, 17) + "...";
            }
            currentRow.add(new InlineButton("🛒 " + btnText, "cart_buy:" + item.id()));
            
            if (currentRow.size() == 2) {
                inlineKeyboard.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            inlineKeyboard.add(currentRow);
        }

        // Add control button row at the bottom
        inlineKeyboard.add(List.of(
            new InlineButton("❌ Clear Cart", "cart_clear")
        ));

        return new BotReply(text.toString().trim(), TaskService.CART_MENU_BUTTONS, inlineKeyboard, editCurrentMessage);
    }

    public BotReply handleCallback(long chatId, long userId, String callbackData) {
        String data = callbackData == null ? "" : callbackData.trim();
        if (data.startsWith("cart_buy:")) {
            try {
                long id = Long.parseLong(data.substring(9));
                repository.delete(chatId, id);
                return cart(chatId, true); // Re-render shopping list and edit the current message!
            } catch (NumberFormatException e) {
                return new BotReply("⚠️ Invalid item selection.", TaskService.CART_MENU_BUTTONS);
            }
        } else if (data.equals("cart_clear")) {
            repository.clear(chatId);
            return new BotReply("🛒 Cart cleared! Your Family Shopping Cart is now empty.", TaskService.CART_MENU_BUTTONS, List.of(), true);
        }
        return new BotReply("Unknown shopping action.", TaskService.CART_MENU_BUTTONS);
    }
}
