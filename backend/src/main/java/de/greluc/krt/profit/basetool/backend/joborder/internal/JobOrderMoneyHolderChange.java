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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Keeps the reason and subject of a holder correction independently of monetary facts. */
@Entity
@Table(name = "job_order_money_holder_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class JobOrderMoneyHolderChange {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Column(nullable = false, updatable = false)
  private UUID holderId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 16)
  private JobOrderMoneyHolderIdentityDto.Kind kind;

  @Nullable private UUID actorId;
  @Nullable private UUID previousUserId;
  @Nullable private UUID userId;

  @Column(length = 200)
  @Nullable
  private String previousName;

  @Column(length = 200)
  @Nullable
  private String name;

  @Column(updatable = false)
  @Nullable
  private UUID targetHolderId;

  @Column(nullable = false, updatable = false)
  private Instant recordedAt;

  JobOrderMoneyHolderChange(
      JobOrderMoneyHolder holder,
      JobOrderMoneyHolderIdentityDto.Kind kind,
      UUID actorId,
      @Nullable UUID userId,
      @Nullable String name,
      @Nullable UUID targetHolderId) {
    this.orderId = holder.getOrderId();
    this.holderId = Objects.requireNonNull(holder.getId());
    this.kind = kind;
    this.actorId = actorId;
    this.previousUserId = holder.getUserId();
    this.previousName = previousUserId == null ? holder.getAccountlessName() : null;
    this.userId = userId;
    this.name = userId == null ? name : null;
    this.targetHolderId = targetHolderId;
    this.recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
