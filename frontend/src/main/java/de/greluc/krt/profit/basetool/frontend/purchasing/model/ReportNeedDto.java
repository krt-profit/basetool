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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocalisedNames;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code ReportNeedDto}; must match it field for field.
 *
 * @param needId the need
 * @param unit the raising unit's name
 * @param entry the material or item
 * @param dealCount deals for the need
 * @param boughtAmount the amount accepted
 * @param entryDe the German name of the material or item, or {@code null} (REQ-P4K-023)
 */
@DtoMirror
public record ReportNeedDto(
    UUID needId,
    String unit,
    String entry,
    int dealCount,
    double boughtAmount,
    @Nullable String entryDe) {

  /**
   * Builds a report row without a German entry name.
   *
   * @param needId the need
   * @param unit the raising unit's name
   * @param entry the material or item
   * @param dealCount deals for the need
   * @param boughtAmount the amount accepted
   */
  public ReportNeedDto(UUID needId, String unit, String entry, int dealCount, double boughtAmount) {
    this(needId, unit, entry, dealCount, boughtAmount, null);
  }

  /**
   * Returns the entry name the page shows (REQ-P4K-023).
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String entryDisplayName(@Nullable Locale locale) {
    return LocalisedNames.display(entry, entryDe, locale);
  }
}
