package com.example.telegrambot.domain;

public enum TaskCategory {
    CHORES("🧹 Chores"),
    SHOPPING("🛒 Shopping"),
    ADMIN("📁 Admin"),
    KIDS("🧸 Kids"),
    HEALTH("🩺 Health"),
    CAR("🚗 Car"),
    PETS("🐶 Pets"),
    GARDEN("🏡 Garden");

    private final String displayName;

    TaskCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
