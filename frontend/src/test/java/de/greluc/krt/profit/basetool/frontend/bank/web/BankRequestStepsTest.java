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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Covers the approval path the org-unit bank derives for its running requests. */
class BankRequestStepsTest {

  /**
   * A booking request with the approval fields under test.
   *
   * @param id the request id
   * @param status the request status
   * @param requiresApproval whether the amount exceeds the requester's limit
   * @param approver the required approver class, or {@code null}
   * @param granted whether the owner approval was granted
   * @param createdAt the creation instant
   * @return the request
   */
  static @NotNull BankBookingRequestDto request(
      @NotNull UUID id,
      @NotNull String status,
      boolean requiresApproval,
      @Nullable String approver,
      boolean granted,
      @NotNull String createdAt) {
    return new BankBookingRequestDto(
        id,
        UUID.randomUUID(),
        "KB-0042",
        "IRI Betriebskonto",
        null,
        null,
        null,
        "WITHDRAWAL",
        new BigDecimal("180000"),
        null,
        "Treibstoff",
        null,
        status,
        "requester",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.parse(createdAt),
        null,
        null,
        requiresApproval,
        null,
        approver,
        granted,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        0L,
        null);
  }

  /**
   * Renders a step list as {@code key:STATE} pairs for compact assertions.
   *
   * @param steps the steps
   * @return the pairs in path order
   */
  private static List<String> pairs(List<BankRequestSteps.Step> steps) {
    return steps.stream()
        .map(s -> s.labelKey().substring("bank.orgUnit.step.".length()) + ":" + s.state())
        .toList();
  }

  /** A request within the limit waits on the bank right after submission. */
  @Test
  void requestWithinTheLimitWaitsOnTheBank() {
    List<BankRequestSteps.Step> steps =
        BankRequestSteps.of(
            request(UUID.randomUUID(), "PENDING", false, null, false, "2026-10-01T10:00:00Z"));

    assertThat(pairs(steps)).containsExactly("submitted:DONE", "bank:CURRENT");
    assertThat(steps.get(1).modifier()).isEqualTo("current");
  }

  /** An over-limit request waits on its approver class before the bank. */
  @Test
  void overLimitRequestWaitsOnTheRequiredApprover() {
    List<BankRequestSteps.Step> steps =
        BankRequestSteps.of(
            request(
                UUID.randomUUID(),
                "PENDING",
                true,
                "BANK_MANAGEMENT",
                false,
                "2026-10-01T10:00:00Z"));

    assertThat(pairs(steps))
        .containsExactly("submitted:DONE", "BANK_MANAGEMENT:CURRENT", "bank:OPEN");
  }

  /** A granted owner approval completes the approver step and moves the request to the bank. */
  @Test
  void grantedApprovalMovesTheRequestToTheBank() {
    List<BankRequestSteps.Step> steps =
        BankRequestSteps.of(
            request(
                UUID.randomUUID(),
                "PENDING",
                true,
                "ORGANISATIONSLEITUNG",
                true,
                "2026-10-01T10:00:00Z"));

    assertThat(pairs(steps))
        .containsExactly("submitted:DONE", "ORGANISATIONSLEITUNG:DONE", "bank:CURRENT");
  }

  /** A confirmed request has every step done; an unknown approver class gets the generic label. */
  @Test
  void confirmedRequestCompletesEveryStepAndUnknownApproverFallsBack() {
    List<BankRequestSteps.Step> steps =
        BankRequestSteps.of(
            request(
                UUID.randomUUID(), "CONFIRMED", true, "SOMEONE", false, "2026-10-01T10:00:00Z"));

    assertThat(pairs(steps)).containsExactly("submitted:DONE", "approval:DONE", "bank:DONE");
  }

  /** Own and foreign requests merge into one pending list without duplicates, newest first. */
  @Test
  void openRequestsMergesPendingRequestsNewestFirst() {
    UUID shared = UUID.randomUUID();
    UUID ownOnly = UUID.randomUUID();
    UUID foreignOnly = UUID.randomUUID();
    List<BankBookingRequestDto> own =
        List.of(
            request(shared, "PENDING", false, null, false, "2026-10-01T10:00:00Z"),
            request(ownOnly, "PENDING", false, null, false, "2026-09-30T10:00:00Z"),
            request(UUID.randomUUID(), "CONFIRMED", false, null, false, "2026-10-02T10:00:00Z"));
    List<BankBookingRequestDto> foreign =
        List.of(
            request(shared, "PENDING", false, null, false, "2026-10-01T10:00:00Z"),
            request(
                foreignOnly, "PENDING", true, "RESPONSIBLE_HOLDER", false, "2026-10-02T12:00:00Z"));

    List<BankRequestSteps.OpenRequest> open = BankRequestSteps.openRequests(own, foreign);

    assertThat(open)
        .extracting(o -> o.request().id())
        .containsExactly(foreignOnly, shared, ownOnly);
    assertThat(pairs(open.getFirst().steps()))
        .containsExactly("submitted:DONE", "RESPONSIBLE_HOLDER:CURRENT", "bank:OPEN");
    assertThat(BankRequestSteps.openRequests(null, null)).isEmpty();
  }
}
