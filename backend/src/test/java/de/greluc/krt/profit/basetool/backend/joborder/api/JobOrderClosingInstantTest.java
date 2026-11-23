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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Verifies that a job order's status keeps its closing instant in step (REQ-ORDERS-054). */
class JobOrderClosingInstantTest {

  @ParameterizedTest
  @EnumSource(
      value = JobOrderStatus.class,
      names = {"COMPLETED", "CANCELLED", "REJECTED"})
  void enteringATerminalStatusRecordsTheClosingInstant(JobOrderStatus terminal) {
    JobOrder order = JobOrder.builder().status(JobOrderStatus.IN_PROGRESS).build();
    Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

    order.setStatus(terminal);

    assertThat(order.getClosedAt()).isBetween(before, Instant.now());
  }

  @ParameterizedTest
  @EnumSource(
      value = JobOrderStatus.class,
      names = {"OPEN", "IN_PROGRESS"})
  void reopeningClearsTheClosingInstant(JobOrderStatus active) {
    JobOrder order = JobOrder.builder().build();
    order.setStatus(JobOrderStatus.CANCELLED);

    order.setStatus(active);

    assertThat(order.getClosedAt()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = JobOrderStatus.class,
      names = {"OPEN", "IN_PROGRESS"})
  void anActiveOrderHasNoClosingInstant(JobOrderStatus active) {
    JobOrder order = JobOrder.builder().build();

    order.setStatus(active);

    assertThat(order.getClosedAt()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = JobOrderStatus.class,
      names = {"COMPLETED", "CANCELLED", "REJECTED"})
  void aChangeBetweenTerminalStatusesKeepsTheFirstClosingInstant(JobOrderStatus terminal) {
    Instant closed = Instant.parse("2026-11-01T10:15:30.123456Z");
    JobOrder order = JobOrder.builder().status(JobOrderStatus.COMPLETED).closedAt(closed).build();

    order.setStatus(terminal);

    assertThat(order.getClosedAt()).isEqualTo(closed);
  }
}
