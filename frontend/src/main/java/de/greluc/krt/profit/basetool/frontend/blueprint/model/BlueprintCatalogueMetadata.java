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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Retains optional Wiki recipe evidence separately from the canonical crafting recipe.
 *
 * @param source catalogue supplying this snapshot
 * @param gameVersion game patch described by the evidence
 * @param loadedSections sections explicitly supplied by a detail response
 * @param unlockingMissions missions that unlock the recipe
 * @param unlockingMissionGroups missions grouped by reward chance
 * @param aspects source aspect inputs and selection constraints
 * @param tiers complete source requirement trees for each crafting tier
 */
@DtoMirror
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlueprintCatalogueMetadata(
    Source source,
    @Nullable String gameVersion,
    Set<DetailSection> loadedSections,
    List<Mission> unlockingMissions,
    List<MissionGroup> unlockingMissionGroups,
    @Nullable Aspects aspects,
    List<Tier> tiers) {

  /** Copies source collections and records which detail sections were actually loaded. */
  public BlueprintCatalogueMetadata {
    loadedSections = loadedSections == null ? Set.of() : Set.copyOf(loadedSections);
    unlockingMissions = immutable(unlockingMissions);
    unlockingMissionGroups = immutable(unlockingMissionGroups);
    tiers = immutable(tiers);
  }

  /** Identifies the catalogue supplying optional blueprint evidence. */
  public enum Source {
    /** Community Wiki game-data catalogue. */
    SC_WIKI
  }

  /** Distinguishes an absent detail section from a loaded empty section. */
  public enum DetailSection {
    /** Mission unlock conditions and reward chances. */
    MISSIONS,
    /** Mission rewards grouped by their chance. */
    MISSION_GROUPS,
    /** Source aspect inputs and selection constraints. */
    ASPECTS,
    /** Complete per-tier source requirement trees. */
    TIERS
  }

  /** Identifies the role of a node in a source requirement tree. */
  public enum RequirementKind {
    /** Root of the complete requirement tree. */
    @JsonProperty("root")
    ROOT,
    /** Group containing required or alternative child inputs. */
    @JsonProperty("group")
    GROUP,
    /** Commodity consumed in SCU. */
    @JsonProperty("resource")
    RESOURCE,
    /** Discrete item consumed in pieces. */
    @JsonProperty("item")
    ITEM
  }

  /**
   * Describes a mission reward that unlocks the blueprint.
   *
   * @param title source mission title
   * @param debugName engine mission identifier
   * @param rewardScope source reward category
   * @param chance reward probability as a fraction
   * @param count number of equivalent missions in a grouped entry
   * @param webUrl source mission browser address
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Mission(
      @Nullable String title,
      @Nullable @JsonAlias("debug_name") String debugName,
      @Nullable @JsonAlias("reward_scope") String rewardScope,
      @Nullable Double chance,
      @Nullable Integer count,
      @Nullable @JsonAlias("web_url") String webUrl) {}

  /**
   * Groups equivalent mission rewards by their chance.
   *
   * @param label source chance label
   * @param chance reward probability as a fraction
   * @param missions missions in this chance group
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MissionGroup(
      @Nullable String label, @Nullable Double chance, List<Mission> missions) {
    /** Copies grouped mission references. */
    public MissionGroup {
      missions = immutable(missions);
    }
  }

  /**
   * Defines the ingredient quality interval of a modifier.
   *
   * @param min minimum quality
   * @param max maximum quality
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record QualityRange(@Nullable Double min, @Nullable Double max) {}

  /**
   * Defines stat multipliers at the ends of a quality interval.
   *
   * @param atMinQuality multiplier at the minimum quality
   * @param atMaxQuality multiplier at the maximum quality
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ModifierRange(
      @Nullable @JsonAlias("at_min_quality") Double atMinQuality,
      @Nullable @JsonAlias("at_max_quality") Double atMaxQuality) {}

  /**
   * Describes one source quality interpolation segment.
   *
   * @param qualityMin segment's minimum quality
   * @param qualityMax segment's maximum quality
   * @param modifierAtStart multiplier at the segment start
   * @param modifierAtEnd multiplier at the segment end
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Segment(
      @Nullable @JsonAlias("quality_min") Double qualityMin,
      @Nullable @JsonAlias("quality_max") Double qualityMax,
      @Nullable @JsonAlias("modifier_at_start") Double modifierAtStart,
      @Nullable @JsonAlias("modifier_at_end") Double modifierAtEnd) {}

  /**
   * Retains normalized or raw source modifier definitions.
   *
   * @param propertyKey source stat key
   * @param propertyUuid source stat definition UUID
   * @param label source stat label
   * @param betterWhen source direction of improvement
   * @param unitFormat source value format
   * @param qualityRange ingredient quality interval
   * @param modifierRange stat multiplier interval
   * @param valueRangeType source interpolation type
   * @param valueSegments piecewise interpolation segments
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Modifier(
      @Nullable @JsonAlias({"property_key", "key"}) String propertyKey,
      @Nullable @JsonAlias({"property_uuid", "uuid"}) UUID propertyUuid,
      @Nullable @JsonAlias({"label", "name"}) String label,
      @Nullable @JsonAlias("better_when") String betterWhen,
      @Nullable @JsonAlias("unit_format") String unitFormat,
      @Nullable @JsonAlias("quality_range") QualityRange qualityRange,
      @Nullable @JsonAlias("modifier_range") ModifierRange modifierRange,
      @Nullable @JsonAlias("value_range_type") String valueRangeType,
      @JsonAlias("value_segments") List<Segment> valueSegments) {
    /** Copies piecewise interpolation segments. */
    public Modifier {
      valueSegments = immutable(valueSegments);
    }
  }

  /**
   * Preserves a recursive source requirement without flattening choice groups.
   *
   * @param kind node's requirement role
   * @param key source requirement key
   * @param name source input or group label
   * @param uuid resource or item UUID selected by kind
   * @param requiredCount number of children required
   * @param quantity discrete source item count
   * @param quantityScu commodity quantity in SCU
   * @param minQuality minimum ingredient quality
   * @param modifiers modifiers attached to this node
   * @param children nested requirement nodes
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Requirement(
      @Nullable RequirementKind kind,
      @Nullable String key,
      @Nullable String name,
      @Nullable UUID uuid,
      @Nullable @JsonAlias("required_count") Integer requiredCount,
      @Nullable BigDecimal quantity,
      @Nullable @JsonAlias("quantity_scu") BigDecimal quantityScu,
      @Nullable @JsonAlias("min_quality") Integer minQuality,
      List<Modifier> modifiers,
      List<Requirement> children) {
    /** Copies recursive requirement and modifier collections. */
    public Requirement {
      modifiers = immutable(modifiers);
      children = immutable(children);
    }
  }

  /**
   * Retains one complete crafting tier from the source.
   *
   * @param tierIndex zero-based tier index
   * @param craftTimeSeconds tier-specific crafting duration
   * @param requirements complete source requirement tree
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tier(
      @Nullable @JsonAlias("tier_index") Integer tierIndex,
      @Nullable @JsonAlias("craft_time_seconds") Integer craftTimeSeconds,
      @Nullable Requirement requirements) {}

  /**
   * Identifies the input selected by a source aspect.
   *
   * @param kind resource or item role
   * @param uuid source resource or item UUID
   * @param name source input label
   * @param quantity discrete source item count
   * @param quantityScu commodity quantity in SCU
   * @param minQuality minimum ingredient quality
   * @param webUrl source input browser address
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AspectInput(
      @Nullable RequirementKind kind,
      @Nullable UUID uuid,
      @Nullable String name,
      @Nullable BigDecimal quantity,
      @Nullable @JsonAlias("quantity_scu") BigDecimal quantityScu,
      @Nullable @JsonAlias("min_quality") Integer minQuality,
      @Nullable @JsonAlias("web_url") String webUrl) {}

  /**
   * Retains a source aspect's input, modifiers and selection state.
   *
   * @param key source aspect key
   * @param name source aspect label
   * @param requiredCount number required within its group
   * @param selectionGroup source choice-group identifier
   * @param input selected input
   * @param modifiers quality effects of this aspect
   * @param initialQuality source default ingredient quality
   * @param sliderMin source minimum quality
   * @param sliderMax source maximum quality
   * @param hasModifiers whether modifiers are present
   * @param hasDynamicModifiers whether modifier values vary with quality
   * @param selected source default selection
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Aspect(
      @Nullable String key,
      @Nullable String name,
      @Nullable @JsonAlias("required_count") Integer requiredCount,
      @Nullable @JsonAlias("selection_group") String selectionGroup,
      @Nullable AspectInput input,
      List<Modifier> modifiers,
      @Nullable @JsonAlias("initial_quality") Integer initialQuality,
      @Nullable @JsonAlias("slider_min") Integer sliderMin,
      @Nullable @JsonAlias("slider_max") Integer sliderMax,
      @Nullable @JsonAlias("has_modifiers") Boolean hasModifiers,
      @Nullable @JsonAlias("has_dynamic_modifiers") Boolean hasDynamicModifiers,
      @Nullable @JsonAlias("is_selected") Boolean selected) {
    /** Copies aspect modifier definitions. */
    public Aspect {
      modifiers = immutable(modifiers);
    }
  }

  /**
   * Preserves the source's choice constraints between aspects.
   *
   * @param key source group key
   * @param name source group label
   * @param displayName source presentation label
   * @param requiredCount number of selections required
   * @param optionCount number of available options
   * @param choiceGroup whether the group contains alternatives
   * @param selectedCount source default number selected
   * @param aspectIndexes indexes into the ordered aspect list
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AspectGroup(
      @Nullable String key,
      @Nullable String name,
      @Nullable @JsonAlias("display_name") String displayName,
      @Nullable @JsonAlias("required_count") Integer requiredCount,
      @Nullable @JsonAlias("option_count") Integer optionCount,
      @Nullable @JsonAlias("is_choice_group") Boolean choiceGroup,
      @Nullable @JsonAlias("selected_count") Integer selectedCount,
      @JsonAlias("aspect_indexes") List<Integer> aspectIndexes) {
    /** Copies ordered source aspect indexes. */
    public AspectGroup {
      aspectIndexes = immutable(aspectIndexes);
    }
  }

  /**
   * Retains the source's aspect and choice-group model.
   *
   * @param aspects ordered source aspects
   * @param aspectGroups source selection groups
   * @param interactive whether source aspects vary with user input
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Aspects(
      List<Aspect> aspects,
      @JsonAlias("aspect_groups") List<AspectGroup> aspectGroups,
      @Nullable @JsonAlias("has_interactive_aspects") Boolean interactive) {
    /** Copies ordered aspects and their selection groups. */
    public Aspects {
      aspects = immutable(aspects);
      aspectGroups = immutable(aspectGroups);
    }
  }

  /**
   * Copies an optional source collection.
   *
   * @param <T> element type
   * @param values optional source values
   * @return immutable source values
   */
  private static <T> List<T> immutable(@Nullable List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
