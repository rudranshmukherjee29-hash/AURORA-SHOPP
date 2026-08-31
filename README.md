# AuroraShop

A secure, balanced, GUI-based vanilla-item shop and economy plugin for
Paper servers (1.26.2+, Java 25). Built around Vault, a curated ~80-item
catalogue, and a transaction pipeline designed so that stock, money, and
items can never desynchronize — even under concurrent clicks, lag, or a
disconnect mid-purchase.

This document covers installation, the economy design rationale, the
security model, and a testing checklist. It is a required deliverable
alongside the source, not optional reading — please read the **Known
Limitations** section at the bottom before deploying to a live server.

---

## 1. Requirements

- **Paper 1.26.2** (or a Paper fork). Spigot is not supported — AuroraShop
  uses Paper-only APIs (Adventure/MiniMessage text components, the
  region-aware schedulers used for Folia compatibility).
- **Java 25**.
- **[Vault](https://www.spigotmc.org/resources/vault.34315/)**, plus any
  economy plugin that registers a Vault `Economy` provider (EssentialsX is
  the most common; AuroraShop works with any of them, since it only ever
  talks to the Vault abstraction, never to a specific economy plugin's
  internals or files).
- Optional: **PlaceholderAPI** (adds `%aurorashop_*%` placeholders — see
  §6). Optional: **Geyser/Floodgate** — the GUI uses plain single-clicks
  and shift-clicks only, which Bedrock players can perform normally.

## 2. Building

```bash
git clone <this project>
cd AuroraShop
gradle build          # or: ./gradlew build, if you've generated wrapper scripts
```

This project does not ship Gradle wrapper binaries (see **Known
Limitations**). If you don't have Gradle installed, install Gradle 8.5+
first, or run `gradle wrapper` once inside the project directory to
generate `gradlew`/`gradlew.bat` for future builds.

The build produces a single shaded jar at
`build/libs/AuroraShop-1.0.0.jar` — SQLite, MariaDB's JDBC driver, and
HikariCP are bundled and relocated inside it, so no extra jars are needed
on the server.

## 3. Installation

1. Install Vault and an economy plugin (e.g. EssentialsX) if you haven't
   already, and confirm `/eco` or equivalent works.
2. Drop `AuroraShop-1.0.0.jar` into your server's `plugins/` folder.
3. Start the server. On first start AuroraShop will:
   - Detect Vault and log which economy provider it found.
   - Generate `config.yml`, `limits.yml`, `messages.yml`, `gui.yml`,
     `shops.yml`, and `prices.yml` under `plugins/AuroraShop/`.
   - Run a price-validation pass and log the result.
   - Create `aurorashop.db` (SQLite) unless you've configured MySQL.
4. That's it — the default configuration is a complete, balanced shop.
   `/shop` opens it immediately.
5. To use MySQL/MariaDB instead of SQLite, set `database.type: MYSQL` in
   `config.yml` and fill in `database.mysql.*` before the first start (or
   run `/shopadmin reload` after editing — the connection is only opened
   once, at plugin enable, so a database-type change specifically
   requires a full restart, not just a reload).

If Vault or a working Economy provider isn't found, or the database
fails to initialize, AuroraShop logs a clear error and disables itself
rather than running in a half-working state.

## 4. Commands & Permissions

| Command | Permission | Notes |
|---|---|---|
| `/shop` | `aurorashop.shop` (default: true) | Opens the main menu |
| `/shop <category>` | `aurorashop.shop` | Jumps to a category |
| `/shop search <query>` | `aurorashop.shop` | Search across all items |
| `/sellall`, `/sellinventory` | `aurorashop.sell` (default: true) | Sell everything sellable |
| `/sellhand` | `aurorashop.sell` | Sell the stack in your main hand |
| `/shopadmin reload` | `aurorashop.admin` (default: op) | Reload config + prices (see §7 for what reload does *not* touch) |
| `/shopadmin stats [item]` | `aurorashop.admin` | Global or per-item statistics |
| `/shopadmin givebalance <player> <amount>` | `aurorashop.admin` | Deposits via Vault |
| `/shopadmin audit <item>` | `aurorashop.admin` | Full config dump for one item |
| `/shopadmin validateprices` | `aurorashop.admin` | Re-run arbitrage detection on demand |
| `/shopadmin restock <item> [amount\|max]` | `aurorashop.admin` | Manual restock for limited-stock items |

`aurorashop.bypasslimits` (default: op) skips stock and daily buy/sell
limits for that player.

## 5. GUI controls

Inside a category or search screen, each item icon responds to:

- **Left-click** — buy at the current quantity mode (default `1`)
- **Shift + Left-click** — buy 64
- **Right-click** — sell 1
- **Shift + Right-click** — sell all matching items in your inventory
- The control bar's **quantity toggle** cycles the plain-click amount
  through `1 → 16 → 32 → 64 → Max affordable`.

Any transaction whose total price/payout meets `confirmationThreshold`
in `config.yml` opens a confirm/cancel screen instead of completing
immediately.

## 6. PlaceholderAPI

If PlaceholderAPI is installed, AuroraShop registers automatically:

- `%aurorashop_balance%`, `%aurorashop_total_bought%`,
  `%aurorashop_total_sold%`, `%aurorashop_total_transactions%`,
  `%aurorashop_total_spent%`, `%aurorashop_total_earned%`,
  `%aurorashop_most_purchased%`, `%aurorashop_most_sold%`
- Per item: `%aurorashop_item_<id>_buy%`, `%_sell%`, `%_bought%`, `%_sold%`
  (item ids are the ones in `prices.yml`, e.g. `iron_ingot`)

---

## 7. Economy Balancing

The catalogue is deliberately **curated, not exhaustive** — 79 items
across 7 categories (Blocks, Ores & Minerals, Farming, Mob Drops, Food,
Redstone & Utility, Nether & End). Every price in `prices.yml` was set,
and then checked, against one rule:

> **No sequence of buying, converting (smelting/crafting/compression),
> and selling should ever return more money than it cost.**

### The pricing model

Each item's buy price reflects rarity, effort, and danger; sell price is
set to roughly 35–55% of buy price, tighter for common/renewable items
(so mass-farming a basic resource can't fund anything) and slightly
wider for high-danger/low-renewability items (so genuine risk — Nether
exploration, the End, mob farming — is worth doing). A few examples:

| Item | Buy | Sell | Why |
|---|---|---|---|
| Dirt | $1.00 | $0.05 | Infinite/trivial — sell value is a rounding error |
| Iron Ingot | $14.00 | $6.00 | Common mining+smelting effort |
| Diamond | $55.00 | $22.00 | Real mining risk/depth requirement |
| Netherite Ingot | $950.00 | $400.00 | End-game; also has a 4/day buy limit |
| Nether Star | $5000.00 | *(not sellable)* | Global stock of 1; a gold sink, not a farm |

### Conversion-loop checking

`prices.yml` lets an item declare how it relates to others:

- `smeltsFrom` — a 1:1 furnace conversion (e.g. `iron_ingot` smelts from `raw_iron`)
- `compressesInto: {item, ratio}` — N of this item craft into 1 of another,
  **and vanilla also lets you craft it back** (e.g. 9 `iron_ingot` ↔ 1
  `iron_block`) — checked in **both directions**
- `craftsFrom: {outputAmount, components}` — a multi-ingredient recipe
  (e.g. `bread` from 3 `wheat`), checked forward only, since most crafting
  recipes have no vanilla "uncraft"

`PriceValidator` walks every declared relationship at startup and on
`/shopadmin validateprices`, and flags anything where buying the
input(s) and selling the output (or vice versa, for compression) would
turn a profit. All ~20 declared relationships in the default
`prices.yml` — every ore/ingot/block triple, gold nugget↔ingot, bread,
golden apple, hopper, sticky piston, glowstone, quartz block, blaze
powder, magma cream, and eye of ender — were hand-verified to always
show a **loss** on both the raw-to-converted and converted-to-raw paths
before being shipped as defaults. Example: buying 9 iron ingots ($126)
and crafting an iron block sells for $45 (a $81 loss); buying an iron
block ($110) and un-crafting it back to 9 ingots sells for $54 (a $56
loss). Neither direction is exploitable.

A handful of items also carry a `dailyBuyLimit` or `dailySellLimit`
(Ender Pearl, Blaze Rod, Phantom Membrane, Shulker Shell, Netherite
Ingot) specifically because they're realistic AFK-farm or bulk-trading
targets — the limit caps how much any single farm can flood the economy
per player per day without banning the item outright.

### What admins should still watch

`PriceValidator` only catches loops *within* the mechanics AuroraShop
knows about. It cannot see, for example, a villager trading hall
generating Emeralds far cheaper than intended, or a third-party
plugin's custom farms. Use `/shopadmin stats` regularly — it's built
specifically to surface "this item's sell volume looks abnormal" before
it becomes an inflation problem, and `/shopadmin validateprices` any
time you edit `prices.yml` by hand.

---

## 8. Security & Anti-Exploit Design

This section maps directly to the threat list AuroraShop was designed
against. Every item below is implemented, not aspirational.

### Transaction pipeline

Every buy and every sell — from the GUI, `/sellhand`, and `/sellall`
alike — goes through **one** code path (`TransactionService`), in this
order:

**Buy:** acquire a per-player lock → validate quantity → validate item
config → reserve daily limit → compute exact price (`BigDecimal`) →
atomically reserve stock → verify balance → verify inventory has room →
withdraw via Vault → verify the withdrawal succeeded → deliver the exact
item count → verify delivery succeeded → record the transaction →
release the lock. **Sell** is the mirror image: count exact sellable
items → remove exactly that many → verify the removal matched → deposit
via Vault → verify the deposit succeeded → record → release the lock.

If a step fails **after** money or stock was already committed (e.g.
Vault accepts the withdrawal but the inventory somehow can't hold the
items), AuroraShop runs an explicit compensation: refund the withdrawal
or hand back the removed items, and roll back any stock/daily-limit
reservation — never money-with-no-item or item-with-no-money.

### Concurrency & race conditions

- **Per-player single-flight guard**: a lock-free `ConcurrentHashMap`-backed
  set means a player can never have two transactions in flight
  simultaneously — the second click is rejected outright, not queued,
  not partially processed.
- **Per-item stock** uses a compare-and-set retry loop on an
  `AtomicInteger`, not a `synchronized` block — this is what makes
  "two players buy the last item at the same instant" resolve correctly:
  exactly one CAS wins, the other sees insufficient stock.
- **Rate limiting** enforces a minimum gap (default 150ms) between two
  transactions from the same player, independent of the lock — this is
  what actually stops rapid click-spam rather than just serializing it.

### Inventory GUI hardening

The click listener cancels **every** click and drag event the instant it
recognizes the top inventory as AuroraShop's — before looking at click
type — which blocks shift-clicking, number-key hotbar swaps,
double-click/collect-to-cursor, offhand swaps, and drag-placement against
shop icons in one place, rather than trying to enumerate and cancel each
individually. Only a direct click on a recognized icon, in the top
inventory, is ever acted on.

Every clickable icon is identified by a **PersistentDataContainer** tag
(`shop_item_id`, `shop_action`) set server-side — never by display name,
lore, or custom model data, all three of which are purely cosmetic and
never read by the click handler. Shop icons are also never the target of
a real inventory move: buying/selling manipulates the player's actual
inventory directly, so a shop icon can never end up duplicated into a
player's real items.

### Numeric safety

- All prices are computed in `BigDecimal`, rounded to 2 decimal places
  with `HALF_UP`, and converted to `double` only at the Vault API
  boundary (which is the one place `double` is unavoidable, since that's
  Vault's contract) — internal arithmetic never accumulates
  floating-point drift.
- Quantities are validated as positive `long`s with an explicit ceiling
  (`transactions.maxTransactionSize`, default 10,000) before anything
  else happens; zero, negative, and oversized quantities are rejected at
  the very first step.
- Item delivery is all-or-nothing: if any max-stack chunk of a multi-stack
  purchase doesn't fully fit, every chunk given so far in that call is
  rolled back rather than leaving a partial delivery.

### Selling hardening

- Selling counts items by walking the inventory's storage contents
  directly and removing the exact verified count — never
  `Inventory#removeItem`'s leftover-based semantics, which are easy to
  get subtly wrong.
- `sell.requireVanillaOnly` (on by default) rejects selling any item with
  a custom name, lore, enchantments, custom model data, or any other
  non-default `ItemMeta` — comparing against a freshly-constructed plain
  `ItemStack` of the same material — so a renamed or NBT-modified item
  can never be sold as if it were the plain version.

### Cleanup

Transaction locks and GUI session state (quantity mode, open screen) are
cleared on `PlayerQuitEvent`/`PlayerKickEvent`, so a disconnect
mid-transaction can never leave a player permanently locked out of the
shop.

---

## 9. Performance

- All database I/O (transaction logging, stock/daily-limit persistence,
  startup statistics aggregation) runs through Paper's async scheduler —
  never on the main/region thread.
- `ShopRegistry` parses `shops.yml`/`prices.yml` once per load/reload
  into immutable objects; nothing re-parses YAML during normal play.
- Stock and daily-limit counters live in memory (`AtomicInteger`/simple
  monitors) and are flushed to the database every 5 minutes and once
  more (with a bounded 2-second wait) on shutdown — worst case, a crash
  loses a few minutes of stock/limit bookkeeping, never money or items.
- Folia compatibility: cross-thread work uses Paper's
  `GlobalRegionScheduler`/`AsyncScheduler`/`Entity#getScheduler()`
  rather than the legacy `BukkitScheduler`, so the same jar behaves
  correctly on both regular Paper and Folia.

---

## 10. Testing Checklist

Run through this before trusting AuroraShop with a live economy.
Everything here is testable solo on a local test server except the
"Simultaneous" rows, which need two clients (or one client + `/shopadmin`
running the equivalent action via console/another account).

### Normal operation
- [ ] Buy 1 of an item; balance and inventory both update correctly
- [ ] Buy a full stack (shift-click) of an item
- [ ] Buy at "Max affordable" quantity mode; confirm it never exceeds
      balance, inventory space, or stock
- [ ] Sell 1 of an item; sell all of an item (shift-right-click)
- [ ] `/sellhand` with a stack in hand; with an empty hand
- [ ] `/sellall` with a full inventory of mixed sellable/non-shop items
- [ ] Attempt to buy with insufficient funds — no money taken, no items given
- [ ] Attempt to buy with a full inventory — no money taken
- [ ] Attempt to sell an item you don't have — clear message, no payout
- [ ] Disable buy or sell on an item in `prices.yml`, reload, confirm the
      GUI reflects it and the disabled action is rejected

### Exploit resistance
- [ ] Spam left-click a single item as fast as possible — verify final
      item count and balance change match exactly what should have been
      purchased, no more
- [ ] Shift-click rapidly, including shift-clicking from your own
      inventory into the shop GUI — nothing transfers
- [ ] Double-click an item icon (collect-to-cursor) — nothing happens,
      no shop item enters your cursor/inventory
- [ ] Drag across multiple shop GUI slots — event is cancelled, no items move
- [ ] Press number keys (1-9) while hovering a shop icon — no hotbar swap occurs
- [ ] Press F (swap offhand) while hovering a shop icon — no swap occurs
- [ ] Close the shop GUI mid-click / disconnect immediately after
      clicking buy — no duplicated money or items on reconnect
- [ ] Two players (or a player + `/shopadmin`) attempt to buy the last
      unit of a stock-limited item at the same time — exactly one succeeds
- [ ] Request a negative or zero quantity via any code path you can
      reach — rejected with `INVALID_QUANTITY`, no state change
- [ ] Request an extremely large quantity (bigger than
      `maxTransactionSize`) — rejected outright
- [ ] Rename or enchant a plain item and try to sell it with
      `requireVanillaOnly: true` — rejected with the not-vanilla message
- [ ] Simulate an economy failure (e.g. temporarily unregister the Vault
      provider) mid-test — transaction fails cleanly, no partial state
- [ ] Stop the database mid-session (e.g. corrupt the SQLite file) —
      transactions still complete correctly (persistence is async and
      failures there don't block the transaction itself); check the
      console logs the failure

### Economy / arbitrage
- [ ] Run `/shopadmin validateprices` on the shipped `prices.yml` —
      expect zero problems reported
- [ ] Buy raw materials, smelt/craft/compress them, and sell the result
      for every declared conversion in `prices.yml` — confirm a loss
      every time
- [ ] Intentionally set a sell price above a buy price in `prices.yml`,
      reload, run `/shopadmin validateprices` — confirm it's detected
      and reported
- [ ] Farm a renewable item (crops, mob drops) for an extended session
      and sell continuously — confirm no daily-limited item lets you
      bypass its cap, and confirm the per-item stats in
      `/shopadmin stats <item>` look sane afterward

---

## 11. Known Limitations

This plugin was built and hand-reviewed in a sandboxed environment
**without internet/Maven access**, so it has not been compiled or
run against a live server. Every file was written carefully and then
manually cross-checked line-by-line against the actual Paper/Vault/
HikariCP APIs (constructor signatures, method names, record accessors,
brace/paren balance) — one real bug was caught this way (the inventory
helper methods needed to be typed against `PlayerInventory` rather than
the generic `Inventory` interface, since `getStorageContents()` only
exists on the former). That said, this review cannot fully substitute
for an actual `gradle build`.

**Before relying on this in production:** run `gradle build`, fix
anything the compiler flags (there may be small issues my manual review
missed — a typo'd method name is the most likely category), and work
through the testing checklist above. I'm glad to fix any build errors or
runtime issues you hit — just paste the error.

Two intentional scope decisions, not bugs:
- The catalogue is 79 items, not "every vanilla item" — this was an
  explicit requirement, not an oversight.
- `hopper`'s crafting relationship uses `oak_planks` × 8 as a stand-in
  for its actual chest-ingredient cost (a chest isn't itself a priced
  shop item) — the arbitrage math still holds since planks are priced
  and a chest is worth less than 8 planks' equivalent effort, so this
  is a conservative (not exploitable) approximation.
