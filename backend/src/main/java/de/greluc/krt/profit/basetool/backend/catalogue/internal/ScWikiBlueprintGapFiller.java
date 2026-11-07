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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementModifier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintModifierDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintRequirementChildDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintRequirementGroupDto;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The SC Wiki blueprint sync's writes on the columns the P4K leads (REQ-P4K-027, ADR-0340): the
 * Wiki writes a led column only where the P4K delivers {@code null}, and a Wiki value that differs
 * from the P4K's is reported as {@code P4K_DISAGREEMENT}. On a recipe the P4K wrote, the Wiki only
 * fills the slot names, the stat labels and directions and the lines' Wiki identities and missing
 * links, and a differing quantity is reported.
 */
@Component
@RequiredArgsConstructor
public class ScWikiBlueprintGapFiller {

  /** The aggregate a disagreement names. */
  static final String AGGREGATE = "blueprint";

  /** Receives the disagreements. */
  private final SyncReportService syncReportService;

  /**
   * Resolves the Wiki's references of one recipe.
   *
   * @param material resolves a Wiki resource line to a material, or {@code null}
   * @param item resolves a Wiki item UUID to an item, or {@code null}
   */
  public record Resolvers(
      @NotNull Function<ScWikiBlueprintRequirementChildDto, Material> material,
      @NotNull Function<UUID, GameItem> item) {}

  /**
   * Writes the Wiki's craft time, default availability and dismantle values where the P4K holds
   * none, and reports the ones that differ.
   *
   * @param blueprint the blueprint
   * @param dto the Wiki's blueprint
   * @param runId the Wiki run
   */
  public void fillScalars(
      @NotNull Blueprint blueprint, @NotNull ScWikiBlueprintDto dto, @NotNull UUID runId) {
    fill(
        blueprint,
        runId,
        "craft_time_seconds",
        blueprint.getP4kCraftTimeSeconds(),
        dto.craftTimeSeconds(),
        blueprint::setCraftTimeSeconds);
    fill(
        blueprint,
        runId,
        "is_available_by_default",
        blueprint.getP4kDefaultAvailable(),
        Boolean.TRUE.equals(dto.isAvailableByDefault()),
        blueprint::setIsAvailableByDefault);
    if (dto.dismantle() != null) {
      fill(
          blueprint,
          runId,
          "dismantle_time_seconds",
          blueprint.getP4kDismantleTimeSeconds(),
          dto.dismantle().timeSeconds(),
          blueprint::setDismantleTimeSeconds);
      fill(
          blueprint,
          runId,
          "dismantle_efficiency",
          blueprint.getP4kDismantleEfficiency(),
          dto.dismantle().efficiency(),
          blueprint::setDismantleEfficiency);
    }
  }

  /**
   * Fills the gaps of a recipe the P4K wrote from the Wiki's requirement groups, matching a group
   * by its key, a modifier by its stat and a line by its kind and resource or item identity within
   * the group; a line no Wiki line names stays unfilled.
   *
   * @param blueprint the blueprint, whose recipe is the P4K's
   * @param dto the Wiki's blueprint detail
   * @param runId the Wiki run
   * @param resolvers resolves the Wiki's references
   */
  public void fillRecipe(
      @NotNull Blueprint blueprint,
      @NotNull ScWikiBlueprintDto dto,
      @NotNull UUID runId,
      @NotNull Resolvers resolvers) {
    if (dto.requirementGroups() == null) {
      return;
    }
    for (ScWikiBlueprintRequirementGroupDto wikiGroup : dto.requirementGroups()) {
      BlueprintRequirementGroup group = group(blueprint, wikiGroup.key());
      if (group == null) {
        continue;
      }
      if (group.getName() == null) {
        group.setName(wikiGroup.name());
      }
      if (wikiGroup.modifiers() != null) {
        wikiGroup.modifiers().forEach(m -> fillModifier(group, m));
      }
      if (wikiGroup.children() != null) {
        List<BlueprintIngredient> lines =
            blueprint.getIngredients().stream()
                .filter(line -> Objects.equals(line.getRequirementGroup(), group))
                .toList();
        for (BlueprintIngredient line : lines) {
          wikiGroup.children().stream()
              .filter(wiki -> sameIdentity(line, wiki))
              .findFirst()
              .ifPresent(wiki -> fillLine(blueprint, line, wiki, runId, resolvers));
        }
      }
    }
  }

  /**
   * Whether a Wiki line names the resource or item of a P4K line: the Wiki's resource-type or item
   * UUID equals the line's P4K GUID of that kind or the Wiki identity it already holds.
   *
   * @param line the P4K's line
   * @param wiki the Wiki's line
   * @return {@code true} when both are one kind and one identity
   */
  private static boolean sameIdentity(
      @NotNull BlueprintIngredient line, @NotNull ScWikiBlueprintRequirementChildDto wiki) {
    boolean wikiItem = "item".equalsIgnoreCase(wiki.kind());
    if (wiki.uuid() == null || wikiItem != (line.getKind() == BlueprintIngredientKind.ITEM)) {
      return false;
    }
    return wikiItem
        ? wiki.uuid().equals(line.getP4kItemGuid()) || wiki.uuid().equals(line.getWikiItemUuid())
        : wiki.uuid().equals(line.getP4kResourceGuid())
            || wiki.uuid().equals(line.getWikiResourceUuid());
  }

