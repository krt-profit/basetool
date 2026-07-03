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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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
  @Mock private OperationRepository operationRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private P4kImportJobRepository p4kImportJobRepository;

  private SimpleMeterRegistry registry;
  private BusinessMetricsCollector collector;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    collector =
        new BusinessMetricsCollector(
            registry,
            userRepository,
            bankBookingRequestRepository,
            jobOrderRepository,
            operationRepository,
            refineryOrderRepository,
            p4kImportJobRepository);
    // @PostConstruct is not invoked for a plain unit-constructed bean.
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

    collector.refresh();

    assertThat(gauge(MetricNames.REGISTRATION_PENDING)).isEqualTo(3.0d);
    assertThat(gauge(MetricNames.REGISTRATION_PENDING_OLDEST_AGE)).isGreaterThanOrEqualTo(119.0d);
    assertThat(gauge(MetricNames.BANK_BOOKING_REQUEST_PENDING)).isEqualTo(4.0d);
    assertThat(statusGauge(MetricNames.JOB_ORDER_OPEN, JobOrderStatus.OPEN.name())).isEqualTo(5.0d);
    assertThat(statusGauge(MetricNames.JOB_ORDER_OPEN, JobOrderStatus.IN_PROGRESS.name()))
        .isEqualTo(2.0d);
    assertThat(statusGauge(MetricNames.P4K_IMPORT_JOB_PENDING, P4kImportJobStatus.PENDING.name()))
        .isEqualTo(1.0d);
  }

  @Test
  void refresh_reportsZeroForEmptyQueuesAndAges() {
    // No stubs: every count returns 0L and every MIN(createdAt) returns null.
    collector.refresh();

    assertThat(gauge(MetricNames.REGISTRATION_PENDING)).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.BANK_BOOKING_REQUEST_PENDING_OLDEST_AGE)).isEqualTo(0.0d);
    assertThat(statusGauge(MetricNames.JOB_ORDER_OPEN, JobOrderStatus.OPEN.name())).isEqualTo(0.0d);
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  private double statusGauge(String name, String status) {
    return registry.get(name).tag(MetricNames.TAG_STATUS, status).gauge().value();
  }
}
