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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.DeletionRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BusinessMetricsCollector}: the fixed-cadence sweep populates the
 * queue-depth and oldest-pending-age gauges from the repository counts, an empty queue reports
 * {@code 0}, and the {@code status}-tagged gauges are keyed by the bounded lifecycle enum.
 */
@ExtendWith(MockitoExtension.class)
class BusinessMetricsCollectorTest {

  @Mock private UserRepository userRepository;
  @Mock private BankBookingRequestRepository bankBookingRequestRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private DeletionRequestRepository deletionRequestRepository;
  @Mock private OperationRepository operationRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private P4kImportJobRepository p4kImportJobRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private MaterialExchangeRequestRepository materialExchangeRequestRepository;

  private SimpleMeterRegistry registry;
  private BusinessMetricsCollector collector;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    collector =
        new BusinessMetricsCollector(
            registry,
            userRepository,
            deletionRequestRepository,
            bankBookingRequestRepository,
            jobOrderRepository,
            operationRepository,
            refineryOrderRepository,
            p4kImportJobRepository,
            materialExchangeOfferRepository,
            materialExchangeRequestRepository,
            new TaskMetrics(registry));
    collector.registerGauges();
  }

  @Test
  void refresh_populatesQueueDepthAndOldestAgeGaugesFromRepositories() {
    when(userRepository.countByApprovalStatus(ApprovalStatus.PENDING)).thenReturn(3L);
    when(userRepository.findOldestCreatedAtByApprovalStatus(ApprovalStatus.PENDING))
        .thenReturn(Instant.now().minusSeconds(120));
    when(bankBookingRequestRepository.countByStatus(BankBookingRequestStatus.PENDING))
        .thenReturn(4L);
    when(jobOrderRepository.countByStatus(JobOrderStatus.OPEN)).thenReturn(5L);
    when(jobOrderRepository.countByStatus(JobOrderStatus.IN_PROGRESS)).thenReturn(2L);
    when(p4kImportJobRepository.countByStatus(P4kImportJobStatus.PENDING)).thenReturn(1L);
    when(materialExchangeOfferRepository.countByStatus(MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(7L);

    collector.refresh();

    assertThat(gauge(MetricNames.REGISTRATION_PENDING)).isEqualTo(3.0d);
    assertThat(gauge(MetricNames.REGISTRATION_PENDING_OLDEST_AGE)).isGreaterThanOrEqualTo(119.0d);
    assertThat(gauge(MetricNames.BANK_BOOKING_REQUEST_PENDING)).isEqualTo(4.0d);
    assertThat(statusGauge(MetricNames.JOB_ORDER_OPEN, JobOrderStatus.OPEN.name())).isEqualTo(5.0d);
    assertThat(statusGauge(MetricNames.JOB_ORDER_OPEN, JobOrderStatus.IN_PROGRESS.name()))
        .isEqualTo(2.0d);
    assertThat(statusGauge(MetricNames.P4K_IMPORT_JOB_PENDING, P4kImportJobStatus.PENDING.name()))
        .isEqualTo(1.0d);
    assertThat(
            statusGauge(
                MetricNames.MATERIAL_EXCHANGE_ACTIVE, MaterialExchangeOfferStatus.ACTIVE.name()))
        .isEqualTo(7.0d);
  }

  @Test
  void refresh_reportsZeroForEmptyQueuesAndAges() {
    collector.refresh();

    assertThat(gauge(MetricNames.REGISTRATION_PENDING)).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.BANK_BOOKING_REQUEST_PENDING_OLDEST_AGE)).isEqualTo(0.0d);
    assertThat(statusGauge(MetricNames.JOB_ORDER_OPEN, JobOrderStatus.OPEN.name())).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.USERS_PENDING_DELETION)).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.USERS_PENDING_DELETION_OLDEST_AGE)).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.DELETION_REQUEST_PENDING)).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.DELETION_REQUEST_PENDING_OLDEST_AGE)).isEqualTo(0.0d);
  }

  @Test
  void refresh_populatesTheDeletionRequestQueueGauges() {
    when(deletionRequestRepository.countByStatus(DeletionRequestStatus.PENDING)).thenReturn(1L);
    when(deletionRequestRepository.findOldestCreatedAtByStatus(DeletionRequestStatus.PENDING))
        .thenReturn(Instant.now().minus(20, ChronoUnit.DAYS));

    collector.refresh();

    assertThat(gauge(MetricNames.DELETION_REQUEST_PENDING)).isEqualTo(1.0d);
    assertThat(gauge(MetricNames.DELETION_REQUEST_PENDING_OLDEST_AGE)).isGreaterThan(1209600.0d);
  }

  @Test
  void refresh_populatesTheUnfinishedDeletionGauges() {
    when(userRepository.countOrphanedMemberAccounts()).thenReturn(2L);
    when(userRepository.findOldestOrphanedMemberAbsenceStamp())
        .thenReturn(Instant.now().minus(9, ChronoUnit.DAYS));

    collector.refresh();

    assertThat(gauge(MetricNames.USERS_PENDING_DELETION)).isEqualTo(2.0d);
    assertThat(gauge(MetricNames.USERS_PENDING_DELETION_OLDEST_AGE)).isGreaterThan(604800.0d);
  }

  @Test
  void refresh_readsTheOrphanQueriesThatExcludeServiceAccounts() {
    collector.refresh();

    verify(userRepository).countOrphanedMemberAccounts();
    verify(userRepository).findOldestOrphanedMemberAbsenceStamp();
  }

  @Test
  void refresh_recordsSuccessfulRunThroughTaskMetrics() {
    collector.refresh();

    assertThat(execCount(MetricNames.OUTCOME_SUCCESS)).isEqualTo(1.0d);
    assertThat(lastSuccess()).isGreaterThan(0.0d);
  }

  @Test
  void refresh_recordsFailureAndDoesNotPropagate_whenAQueryThrows() {
    when(userRepository.countByApprovalStatus(ApprovalStatus.PENDING))
        .thenThrow(new RuntimeException("DB down"));

    collector.refresh();

    assertThat(execCount(MetricNames.OUTCOME_FAILURE)).isEqualTo(1.0d);
    assertThat(
            registry
                .find(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.BUSINESS_METRICS.label())
                .gauge())
        .isNull();
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  private double statusGauge(String name, String status) {
    return registry.get(name).tag(MetricNames.TAG_STATUS, status).gauge().value();
  }

  private double execCount(String outcome) {
    return registry
        .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
        .tag(MetricNames.TAG_JOB, ScheduledJob.BUSINESS_METRICS.label())
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .counter()
        .count();
  }

  private double lastSuccess() {
    return registry
        .get(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
        .tag(MetricNames.TAG_JOB, ScheduledJob.BUSINESS_METRICS.label())
        .gauge()
        .value();
  }
}
