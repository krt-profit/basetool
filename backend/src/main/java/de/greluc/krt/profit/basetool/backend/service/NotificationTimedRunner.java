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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every {@link TimedNoticeProducer} once, on at most one backend instance at a time
 * (REQ-NOTIF-026).
 *
 * <p>The run takes the transaction-scoped Postgres advisory lock {@link #LOCK_KEY}; an instance
 * that does not get it skips the run. Each producer runs in its own transaction, so one failing
 * producer neither blocks the others nor rolls back what they have marked.
 */
@Service
@Slf4j
public class NotificationTimedRunner {

  /** The advisory lock key of the producer run, apart from the exchange's lot locks. */
  static final long LOCK_KEY = 0x4e4f54494d454421L;

  private final List<TimedNoticeProducer> producers;
  private final NotificationRepository notificationRepository;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate lockTransaction;
  private final TransactionTemplate producerTransaction;

  /**
   * Creates the runner.
   *
   * @param producers every producer bean, possibly none
   * @param notificationRepository the repository taking the advisory lock
   * @param meterRegistry where the produced-notices counter is registered
   * @param transactionManager the manager both transaction templates use
   */
  public NotificationTimedRunner(
      @NotNull List<TimedNoticeProducer> producers,
      @NotNull NotificationRepository notificationRepository,
      @NotNull MeterRegistry meterRegistry,
      @NotNull PlatformTransactionManager transactionManager) {
    this.producers = List.copyOf(producers);
    this.notificationRepository = notificationRepository;
    this.meterRegistry = meterRegistry;
    this.lockTransaction = new TransactionTemplate(transactionManager);
    this.producerTransaction = new TransactionTemplate(transactionManager);
    this.producerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Runs every producer if this instance gets the cross-instance lock.
   *
   * @param now the instant the producers evaluate against
   * @return the number of notices published, {@code 0} when another instance holds the lock
   * @throws RuntimeException the first failure of a producer, rethrown after the others have run
   */
  public int runOnce(@NotNull Instant now) {
    Integer produced = lockTransaction.execute(_ -> runLocked(now));
    return produced == null ? 0 : produced;
  }

  private int runLocked(@NotNull Instant now) {
    if (!notificationRepository.tryTimedProducerLock(LOCK_KEY)) {
      log.debug("Another instance holds the timed-notification lock; skipping this run");
      return 0;
    }
    int total = 0;
    RuntimeException firstFailure = null;
    for (TimedNoticeProducer producer : producers) {
      try {
        Integer count = producerTransaction.execute(_ -> producer.produce(now));
        int produced = count == null ? 0 : count;
        meterRegistry
            .counter(MetricNames.NOTIFICATION_TIMED_PRODUCED, MetricNames.TAG_KIND, producer.kind())
            .increment(produced);
        total += produced;
      } catch (RuntimeException e) {
        log.warn("Timed notice producer '{}' failed: {}", producer.kind(), e.toString());
        firstFailure = firstFailure == null ? e : firstFailure;
      }
    }
    rethrow(firstFailure);
    return total;
  }

  private static void rethrow(@Nullable RuntimeException failure) {
    if (failure != null) {
      throw failure;
    }
  }
}
