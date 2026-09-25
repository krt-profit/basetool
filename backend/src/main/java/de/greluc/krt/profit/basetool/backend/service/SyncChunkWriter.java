/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes an external catalogue into the database in short, isolated transactions (REQ-DATA-005).
 *
 * <p>Rows are written in chunks, each in its own {@code REQUIRES_NEW} transaction; a failed chunk
 * is replayed row by row, each in its own transaction, so a bad row rolls back alone.
 *
 * <p>Chunk callbacks must let database exceptions propagate, derive counts only from returned
 * results, and receive ids and DTOs, never entities from another transaction.
 */
@Slf4j
@Component
public class SyncChunkWriter {

  /** Default rows per chunk transaction — a few hundred rows keep the persistence context small. */
  public static final int DEFAULT_CHUNK_SIZE = 500;

  /** Runs a callback in its own new transaction, suspending any the caller holds. */
  private final TransactionTemplate requiresNew;

  /**
   * Creates the writer over the application's transaction manager.
   *
   * @param transactionManager the transaction manager every chunk and row transaction runs on
   */
  public SyncChunkWriter(@NotNull PlatformTransactionManager transactionManager) {
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Writes {@code rows} through {@code writer} chunk by chunk, retrying a failed chunk row by row.
   *
   * @param rows the rows to write, in order; never {@code null}
   * @param chunkSize rows per chunk transaction; at least 1
   * @param writer writes one chunk inside its transaction and returns one result per row it wrote
   *     (none for a row it skipped); must let a database exception propagate
   * @param label names the rows in the log line of a failed row, e.g. {@code "commodity price"}
   * @param describe renders one row for that log line; must not return personal data
   * @param <T> the row type
   * @param <R> the per-row result type
   * @return every result the chunks and retried rows produced, plus the count of rows that failed
   *     even on their own
   */
  @NotNull
  public <T, R> Outcome<R> write(
      @NotNull List<T> rows,
      int chunkSize,
      @NotNull Function<List<T>, List<R>> writer,
      @NotNull String label,
      @NotNull Function<T, String> describe) {
    if (chunkSize < 1) {
      throw new IllegalArgumentException("chunkSize must be at least 1");
    }
    List<R> results = new ArrayList<>();
    int failedRows = 0;
    for (int from = 0; from < rows.size(); from += chunkSize) {
      List<T> chunk = rows.subList(from, Math.min(rows.size(), from + chunkSize));
      try {
        results.addAll(Objects.requireNonNull(requiresNew.execute(status -> writer.apply(chunk))));
      } catch (RuntimeException chunkFailure) {
        log.warn(
            "A chunk of {} {} row(s) failed and was rolled back; retrying its rows one by one ({})",
            chunk.size(),
            label,
            chunkFailure.getClass().getSimpleName());
        for (T row : chunk) {
          try {
            results.addAll(
                Objects.requireNonNull(requiresNew.execute(status -> writer.apply(List.of(row)))));
          } catch (RuntimeException rowFailure) {
            failedRows++;
            log.error("Failed to write {} {}", label, describe.apply(row), rowFailure);
          }
        }
      }
    }
    return new Outcome<>(Collections.unmodifiableList(results), failedRows);
  }

  /**
   * Runs {@code work} in its own new transaction and returns its result — for the read that
   * preloads a lookup map, or the sweep that follows the writes.
   *
   * @param work the work to run; its result may be {@code null}
   * @param <R> the result type
   * @return what {@code work} returned
   */
  public <R> R inNewTransaction(@NotNull Supplier<R> work) {
    return requiresNew.execute(status -> work.get());
  }

  /**
   * What {@link #write} produced.
   *
   * @param results one entry per row written, in chunk order; retried rows follow their chunk's
   *     position
   * @param failedRows how many rows failed even in their own transaction and were skipped
   * @param <R> the per-row result type
   */
  public record Outcome<R>(@NotNull @Unmodifiable List<R> results, int failedRows) {}
}
