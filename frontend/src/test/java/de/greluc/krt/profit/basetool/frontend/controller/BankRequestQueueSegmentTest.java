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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Pins how the request queue maps its {@code status} parameter onto a segment and which pending
 * requests count as waiting for the bank.
 */
class BankRequestQueueSegmentTest {

  /**
   * A request in the given state.
   *
   * @param status the lifecycle state
   * @param requiresApproval whether the owner approval is required
   * @param granted whether it was granted in-app
   * @return the request
   */
  private static @NotNull BankBookingRequestDto request(
      @NotNull String status, boolean requiresApproval, boolean granted) {
    return new BankBookingRequestDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        null,
        null,
        null,
        "WITHDRAWAL",
        BigDecimal.TEN,
        null,
        null,
        null,
        status,
        "talon",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.EPOCH,
        null,
        null,
        requiresApproval,
        null,
        requiresApproval ? "RESPONSIBLE_HOLDER" : null,
        granted,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        0L);
  }

  /** Absent, blank and unknown values open the pending segment. */
  @Test
  void defaultsToPending() {
    assertThat(BankRequestQueuePageController.resolveSegment(null)).isEqualTo("PENDING");
    assertThat(BankRequestQueuePageController.resolveSegment(" ")).isEqualTo("PENDING");
    assertThat(BankRequestQueuePageController.resolveSegment("NONE")).isEqualTo("PENDING");
  }

  /** A single counted state selects its segment, case-insensitively. */
  @Test
  void selectsASingleState() {
    assertThat(BankRequestQueuePageController.resolveSegment("confirmed")).isEqualTo("CONFIRMED");
    assertThat(BankRequestQueuePageController.resolveSegment("REJECTED")).isEqualTo("REJECTED");
  }

  /** {@code ALL}, several states and a lone withdrawn state show every state. */
  @Test
  void mapsEverythingElseOntoAll() {
    assertThat(BankRequestQueuePageController.resolveSegment("ALL")).isEqualTo("ALL");
    assertThat(BankRequestQueuePageController.resolveSegment("PENDING,REJECTED")).isEqualTo("ALL");
    assertThat(BankRequestQueuePageController.resolveSegment("CANCELLED")).isEqualTo("ALL");
  }

  /** Only a pending request without an outstanding owner approval waits for the bank. */
  @Test
  void awaitsTheBankOnlyWithoutAnOutstandingApproval() {
    assertThat(BankRequestQueuePageController.awaitsTheBank(request("PENDING", false, false)))
        .isTrue();
    assertThat(BankRequestQueuePageController.awaitsTheBank(request("PENDING", true, true)))
        .isTrue();
    assertThat(BankRequestQueuePageController.awaitsTheBank(request("PENDING", true, false)))
        .isFalse();
    assertThat(BankRequestQueuePageController.awaitsTheBank(request("CONFIRMED", false, false)))
        .isFalse();
  }
}
