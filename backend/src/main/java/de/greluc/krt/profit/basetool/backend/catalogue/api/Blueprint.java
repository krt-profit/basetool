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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * A crafting blueprint, keyed by its P4K GUID and its SC Wiki UUID. The P4K leads its recipe and
 * the SC Wiki blueprint sync fills the gaps (REQ-P4K-027, ADR-0340). It produces an output {@link
 * GameItem} from ordered {@link BlueprintIngredient}s and yields {@link BlueprintDismantleReturn}s
 * when dismantled; it owns both child collections with cascade and orphan removal.
 */
@Entity
@Table(name = "blueprint")
@Getter
@Setter
@ToString(
    exclude = {
      "outputItem",
      "category",
      "ingredients",
      "dismantleReturns",
      "requirementGroups",
      "recipeModifiers",
      "summaryProperties"
    })
@NoArgsConstructor
public class Blueprint extends AbstractEntity<UUID> implements P4kKeyedRow {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** SC Wiki blueprint UUID, unique; {@code null} on a P4K-seeded row the Wiki has not listed. */
  @Column(name = "scwiki_uuid", unique = true)
  @Nullable
  private UUID scwikiUuid;

  /** SC Wiki internal key, e.g. {@code "BP_CRAFT_AMRS_LaserCannon_S1"}. */
  @Column(name = "scwiki_key")
  @Nullable
  private String scwikiKey;

  /**
   * The item this blueprint produces, resolved from the Wiki {@code output_item_uuid} against
   * {@code game_item.external_uuid}. {@code null} when the output item is not (yet) in {@code
   * game_item}; {@link #outputName} preserves the Wiki name regardless.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "output_item_id")
  @Nullable
  private GameItem outputItem;

  /** Wiki display name of the output item (kept even when {@link #outputItem} is unresolved). */
  @Column(name = "output_name")
  @Nullable
  private String outputName;

  /**
   * The DataForge GUID of the item the blueprint produces, written by the P4K import whether or not
   * a {@code game_item} carries it; the key of a member's personal blueprint (REQ-P4K-029,
   * ADR-0343). Never a Wiki UUID.
   */
  @Column(name = "produced_item_guid")
  @Nullable
  private UUID producedItemGuid;

  /** The SC Wiki's category UUID, kept as the Wiki serves it. */
  @Column(name = "category_uuid")
  private UUID categoryUuid;

  /** The P4K blueprint category ({@code categoryGuid}), or {@code null}. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  @Nullable
  private BlueprintCategory category;

  /**
   * How many of the mandatory requirement groups one craft fills (a choose-n selection), or {@code
   * null} when every group is required.
   */
  @Column(name = "required_group_count")
  @Nullable
  private Integer requiredGroupCount;

  /** The P4K's craft time in seconds; the Wiki writes {@link #craftTimeSeconds} only without it. */
  @Column(name = "p4k_craft_time_seconds")
  @Nullable
  private Integer p4kCraftTimeSeconds;

  /** The P4K's {@code defaultAvailable}; the Wiki writes the flag only without it. */
  @Column(name = "p4k_default_available")
  @Nullable
  private Boolean p4kDefaultAvailable;

  /** The P4K's dismantle time in seconds; the Wiki writes the column only without it. */
  @Column(name = "p4k_dismantle_time_seconds")
  @Nullable
  private Integer p4kDismantleTimeSeconds;

  /** The P4K's dismantle efficiency; the Wiki writes the column and the returns only without it. */
  @Column(name = "p4k_dismantle_efficiency")
  @Nullable
  private Double p4kDismantleEfficiency;

  /**
   * Whether the requirement groups and ingredient lines are the P4K's; the Wiki only fills them.
   */
  @Column(name = "p4k_recipe", nullable = false)
  private boolean p4kRecipe;

  /** Craft time in seconds. */
  @Column(name = "craft_time_seconds")
  @Nullable
  private Integer craftTimeSeconds;

  /**
   * Dismantle duration in seconds (Wiki {@code dismantle.time_seconds}); {@code null} if absent.
   */
  @Column(name = "dismantle_time_seconds")
  @Nullable
  private Integer dismantleTimeSeconds;

  /** Fraction of inputs recovered on dismantle (Wiki {@code dismantle.efficiency}, e.g. 0.5). */
  @Column(name = "dismantle_efficiency")
  @Nullable
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

  /** Optional Wiki detail evidence, independent of canonical recipe ownership. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "wiki_metadata", columnDefinition = "jsonb")
  @Nullable
  private BlueprintCatalogueMetadata wikiMetadata;

  /** Last successful SC Wiki sync touch. */
  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  /** Soft-delete marker: first run in which the Wiki stopped returning this blueprint. */
  @Column(name = "scwiki_deleted_at")
  @Nullable
  private Instant scwikiDeletedAt;

  /**
   * The P4K GUID, the row's identity in the catalogue and unique per kind (REQ-P4K-007); the SC
   * Wiki UUID stays in {@code scwiki_uuid}.
   */
  @Column(name = "p4k_uuid", unique = true)
  private UUID p4kUuid;

  /** Last successful KRT P4K Reader import touch; non-null marks P4K participation. */
  @Column(name = "p4k_synced_at")
  private Instant p4kSyncedAt;

  /**
   * When the P4K sweep found the row gone from a complete catalogue (REQ-P4K-011), or {@code null};
   * a later match clears it.
   */
  @Column(name = "p4k_deleted_at")
  @Nullable
  private Instant p4kDeletedAt;

  /**
   * When an admin hid the row through a P4K review case (REQ-P4K-016), or {@code null}; no match
   * clears it.
   */
  @Column(name = "p4k_hidden_at")
  @Nullable
  private Instant p4kHiddenAt;

  /** The P4K import run that last matched this row (REQ-P4K-005), or {@code null}. */
  @Column(name = "p4k_seen_run_id")
  @Nullable
  private UUID p4kSeenRunId;

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

  /**
   * Ordered recipe-wide stat modifiers: those of the P4K's choose-n root selection, which belong to
   * no single slot (REQ-P4K-027). Owned, cascade + orphan.
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
  private List<BlueprintRequirementModifier> recipeModifiers = new ArrayList<>();

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

  /** Clears all dismantle-return lines; orphan removal deletes them on flush. */
  public void clearDismantleReturns() {
    dismantleReturns.clear();
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
   * Returns the ordered recipe-wide modifiers as an unmodifiable view. Mutate through {@link
   * #addRecipeModifier} / {@link #clearRecipeModifiers}.
   *
   * @return an unmodifiable view of the recipe-wide modifiers
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintRequirementModifier> getRecipeModifiers() {
    return Collections.unmodifiableList(recipeModifiers);
  }

  /**
   * Appends a recipe-wide modifier and sets its back-reference to this blueprint.
   *
   * @param modifier the modifier to add
   */
  public void addRecipeModifier(@NotNull BlueprintRequirementModifier modifier) {
    modifier.setBlueprint(this);
    recipeModifiers.add(modifier);
  }

  /** Clears all recipe-wide modifiers; orphan removal deletes them on flush. */
  public void clearRecipeModifiers() {
    recipeModifiers.clear();
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