  /**
   * Finds the requirement group of a Wiki slot key, ignoring case.
   *
   * @param blueprint the blueprint
   * @param key the Wiki's slot key, or {@code null}
   * @return the group, or {@code null}
   */
  @Nullable
  private static BlueprintRequirementGroup group(
      @NotNull Blueprint blueprint, @Nullable String key) {
    if (key == null) {
      return null;
    }
    return blueprint.getRequirementGroups().stream()
        .filter(g -> key.equalsIgnoreCase(g.getGroupKey()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Fills the label and the direction of the P4K modifier of the Wiki modifier's stat.
   *
   * @param group the requirement group
   * @param wiki the Wiki's modifier
   */
  private static void fillModifier(
      @NotNull BlueprintRequirementGroup group, @NotNull ScWikiBlueprintModifierDto wiki) {
    for (BlueprintRequirementModifier modifier : group.getModifiers()) {
      boolean sameStat =
          (wiki.propertyUuid() != null && wiki.propertyUuid().equals(modifier.getPropertyGuid()))
              || (wiki.propertyKey() != null
                  && wiki.propertyKey().equalsIgnoreCase(modifier.getPropertyKey()));
      if (sameStat) {
        if (modifier.getLabel() == null) {
          modifier.setLabel(wiki.label());
        }
        if (modifier.getBetterWhen() == null) {
          modifier.setBetterWhen(wiki.betterWhen());
        }
        if (modifier.getPropertyKey() == null) {
          modifier.setPropertyKey(wiki.propertyKey());
        }
        return;
      }
    }
  }

  /**
   * Fills a P4K line from the Wiki line of its identity ({@link #sameIdentity}), and reports a
   * differing quantity.
   *
   * @param blueprint the blueprint
   * @param line the P4K's line
   * @param wiki the Wiki's line
   * @param runId the Wiki run
   * @param resolvers resolves the Wiki's references
   */
  private void fillLine(
      @NotNull Blueprint blueprint,
      @NotNull BlueprintIngredient line,
      @NotNull ScWikiBlueprintRequirementChildDto wiki,
      @NotNull UUID runId,
      @NotNull Resolvers resolvers) {
    boolean wikiItem = line.getKind() == BlueprintIngredientKind.ITEM;
    if (line.getWikiNameSnapshot() == null) {
      line.setWikiNameSnapshot(wiki.name());
    }
    if (wikiItem) {
      if (line.getWikiItemUuid() == null) {
        line.setWikiItemUuid(wiki.uuid());
      }
      if (line.getGameItem() == null && wiki.uuid() != null) {
        line.setGameItem(resolvers.item().apply(wiki.uuid()));
      }
      report(blueprint, runId, "quantity_units", line.getQuantityUnits(), wiki.quantity());
    } else {
      if (line.getWikiResourceUuid() == null) {
        line.setWikiResourceUuid(wiki.uuid());
      }
      if (line.getWikiOreUuid() == null) {
        line.setWikiOreUuid(wiki.oreUuid());
      }
      if (line.getMaterial() == null) {
        line.setMaterial(resolvers.material().apply(wiki));
      }
      report(blueprint, runId, "quantity_scu", line.getQuantityScu(), wiki.quantityScu());
    }
  }

  /**
   * Writes the Wiki's value where the P4K holds none, else reports a Wiki value that differs.
   *
   * @param blueprint the blueprint
   * @param runId the Wiki run
   * @param column the column
   * @param p4kValue the P4K's value of the column
   * @param wikiValue the Wiki's value
   * @param setter writes the column
   * @param <V> the value type
   */
  private <V> void fill(
      @NotNull Blueprint blueprint,
      @NotNull UUID runId,
      @NotNull String column,
      @Nullable V p4kValue,
      @Nullable V wikiValue,
      @NotNull Consumer<V> setter) {
    if (p4kValue == null) {
      setter.accept(wikiValue);
      return;
    }
    report(blueprint, runId, column, p4kValue, wikiValue);
  }

  /**
   * Reports a Wiki value that differs from the P4K's.
   *
   * @param blueprint the blueprint
   * @param runId the Wiki run
   * @param column the column
   * @param p4kValue the P4K's value, or {@code null}
   * @param wikiValue the Wiki's value, or {@code null}
   */
  private void report(
      @NotNull Blueprint blueprint,
      @NotNull UUID runId,
      @NotNull String column,
      @Nullable Object p4kValue,
      @Nullable Object wikiValue) {
    if (p4kValue == null || wikiValue == null || P4kFields.sameValue(p4kValue, wikiValue)) {
      return;
    }
    syncReportService.logP4kDisagreement(
        SyncSourceSystem.SCWIKI,
        runId,
        AGGREGATE,
        Objects.requireNonNull(blueprint.getP4kUuid()),
        column,
        String.valueOf(p4kValue),
        String.valueOf(wikiValue));
  }
}
