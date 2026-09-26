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

package de.greluc.krt.profit.basetool.backend.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the lifecycle state machine for an operation. Service callers other than ROLE_ADMIN are
 * gated on these rules; loosening one transition here silently loosens the API contract too.
 */
class OperationStatusTest {

  @ParameterizedTest(name = "{0} -> {1} is allowed")
  @CsvSource({
    "PLANNED, PLANNED",
    "ACTIVE, ACTIVE",
    "COMPLETED, COMPLETED",
    "CANCELED, CANCELED",
    "PLANNED, ACTIVE",
    "ACTIVE, COMPLETED",
    "PLANNED, CANCELED",
    "ACTIVE, CANCELED"
  })
  void allowedTransitions(OperationStatus from, OperationStatus to) {
    assertTrue(from.canTransitionTo(to), "expected " + from + " -> " + to + " to be allowed");
  }

  @ParameterizedTest(name = "{0} -> {1} is rejected")
  @CsvSource({
    "PLANNED, COMPLETED",
    "ACTIVE, PLANNED",
    "COMPLETED, PLANNED",
    "COMPLETED, ACTIVE",
    "COMPLETED, CANCELED",
    "CANCELED, PLANNED",
    "CANCELED, ACTIVE",
    "CANCELED, COMPLETED"
  })
  void rejectedTransitions(OperationStatus from, OperationStatus to) {
    assertFalse(from.canTransitionTo(to), "expected " + from + " -> " + to + " to be rejected");
  }

  @Test
  void everyStatusCanTransitionToItself() {
    for (OperationStatus s : OperationStatus.values()) {
      assertTrue(s.canTransitionTo(s));
    }
  }
}
