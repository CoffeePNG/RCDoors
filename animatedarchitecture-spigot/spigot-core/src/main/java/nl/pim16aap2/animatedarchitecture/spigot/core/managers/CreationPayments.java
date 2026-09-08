package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.republicraft.platform.api.data.*;
import nl.pim16aap2.animatedarchitecture.core.api.IEconomyManager.CreationResult;
import nl.pim16aap2.animatedarchitecture.core.managers.DatabaseManager.StructureInsertResult;

/** Permanent intents around non-idempotent native wallet and structure insertion calls. */
final class CreationPayments {
  enum WalletResult {
    SUCCESS,
    FAILED,
    UNKNOWN
  }

  record Receipt(
      String id,
      String player,
      String world,
      String description,
      long cents,
      String state,
      String structure,
      String detail) {}

  private record Work(Receipt receipt, boolean fresh) {}

  private final DatabaseHandle database;
  private final BiFunction<Receipt, Boolean, CompletionStage<WalletResult>> wallet;
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private volatile boolean ready;
  private volatile String recoveryCursor = "";
  private volatile boolean closed;

  CreationPayments(
      DatabaseHandle database, BiFunction<Receipt, Boolean, CompletionStage<WalletResult>> wallet) {
    this.database = database;
    this.wallet = wallet;
  }

  static DatabaseSpec schema() {
    return DatabaseSpec.of(
        "creation-payments",
        Migration.of(
            1,
            "Durable native creation fees and observed outcomes",
            "CREATE TABLE creation_fees(id TEXT PRIMARY KEY,player TEXT NOT NULL,world TEXT NOT"
                + " NULL,description TEXT NOT NULL,cents INTEGER NOT NULL,state TEXT NOT"
                + " NULL,structure TEXT NOT NULL,detail TEXT NOT NULL)",
            "CREATE UNIQUE INDEX creation_fee_player_pending ON creation_fees(player) WHERE state"
                + " NOT IN ('SUCCESS','DEBIT_FAILED','REFUNDED')",
            "CREATE TABLE creation_fee_reviews(id TEXT PRIMARY KEY,receipt TEXT NOT NULL,actor TEXT"
                + " NOT NULL,decision TEXT NOT NULL,reason TEXT NOT NULL,created INTEGER NOT NULL)",
            "CREATE TRIGGER creation_fee_reviews_no_update BEFORE UPDATE ON creation_fee_reviews"
                + " BEGIN SELECT RAISE(ABORT,'immutable review'); END",
            "CREATE TRIGGER creation_fee_reviews_no_delete BEFORE DELETE ON creation_fee_reviews"
                + " BEGIN SELECT RAISE(ABORT,'immutable review'); END"));
  }

  CompletionStage<Void> start() {
    return database
        .transaction(
            c -> {
              execute(
                  c,
                  "UPDATE creation_fees SET state='DEBIT_REVIEW',detail='Interrupted debit; inspect"
                      + " provider history' WHERE state='DEBIT_DISPATCHING'");
              execute(
                  c,
                  "UPDATE creation_fees SET state='INSERT_REVIEW',detail='Interrupted native"
                      + " insert; inspect native structures' WHERE state='INSERTING'");
              execute(
                  c,
                  "UPDATE creation_fees SET state='REFUND_REVIEW',detail='Interrupted refund;"
                      + " inspect provider history' WHERE state='REFUND_DISPATCHING'");
              execute(c, "UPDATE creation_fees SET state='REFUND_READY' WHERE state='DEBIT_PAID'");
              return null;
            })
        .thenAccept(ignored -> ready = !closed);
  }

  void close() {
    closed = true;
    ready = false;
  }

