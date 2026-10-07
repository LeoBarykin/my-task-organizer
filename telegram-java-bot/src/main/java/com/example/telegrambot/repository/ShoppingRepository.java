package com.example.telegrambot.repository;

import com.example.telegrambot.domain.ShoppingItem;
import java.util.List;

public interface ShoppingRepository {
    ShoppingItem add(long chatId, String name, long addedByUserId);
    List<ShoppingItem> list(long chatId);
    void delete(long chatId, long id);
    void clear(long chatId);
}
