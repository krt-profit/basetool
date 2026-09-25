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
 * Writes an external catalogue into the database in short, isolated transactions (BE-PERF-09,
 * REQ-DATA-005).
 *
 * <p>The UEX syncs used to open one transaction per sync step and hold it across the HTTP fetch and
 * every row of the upsert: a pooled connection sat idle while UEX answered, the whole price matrix
 * shared one persistence context (so every lookup query auto-flushed a growing set of dirty rows,
 * quadratic over the run), and a single row the database refused — a unique violation, a too-long
 * value — marked the transaction rollback-only, so the run lost <em>every</em> row, not just that
 * one. This writer is the other half of the fix: the caller fetches with no transaction open, then
 * hands the rows over, and they are written
 *
 * <ol>
 *   <li>in chunks of {@code chunkSize}, each chunk in its own {@code REQUIRES_NEW} transaction, so
 *       a normal run costs one commit per chunk and a bounded persistence context; and
 *   <li>when a chunk fails, row by row, each row in its own {@code REQUIRES_NEW} transaction, so
 *       the one bad row rolls back alone and every other row of the chunk still commits.
 * </ol>
 *
 * <p>The chunk callback therefore must <strong>not</strong> swallow a database exception for a row
 * (that would leave the chunk's transaction marked rollback-only and lose its siblings); it lets it
 * propagate and the writer isolates it. A row the callback decides to skip (unknown parent, missing
 * id) simply produces no result. Because a failed chunk is replayed, the callback must derive its
 * counts from the returned results, never from side effects.
 *
 * <p>Callbacks receive ids and DTOs, never entities from another transaction: a managed entity does
 * not survive its transaction's commit, and assigning a detached one to a managed association is
 * how the 2026-09-06 login outage happened (vault: Backend, "An N+1 fix must not hand entities
 * across transactions"). Preloaded lookup maps hold ids; the callback resolves them with {@code
 * getReferenceById} / {@code findAllById} inside its own transaction.
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
   * Writes {@code rows} through {@code writer}, chunk by chunk, isolating a failing row as
   * described in the class Javadoc.
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
