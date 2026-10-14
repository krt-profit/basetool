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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/** One money request of a deal, mirrored from its bank request (REQ-PUR-012, REQ-PUR-014). */
@Entity
@Table(name = "purchasing_deal_tranche")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingDealTranche extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "deal_id", nullable = false)
  private UUID dealId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private TrancheKind kind;

  /** The requested amount in whole aUEC. */
  @Column(nullable = false)
  private long amount;

  /** The bank request that pays the tranche, a plain id into the bank module. */
  @Nullable
  @Column(name = "bank_request_id")
  private UUID bankRequestId;

  /** The booked ledger transaction once the bank confirmed. */
  @Nullable
  @Column(name = "transaction_id")
  private UUID transactionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private TrancheState state = TrancheState.REQUESTED;

  @Nullable
  @Column(name = "decided_at")
  private Instant decidedAt;

  /**
   * Whether the tranche still counts against the limits: requested or confirmed.
   *
   * @return {@code true} for a requested or confirmed tranche
   */
  public boolean isLive() {
    return state == TrancheState.REQUESTED || state == TrancheState.CONFIRMED;
  }
}
