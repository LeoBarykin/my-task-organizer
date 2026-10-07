# Telegram Java Bot

A family-group task bot using Java 17, long polling, and SQLite. Tasks are stored locally and isolated per Telegram chat.

## Before you run it

1. In Telegram, open [@BotFather](https://t.me/BotFather), run `/newbot`, and copy the token.
2. Install Java 17+ and Maven 3.9+.
3. Set the token only in your environment:

```powershell
$env:TELEGRAM_BOT_TOKEN="123456789:your-real-token"
```

Never add a real token to `.env`, source files, a commit, or a chat message. If it leaks, revoke it with BotFather and create a new token.

## Run locally

```powershell
cd telegram-java-bot
mvn test
mvn compile exec:java
```

Send the bot `/help` in a private chat. In groups, Telegram normally sends a bot only commands, replies, and mentions until you disable privacy mode through BotFather.

## Commands

| Command | What it does |
| --- | --- |
| `/add Buy groceries | 2026-09-15 18:00 | @alex` | Creates a task; assignee is optional |
| `/tasks` | Lists open tasks in this group |
| `/today`, `/week`, `/overdue` | Lists tasks by due date |
| `/done 12` | Completes task 12 |
| `/assign 12 @alex` | Assigns an open task |
| `/edit 12 New text | 2026-09-15 18:00` | Updates its description and deadline |
| `/delete 12` | Deletes a task |

All deadlines are UTC in `YYYY-MM-DD HH:mm` format for now. For example, `/add Call the plumber | 2026-09-15 18:00`.

## Database and backup

By default the bot stores data in `data/family-tasks.db`. To put it elsewhere, set `TASKS_DB_PATH` before starting. Back up this file while the bot is stopped, or use SQLite's online backup mechanism in a scheduled job.

## Project layout

- `BotApplication` validates configuration and starts long polling.
- `TelegramBot` converts Telegram updates into replies.
- `TaskService` contains command and validation logic.
- `SqliteTaskRepository` owns SQLite schema creation and queries.

## Docker

```powershell
docker build -t telegram-java-bot .
docker run --rm -e TELEGRAM_BOT_TOKEN="123456789:your-real-token" telegram-java-bot
```

Long polling means run exactly one instance per bot token. Use webhooks instead if you need multiple replicas or a serverless deployment.
