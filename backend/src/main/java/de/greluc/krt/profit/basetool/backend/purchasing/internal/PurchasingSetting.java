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
import de.greluc.krt.profit.basetool.backend.purchasing.api.BudgetPeriod;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OptimisticLock;
import org.jetbrains.annotations.Nullable;

/**
 * The single row of purchasing settings (REQ-PUR-002): the governing Bereiche, the funding account,
 * the delegates' default limit, the budget cap and its period, the settlement due days, the global
 * pause and the quality tiers purchasing may buy.
 */
@Entity
@DynamicUpdate
@Table(name = "purchasing_setting")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingSetting extends AbstractEntity<UUID> {

  /** The fixed id of the one row. */
  public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-0000000a0001");

  @Getter(onMethod_ = @__(@Override))
  @Id
  private UUID id;

  /**
   * The Bereiche whose Bereichsleiter and Bereichskoordinatoren govern purchasing; the Bereich
   * Profit until an admin changes it.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "purchasing_setting_governing_area",
      joinColumns = @JoinColumn(name = "setting_id"))
  @Column(name = "area_id")
  private Set<UUID> governingAreaIds = new HashSet<>();

  /** The bank account tranches are paid from, a plain id into the bank module. */
  @Nullable
  @Column(name = "funding_account_id")
  private UUID fundingAccountId;

  /** The monthly limit of a delegate without a personal one, in whole aUEC. */
  @Column(name = "default_monthly_limit", nullable = false)
  private long defaultMonthlyLimit;

  /** The most that may be requested in one budget period, or {@code null} for no cap. */
  @Nullable
  @Column(name = "budget_cap")
  private Long budgetCap;

  @Enumerated(EnumType.STRING)
  @Column(name = "budget_period", nullable = false, length = 16)
  private BudgetPeriod budgetPeriod = BudgetPeriod.MONTH;

  /** Days after delivery closed within which the delegate must settle. */
  @Column(name = "settlement_due_days", nullable = false)
  private int settlementDueDays = 7;

  /** Whether purchasing is paused for the whole organisation. */
  @OptimisticLock(excluded = true)
  @Column(nullable = false)
  private boolean paused;

  @Nullable
  @OptimisticLock(excluded = true)
  @Enumerated(EnumType.STRING)
  @Column(name = "pause_reason", length = 32)
  private PauseReason pauseReason;

  @Nullable
  @OptimisticLock(excluded = true)
  @Column(name = "paused_by")
  private UUID pausedBy;

  @Nullable
  @OptimisticLock(excluded = true)
  @Column(name = "paused_at")
  private Instant pausedAt;

  /** The quality tiers a need may ask for and still be bought; empty means none. */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "purchasing_setting_allowed_tier",
      joinColumns = @JoinColumn(name = "setting_id"))
  @Column(name = "quality_tier_id")
  private Set<UUID> allowedQualityTierIds = new HashSet<>();
}
