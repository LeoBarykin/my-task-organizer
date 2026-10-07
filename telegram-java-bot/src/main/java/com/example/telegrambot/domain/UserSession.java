package com.example.telegrambot.domain;

public record UserSession(
    UserState state,
    TaskCategory tempCategory,
    String tempDescription,
    String tempDeadline,
    Long tempTaskId
) {
    public static UserSession idle() {
        return new UserSession(UserState.IDLE, null, null, null, null);
    }
}
