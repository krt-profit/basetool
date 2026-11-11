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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Stable personal identity within one order; booked amounts live in monetary operations. */
@Entity
@Table(name = "job_order_money_holder")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
class JobOrderMoneyHolder {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Nullable private UUID userId;

  @Nullable private UUID mergedIntoId;

  @Column(length = 200)
  @Nullable
  private String accountlessName;

  @Column(nullable = false)
  private long revision;

  private JobOrderMoneyHolder(
      UUID orderId, @Nullable UUID userId, @Nullable String accountlessName) {
    this.orderId = orderId;
    this.userId = userId;
    this.accountlessName = accountlessName;
  }

  static JobOrderMoneyHolder linked(UUID orderId, UUID userId) {
    return new JobOrderMoneyHolder(orderId, userId, null);
  }

  static JobOrderMoneyHolder named(UUID orderId, String name) {
    return new JobOrderMoneyHolder(orderId, null, name);
  }

  void replaceIdentity(@Nullable UUID linkedAccountId, @Nullable String name) {
    userId = linkedAccountId;
    accountlessName = name;
    recordChange();
  }

  void retireInto(UUID survivorId) {
    mergedIntoId = survivorId;
    replaceIdentity(null, null);
  }

  void recordChange() {
    revision = Math.incrementExact(revision);
  }
}
