package com.example.telegrambot.repository;

import com.example.telegrambot.domain.UserSession;

public interface UserSessionRepository {
    UserSession getSession(long userId);
    void saveSession(long userId, UserSession session);
    void clearSession(long userId);
}
