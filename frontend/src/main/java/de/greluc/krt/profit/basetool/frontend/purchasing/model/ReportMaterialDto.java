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
 * Frontend mirror of the backend {@code ReportMaterialDto}; must match it field for field.
 *
 * @param catalogueId the material or item
 * @param name its name
 * @param dealCount deals for it
 * @param boughtAmount the amount accepted
 * @param averageUnitPrice the weighted unit price
 * @param uexReferencePrice the UEX buy price, or {@code null}
 * @param nameDe its German name, or {@code null} (REQ-P4K-023)
 */
@DtoMirror
public record ReportMaterialDto(
    UUID catalogueId,
    String name,
    int dealCount,
    double boughtAmount,
    double averageUnitPrice,
    Double uexReferencePrice,
    @Nullable String nameDe) {

  /**
   * Builds a report row without a German name.
   *
   * @param catalogueId the material or item
   * @param name its name
   * @param dealCount deals for it
   * @param boughtAmount the amount accepted
   * @param averageUnitPrice the weighted unit price
   * @param uexReferencePrice the UEX buy price, or {@code null}
   */
  public ReportMaterialDto(
      UUID catalogueId,
      String name,
      int dealCount,
      double boughtAmount,
      double averageUnitPrice,
      Double uexReferencePrice) {
    this(catalogueId, name, dealCount, boughtAmount, averageUnitPrice, uexReferencePrice, null);
  }

  /**
   * Returns the name the page shows (REQ-P4K-023).
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String displayName(@Nullable Locale locale) {
    return LocalisedNames.display(name, nameDe, locale);
  }
}
