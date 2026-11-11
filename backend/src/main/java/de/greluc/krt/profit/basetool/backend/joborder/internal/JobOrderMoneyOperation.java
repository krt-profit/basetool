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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** The durable submitted terms and booked effect of a Bank-free provision. */
@Entity
@Table(name = "job_order_money_operation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
class JobOrderMoneyOperation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Column(nullable = false, updatable = false)
  private UUID operationId;

  @Column(nullable = false, updatable = false)
  private UUID holderId;

  @Nullable private UUID actorId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private JobOrderMoneySelection.Kind selectionKind;

  @Nullable
  @Column(updatable = false)
  private UUID selectedUserId;

  @Nullable
  @Column(length = 200, updatable = false)
  private String selectedAccountlessName;

  @Nullable
  @Column(updatable = false)
  private UUID commitmentId;

  @Column(nullable = false, updatable = false)
  private boolean bankHandling;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal amount;

  @Column(nullable = false, updatable = false)
  private Instant recordedAt;

  JobOrderMoneyOperation(
      UUID orderId,
      UUID operationId,
      UUID holderId,
      UUID actorId,
      JobOrderMoneySelection selection,
      @Nullable UUID commitmentId,
      boolean bankHandling,
      BigDecimal amount) {
    this.orderId = orderId;
    this.operationId = operationId;
    this.holderId = holderId;
    this.actorId = actorId;
    this.selectionKind = selection.kind();
    this.selectedUserId =
        selectionKind == JobOrderMoneySelection.Kind.LINKED ? selection.reference() : null;
    this.selectedAccountlessName =
        selectionKind == JobOrderMoneySelection.Kind.ACCOUNTLESS ? selection.name() : null;
    this.commitmentId = commitmentId;
    this.bankHandling = bankHandling;
    this.amount = amount;
    this.recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  boolean matchesSelection(JobOrderMoneySelection selection) {
    if (selection.kind() != selectionKind) {
      return false;
    }
    return switch (selectionKind) {
      case SELF -> true;
      case EXISTING -> holderId.equals(selection.reference());
      case LINKED -> Objects.equals(selectedUserId, selection.reference());
      case ACCOUNTLESS -> Objects.equals(selectedAccountlessName, selection.name());
    };
  }
}
