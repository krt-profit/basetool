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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The audit details of a minimum stock level: ids, the unit as {@code kind:id}, the tier code and
 * the amounts, never free text (REQ-AUDIT-001, REQ-INV-068).
 */
final class StockLevelAuditDetails {

  /** The tier code recorded for a level without a tier. */
  private static final String NO_TIER = "-";

  /** Non-instantiable helper. */
  private StockLevelAuditDetails() {}

  /**
   * The details of a level as it stands, for its creation and its removal.
   *
   * @param level the level, unit and catalogue entry loaded
   * @return the details
   */
  static @NotNull AuditDetails snapshot(@NotNull StockLevel level) {
    AuditDetails unit = unit(level);
    AuditDetails subject =
        level.getMaterial() != null
            ? unit.with("material", level.getMaterial().getId())
            : unit.with("gameItem", level.subjectId());
    return subject
        .with("tier", tierCode(level.getQualityTier()))
        .with("minimum", level.getMinimum())
        .with("target", level.getTarget());
  }

  /**
   * The details that start every level event: the unit as {@code kind:id}.
   *
   * @param level the level, unit loaded
   * @return the details holding the unit
   */
  static @NotNull AuditDetails unit(@NotNull StockLevel level) {
    return AuditDetails.of("unit", InventoryAuditLabels.orgUnitRef(level.getOrgUnit()));
  }

  /**
   * The code of a tier for the details.
   *
   * @param tier the tier, or {@code null} for any quality
   * @return the tier code, or a dash
   */
  static @NotNull String tierCode(@Nullable QualityTier tier) {
    return tier == null ? NO_TIER : tier.getCode();
  }
}
