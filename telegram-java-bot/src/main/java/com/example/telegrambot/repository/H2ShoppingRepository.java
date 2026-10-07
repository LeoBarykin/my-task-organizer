package com.example.telegrambot.repository;

import com.example.telegrambot.domain.ShoppingItem;
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

public final class H2ShoppingRepository implements ShoppingRepository {
    private final String jdbcUrl;

    public H2ShoppingRepository(Path databasePath) {
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
                CREATE TABLE IF NOT EXISTS shopping_items (
                  id INT AUTO_INCREMENT PRIMARY KEY,
                  chat_id BIGINT NOT NULL,
                  name VARCHAR(255) NOT NULL,
                  added_by_user_id BIGINT NOT NULL,
                  added_at_ms BIGINT NOT NULL
                );
                """;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_shopping_items_chat ON shopping_items(chat_id)");
        }
        catch (SQLException exception) { throw new IllegalStateException("Cannot initialize H2 database", exception); }
    }

    @Override
    public ShoppingItem add(long chatId, String name, long addedByUserId) {
        String sql = "INSERT INTO shopping_items(chat_id, name, added_by_user_id, added_at_ms) VALUES(?, ?, ?, ?)";
        Instant now = Instant.now();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, chatId);
            p.setString(2, name);
            p.setLong(3, addedByUserId);
            p.setLong(4, now.toEpochMilli());
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (keys.next()) return new ShoppingItem(keys.getLong(1), chatId, name, addedByUserId, now);
            }
            throw new SQLException("H2 did not return a shopping item ID");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot save shopping item", exception);
        }
    }

    @Override
    public List<ShoppingItem> list(long chatId) {
        String sql = "SELECT * FROM shopping_items WHERE chat_id=? ORDER BY added_at_ms ASC";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, chatId);
            try (ResultSet r = p.executeQuery()) {
                List<ShoppingItem> items = new ArrayList<>();
                while (r.next()) {
                    items.add(new ShoppingItem(
                        r.getLong("id"),
                        r.getLong("chat_id"),
                        r.getString("name"),
                        r.getLong("added_by_user_id"),
                        Instant.ofEpochMilli(r.getLong("added_at_ms"))
                    ));
                }
                return items;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read shopping items", exception);
        }
    }

    @Override
    public void delete(long chatId, long id) {
        String sql = "DELETE FROM shopping_items WHERE chat_id=? AND id=?";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, chatId);
            p.setLong(2, id);
            p.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot delete shopping item", exception);
        }
    }

    @Override
    public void clear(long chatId) {
        String sql = "DELETE FROM shopping_items WHERE chat_id=?";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, chatId);
            p.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot clear shopping items", exception);
        }
    }
}
