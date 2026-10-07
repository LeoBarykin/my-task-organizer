# SQLite to H2 Persistence Transition Plan

## 1. Background & Target
To eliminate native OS-dependent C libraries (JNI) associated with SQLite and provide a 100% pure Java portable database runtime, we will migrate our file-based database persistence from SQLite to H2 Database Engine in **Embedded File Mode** (`AUTO_SERVER=TRUE`).

## 2. Scope & Files Affected
- **`pom.xml`**: Replace `sqlite-jdbc` with the latest stable `h2` dependency.
- **`src/main/java/com/example/telegrambot/repository/`**:
  - Delete `SqliteTaskRepository.java` -> Create `H2TaskRepository.java`
  - Delete `SqliteShoppingRepository.java` -> Create `H2ShoppingRepository.java`
- **`BotApplication.java`**: Update bootstrapping instantiations and JDBC connection strings.
- **`TaskServiceTest.java` / `ShoppingServiceTest.java` / `ReminderServiceTest.java`**: No changes to test logic are required as they already use our decoupled `InMemoryTaskRepository` and `InMemoryShoppingRepository` mocks! (Any integration tests using SQLite will be safely ported to H2 `:memory:` mode).

---

## 3. Detailed Translation Blueprint

### A. Dependency Updates (`pom.xml`)
We will swap:
```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>${sqlite.version}</version>
</dependency>
```
With:
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
</dependency>
```

### B. Table Schema Conversion (H2-Compatible SQL)
H2 utilizes ANSI SQL which has slightly more robust types than SQLite.

#### Tasks Table Schema:
```sql
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
```

#### Safe Migrations (Altering Columns):
H2 natively supports `IF NOT EXISTS` for altering tables:
```sql
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS reminder_sent INT DEFAULT 0;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS category VARCHAR(50) DEFAULT 'CHORES';
```

#### Shopping Items Table Schema:
```sql
CREATE TABLE IF NOT EXISTS shopping_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    added_by_user_id BIGINT NOT NULL,
    added_at_ms BIGINT NOT NULL
);
```

### C. Repository Class Refactoring
We will rename our repository classes to `H2TaskRepository.java` and `H2ShoppingRepository.java`.
- **Imports:** Swap any SQLite imports with H2-specific packages if needed (H2 uses standard Java `java.sql` prepared statements and connections just like SQLite, so standard JDBC remains completely unchanged!).
- **JDBC Driver loading:** H2 JDBC driver is standard `org.h2.Driver` (autoloaded by modern JDBC SPI).
- **Class Implementation:**
  - `H2TaskRepository` implements `TaskRepository`.
  - `H2ShoppingRepository` implements `ShoppingRepository`.

### D. App Bootstrapping (`BotApplication.java`)
- Update the default file database path to `data/family-tasks` (H2 automatically appends `.mv.db` to the directory name).
- Pass this path to `H2TaskRepository` and `H2ShoppingRepository` constructors.
- The repository constructor will instantiate the JDBC URL as:
  ```java
  jdbcUrl = "jdbc:h2:file:" + databasePath.toAbsolutePath() + ";AUTO_SERVER=TRUE";
  ```

---

## 4. Verification & Testing
- Compile and build using `mvn clean compile` to verify H2 dependency downloads successfully.
- Run `mvn test` to ensure mock repositories and bot handlers are completely unaffected.
- Start the bot locally (`mvn compile exec:java`), add some tasks, stop the bot, and restart it to verify data is 100% persistent and preserved inside `data/family-tasks.mv.db`.
