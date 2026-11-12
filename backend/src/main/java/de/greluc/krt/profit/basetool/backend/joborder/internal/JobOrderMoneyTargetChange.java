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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Records one accepted target amendment in the participant-readable history. */
@Entity
@Table(name = "job_order_money_target_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class JobOrderMoneyTargetChange {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal previousTarget;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal target;

  @Nullable private UUID actorId;

  @Column(nullable = false, updatable = false)
  private Instant recordedAt;

  JobOrderMoneyTargetChange(
      UUID orderId, UUID actorId, BigDecimal previousTarget, BigDecimal target) {
    this.orderId = orderId;
    this.actorId = actorId;
    this.previousTarget = previousTarget;
    this.target = target;
    this.recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
