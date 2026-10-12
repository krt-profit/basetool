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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * One linear segment of a stepped / piecewise-linear {@link BlueprintRequirementModifier} (SC Wiki
 * {@code blueprint_modifier.value_segments[]}). Across {@link #qualityMin}..{@link #qualityMax} the
 * stat multiplier interpolates linearly from {@link #modifierAtStart} to {@link #modifierAtEnd};
 * the modifier's ordered segment list reproduces a non-linear quality&rarr;stat curve that the
 * single {@code modifier_at_min/max_quality} pair on the parent cannot express. Owned by the
 * modifier (cascade ALL + orphan removal).
 */
@Entity
@Table(name = "blueprint_modifier_segment")
@Getter
@Setter
@ToString(exclude = {"modifier"})
@NoArgsConstructor
public class BlueprintModifierSegment extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning requirement modifier. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "requirement_modifier_id", nullable = false)
  @Nullable
  private BlueprintRequirementModifier modifier;

  /** Position within the modifier's segment list (drives {@code @OrderBy}). */
  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  /** Lowest ingredient-quality value of this segment. */
  @Column(name = "quality_min")
  private Double qualityMin;

  /** Highest ingredient-quality value of this segment. */
  @Column(name = "quality_max")
  private Double qualityMax;

  /** Stat multiplier applied at {@link #qualityMin}. */
  @Column(name = "modifier_at_start")
  private Double modifierAtStart;

  /** Stat multiplier applied at {@link #qualityMax}. */
  @Column(name = "modifier_at_end")
  private Double modifierAtEnd;
}
