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

package de.greluc.krt.profit.basetool.backend.model;

import de.greluc.krt.profit.basetool.backend.validation.QualityValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.NotNull;

/**
 * One step of the quality-tier catalogue a material requirement is stated in (REQ-ORDERS-036): a
 * stable code, the floor a stock row must reach, and the two display labels.
 *
 * <p>The tier with floor 0 is the "no floor" tier and is always active. A tier's floor is fixed
 * once any requirement or claim references it.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@BatchSize(size = 32)
@Table(name = "quality_tier")
public class QualityTier extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Stable machine code, upper case, unique; the wire value of {@code qualityRequirement}. */
  @Column(name = "code", nullable = false, length = 32)
  private String code;

  /** Lowest stock quality that satisfies the tier; unique across the catalogue. */
  @QualityValue
  @Column(name = "min_quality", nullable = false)
  private int minQuality;

  /** German display label. */
  @Column(name = "label_de", nullable = false, length = 64)
  private String labelDe;

  /** English display label. */
  @Column(name = "label_en", nullable = false, length = 64)
  private String labelEn;

  /** Position in pickers, ascending. */
  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  /** Whether the tier is offered for new requirements; existing references keep working. */
  @Column(name = "active", nullable = false)
  private boolean active;

  /**
   * Whether this is the catalogue's "no floor" tier, which every stock row satisfies.
   *
   * @return {@code true} for the tier with floor 0
   */
  public boolean isBaseTier() {
    return minQuality == 0;
  }

  /**
   * Whether a stock row of the given quality satisfies this tier.
   *
   * @param quality the stock row's quality; {@code null} counts as 0
   * @return {@code true} when the quality reaches the floor
   */
  public boolean isSatisfiedBy(Integer quality) {
    return (quality == null ? 0 : quality) >= minQuality;
  }

  /**
   * Renders the tier without its labels.
   *
   * @return a single-line representation
   */
  @NotNull
  @Override
  public String toString() {
    return "QualityTier{id=" + id + ", code=" + code + ", minQuality=" + minQuality + '}';
  }
}
