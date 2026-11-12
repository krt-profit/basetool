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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** One unit's independently versioned commitment occurrence on an aUEC order. */
@Entity
@Table(name = "job_order_money_commitment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
class JobOrderMoneyCommitment {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private JobOrder order;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "unit_id", nullable = false, updatable = false)
  private OrgUnit unit;

  @Column(updatable = false)
  @Nullable
  private UUID participationId;

  @Column(nullable = false, columnDefinition = "numeric")
  private BigDecimal outstanding;

  @Column(nullable = false, updatable = false)
  private Instant openedAt;

  @Nullable private Instant endedAt;

  @Version @Nullable private Long version;

  JobOrderMoneyCommitment(
      JobOrder order, OrgUnit unit, @Nullable UUID participationId, BigDecimal amount) {
    this.id = UUID.randomUUID();
    this.order = order;
    this.unit = unit;
    this.participationId = participationId;
    outstanding = amount;
    openedAt = Instant.now();
  }

  void revise(BigDecimal amount) {
    outstanding = amount;
  }

  void end() {
    outstanding = BigDecimal.ZERO;
    endedAt = Instant.now();
  }
}
