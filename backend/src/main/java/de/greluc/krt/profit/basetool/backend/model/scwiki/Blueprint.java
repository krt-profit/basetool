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
import java.time.Instant;
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
 * A crafting blueprint synced from the SC Wiki {@code /api/blueprints} endpoint, keyed by {@link
 * #scwikiUuid}. It produces an output {@link GameItem} from ordered {@link BlueprintIngredient}s
 * and yields {@link BlueprintDismantleReturn}s when dismantled; it owns both child collections with
 * cascade and orphan removal.
 */
@Entity
@Table(name = "blueprint")
@Getter
@Setter
@ToString(
    exclude = {
      "outputItem",
      "ingredients",
      "dismantleReturns",
      "requirementGroups",
      "summaryProperties"
    })
@NoArgsConstructor
public class Blueprint extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** SC Wiki blueprint UUID — the cross-sync identity key. UNIQUE at the DB level. */
  @Column(name = "scwiki_uuid", nullable = false, unique = true)
  private UUID scwikiUuid;

  /** SC Wiki internal key, e.g. {@code "BP_CRAFT_AMRS_LaserCannon_S1"}. */
  @Column(name = "scwiki_key")
  private String scwikiKey;

  /**
   * The item this blueprint produces, resolved from the Wiki {@code output_item_uuid} against
   * {@code game_item.external_uuid}. {@code null} when the output item is not (yet) in {@code
   * game_item}; {@link #outputName} preserves the Wiki name regardless.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "output_item_id")
  private GameItem outputItem;

  /** Wiki display name of the output item (kept even when {@link #outputItem} is unresolved). */
  @Column(name = "output_name")
  private String outputName;

  /** Wiki category UUID for the blueprint (informational; not resolved to an entity in R4). */
  @Column(name = "category_uuid")
  private UUID categoryUuid;

  /** Craft time in seconds. */
  @Column(name = "craft_time_seconds")
  private Integer craftTimeSeconds;

  /**
   * Dismantle duration in seconds (Wiki {@code dismantle.time_seconds}); {@code null} if absent.
   */
  @Column(name = "dismantle_time_seconds")
  private Integer dismantleTimeSeconds;

  /** Fraction of inputs recovered on dismantle (Wiki {@code dismantle.efficiency}, e.g. 0.5). */
  @Column(name = "dismantle_efficiency")
  private Double dismantleEfficiency;

  /** Whether the recipe is unlocked by default (vs. mission-gated). */
  @Column(name = "is_available_by_default", nullable = false)
  private Boolean isAvailableByDefault = false;

  /** Wiki-reported ingredient count (a cross-check against the persisted {@link #ingredients}). */
  @Column(name = "ingredient_count")
  private Integer ingredientCount;

  /** Wiki-reported count of missions that unlock this blueprint. */
  @Column(name = "unlocking_missions_count")
  private Integer unlockingMissionsCount;

  /** Game version in which the Wiki last reported this blueprint. */
  @Column(name = "game_version_seen")
  private String gameVersionSeen;

  /** Last successful SC Wiki sync touch. */
  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  /** Soft-delete marker: first run in which the Wiki stopped returning this blueprint. */
  @Column(name = "scwiki_deleted_at")
  private Instant scwikiDeletedAt;

  /**
   * DataForge {@code __ref} blueprint GUID observed by the KRT P4K Reader import. Kept alongside
   * (not in place of) {@link #scwikiUuid}: the importer backfills {@code scwiki_uuid} only when it
   * is null and unclaimed, but always records the P4K-observed GUID here so a UUID disagreement
   * stays auditable. Not UNIQUE.
   */
  @Column(name = "p4k_uuid")
  private UUID p4kUuid;

  /** Last successful KRT P4K Reader import touch; non-null marks P4K participation. */
  @Column(name = "p4k_synced_at")
  private Instant p4kSyncedAt;

  /** KRT P4K Reader soft-delete marker (reserved for a future orphan sweep). */
  @Column(name = "p4k_deleted_at")
  private Instant p4kDeletedAt;

  /** Ordered ingredient lines (RESOURCE or ITEM). Owned with cascade + orphan removal. */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @OneToMany(
      mappedBy = "blueprint",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintIngredient> ingredients = new ArrayList<>();

  /** Ordered dismantle-return lines (RESOURCE only). Owned with cascade + orphan removal. */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @OneToMany(
      mappedBy = "blueprint",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintDismantleReturn> dismantleReturns = new ArrayList<>();

  /**
   * Ordered requirement groups (build slots) with their stat modifiers. Owned, cascade + orphan.
   */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @OneToMany(
      mappedBy = "blueprint",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintRequirementGroup> requirementGroups = new ArrayList<>();

  /** Ordered summary stat roll-up (which stats this blueprint affects). Owned, cascade + orphan. */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @OneToMany(
      mappedBy = "blueprint",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintSummaryProperty> summaryProperties = new ArrayList<>();

  /**
   * Returns the ordered ingredient lines as an unmodifiable view. Mutate the recipe through {@link
   * #addIngredient} / {@link #removeLastIngredient} so the managed collection (and its
   * orphan-removal semantics) stays under the entity's control.
   *
   * @return an unmodifiable view of the ingredient lines
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintIngredient> getIngredients() {
    return Collections.unmodifiableList(ingredients);
  }

  /**
   * Appends an ingredient line and sets its back-reference to this blueprint.
   *
   * @param ingredient the line to add
   */
  public void addIngredient(@NotNull BlueprintIngredient ingredient) {
    ingredient.setBlueprint(this);
    ingredients.add(ingredient);
  }

  /** Removes the last ingredient line; orphan removal deletes it on flush. */
  public void removeLastIngredient() {
    ingredients.removeLast();
  }

  /** Clears all ingredient lines; orphan removal deletes them on flush. */
  public void clearIngredients() {
    ingredients.clear();
  }

  /**
   * Returns the ordered dismantle-return lines as an unmodifiable view. Mutate through {@link
   * #addDismantleReturn} / {@link #removeLastDismantleReturn}.
   *
   * @return an unmodifiable view of the dismantle-return lines
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintDismantleReturn> getDismantleReturns() {
    return Collections.unmodifiableList(dismantleReturns);
  }

  /**
   * Appends a dismantle-return line and sets its back-reference to this blueprint.
   *
   * @param dismantleReturn the line to add
   */
  public void addDismantleReturn(@NotNull BlueprintDismantleReturn dismantleReturn) {
    dismantleReturn.setBlueprint(this);
    dismantleReturns.add(dismantleReturn);
  }

  /** Removes the last dismantle-return line; orphan removal deletes it on flush. */
  public void removeLastDismantleReturn() {
    dismantleReturns.removeLast();
  }

  /**
   * Returns the ordered requirement groups as an unmodifiable view. Mutate through {@link
   * #addRequirementGroup} / {@link #clearRequirementGroups}.
   *
   * @return an unmodifiable view of the requirement groups
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintRequirementGroup> getRequirementGroups() {
    return Collections.unmodifiableList(requirementGroups);
  }

  /**
   * Appends a requirement group and sets its back-reference to this blueprint.
   *
   * @param group the group to add
   */
  public void addRequirementGroup(@NotNull BlueprintRequirementGroup group) {
    group.setBlueprint(this);
    requirementGroups.add(group);
  }

  /** Clears all requirement groups; orphan removal deletes them (and their modifiers) on flush. */
  public void clearRequirementGroups() {
    requirementGroups.clear();
  }

  /**
   * Returns the ordered summary properties as an unmodifiable view. Mutate through {@link
   * #addSummaryProperty} / {@link #clearSummaryProperties}.
   *
   * @return an unmodifiable view of the summary properties
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintSummaryProperty> getSummaryProperties() {
    return Collections.unmodifiableList(summaryProperties);
  }

  /**
   * Appends a summary property and sets its back-reference to this blueprint.
   *
   * @param summaryProperty the summary property to add
   */
  public void addSummaryProperty(@NotNull BlueprintSummaryProperty summaryProperty) {
    summaryProperty.setBlueprint(this);
    summaryProperties.add(summaryProperty);
  }

  /** Clears all summary properties; orphan removal deletes them on flush. */
  public void clearSummaryProperties() {
    summaryProperties.clear();
  }
}