  CompletionStage<CreationResult> create(
      UUID id,
      UUID player,
      String world,
      String description,
      double price,
      Supplier<CompletableFuture<StructureInsertResult>> insert) {
    final long cents;
    try {
      cents = BigDecimal.valueOf(price).movePointRight(2).longValueExact();
    } catch (RuntimeException invalid) {
      return done("FAILED", "Invalid exact-cent creation price");
    }
    if (cents <= 0) return done("FAILED", "Paid creation requires a positive amount");
    if (!ready || closed) return done("UNAVAILABLE", "Creation payment storage is unavailable");
    if (!active.add(id.toString())) return done("PENDING", id.toString());
    return database
        .transaction(
            c -> {
              Receipt existing = read(c, id.toString());
              if (existing != null) {
                if (!existing.player.equals(player.toString())
                    || !existing.world.equals(world)
                    || !existing.description.equals(description)
                    || existing.cents != cents)
                  throw new IllegalArgumentException("Receipt terms cannot change");
                return new Work(existing, false);
              }
              try (var check =
                  c.prepareStatement(
                      "SELECT id FROM creation_fees WHERE player=? AND state NOT IN"
                          + " ('SUCCESS','DEBIT_FAILED','REFUNDED') LIMIT 1")) {
                check.setString(1, player.toString());
                try (var rows = check.executeQuery()) {
                  if (rows.next())
                    throw new IllegalStateException(
                        "Resolve pending creation receipt " + rows.getString(1));
                }
              }
              execute(
                  c,
                  "INSERT INTO creation_fees VALUES(?,?,?,?,?,'DEBIT_DISPATCHING','','')",
                  id.toString(),
                  player.toString(),
                  world,
                  description,
                  cents);
              return new Work(Objects.requireNonNull(read(c, id.toString())), true);
            })
        .thenCompose(
            work -> {
              if (!work.fresh) return CompletableFuture.completedFuture(result(work.receipt));
              Receipt receipt = work.receipt;
              return callWallet(receipt, false)
                  .thenCompose(
                      outcome -> {
                        if (outcome != WalletResult.SUCCESS)
                          return state(
                              receipt.id,
                              outcome == WalletResult.UNKNOWN ? "DEBIT_REVIEW" : "DEBIT_FAILED",
                              "",
                              outcome == WalletResult.UNKNOWN
                                  ? "Inspect provider debit before resolving"
                                  : "Wallet debit rejected");
                        return state(receipt.id, "DEBIT_PAID", "", "Wallet debit completed")
                            .thenCompose(
                                ignored ->
                                    state(receipt.id, "INSERTING", "", "Native insert dispatched"))
                            .thenCompose(
                                ignored ->
                                    CompletableFuture.completedFuture(null)
                                        .thenCompose(nothing -> insert.get())
                                        .handle(
                                            (inserted, error) -> {
                                              if (error != null
                                                  || inserted == null
                                                  || (!inserted.cancelled()
                                                      && inserted.structure().isEmpty()))
                                                return state(
                                                    receipt.id,
                                                    "INSERT_REVIEW",
                                                    "",
                                                    "Native insertion outcome requires inspection");
                                              if (inserted.cancelled())
                                                return state(
                                                        receipt.id,
                                                        "REFUND_READY",
                                                        "",
                                                        "Native creation event cancelled")
                                                    .thenCompose(nothing -> refund(receipt.id));
                                              return state(
                                                  receipt.id,
                                                  "SUCCESS",
                                                  Long.toString(
                                                      inserted.structure().orElseThrow().getUid()),
                                                  "Created and paid");
                                            })
                                        .thenCompose(Function.identity()));
                      });
            })
        .exceptionally(error -> new CreationResult("NEEDS_REVIEW", id + ": " + message(error)))
        .whenComplete((result, error) -> active.remove(id.toString()));
  }

  private CompletionStage<WalletResult> callWallet(Receipt receipt, boolean refund) {
    if (closed) return CompletableFuture.completedFuture(WalletResult.FAILED);
    return CompletableFuture.completedFuture(null)
        .thenCompose(ignored -> wallet.apply(receipt, refund))
        .handle((result, error) -> error == null && result != null ? result : WalletResult.UNKNOWN);
  }

  private CompletionStage<CreationResult> refund(String id) {
    return database
        .transaction(
            c -> {
              Receipt receipt = read(c, id);
              if (receipt == null || !receipt.state.equals("REFUND_READY"))
                return new Work(receipt, false);
              execute(c, "UPDATE creation_fees SET state='REFUND_DISPATCHING' WHERE id=?", id);
              return new Work(receipt, true);
            })
        .thenCompose(
            work -> {
              if (!work.fresh)
                return CompletableFuture.completedFuture(
                    work.receipt == null
                        ? new CreationResult("FAILED", "Receipt missing")
                        : result(work.receipt));
              return callWallet(work.receipt, true)
                  .thenCompose(
                      outcome ->
                          state(
                              id,
                              outcome == WalletResult.SUCCESS
                                  ? "REFUNDED"
                                  : outcome == WalletResult.UNKNOWN
                                      ? "REFUND_REVIEW"
                                      : "REFUND_READY",
                              "",
                              outcome == WalletResult.SUCCESS
                                  ? "Creation cancelled; fee refunded"
                                  : "Refund pending"));
            });
  }

