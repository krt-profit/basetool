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

/**
 * One stat a {@link Blueprint} affects across all its requirement groups: a de-duplicated roll-up
 * of the per-group {@link BlueprintRequirementModifier} property keys. Owned by the {@link
 * Blueprint} aggregate.
 */
@Entity
@Table(name = "blueprint_summary_property")
@Getter
@Setter
@ToString(exclude = {"blueprint"})
@NoArgsConstructor
public class BlueprintSummaryProperty extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning blueprint. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blueprint_id", nullable = false)
  private Blueprint blueprint;

  /** Position within the blueprint's summary-property list (drives {@code @OrderBy}). */
  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  /** Internal stat key, e.g. {@code "weapon_damage"}. */
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
}
