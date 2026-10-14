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
import de.greluc.krt.profit.basetool.backend.purchasing.api.DeliveryStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RejectReason;
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
 * One handover of goods the delegate recorded and the raising unit accepted or rejected
 * (REQ-PUR-015, REQ-PUR-016).
 */
@Entity
@Table(name = "purchasing_deal_delivery")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingDealDelivery extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "deal_id", nullable = false)
  private UUID dealId;

  /** The amount the delegate handed over. */
  @Column(nullable = false)
  private double amount;

  /** Where the handover took place, a catalogue location, or {@code null}. */
  @Nullable
  @Column(name = "location_id")
  private UUID locationId;

  @Nullable
  @Column(name = "recorded_by")
  private UUID recordedBy;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private DeliveryStatus status = DeliveryStatus.RECORDED;

  @Nullable
  @Column(name = "accepted_amount")
  private Double acceptedAmount;

  @Nullable
  @Column(name = "rejected_amount")
  private Double rejectedAmount;

  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "reject_reason", length = 32)
  private RejectReason rejectReason;

  /** The quality measured on acceptance, for a material. */
  @Nullable
  @Column(name = "measured_quality")
  private Integer measuredQuality;

  @Nullable
  @Column(name = "accepted_by")
  private UUID acceptedBy;

  @Nullable
  @Column(name = "accepted_at")
  private Instant acceptedAt;

  /** The pool row the accepted amount was booked into, a plain id into the Lager. */
  @Nullable
  @Column(name = "booked_row_id")
  private UUID bookedRowId;

  /**
   * How much of the handover still counts as delivered: all of it while undecided, the accepted
   * part afterwards.
   *
   * @return the effective delivered amount
   */
  public double effectiveAmount() {
    return switch (status) {
      case RECORDED -> amount;
      case ACCEPTED, PARTIALLY_ACCEPTED -> acceptedAmount == null ? 0.0 : acceptedAmount;
      case REJECTED -> 0.0;
    };
  }
}
