package com.example.telegrambot.repository;

import com.example.telegrambot.domain.UserSession;
import com.example.telegrambot.domain.UserState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryUserSessionRepository implements UserSessionRepository {
    private final Map<Long, UserSession> store = new ConcurrentHashMap<>();

    @Override
    public UserSession getSession(long userId) {
        return store.getOrDefault(userId, UserSession.idle());
    }

    @Override
    public void saveSession(long userId, UserSession session) {
        if (session == null || session.state() == UserState.IDLE) {
            clearSession(userId);
        } else {
            store.put(userId, session);
        }
    }

    @Override
    public void clearSession(long userId) {
        store.remove(userId);
    }
}
