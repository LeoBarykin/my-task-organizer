package com.example.telegrambot.repository;

import com.example.telegrambot.domain.Task;
import com.example.telegrambot.domain.TaskStatus;
import com.example.telegrambot.domain.TaskCategory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class H2TaskRepository implements TaskRepository {
    private final String jdbcUrl;

    public H2TaskRepository(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create database directory", exception);
        }
        jdbcUrl = "jdbc:h2:file:" + databasePath.toAbsolutePath() + ";AUTO_SERVER=TRUE";
        initialize();
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(jdbcUrl); }

    private void initialize() {
        String sql = """
                CREATE TABLE IF NOT EXISTS tasks (
                  id INT AUTO_INCREMENT PRIMARY KEY,
                  chat_id BIGINT NOT NULL,
                  description VARCHAR(500) NOT NULL,
                  creator_user_id BIGINT NOT NULL,
                  creator_name VARCHAR(255) NOT NULL,
                  assignee VARCHAR(255),
                  deadline_ms BIGINT,
                  status VARCHAR(50) NOT NULL,
                  created_at_ms BIGINT NOT NULL,
                  completed_at_ms BIGINT
                );
                """;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tasks_open ON tasks(chat_id, status, deadline_ms)");
            statement.executeUpdate("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS reminder_sent INT DEFAULT 0");
            statement.executeUpdate("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS category VARCHAR(50) DEFAULT 'CHORES'");
        }
        catch (SQLException exception) { throw new IllegalStateException("Cannot initialize H2 database", exception); }
    }

    @Override public Task create(long chatId, String description, long creatorUserId, String creatorName, String assignee, Instant deadline, TaskCategory category) {
        String sql = "INSERT INTO tasks(chat_id,description,creator_user_id,creator_name,assignee,deadline_ms,status,created_at_ms,category) VALUES(?,?,?,?,?,?,?,?,?)";
        Instant now = Instant.now();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, chatId); p.setString(2, description); p.setLong(3, creatorUserId); p.setString(4, creatorName);
            p.setString(5, assignee); nullableInstant(p, 6, deadline); p.setString(7, TaskStatus.OPEN.name()); p.setLong(8, now.toEpochMilli()); p.setString(9, category.name()); p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) { if (keys.next()) return new Task(keys.getLong(1), chatId, description, creatorUserId, creatorName, assignee, deadline, TaskStatus.OPEN, now, null, false, category); }
            throw new SQLException("H2 did not return a task ID");
        } catch (SQLException exception) { throw new IllegalStateException("Cannot save task", exception); }
    }

    @Override public List<Task> findOpen(long chatId) { return query("SELECT * FROM tasks WHERE chat_id=? AND status='OPEN' ORDER BY CASE WHEN deadline_ms IS NULL THEN 1 ELSE 0 END, deadline_ms, id", chatId); }
    @Override public List<Task> findOpenDueBetween(long chatId, Instant from, Instant to) {
        return query("SELECT * FROM tasks WHERE chat_id=? AND status='OPEN' AND deadline_ms>=? AND deadline_ms<? ORDER BY deadline_ms, id", chatId, from.toEpochMilli(), to.toEpochMilli());
    }
    @Override public List<Task> findOverdue(long chatId, Instant now) {
        return query("SELECT * FROM tasks WHERE chat_id=? AND status='OPEN' AND deadline_ms IS NOT NULL AND deadline_ms<? ORDER BY deadline_ms, id", chatId, now.toEpochMilli());
    }
    @Override public Optional<Task> markDone(long chatId, long id, Instant completedAt) { return updateAndFind("UPDATE tasks SET status='DONE', completed_at_ms=? WHERE chat_id=? AND id=? AND status='OPEN'", completedAt.toEpochMilli(), chatId, id); }
    @Override public Optional<Task> cancel(long chatId, long id, Instant cancelledAt) { return updateAndFind("UPDATE tasks SET status='CANCELLED', completed_at_ms=? WHERE chat_id=? AND id=? AND status='OPEN'", cancelledAt.toEpochMilli(), chatId, id); }
    @Override public Optional<Task> assign(long chatId, long id, String assignee) { return updateAndFind("UPDATE tasks SET assignee=? WHERE chat_id=? AND id=? AND status='OPEN'", assignee, chatId, id); }
    @Override public Optional<Task> edit(long chatId, long id, String description, Instant deadline, TaskCategory category) { return updateAndFind("UPDATE tasks SET description=?, deadline_ms=?, category=? WHERE chat_id=? AND id=? AND status='OPEN'", description, deadline, category.name(), chatId, id); }

    @Override public Optional<Task> delete(long chatId, long id) {
        List<Task> existing = query("SELECT * FROM tasks WHERE chat_id=? AND id=?", chatId, id);
        if (existing.isEmpty()) return Optional.empty();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("DELETE FROM tasks WHERE chat_id=? AND id=?")) {
            p.setLong(1, chatId);
            p.setLong(2, id);
            p.executeUpdate();
            return Optional.of(existing.get(0));
        }
        catch (SQLException exception) { throw new IllegalStateException("Cannot delete task", exception); }
    }

    @Override public List<Task> findTasksNeedingReminder(Instant now, Instant limit) {
        return query("SELECT * FROM tasks WHERE status='OPEN' AND reminder_sent=0 AND deadline_ms IS NOT NULL AND deadline_ms>? AND deadline_ms<=? ORDER BY deadline_ms, id", now.toEpochMilli(), limit.toEpochMilli());
    }

    @Override public void markReminderSent(long chatId, long id) {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("UPDATE tasks SET reminder_sent=1 WHERE chat_id=? AND id=?")) {
            p.setLong(1, chatId);
            p.setLong(2, id);
            p.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot mark reminder as sent", exception);
        }
    }

    @Override public List<Task> findCompletedBetween(long chatId, Instant fromInclusive, Instant toExclusive) {
        return query("SELECT * FROM tasks WHERE chat_id=? AND status='DONE' AND completed_at_ms>=? AND completed_at_ms<? ORDER BY completed_at_ms DESC", chatId, fromInclusive.toEpochMilli(), toExclusive.toEpochMilli());
    }

    @Override public List<Long> findAllChatIds() {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT DISTINCT chat_id FROM tasks"); ResultSet r = p.executeQuery()) {
            List<Long> chatIds = new ArrayList<>();
            while (r.next()) chatIds.add(r.getLong("chat_id"));
            return chatIds;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read distinct chat IDs", exception);
        }
    }

    private List<Task> query(String sql, Object... values) {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) { bind(p, values); try (ResultSet r = p.executeQuery()) { List<Task> tasks = new ArrayList<>(); while (r.next()) tasks.add(map(r)); return tasks; } }
        catch (SQLException exception) { throw new IllegalStateException("Cannot read tasks", exception); }
    }

    private Optional<Task> updateAndFind(String sql, Object... values) {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, values); if (p.executeUpdate() == 0) return Optional.empty();
            long chatId = (Long) values[values.length - 2];
            long id = (Long) values[values.length - 1];
            List<Task> existing = query("SELECT * FROM tasks WHERE chat_id=? AND id=?", chatId, id);
            return existing.isEmpty() ? Optional.empty() : Optional.of(existing.get(0));
        }
        catch (SQLException exception) { throw new IllegalStateException("Cannot write task", exception); }
    }

    private static void bind(PreparedStatement p, Object... values) throws SQLException { for (int i=0; i<values.length; i++) { Object value=values[i]; if (value instanceof Instant instant) p.setLong(i+1, instant.toEpochMilli()); else if (value == null) p.setNull(i+1, java.sql.Types.BIGINT); else p.setObject(i+1, value); } }
    private static void nullableInstant(PreparedStatement p, int index, Instant instant) throws SQLException { if (instant == null) p.setNull(index, java.sql.Types.BIGINT); else p.setLong(index, instant.toEpochMilli()); }

    private static Task map(ResultSet r) throws SQLException {
        boolean reminderSent = false;
        try {
            reminderSent = r.getInt("reminder_sent") == 1;
        } catch (SQLException ignored) {
            // column does not exist or wasn't loaded
        }
        TaskCategory category = TaskCategory.CHORES;
        try {
            String catStr = r.getString("category");
            if (catStr != null) {
                category = TaskCategory.valueOf(catStr);
            }
        } catch (SQLException ignored) {
            // column does not exist or wasn't loaded
        }
        return new Task(r.getLong("id"), r.getLong("chat_id"), r.getString("description"), r.getLong("creator_user_id"), r.getString("creator_name"), r.getString("assignee"), instant(r, "deadline_ms"), TaskStatus.valueOf(r.getString("status")), Instant.ofEpochMilli(r.getLong("created_at_ms")), instant(r, "completed_at_ms"), reminderSent, category);
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        if (r.wasNull()) return null;
        return Instant.ofEpochMilli(value);
    }
}
