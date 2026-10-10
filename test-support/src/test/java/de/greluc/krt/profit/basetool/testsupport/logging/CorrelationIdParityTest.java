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

package de.greluc.krt.profit.basetool.testsupport.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves {@link CorrelationIdParity} can fail, and that it accepts the outcomes it should. */
class CorrelationIdParityTest {

  /** A safe id echoed unchanged and a fresh UUID for an absent header hold the contract. */
  @Test
  void aConformingOutcomeHasNoViolation() {
    assertThat(CorrelationIdParity.violations("abc-123", "abc-123")).isEmpty();
    assertThat(CorrelationIdParity.violations(null, UUID.randomUUID().toString())).isEmpty();
    assertThat(CorrelationIdParity.violations("a b", UUID.randomUUID().toString())).isEmpty();
    assertThat(CorrelationIdParity.violations("A".repeat(300), "A".repeat(128))).isEmpty();
  }

  /** An unsafe character, an over-long id and a missing echo are each reported. */
  @Test
  void aBrokenOutcomeIsReported() {
    assertThat(CorrelationIdParity.violations("a\r\nb", "a\r\nb")).isNotEmpty();
    assertThat(CorrelationIdParity.violations("x", "x".repeat(129))).isNotEmpty();
    assertThat(CorrelationIdParity.violations("x", null)).isNotEmpty();
  }

  /** A safe inbound id that comes back altered, and a blank one that gets no UUID, are reported. */
  @Test
  void anAlteredSafeIdAndAMissingUuidAreReported() {
    assertThat(CorrelationIdParity.violations("abc", "abd")).isNotEmpty();
    assertThat(CorrelationIdParity.violations("", "not-a-uuid")).isNotEmpty();
    assertThat(CorrelationIdParity.violations(null, "abc")).isNotEmpty();
  }

  /** The case list covers an absent header, injection attempts and both length edges. */
  @Test
  void theCaseListHasItsFloor() {
    assertThat(CorrelationIdParity.inboundCases())
        .hasSizeGreaterThanOrEqualTo(15)
        .contains((String) null);
  }
}
