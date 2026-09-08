package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import nl.pim16aap2.animatedarchitecture.core.managers.DatabaseManager.StructureInsertResult;
import nl.pim16aap2.animatedarchitecture.core.structures.Structure;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CreationPaymentsTest {
  @TempDir Path directory;
  FeeDatabase database;
  CreationPayments payments;
  UUID player = UUID.randomUUID(), id = UUID.randomUUID();
  AtomicInteger debits = new AtomicInteger(),
      refunds = new AtomicInteger(),
      inserts = new AtomicInteger();
  BiFunction<CreationPayments.Receipt, Boolean, CompletionStage<CreationPayments.WalletResult>>
      behavior;

  static <T> T await(CompletionStage<T> future) throws Exception {
    return future.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @BeforeEach
  void setup() throws Exception {
    database = new FeeDatabase(directory.resolve("fees.db"));
    behavior =
        (receipt, refund) ->
            CompletableFuture.completedFuture(CreationPayments.WalletResult.SUCCESS);
    restart();
  }

  void restart() throws Exception {
    if (payments != null) payments.close();
    payments =
        new CreationPayments(
            database,
            (receipt, refund) -> {
              (refund ? refunds : debits).incrementAndGet();
              assertEquals(
                  refund ? "REFUND_DISPATCHING" : "DEBIT_DISPATCHING", state(receipt.id()));
              return behavior.apply(receipt, refund);
            });
    await(payments.start());
  }

  @AfterEach
  void close() {
    payments.close();
    database.close();
  }

  String state(String receipt) {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + database.path());
        var s = c.prepareStatement("SELECT state FROM creation_fees WHERE id=?")) {
      s.setString(1, receipt);
      try (var r = s.executeQuery()) {
        return r.next() ? r.getString(1) : "MISSING";
      }
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  void sql(String sql) throws Exception {
    await(
        database.transaction(
            c -> {
              c.createStatement().execute(sql);
              return null;
            }));
  }

  CompletableFuture<StructureInsertResult> insert() {
    inserts.incrementAndGet();
    assertEquals("INSERTING", state(id.toString()));
    var structure = mock(Structure.class);
    when(structure.getUid()).thenReturn(123L);
    return CompletableFuture.completedFuture(
        new StructureInsertResult(Optional.of(structure), false));
  }

  CompletionStage<nl.pim16aap2.animatedarchitecture.core.api.IEconomyManager.CreationResult>
      create() {
    return payments.create(id, player, "world", "bigdoor/shop/(1,2,3)-(4,5,6)", 10, this::insert);
  }

  @Test
  void committedIntentPrecedesWalletAndSuccessWaitsForNativeInsertion() throws Exception {
    var pending = new CompletableFuture<CreationPayments.WalletResult>();
    behavior = (r, f) -> pending;
    var result = create();
    for (int i = 0; i < 100 && debits.get() == 0; i++) Thread.sleep(5);
    assertFalse(result.toCompletableFuture().isDone());
    assertEquals(0, inserts.get());
    pending.complete(CreationPayments.WalletResult.SUCCESS);
    assertEquals("SUCCESS", await(result).status());
    assertEquals(1, inserts.get());
    restart();
    assertEquals("SUCCESS", await(create()).status());
    assertEquals(1, debits.get());
    assertEquals(1, inserts.get());
  }

  @Test
  void cancelledPrepareEventRefundsExactlyOnce() throws Exception {
    var result =
        payments.create(
            id,
            player,
            "world",
            "door",
            10,
            () ->
                CompletableFuture.completedFuture(
                    new StructureInsertResult(Optional.empty(), true)));
    assertEquals("REFUNDED", await(result).status());
    assertEquals(1, debits.get());
    assertEquals(1, refunds.get());
    restart();
    await(payments.recoverRefunds());
    assertEquals(1, refunds.get());
  }

  @Test
  void unknownDebitBlocksNewIdentityAndRequiresAuditedObservedOutcome() throws Exception {
    behavior =
        (r, f) -> {
          throw new IllegalStateException("unknown after debit");
        };
    assertEquals("DEBIT_REVIEW", await(create()).status());
    assertEquals(0, inserts.get());
    assertEquals(
        "NEEDS_REVIEW",
        await(payments.create(UUID.randomUUID(), player, "world", "another", 10, this::insert))
            .status());
    assertEquals(1, debits.get());
    restart();
    await(payments.recoverRefunds());
    assertEquals(0, refunds.get());
    assertTrue(
        await(
            payments.resolve(
                id, "operator", "debit-applied", "Wallet statement shows the original debit")));
    behavior = (r, f) -> CompletableFuture.completedFuture(CreationPayments.WalletResult.SUCCESS);
    await(payments.recoverRefunds());
    assertEquals("REFUNDED", state(id.toString()));
    assertEquals(1, refunds.get());
    assertThrows(Exception.class, () -> sql("DELETE FROM creation_fee_reviews"));
  }

  @Test
  void unknownInsertionNeverReceivesAutomaticRefund() throws Exception {
    assertEquals(
        "INSERT_REVIEW",
        await(
                payments.create(
                    id,
                    player,
                    "world",
                    "door",
                    10,
                    () ->
                        CompletableFuture.failedFuture(
                            new IllegalStateException("native insert outcome unknown"))))
            .status());
    restart();
    await(payments.recoverRefunds());
    assertEquals(0, refunds.get());
    assertFalse(await(payments.resolve(id, "operator", "created", "")));
    assertTrue(
        await(
            payments.resolve(
                id, "operator", "created", "Native UID 123 matches owner, world and cuboid")));
    assertEquals("SUCCESS", state(id.toString()));
    assertEquals(0, refunds.get());
  }

  @Test
  void intentCommitFailureCannotCallWallet() throws Exception {
    database.failNextCommit = true;
    assertEquals("NEEDS_REVIEW", await(create()).status());
    assertEquals(0, debits.get());
    assertEquals("MISSING", state(id.toString()));
  }

  @Test
  void postDebitPersistenceFailureStaysQuarantinedAcrossRestart() throws Exception {
    sql(
        "CREATE TRIGGER failed_result BEFORE UPDATE ON creation_fees WHEN NEW.state='DEBIT_PAID'"
            + " BEGIN SELECT RAISE(ABORT,'injected'); END");
    assertEquals("NEEDS_REVIEW", await(create()).status());
    assertEquals(1, debits.get());
    assertEquals(0, inserts.get());
    restart();
    assertEquals("DEBIT_REVIEW", state(id.toString()));
    await(create());
    assertEquals(1, debits.get());
  }

  @Test
  void knownPaidWithoutInsertIntentRecoversAsRefund() throws Exception {
    sql(
        "INSERT INTO creation_fees VALUES('"
            + id
            + "','"
            + player
            + "','world','door',1000,'DEBIT_PAID','','')");
    restart();
    await(payments.recoverRefunds());
    assertEquals("REFUNDED", state(id.toString()));
    assertEquals(0, debits.get());
    assertEquals(1, refunds.get());
  }

  @Test
  void unknownRefundCannotBeRetriedUntilStaffObservedOutcome() throws Exception {
    behavior =
        (r, f) ->
            CompletableFuture.completedFuture(
                f ? CreationPayments.WalletResult.UNKNOWN : CreationPayments.WalletResult.SUCCESS);
    await(
        payments.create(
            id,
            player,
            "world",
            "door",
            10,
            () ->
                CompletableFuture.completedFuture(
                    new StructureInsertResult(Optional.empty(), true))));
    assertEquals("REFUND_REVIEW", state(id.toString()));
    restart();
    await(payments.recoverRefunds());
    assertEquals(1, refunds.get());
    assertTrue(
        await(
            payments.resolve(id, "operator", "refund-applied", "Wallet history confirms refund")));
    assertEquals("REFUNDED", state(id.toString()));
    assertEquals(1, refunds.get());
  }

  @Test
  void changedReceiptTermsAndRetiredProviderCannotCallWallet() throws Exception {
    await(create());
    assertEquals(
        "NEEDS_REVIEW",
        await(payments.create(id, player, "world", "different", 10, this::insert)).status());
    payments.close();
    assertEquals("UNAVAILABLE", await(create()).status());
    assertEquals(1, debits.get());
  }

  @Test
  void failedRefundBacklogCannotStarveLaterRecoverableReceipt() throws Exception {
    await(
        database.transaction(
            c -> {
              try (var statement =
                  c.prepareStatement(
                      "INSERT INTO creation_fees"
                          + " VALUES(?,?,'world','door',1000,'REFUND_READY','','')")) {
                for (int i = 1; i <= 51; i++) {
                  statement.setString(1, new UUID(0, i).toString());
                  statement.setString(2, UUID.randomUUID().toString());
                  statement.executeUpdate();
                }
              }
              return null;
            }));
    String last = new UUID(0, 51).toString();
    behavior =
        (receipt, refund) ->
            CompletableFuture.completedFuture(
                receipt.id().equals(last)
                    ? CreationPayments.WalletResult.SUCCESS
                    : CreationPayments.WalletResult.FAILED);
    await(payments.recoverRefunds());
    assertEquals("REFUND_READY", state(last));
    await(payments.recoverRefunds());
    assertEquals("REFUNDED", state(last));
    assertEquals(51, refunds.get());
  }
}
