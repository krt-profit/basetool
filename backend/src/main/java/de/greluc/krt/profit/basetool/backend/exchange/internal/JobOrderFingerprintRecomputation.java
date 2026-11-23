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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.AuecProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes the exchange fingerprints of marked job orders (REQ-OXCH-003, ADR-0406).
 *
 * <p>A run reads the marks of the orders marked longest ago and projects those orders in one {@code
 * REPEATABLE READ} transaction, so every read figure and every mark it sees come from one committed
 * state. It writes a fingerprint and its instant only when the fingerprint changed, and deletes
 * exactly the marks it read: a mark committed after its snapshot survives for the next run, so no
 * change is lost. One backend instance at a time runs, under {@link
 * ExchangeJobOrderMarkRepository#tryWorkerLock()}.
 */
@Service
public class JobOrderFingerprintRecomputation {

  private final ExchangeJobOrderMarkRepository marks;
  private final ExchangeJobOrderFingerprintRepository fingerprints;
  private final JobOrderExchangeReads reads;
  private final ExchangeJobOrderMetrics metrics;
  private final int batchSize;
  private final TransactionTemplate lockTransaction;
  private final TransactionTemplate batchTransaction;
  private final Clock clock = Clock.systemUTC();

  /**
   * Creates the recomputation.
   *
   * @param marks the dirty marks
   * @param fingerprints the stored fingerprints
   * @param reads the job-order module's projection of the member-independent read
   * @param metrics the family's counters
   * @param properties the worker configuration, read for the batch size
   * @param transactionManager the manager of the lock and the batch transaction
   */
  public JobOrderFingerprintRecomputation(
      @NotNull ExchangeJobOrderMarkRepository marks,
      @NotNull ExchangeJobOrderFingerprintRepository fingerprints,
      @NotNull JobOrderExchangeReads reads,
      @NotNull ExchangeJobOrderMetrics metrics,
      @NotNull JobOrderFingerprintProperties properties,
      @NotNull PlatformTransactionManager transactionManager) {
    this.marks = marks;
    this.fingerprints = fingerprints;
    this.reads = reads;
    this.metrics = metrics;
    this.batchSize = properties.batchSize();
    this.lockTransaction = new TransactionTemplate(transactionManager);
    this.batchTransaction = new TransactionTemplate(transactionManager);
    this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.batchTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * Recomputes one batch of marked orders and counts its outcomes once it committed.
   *
   * @return the number of orders recomputed, {@code 0} when nothing is marked or another instance
   *     holds the worker's lock
   */
  public int recomputeMarked() {
    Batch batch =
        lockTransaction.execute(_ -> marks.tryWorkerLock() ? recomputeBatch() : Batch.NONE);
    if (batch == null) {
      return 0;
    }
    metrics.countFingerprints(batch.changed(), batch.unchanged());
    return batch.changed() + batch.unchanged();
  }

  private Batch recomputeBatch() {
    Batch batch = batchTransaction.execute(_ -> recompute());
    return batch == null ? Batch.NONE : batch;
  }

  private Batch recompute() {
    List<ExchangeJobOrderMark> read = marks.findMarksOfOldestOrders(batchSize);
    if (read.isEmpty()) {
      return Batch.NONE;
    }
    Set<UUID> orderIds =
        read.stream().map(ExchangeJobOrderMark::getJobOrderId).collect(Collectors.toSet());
    Map<UUID, JobOrderExchangeRead> current = reads.project(orderIds);
    Map<UUID, ExchangeJobOrderFingerprint> stored =
        fingerprints.findAllById(current.keySet()).stream()
            .collect(
                Collectors.toMap(ExchangeJobOrderFingerprint::getJobOrderId, Function.identity()));
    Instant now = clock.instant();
    int changed = 0;
    for (Map.Entry<UUID, JobOrderExchangeRead> entry : current.entrySet()) {
      byte[] fingerprint = JobOrderFingerprints.of(entry.getValue());
      ExchangeJobOrderFingerprint previous = stored.get(entry.getKey());
      if (previous == null || !previous.holds(fingerprint)) {
        fingerprints.write(entry.getKey(), fingerprint, now, settlementOpen(entry.getValue()));
        changed++;
      }
    }
    marks.deleteAllByIdInBatch(read.stream().map(ExchangeJobOrderMark::getId).toList());
    return new Batch(changed, current.size() - changed);
  }

  private static boolean settlementOpen(JobOrderExchangeRead read) {
    return read.progress() instanceof AuecProgress money && money.settlementOpen();
  }

  /**
   * The outcome of one batch.
   *
   * @param changed the orders whose fingerprint changed
   * @param unchanged the orders whose fingerprint stayed
   */
  private record Batch(int changed, int unchanged) {

    /** A batch that recomputed nothing. */
    static final Batch NONE = new Batch(0, 0);
  }
}
