# Nested Multi-Layer Menus Plan

## 1. Background & Layout Re-design
To simplify the user interface, we will restructure the main keyboard layout from a single flat list into a hierarchical, nested sub-menu system:

### A. Root Menu (Main Menu)
- **`📋 Tasks`** (opens Tasks Sub-Menu)
- **`🛒 Cart`** (opens Cart Sub-Menu)
- **`ℹ️ Help`** (shows general help text)

### B. Tasks Sub-Menu
- **`➕ Add Task`** (starts task wizard)
- **`📋 List Tasks`** (lists active tasks)
- **`📅 Today`** (tasks due today)
- **`📅 Week`** (tasks due this week)
- **`⚠️ Overdue`** (overdue tasks)
- **`✅ Done Task`** (starts done task wizard)
- **`🗑 Delete Task`** (starts delete task wizard)
- **`⬅️ Main Menu`** (returns to Root Menu)

### C. Cart Sub-Menu
- **`📋 View Cart`** (lists shopping cart items)
- **`➕ Add to Cart`** (starts frictionless add-to-cart wizard)
- **`⬅️ Main Menu`** (returns to Root Menu)

---

## 2. Dynamic Keyboard Context & Session State
When wizards are cancelled or completed, the bot must return the user to the correct keyboard context:
- Completing or cancelling `Add Task`, `Done Task`, or `Delete Task` -> returns `TASKS_MENU_BUTTONS`.
- Completing or cancelling `Add to Cart` -> returns `CART_MENU_BUTTONS`.

### State Machine Changes:
- **`UserState.java`**: Add `AWAITING_CART_ITEM`.
- **`TaskService.java`**: 
  - Update `getButtonsForState(UserState state)`:
    - If `AWAITING_CART_ITEM` -> return `CANCEL_BUTTON`.
    - Otherwise -> return `CANCEL_BUTTON` (existing behavior).
  - Handle state transitions in `handleStateFlow()`:
    - Add `case AWAITING_CART_ITEM`: when text is received, call `shoppingService.handleBuy()`, but modify `handleBuy` to return a `CART_MENU_BUTTONS` layout!

---

## 3. Implementation Steps

### A. Domain & Repository
- **`UserState.java`**: Add `AWAITING_CART_ITEM` to enum.

### B. Services Layer (`TaskService.java`)
- Define three keyboard layouts:
  ```java
  public static final List<String> MAIN_MENU_BUTTONS = List.of("📋 Tasks", "🛒 Cart", "ℹ️ Help");
  public static final List<String> TASKS_MENU_BUTTONS = List.of("➕ Add Task", "📋 List Tasks", "📅 Today", "📅 Week", "⚠️ Overdue", "✅ Done Task", "🗑 Delete Task", "⬅️ Main Menu");
  public static final List<String> CART_MENU_BUTTONS = List.of("📋 View Cart", "➕ Add to Cart", "⬅️ Main Menu");
  ```
- **Wizards Navigation Backtrack:**
  - Update `replyTo()` universal cancellation check:
    - Instead of always returning `MAIN_MENU_BUTTONS`, check the user's session state.
    - If user was in `AWAITING_CART_ITEM` -> return to `CART_MENU_BUTTONS`.
    - If user was in task wizards -> return to `TASKS_MENU_BUTTONS`.
- **Sub-Menu Navigation (`handleIdleState`):**
  - `📋 tasks` -> returns message `"📋 Tasks Menu opened. Choose an option below:"` with `TASKS_MENU_BUTTONS`.
  - `🛒 cart` -> returns message `"🛒 Cart Menu opened. Choose an option below:"` with `CART_MENU_BUTTONS`.
  - `⬅️ main menu` -> returns message `"Main Menu opened:"` with `MAIN_MENU_BUTTONS`.
  - `ℹ️ help` -> returns `help()` with `MAIN_MENU_BUTTONS`.
  - `📋 view cart` -> delegates to `shoppingService.cart(chatId)`.
  - `➕ add to cart` -> sets state to `AWAITING_CART_ITEM`, returns `"Please enter the item(s) you want to add to your shopping cart (separated by commas):"` with `CANCEL_BUTTON`.
- **Task Creation & Actions:**
  - Update `Add Task` final success message to return `TASKS_MENU_BUTTONS`.
  - Update `Done Task` and `Delete Task` final success messages to return `TASKS_MENU_BUTTONS`.
- **State Flow (`handleStateFlow`):**
  - Add `case AWAITING_CART_ITEM`: delegates to `shoppingService.handleBuy(chatId, userId, input)`, then clears state and returns.

### C. Shopping Service Layer (`ShoppingService.java`)
- Update `handleBuy()`, `cart()`, and `handleCallback()` return buttons to use `CART_MENU_BUTTONS` instead of `TaskService.MAIN_MENU_BUTTONS` so that users stay in the cart context!

### D. Testing
- Update `TaskServiceTest` and `ShoppingServiceTest` to assert on correct button layouts (`TASKS_MENU_BUTTONS` or `CART_MENU_BUTTONS`) and verify the new `AWAITING_CART_ITEM` step transitions.
