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
import de.greluc.krt.profit.basetool.backend.purchasing.api.LossReason;
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

/**
 * The delegate's account of a deal's money (REQ-PUR-017): what came in, what was paid out, what
 * returns to the funding account and what was lost. It carries its own version beside the deal's.
 */
@Entity
@Table(name = "purchasing_deal_settlement")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingDealSettlement extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "deal_id", nullable = false)
  private UUID dealId;

  /** What the confirmed tranches put into the delegate's hands. */
  @Column(name = "money_received", nullable = false)
  private long moneyReceived;

  /** What the delegate paid the counterparty. */
  @Column(name = "money_paid", nullable = false)
  private long moneyPaid;

  /** What returns to the funding account: received minus paid. */
  @Column(nullable = false)
  private long remainder;

  /** The part of the paid money that bought nothing usable. */
  @Column(name = "loss_amount", nullable = false)
  private long lossAmount;

  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "loss_reason", length = 16)
  private LossReason lossReason;

  /** The pending or confirmed deposit request that returns the remainder, a plain id. */
  @Nullable
  @Column(name = "remainder_request_id")
  private UUID remainderRequestId;

  @Nullable
  @Column(name = "recorded_by")
  private UUID recordedBy;

  /** When the deal was settled; {@code null} while the remainder is still on its way. */
  @Nullable
  @Column(name = "settled_at")
  private Instant settledAt;
}
