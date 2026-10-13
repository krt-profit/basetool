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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What an org unit does in the supply module: whether it raises needs and whether it receives
 * donations (REQ-SUP-002). A unit without a row does neither; no unit is hardcoded.
 */
@Entity
@Table(name = "supply_unit_setting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SupplyUnitSetting extends AbstractEntity<UUID> {

  /** The configured org unit, which is also the row's key. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @Column(name = "org_unit_id")
  private UUID id;

  /** Whether the unit raises needs. */
  @Column(name = "may_raise_needs", nullable = false)
  private boolean mayRaiseNeeds;

  /** Whether the unit confirms donations. */
  @Column(name = "accepts_donations", nullable = false)
  private boolean acceptsDonations;
}