  CompletionStage<Void> recoverRefunds() {
    if (!ready || closed) return CompletableFuture.completedFuture(null);
    return database
        .read(
            c -> {
              List<String> ids = new ArrayList<>();
              try (var statement =
                  c.prepareStatement(
                      "SELECT id FROM creation_fees WHERE state='REFUND_READY' AND id>? ORDER BY id"
                          + " LIMIT 50")) {
                statement.setString(1, recoveryCursor);
                try (var rows = statement.executeQuery()) {
                  while (rows.next()) ids.add(rows.getString(1));
                }
              }
              recoveryCursor = ids.isEmpty() ? "" : ids.getLast();
              return ids;
            })
        .thenCompose(
            ids -> {
              List<CompletableFuture<?>> futures = new ArrayList<>();
              for (String id : ids)
                if (active.add(id))
                  futures.add(
                      refund(id)
                          .whenComplete((result, error) -> active.remove(id))
                          .toCompletableFuture());
              return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            });
  }

  CompletionStage<List<Receipt>> pending(int page) {
    return database.read(
        c -> {
          List<Receipt> result = new ArrayList<>();
          try (var statement =
              c.prepareStatement(
                  "SELECT * FROM creation_fees WHERE state NOT IN"
                      + " ('SUCCESS','DEBIT_FAILED','REFUNDED') ORDER BY id LIMIT 20 OFFSET ?")) {
            statement.setLong(1, Math.max(0L, page) * 20);
            try (var rows = statement.executeQuery()) {
              while (rows.next()) result.add(row(rows));
            }
          }
          return List.copyOf(result);
        });
  }

  CompletionStage<Boolean> resolve(UUID id, String actor, String decision, String reason) {
    if (closed || !ready || reason == null || reason.isBlank() || reason.length() > 1000)
      return CompletableFuture.completedFuture(false);
    if (!active.add(id.toString())) return CompletableFuture.completedFuture(false);
    return database
        .transaction(
            c -> {
              Receipt receipt = read(c, id.toString());
              if (receipt == null) return false;
              String next =
                  switch (decision) {
                    case "debit-applied" ->
                        Set.of("DEBIT_REVIEW", "DEBIT_DISPATCHING").contains(receipt.state)
                            ? "REFUND_READY"
                            : null;
                    case "debit-not-applied" ->
                        Set.of("DEBIT_REVIEW", "DEBIT_DISPATCHING").contains(receipt.state)
                            ? "DEBIT_FAILED"
                            : null;
                    case "created" ->
                        Set.of("INSERT_REVIEW", "INSERTING").contains(receipt.state)
                            ? "SUCCESS"
                            : null;
                    case "not-created" ->
                        Set.of("INSERT_REVIEW", "INSERTING").contains(receipt.state)
                            ? "REFUND_READY"
                            : null;
                    case "refund-applied" ->
                        Set.of("REFUND_REVIEW", "REFUND_DISPATCHING").contains(receipt.state)
                            ? "REFUNDED"
                            : null;
                    case "refund-not-applied" ->
                        Set.of("REFUND_REVIEW", "REFUND_DISPATCHING").contains(receipt.state)
                            ? "REFUND_READY"
                            : null;
                    default -> null;
                  };
              if (next == null) return false;
              execute(
                  c,
                  "UPDATE creation_fees SET state=?,detail=? WHERE id=?",
                  next,
                  reason,
                  id.toString());
              execute(
                  c,
                  "INSERT INTO creation_fee_reviews VALUES(?,?,?,?,?,?)",
                  UUID.randomUUID().toString(),
                  id.toString(),
                  actor,
                  decision,
                  reason,
                  System.currentTimeMillis());
              return true;
            })
        .whenComplete((result, error) -> active.remove(id.toString()));
  }

  private CompletionStage<CreationResult> state(
      String id, String state, String structure, String detail) {
    return database.transaction(
        c -> {
          execute(
              c,
              "UPDATE creation_fees SET state=?,structure=?,detail=? WHERE id=?",
              state,
              structure,
              detail,
              id);
          return result(Objects.requireNonNull(read(c, id)));
        });
  }

  private static CreationResult result(Receipt receipt) {
    return new CreationResult(receipt.state, receipt.id + ": " + receipt.detail);
  }

  private static Receipt read(Connection c, String id) throws SQLException {
    try (var s = c.prepareStatement("SELECT * FROM creation_fees WHERE id=?")) {
      s.setString(1, id);
      try (var rows = s.executeQuery()) {
        return rows.next() ? row(rows) : null;
      }
    }
  }

  private static Receipt row(ResultSet r) throws SQLException {
    return new Receipt(
        r.getString("id"),
        r.getString("player"),
        r.getString("world"),
        r.getString("description"),
        r.getLong("cents"),
        r.getString("state"),
        r.getString("structure"),
        r.getString("detail"));
  }

  private static void execute(Connection c, String sql, Object... values) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      s.executeUpdate();
    }
  }

  private static CompletionStage<CreationResult> done(String status, String detail) {
    return CompletableFuture.completedFuture(new CreationResult(status, detail));
  }

  private static String message(Throwable error) {
    while (error.getCause() != null) error = error.getCause();
    return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
  }
}
