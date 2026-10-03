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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.QualityTier;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierDto;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The two quality tiers V261 seeds, with the seeded ids, for unit tests that build entities and
 * DTOs without a database; integration tests read the same rows from the migrated schema.
 */
public final class QualityTierFixtures {

  /** Id V261 gives the base tier {@code NONE}. */
  public static final UUID NONE_ID = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000000");

  /** Id V261 gives the tier {@code GOOD}. */
  public static final UUID GOOD_ID = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650");

  private QualityTierFixtures() {}

  /**
   * Builds a fresh base tier entity, floor 0.
   *
   * @return the tier
   */
  @NotNull
  public static QualityTier none() {
    return tier(NONE_ID, "NONE", 0, "Keine", "None");
  }

  /**
   * Builds a fresh {@code GOOD} tier entity, floor 650.
   *
   * @return the tier
   */
  @NotNull
  public static QualityTier good() {
    return tier(GOOD_ID, "GOOD", 650, "Gut (650+)", "Good (650+)");
  }

  /**
   * Builds an active tier entity with a random id.
   *
   * @param code the code
   * @param minQuality the floor
   * @return the tier
   */
  @NotNull
  public static QualityTier tier(@NotNull String code, int minQuality) {
    return tier(UUID.randomUUID(), code, minQuality, code, code);
  }

  /**
   * Builds an active tier entity.
   *
   * @param id the id
   * @param code the code
   * @param minQuality the floor
   * @param labelDe the German label
   * @param labelEn the English label
   * @return the tier
   */
  @NotNull
  public static QualityTier tier(
      @NotNull UUID id,
      @NotNull String code,
      int minQuality,
      @NotNull String labelDe,
      @NotNull String labelEn) {
    QualityTier tier =
        QualityTier.builder()
            .id(id)
            .code(code)
            .minQuality(minQuality)
            .labelDe(labelDe)
            .labelEn(labelEn)
            .sortOrder(minQuality)
            .active(true)
            .build();
    tier.setVersion(0L);
    return tier;
  }

  /**
   * Maps a tier entity to its DTO the way {@code QualityTierMapper} does.
   *
   * @param tier the tier
   * @return the DTO
   */
  @NotNull
  public static QualityTierDto dto(@NotNull QualityTier tier) {
    return new QualityTierDto(
        tier.getId(),
        tier.getCode(),
        tier.getMinQuality(),
        tier.getLabelDe(),
        tier.getLabelEn(),
        tier.getSortOrder(),
        tier.isActive(),
        tier.getVersion());
  }

  /**
   * The base tier as a DTO.
   *
   * @return the DTO
   */
  @NotNull
  public static QualityTierDto noneDto() {
    return dto(none());
  }

  /**
   * The {@code GOOD} tier as a DTO.
   *
   * @return the DTO
   */
  @NotNull
  public static QualityTierDto goodDto() {
    return dto(good());
  }
}
