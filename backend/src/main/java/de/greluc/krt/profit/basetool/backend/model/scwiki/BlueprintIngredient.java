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
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One ingredient line of a {@link Blueprint}: a {@link BlueprintIngredientKind#RESOURCE} consumes a
 * {@link Material} in SCU, an {@link BlueprintIngredientKind#ITEM} a {@link GameItem} in whole
 * units.
 *
 * <p>The resolved {@link #material} / {@link #gameItem} may be {@code null} while unresolved; the
 * raw {@link #wikiResourceUuid} / {@link #wikiItemUuid} / {@link #wikiNameSnapshot} are always
 * persisted so a later sync can re-resolve it.
 */
@Entity
@Table(name = "blueprint_ingredient")
@Getter
@Setter
@ToString(exclude = {"blueprint", "requirementGroup", "material", "gameItem"})
@NoArgsConstructor
public class BlueprintIngredient extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning blueprint. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blueprint_id", nullable = false)
  private Blueprint blueprint;

  /**
   * Requirement group (build slot) this ingredient fills, when the blueprint was synced from the
   * detail endpoint. {@code null} for rows populated from the list endpoint's flat {@code
   * ingredients[]} fallback (no group / modifier data available for those).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requirement_group_id")
  private BlueprintRequirementGroup requirementGroup;

  /** Position within the blueprint's ingredient list (drives {@code @OrderBy}). */
  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  /** Whether this line is a RESOURCE (commodity) or an ITEM (game item). */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private BlueprintIngredientKind kind;

  /** Resolved commodity for a RESOURCE line; {@code null} when unresolved or for an ITEM line. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "material_id")
  private Material material;

  /** Resolved game item for an ITEM line; {@code null} when unresolved or for a RESOURCE line. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "game_item_id")
  private GameItem gameItem;

  /** Raw Wiki resource UUID for a RESOURCE line — kept for forensic re-resolution. */
  @Column(name = "wiki_resource_uuid")
  private UUID wikiResourceUuid;

  /** Raw Wiki item UUID for an ITEM line — kept for forensic re-resolution. */
  @Column(name = "wiki_item_uuid")
  private UUID wikiItemUuid;

  /** Wiki display name of the ingredient at sync time — kept even when the FK resolves. */
  @Column(name = "wiki_name_snapshot")
  private String wikiNameSnapshot;

  /** Quantity in SCU for a RESOURCE line. */
  @Column(name = "quantity_scu")
  private Double quantityScu;

  /** Quantity in whole units for an ITEM line. */
  @Column(name = "quantity_units")
  private Integer quantityUnits;

  /**
   * Minimum quality tier the ingredient must have (Wiki {@code min_quality}); {@code null} if none.
   */
  @Column(name = "min_quality")
  private Integer minQuality;
}
