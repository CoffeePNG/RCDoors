package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;
import net.republicraft.platform.api.data.*;

/** Real SQLite with serialized transactions and an injected failure before commit. */
final class FeeDatabase implements DatabaseHandle {
  private final Path path;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  boolean failNextCommit;

  FeeDatabase(Path path) throws Exception {
    this.path = path;
    Class.forName("org.sqlite.JDBC");
    try (Connection c = connect()) {
      for (Migration m : CreationPayments.schema().migrations())
        for (String sql : m.statements()) c.createStatement().execute(sql);
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + path);
  }

  private <T> CompletionStage<T> execute(SqlFunction<T> work, boolean transaction) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (Connection c = connect()) {
            if (transaction) c.setAutoCommit(false);
            try {
              T value = work.apply(c);
              if (transaction) {
                if (failNextCommit) {
                  failNextCommit = false;
                  throw new SQLException("Injected commit failure");
                }
                c.commit();
              }
              return value;
            } catch (Throwable e) {
              if (transaction) c.rollback();
              throw e;
            }
          } catch (Throwable e) {
            throw new CompletionException(e);
          }
        },
        worker);
  }

  public Path path() {
    return path;
  }

  public <T> CompletionStage<T> read(SqlFunction<T> work) {
    return execute(work, false);
  }

  public <T> CompletionStage<T> write(SqlFunction<T> work) {
    return execute(work, false);
  }

  public <T> CompletionStage<T> transaction(SqlFunction<T> work) {
    return execute(work, true);
  }

  public CompletionStage<Void> flush() {
    return read(c -> null);
  }

  public boolean isClosed() {
    return worker.isShutdown();
  }

  public void close() {
    worker.shutdown();
    try {
      worker.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
