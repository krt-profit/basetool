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

package de.greluc.krt.profit.basetool.frontend.support;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The two seeded quality tiers as frontend DTOs, for render and controller tests. */
public final class QualityTierTestData {

  /** The base tier „Keine", floor 0. */
  public static final QualityTierDto NONE =
      new QualityTierDto(
          UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000000"),
          "NONE",
          0,
          "Keine",
          "None",
          0,
          true,
          0L);

  /** The tier „Gut (650+)", floor 650. */
  public static final QualityTierDto GOOD =
      new QualityTierDto(
          UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650"),
          "GOOD",
          650,
          "Gut (650+)",
          "Good (650+)",
          650,
          true,
          0L);

  private QualityTierTestData() {}

  /**
   * Returns the tier of a code.
   *
   * @param code {@code GOOD} or anything else for the base tier
   * @return the tier
   */
  @NotNull
  public static QualityTierDto forCode(@Nullable String code) {
    return "GOOD".equals(code) ? GOOD : NONE;
  }

  /**
   * Returns the tier of a material line's floor.
   *
   * @param minQuality the floor, {@code null} for the base tier
   * @return the tier
   */
  @NotNull
  public static QualityTierDto forFloor(@Nullable Integer minQuality) {
    return minQuality == null || minQuality == 0 ? NONE : GOOD;
  }

  /**
   * Both tiers, as the catalogue lists them.
   *
   * @return base tier first
   */
  @NotNull
  public static List<QualityTierDto> all() {
    return List.of(NONE, GOOD);
  }
}
