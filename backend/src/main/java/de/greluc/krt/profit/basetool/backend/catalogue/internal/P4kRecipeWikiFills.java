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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The SC Wiki's values on a blueprint's stored recipe, captured before the P4K rewrites it, so the
 * new rows keep what the Wiki filled where the P4K delivers {@code null} (REQ-P4K-027): a slot's
 * name, a stat's label, key and direction, and a line's name, Wiki identities and links. A group
 * matches by its key ignoring case, a modifier by its stat within the group, and a line by its kind
 * and resource or item identity within the group, as {@link ScWikiBlueprintGapFiller} matches them.
 */
final class P4kRecipeWikiFills {

  /** The stored groups with their modifiers. */
  @NotNull @Unmodifiable private final List<StoredGroup> groups;

  /** The stored lines with their group's key. */
  @NotNull @Unmodifiable private final List<StoredLine> lines;

  /**
   * Captures the stored values.
   *
   * @param groups the stored groups
   * @param lines the stored lines
   */
  private P4kRecipeWikiFills(@NotNull List<StoredGroup> groups, @NotNull List<StoredLine> lines) {
    this.groups = List.copyOf(groups);
    this.lines = List.copyOf(lines);
  }

  /**
   * One stored group.
   *
   * @param key the slot key, or {@code null}
   * @param name the slot name, or {@code null}
   * @param modifiers the stored modifiers
   */
  private record StoredGroup(
      @Nullable String key,
      @Nullable String name,
      @NotNull @Unmodifiable List<BlueprintRequirementModifier> modifiers) {}

  /**
   * One stored line.
   *
   * @param groupKey the key of the line's slot, or {@code null}
   * @param line the stored line
   */
  private record StoredLine(@Nullable String groupKey, @NotNull BlueprintIngredient line) {}

  /**
   * Captures the stored recipe of a blueprint; call it before the recipe is cleared.
   *
   * @param blueprint the blueprint
   * @return the captured values
   */
  @NotNull
  static P4kRecipeWikiFills of(@NotNull Blueprint blueprint) {
    List<StoredGroup> groups =
        blueprint.getRequirementGroups().stream()
            .map(g -> new StoredGroup(g.getGroupKey(), g.getName(), List.copyOf(g.getModifiers())))
            .toList();
    List<StoredLine> lines =
        blueprint.getIngredients().stream()
            .map(line ->
                new StoredLine(
                    line.getRequirementGroup() == null
                        ? null
                        : line.getRequirementGroup().getGroupKey(),
                    line))
            .toList();
    return new P4kRecipeWikiFills(groups, lines);
  }

  /**
   * Fills a new group's name from the stored group of its key.
   *
   * @param group the new group, its key set
   */
  void fillGroup(@NotNull BlueprintRequirementGroup group) {
    if (group.getName() != null) {
      return;
    }
    StoredGroup stored = group(group.getGroupKey());
    if (stored != null) {
      group.setName(stored.name());
    }
  }

  /**
   * Fills a new modifier's label, stat key and direction from the stored modifier of its stat in
   * the group of the same key.
   *
   * @param groupKey the key of the modifier's group, or {@code null}
   * @param modifier the new modifier
   */
  void fillModifier(@Nullable String groupKey, @NotNull BlueprintRequirementModifier modifier) {
    StoredGroup stored = group(groupKey);
    if (stored == null) {
      return;
    }
    stored.modifiers().stream()
        .filter(old -> sameStat(modifier, old))
        .findFirst()
        .ifPresent(old -> {
          if (modifier.getLabel() == null) {
            modifier.setLabel(old.getLabel());
          }
          if (modifier.getPropertyKey() == null) {
            modifier.setPropertyKey(old.getPropertyKey());
          }
          if (modifier.getBetterWhen() == null) {
            modifier.setBetterWhen(old.getBetterWhen());
          }
        });
  }

  /**
   * Fills a new line's name, Wiki identities, material and item from the stored line of its
   * identity in the group of the same key.
   *
   * @param groupKey the key of the line's group, or {@code null}
   * @param line the new line
   */
  void fillLine(@Nullable String groupKey, @NotNull BlueprintIngredient line) {
    lines.stream()
        .filter(old -> sameKey(groupKey, old.groupKey()) && sameIdentity(line, old.line()))
        .findFirst()
        .map(StoredLine::line)
        .ifPresent(old -> {
          if (line.getWikiNameSnapshot() == null) {
            line.setWikiNameSnapshot(old.getWikiNameSnapshot());
          }
          if (line.getKind() == BlueprintIngredientKind.ITEM) {
            if (line.getWikiItemUuid() == null) {
              line.setWikiItemUuid(old.getWikiItemUuid());
            }
            if (line.getGameItem() == null) {
              line.setGameItem(old.getGameItem());
            }
          } else {
            if (line.getWikiResourceUuid() == null) {
              line.setWikiResourceUuid(old.getWikiResourceUuid());
            }
            if (line.getWikiOreUuid() == null) {
              line.setWikiOreUuid(old.getWikiOreUuid());
            }
            if (line.getMaterial() == null) {
              line.setMaterial(old.getMaterial());
            }
          }
        });
  }

  /**
   * Finds the stored group of a key, ignoring case.
   *
   * @param key the key, or {@code null}
   * @return the stored group, or {@code null}
   */
  @Nullable
  private StoredGroup group(@Nullable String key) {
    return groups.stream().filter(g -> sameKey(key, g.key())).findFirst().orElse(null);
  }

  /**
   * Whether two slot keys name one slot: both {@code null}, or equal ignoring case.
   *
   * @param a one key, or {@code null}
   * @param b the other, or {@code null}
   * @return {@code true} when they name one slot
   */
  private static boolean sameKey(@Nullable String a, @Nullable String b) {
    return a == null ? b == null : a.equalsIgnoreCase(b);
  }

  /**
   * Whether two modifiers carry one stat: one property GUID, or one stat key ignoring case.
   *
   * @param modifier the new modifier
   * @param old the stored modifier
   * @return {@code true} when both carry one stat
   */
  private static boolean sameStat(
      @NotNull BlueprintRequirementModifier modifier, @NotNull BlueprintRequirementModifier old) {
    return (modifier.getPropertyGuid() != null
            && modifier.getPropertyGuid().equals(old.getPropertyGuid()))
        || (modifier.getPropertyKey() != null
            && modifier.getPropertyKey().equalsIgnoreCase(old.getPropertyKey()));
  }

  /**
   * Whether a stored line names the resource or item of a new line: one kind, and the new line's
   * P4K GUID equals the stored line's P4K GUID or its Wiki identity of that kind.
   *
   * @param line the new line
   * @param old the stored line
   * @return {@code true} when both are one kind and one identity
   */
  private static boolean sameIdentity(
      @NotNull BlueprintIngredient line, @NotNull BlueprintIngredient old) {
    if (line.getKind() != old.getKind()) {
      return false;
    }
    UUID guid =
        line.getKind() == BlueprintIngredientKind.ITEM
            ? line.getP4kItemGuid()
            : line.getP4kResourceGuid();
    if (guid == null) {
      return false;
    }
    return line.getKind() == BlueprintIngredientKind.ITEM
        ? guid.equals(old.getP4kItemGuid()) || guid.equals(old.getWikiItemUuid())
        : guid.equals(old.getP4kResourceGuid()) || guid.equals(old.getWikiResourceUuid());
  }
}
