package com.example.telegrambot.domain;

public enum UserState {
    IDLE,
    AWAITING_CATEGORY,
    AWAITING_DESCRIPTION,
    AWAITING_DEADLINE,
    AWAITING_ASSIGNEE,
    AWAITING_DONE_ID,
    AWAITING_DELETE_ID,
    AWAITING_CART_ITEM
}
