package com.example.telegrambot;

import static org.junit.jupiter.api.Assertions.*;

import com.example.telegrambot.bot.BotReply;
import com.example.telegrambot.bot.InlineButton;
import com.example.telegrambot.domain.ShoppingItem;
import com.example.telegrambot.repository.ShoppingRepository;
import com.example.telegrambot.service.ShoppingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShoppingServiceTest {
    private InMemoryShoppingRepository repository;
    private ShoppingService service;
    private static final long CHAT_ID = 202L;
    private static final long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        repository = new InMemoryShoppingRepository();
        service = new ShoppingService(repository);
    }

    @Test
    void testBuySingleAndMultipleItems() {
        // 1. Add single item
        BotReply reply1 = service.handleBuy(CHAT_ID, USER_ID, "Milk");
        assertTrue(reply1.text().contains("Added to shopping cart: Milk"));
        assertEquals(1, repository.list(CHAT_ID).size());
        assertEquals("Milk", repository.list(CHAT_ID).get(0).name());

        // 2. Add multiple items at once
        BotReply reply2 = service.handleBuy(CHAT_ID, USER_ID, " Apples, Bread,  Cheese ");
        assertTrue(reply2.text().contains("Added to shopping cart: Apples, Bread, Cheese"));
        assertEquals(4, repository.list(CHAT_ID).size()); // 1 (Milk) + 3 new
    }

    @Test
    void testCartInlineKeyboardRowPacking() {
        // Seed 3 items in the cart
        repository.add(CHAT_ID, "Eggs", USER_ID);
        repository.add(CHAT_ID, "Soap", USER_ID);
        repository.add(CHAT_ID, "Napkins", USER_ID);

        BotReply cartReply = service.cart(CHAT_ID);
        
        // Assertions on text content
        assertTrue(cartReply.text().contains("Family Shopping Cart"));
        assertTrue(cartReply.text().contains("Eggs"));
        assertTrue(cartReply.text().contains("Soap"));
        assertTrue(cartReply.text().contains("Napkins"));

        // Row packing check:
        // Item 1, 2 on Row 0.
        // Item 3 on Row 1.
        // Clear Cart button on Row 2.
        assertEquals(3, cartReply.inlineKeyboard().size());
        assertEquals(2, cartReply.inlineKeyboard().get(0).size()); // Eggs, Soap
        assertEquals(1, cartReply.inlineKeyboard().get(1).size()); // Napkins
        assertEquals(1, cartReply.inlineKeyboard().get(2).size()); // Clear Cart

        assertEquals("❌ Clear Cart", cartReply.inlineKeyboard().get(2).get(0).text());
        assertEquals("cart_clear", cartReply.inlineKeyboard().get(2).get(0).callbackData());
    }

    @Test
    void testCartInteractionsAndMessageEditing() {
        // Seed items
        ShoppingItem item1 = repository.add(CHAT_ID, "Milk", USER_ID);
        ShoppingItem item2 = repository.add(CHAT_ID, "Sugar", USER_ID);

        // Click on Milk button (Callback)
        BotReply tapReply = service.handleCallback(CHAT_ID, USER_ID, "cart_buy:" + item1.id());
        
        // Check that editCurrentMessage is TRUE (so TelegramBot swaps the inline keyboard)
        assertTrue(tapReply.editCurrentMessage());
        
        // Verify list is shrunk and Milk is removed from local memory
        assertFalse(tapReply.text().contains("Milk"));
        assertTrue(tapReply.text().contains("Sugar"));
        assertEquals(1, repository.list(CHAT_ID).size());
        assertEquals(item2.id(), repository.list(CHAT_ID).get(0).id());

        // Clear Cart Callback
        BotReply clearReply = service.handleCallback(CHAT_ID, USER_ID, "cart_clear");
        assertTrue(clearReply.editCurrentMessage());
        assertTrue(clearReply.text().contains("Cart cleared"));
        assertTrue(repository.list(CHAT_ID).isEmpty());
    }

    private static class InMemoryShoppingRepository implements ShoppingRepository {
        private final List<ShoppingItem> list = new ArrayList<>();
        private long idCounter = 1;

        @Override
        public ShoppingItem add(long chatId, String name, long addedByUserId) {
            ShoppingItem item = new ShoppingItem(idCounter++, chatId, name, addedByUserId, Instant.now());
            list.add(item);
            return item;
        }

        @Override
        public List<ShoppingItem> list(long chatId) {
            return list.stream().filter(item -> item.chatId() == chatId).toList();
        }

        @Override
        public void delete(long chatId, long id) {
            list.removeIf(item -> item.chatId() == chatId && item.id() == id);
        }

        @Override
        public void clear(long chatId) {
            list.removeIf(item -> item.chatId() == chatId);
        }
    }
}
