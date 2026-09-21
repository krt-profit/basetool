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

package de.greluc.krt.profit.basetool.backend.model.scwiki;

import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * One stat contribution a {@link BlueprintRequirementGroup} makes to the crafted item — the "stat
 * the ingredient delivers" (SC Wiki {@code blueprint_modifier}, captured from {@code
 * /api/blueprints/{uuid}}). {@link #propertyKey} / {@link #label} name the affected output stat;
 * the applied value sweeps from {@link #modifierAtMinQuality} to {@link #modifierAtMaxQuality} as
 * the consumed ingredient's quality moves across {@link #qualityMin}..{@link #qualityMax}.
 *
 * <p>When {@link #getSegments()} is non-empty the stat does NOT move linearly between those two
 * endpoints — it follows the ordered, contiguous {@link BlueprintModifierSegment}s (a stepped /
 * piecewise-linear curve). Consumers must prefer the segments over the endpoint pair when present.
 */
@Entity
@Table(name = "blueprint_requirement_modifier")
@Getter
@Setter
@ToString(exclude = {"requirementGroup", "segments"})
@NoArgsConstructor
public class BlueprintRequirementModifier extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning requirement group. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "requirement_group_id", nullable = false)
  private BlueprintRequirementGroup requirementGroup;

  /** Position within the group's modifier list (drives {@code @OrderBy}). */
  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  /** Internal stat key the modifier affects, e.g. {@code "weapon_damage"}. */
  @Column(name = "property_key")
  private String propertyKey;

  /** Human-readable stat name, e.g. {@code "Impact Force"}. */
  @Column(name = "label")
  private String label;

  /**
   * Whether a higher or lower value is desirable: {@code higher} / {@code lower} / {@code neutral}.
   */
  @Column(name = "better_when", length = 16)
  private String betterWhen;

  /** Lowest ingredient-quality value of the interpolation band (typically {@code 0}). */
  @Column(name = "quality_min")
  private Double qualityMin;

  /** Highest ingredient-quality value of the interpolation band (typically {@code 1000}). */
  @Column(name = "quality_max")
  private Double qualityMax;

  /** Stat multiplier applied at {@link #qualityMin} (e.g. {@code 0.95}). */
  @Column(name = "modifier_at_min_quality")
  private Double modifierAtMinQuality;

  /** Stat multiplier applied at {@link #qualityMax} (e.g. {@code 1.05}). */
  @Column(name = "modifier_at_max_quality")
  private Double modifierAtMaxQuality;

  /** Interpolation type between the two multiplier endpoints (currently {@code "linear"}). */
  @Column(name = "value_range_type", length = 32)
  private String valueRangeType;

  /**
   * Ordered segments of a stepped / piecewise-linear modifier; empty for the simple linear form.
   * Owned by this modifier (cascade ALL + orphan removal); batched on access to avoid N+1.
   */
  @Setter(AccessLevel.NONE)
  @OneToMany(mappedBy = "modifier", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintModifierSegment> segments = new ArrayList<>();

  /**
   * Returns an unmodifiable view of this modifier's stepped-curve segments (ordered by {@code
   * orderIndex}); empty when the modifier interpolates linearly between its endpoints.
   *
   * @return the unmodifiable, ordered segment list
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintModifierSegment> getSegments() {
    return Collections.unmodifiableList(segments);
  }

  /**
   * Appends a segment and sets its back-reference to this modifier so the owning side is consistent
   * before a cascade persist.
   *
   * @param segment the segment to add
   */
  public void addSegment(@NotNull BlueprintModifierSegment segment) {
    segment.setModifier(this);
    segments.add(segment);
  }

  /**
   * Detaches and clears all segments (orphan removal deletes the rows on flush), used by the sync
   * before rebuilding the modifier's curve.
   */
  public void clearSegments() {
    segments.forEach(segment -> segment.setModifier(null));
    segments.clear();
  }
}
