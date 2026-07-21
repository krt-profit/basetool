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

package de.greluc.krt.profit.basetool.backend.task;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Samples the approval- and work-queue depths (and the age of the oldest waiting item) into {@code
 * basetool_*_pending_*} / {@code basetool_*_open_*} gauges on a fixed cadence (REQ-OBS-011).
 *
 * <p>Unlike the point-hook counters, queue depth is a snapshot, so it is polled rather than
 * event-driven: every {@code app.monitoring.business-metrics.interval-ms} (default 60&nbsp;s)
 * {@link #refresh()} runs the {@code countBy…} / {@code MIN(createdAt)} queries once inside a
 * single read-only transaction and pushes the results into the gauge holders. Sampling on a fixed
 * timer (rather than binding each gauge to a live repository supplier that would re-query on every
 * Prometheus scrape) bounds the DB load to one pass per minute regardless of scrape frequency.
 *
 * <p>Every gauge carries only bounded labels — the {@code status} value is an application enum, and
 * the counts/ages are pure numbers (no ids, names or amounts), satisfying REQ-OBS-006. Sources that
 * lack a bounded queue notion are deliberately excluded: notifications (per-recipient unread only,
 * no org-wide queue), org units (no lifecycle status) and missions (free-text {@code status}
 * column, not a bounded enum).
 *
 * <p>Lives in the {@code task} package — a top consumer nothing else depends on — so its downward
 * edges onto {@code repository} stay acyclic (ADR-0047). Config-gated off under the test profile so
 * it never queries the DB mid-{@code @SpringBootTest}.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.monitoring.business-metrics",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BusinessMetricsCollector {

  private final MeterRegistry meterRegistry;
  private final UserRepository userRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final JobOrderRepository jobOrderRepository;
  private final OperationRepository operationRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final P4kImportJobRepository p4kImportJobRepository;
  private final MaterialExchangeOfferRepository materialExchangeOfferRepository;
  private final MaterialExchangeRequestRepository materialExchangeRequestRepository;
  private final TaskMetrics taskMetrics;

  private final AtomicLong registrationPending = new AtomicLong();
  private final AtomicLong registrationOldestAge = new AtomicLong();
  private final AtomicLong bankRequestPending = new AtomicLong();
  private final AtomicLong bankRequestOldestAge = new AtomicLong();
  private final AtomicLong jobOrderOpen = new AtomicLong();
  private final AtomicLong jobOrderInProgress = new AtomicLong();
  private final AtomicLong jobOrderOldestAge = new AtomicLong();
  private final AtomicLong operationPlanned = new AtomicLong();
  private final AtomicLong operationActive = new AtomicLong();
  private final AtomicLong operationOldestAge = new AtomicLong();
  private final AtomicLong refineryOpen = new AtomicLong();
  private final AtomicLong refineryInProgress = new AtomicLong();
  private final AtomicLong refineryOldestAge = new AtomicLong();
  private final AtomicLong p4kPending = new AtomicLong();
  private final AtomicLong p4kRunning = new AtomicLong();
  private final AtomicLong p4kOldestAge = new AtomicLong();
  private final AtomicLong materialExchangeActive = new AtomicLong();
  private final AtomicLong materialRequestOpen = new AtomicLong();

  /**
   * Registers every queue-depth and oldest-age gauge against its holder once at startup, so each
   * series exists (reporting {@code 0}) before the first {@link #refresh()} tick populates it.
   */
  @PostConstruct
  void registerGauges() {
    countGauge(MetricNames.REGISTRATION_PENDING, registrationPending);
    ageGauge(MetricNames.REGISTRATION_PENDING_OLDEST_AGE, registrationOldestAge);

    countGauge(MetricNames.BANK_BOOKING_REQUEST_PENDING, bankRequestPending);
    ageGauge(MetricNames.BANK_BOOKING_REQUEST_PENDING_OLDEST_AGE, bankRequestOldestAge);

    countGauge(MetricNames.JOB_ORDER_OPEN, jobOrderOpen, JobOrderStatus.OPEN.name());
    countGauge(MetricNames.JOB_ORDER_OPEN, jobOrderInProgress, JobOrderStatus.IN_PROGRESS.name());
    ageGauge(MetricNames.JOB_ORDER_OPEN_OLDEST_AGE, jobOrderOldestAge);

    countGauge(MetricNames.OPERATION_OPEN, operationPlanned, OperationStatus.PLANNED.name());
    countGauge(MetricNames.OPERATION_OPEN, operationActive, OperationStatus.ACTIVE.name());
    ageGauge(MetricNames.OPERATION_OPEN_OLDEST_AGE, operationOldestAge);

    countGauge(MetricNames.REFINERY_ORDER_OPEN, refineryOpen, RefineryOrderStatus.OPEN.name());
    countGauge(
        MetricNames.REFINERY_ORDER_OPEN,
        refineryInProgress,
        RefineryOrderStatus.IN_PROGRESS.name());
    ageGauge(MetricNames.REFINERY_ORDER_OPEN_OLDEST_AGE, refineryOldestAge);

    countGauge(MetricNames.P4K_IMPORT_JOB_PENDING, p4kPending, P4kImportJobStatus.PENDING.name());
    countGauge(MetricNames.P4K_IMPORT_JOB_PENDING, p4kRunning, P4kImportJobStatus.RUNNING.name());
    ageGauge(MetricNames.P4K_IMPORT_JOB_PENDING_OLDEST_AGE, p4kOldestAge);

    countGauge(
        MetricNames.MATERIAL_EXCHANGE_ACTIVE,
        materialExchangeActive,
        MaterialExchangeOfferStatus.ACTIVE.name());

    countGauge(
        MetricNames.MATERIAL_REQUEST_OPEN,
        materialRequestOpen,
        MaterialExchangeRequestStatus.ACTIVE.name());
  }

  /**
   * Re-samples every queue depth and oldest-pending age into the gauge holders. Runs on a fixed
   * timer in a single read-only transaction; the first execution fires shortly after startup, so
   * the gauges leave their initial {@code 0} within moments of boot.
   *
   * <p>The sampling body is wrapped in {@link TaskMetrics#record} under {@link
   * ScheduledJob#BUSINESS_METRICS} so a wedged sampler is not a silent failure: if a query throws,
   * the run is recorded as a {@code failure}, its {@code last_success} timestamp freezes, and the
   * {@code BusinessMetricsStale} alert fires — otherwise every {@code *_pending_*} / {@code
   * *_open_*} gauge would freeze at its last value while the {@code *ApprovalOverdue} alerts kept
   * evaluating stale numbers (#1041 item 3). The wrapper also swallows the exception so the
   * scheduler thread survives.
   */
  @Scheduled(fixedRateString = "${app.monitoring.business-metrics.interval-ms:60000}")
  @Transactional(readOnly = true)
  public void refresh() {
    taskMetrics.record(ScheduledJob.BUSINESS_METRICS, this::sample);
  }

  /**
   * Runs the queue-depth / oldest-age queries once and writes the results into the gauge holders.
   * Executes within the read-only transaction opened by {@link #refresh()} and carries no
   * transaction annotation of its own; separated from {@code refresh()} only so the whole body can
   * be handed to {@link TaskMetrics#record} as a single instrumented unit.
   */
  private void sample() {
    registrationPending.set(userRepository.countByApprovalStatus(ApprovalStatus.PENDING));
    registrationOldestAge.set(
        ageSeconds(userRepository.findOldestCreatedAtByApprovalStatus(ApprovalStatus.PENDING)));

    bankRequestPending.set(
        bankBookingRequestRepository.countByStatus(BankBookingRequestStatus.PENDING));
    bankRequestOldestAge.set(
        ageSeconds(
            bankBookingRequestRepository.findOldestCreatedAtByStatus(
                BankBookingRequestStatus.PENDING)));

    jobOrderOpen.set(jobOrderRepository.countByStatus(JobOrderStatus.OPEN));
    jobOrderInProgress.set(jobOrderRepository.countByStatus(JobOrderStatus.IN_PROGRESS));
    jobOrderOldestAge.set(
        ageSeconds(jobOrderRepository.findOldestCreatedAtByStatus(JobOrderStatus.OPEN)));

    operationPlanned.set(operationRepository.countByStatus(OperationStatus.PLANNED));
    operationActive.set(operationRepository.countByStatus(OperationStatus.ACTIVE));
    operationOldestAge.set(
        ageSeconds(operationRepository.findOldestCreatedAtByStatus(OperationStatus.PLANNED)));

    refineryOpen.set(refineryOrderRepository.countByStatus(RefineryOrderStatus.OPEN));
    refineryInProgress.set(refineryOrderRepository.countByStatus(RefineryOrderStatus.IN_PROGRESS));
    refineryOldestAge.set(
        ageSeconds(refineryOrderRepository.findOldestCreatedAtByStatus(RefineryOrderStatus.OPEN)));

    p4kPending.set(p4kImportJobRepository.countByStatus(P4kImportJobStatus.PENDING));
    p4kRunning.set(p4kImportJobRepository.countByStatus(P4kImportJobStatus.RUNNING));
    p4kOldestAge.set(
        ageSeconds(p4kImportJobRepository.findOldestCreatedAtByStatus(P4kImportJobStatus.PENDING)));

    materialExchangeActive.set(
        materialExchangeOfferRepository.countByStatus(MaterialExchangeOfferStatus.ACTIVE));

    materialRequestOpen.set(
        materialExchangeRequestRepository.countByStatus(MaterialExchangeRequestStatus.ACTIVE));
  }

  /**
   * Registers a queue-depth gauge with an optional bounded {@code status} tag.
   *
   * @param name the meter name
   * @param holder the mutable holder the sweep writes the current count into
   * @param status the bounded status tag value, or empty for an unlabelled total
   */
  private void countGauge(String name, AtomicLong holder, String... status) {
    Gauge.Builder<AtomicLong> builder = Gauge.builder(name, holder, AtomicLong::doubleValue);
    if (status.length == 1) {
      builder = builder.tag(MetricNames.TAG_STATUS, status[0]);
    }
    builder.register(meterRegistry);
  }

  /**
   * Registers an "oldest pending age" gauge whose value is a duration rendered in seconds.
   *
   * @param name the meter name
   * @param holder the mutable holder the sweep writes the current age (in seconds) into
   */
  private void ageGauge(String name, AtomicLong holder) {
    Gauge.builder(name, holder, AtomicLong::doubleValue)
        .baseUnit(MetricNames.UNIT_SECONDS)
        .register(meterRegistry);
  }

  /**
   * Converts the creation instant of the oldest waiting item into an age in seconds.
   *
   * @param oldest the earliest {@code createdAt} in the queue, or {@code null} when the queue is
   *     empty
   * @return the non-negative age in seconds, or {@code 0} when the queue is empty
   */
  private static long ageSeconds(Instant oldest) {
    if (oldest == null) {
      return 0L;
    }
    return Math.max(0L, Instant.now().getEpochSecond() - oldest.getEpochSecond());
  }
}
