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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.jetbrains.annotations.Nullable;

/**
 * A trade deal of one delegate with one partner for one need (REQ-PUR-007). The header is locked by
 * this row's version; tranches, deliveries and the settlement carry their own (REQ-PUR-026). A deal
 * is never deleted (REQ-PUR-022).
 */
@Entity
@Table(name = "purchasing_deal")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class PurchasingDeal extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The running number shown to people, assigned by the database. */
  @Generated(event = EventType.INSERT)
  @Column(name = "deal_no", insertable = false, updatable = false)
  private Long dealNo;

  /** The need the deal serves, a plain id into the supply module. */
  @Column(name = "need_id", nullable = false)
  private UUID needId;

  /** The need's raising unit, copied at creation since a need never changes its unit. */
  @Column(name = "raising_org_unit_id", nullable = false)
  private UUID raisingOrgUnitId;

  @Column(name = "partner_id", nullable = false)
  private UUID partnerId;

  /** The delegate who trades, or {@code null} once the account is erased. */
  @Nullable
  @Column(name = "delegate_user_id")
  private UUID delegateUserId;

  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id")
  @ToString.Exclude
  private Material material;

  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id")
  @ToString.Exclude
  private GameItem gameItem;

  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "quality_tier_id")
  @ToString.Exclude
  private QualityTier qualityTier;

  /** The amount bought, whole units for pieces and game items. */
  @Column(nullable = false)
  private double amount;

  /** The price of one unit in whole aUEC. */
  @Column(name = "unit_price", nullable = false)
  private long unitPrice;

  /** The deal's total in whole aUEC, the rounded product of amount and unit price. */
  @Column(nullable = false)
  private long total;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_model", nullable = false, length = 16)
  private PaymentModel paymentModel;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_class", nullable = false, length = 8)
  private RiskClassCode riskClass;

  @Column(name = "escort_wish", nullable = false)
  private boolean escortWish;

  /** The Einsatz that escorts the deal, a plain id, or {@code null}. */
  @Nullable
  @Column(name = "mission_id")
  private UUID missionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private DealStatus status = DealStatus.DRAFT;

  /** Whether the delegate closed the delivery. */
  @Column(name = "delivery_closed", nullable = false)
  private boolean deliveryClosed;

  /** When the settlement is due, set when the delivery closes. */
  @Nullable
  @Column(name = "settlement_due_at")
  private Instant settlementDueAt;

  /** Marks that the overdue notice was produced; cleared when the due date moves. */
  @Nullable
  @Column(name = "overdue_notified_at")
  private Instant overdueNotifiedAt;
}
