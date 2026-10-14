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
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row of the risk-class table (REQ-PUR-011): the limits a deal of that class obeys. */
@Entity
@Table(name = "purchasing_risk_class")
@Getter
@Setter
@NoArgsConstructor
public class PurchasingRiskClass extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private RiskClassCode code;

  /** The highest deal total of the class, in whole aUEC. */
  @Column(name = "max_total", nullable = false)
  private long maxTotal;

  /** The share of the total that may be paid in advance, in whole percent. */
  @Column(name = "max_advance_percent", nullable = false)
  private int maxAdvancePercent;

  /** Whether the partner must have a settled deal already. */
  @Column(name = "requires_known_partner", nullable = false)
  private boolean requiresKnownPartner;

  /** Whether an escort is suggested for deals of the class. */
  @Column(name = "escort_suggested", nullable = false)
  private boolean escortSuggested;
}
