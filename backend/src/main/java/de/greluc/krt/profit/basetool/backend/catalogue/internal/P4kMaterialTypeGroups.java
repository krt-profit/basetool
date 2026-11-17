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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The resource group of a material no commodity record of the run reconciles, taken from the {@code
 * ResourceType} it stands for (REQ-P4K-073): the type of its SC Wiki UUID or stored {@code
 * subtypeGuid} (G1), else the only type of its reduced name (G2). Only a live material without a
 * resource group takes one; a group an admin overrides stays, and a placeholder group ({@link
 * P4kResourceTypes}) is never taken.
 */
final class P4kMaterialTypeGroups {

  /** Non-instantiable holder of the rule. */
  private P4kMaterialTypeGroups() {}

  /**
   * Collects the resource groups the materials without one need, so the run creates them.
   *
   * @param materials the stored materials
   * @param types the catalogue's resource types
   * @return the GUIDs of the groups their types name
   */
  @NotNull
  @Unmodifiable
  static Set<UUID> needed(
      @NotNull Collection<Material> materials, @NotNull P4kResourceTypes types) {
    Set<UUID> needed = new HashSet<>();
    for (Material material : materials) {
      UUID group = needsGroup(material) ? groupOf(material, types) : null;
      if (group != null) {
        needed.add(group);
      }
    }
    return Set.copyOf(needed);
  }

  /**
   * Gives each material without a resource group the group of its type, reported as {@code CHANGED
   * category_id}; a preview writes nothing.
   *
   * @param materials the materials no commodity record of the run reconciled
   * @param types the catalogue's resource types
   * @param context the run, whose resource groups are reconciled
   * @return the ids of the saved materials that take a group
   */
  @NotNull
  @Unmodifiable
  static Set<UUID> assign(
      @NotNull Collection<Material> materials,
      @NotNull P4kResourceTypes types,
      @NotNull P4kImportContext context) {
    Set<UUID> grouped = new HashSet<>();
    for (Material material : materials) {
      UUID guid = needsGroup(material) ? groupOf(material, types) : null;
      MaterialCategory group = guid == null ? null : context.resourceGroup(guid.toString());
      if (group == null || material.getId() == null) {
        continue;
      }
      MaterialCategory current = material.getCategory();
      context
          .getReport()
          .changed(
              P4kKind.COMMODITY,
              new P4kMaterialRecord(material),
              MaterialP4kOverride.RESOURCE_GROUP.getColumn(),
              current == null ? null : current.getName(),
              group.getName());
      if (context.isApply()) {
        material.setCategory(group);
      }
      grouped.add(material.getId());
    }
    return Set.copyOf(grouped);
  }

  /**
   * Whether a material lacks a resource group the import may give it: it is live, its group is none
   * or an admin category, and no admin overrides it.
   *
   * @param material the material
   * @return {@code true} when the type's group is written
   */
  private static boolean needsGroup(@NotNull Material material) {
    MaterialCategory category = material.getCategory();
    return !material.isP4kRetired()
        && !material.isResourceGroupManual()
        && (category == null || category.getP4kUuid() == null);
  }

  /**
   * Returns the group of the type a material stands for.
   *
   * @param material the material
   * @param types the resource types
   * @return the group's GUID, or {@code null} when the material stands for no type, its type for no
   *     single group, or only for a placeholder group
   */
  @Nullable
  private static UUID groupOf(@NotNull Material material, @NotNull P4kResourceTypes types) {
    P4kResourceTypes.Type type = types.typeOf(material);
    return type == null || type.placeholder() ? null : type.groupGuid();
  }

  /**
   * Whether the catalogue's files know a material: it holds a listed commodity's GUID, or it stands
   * for a {@code ResourceType} of the catalogue that no placeholder group lists (REQ-P4K-074).
   *
   * @param material the material
   * @param listed the GUIDs of the catalogue's commodity records
   * @param types the resource types
   * @return {@code false} for a material the game files lack
   */
  static boolean isKnown(
      @NotNull Material material, @NotNull Set<UUID> listed, @NotNull P4kResourceTypes types) {
    UUID guid = material.getP4kUuid();
    if (guid != null && listed.contains(guid)) {
      return true;
    }
    P4kResourceTypes.Type type = types.typeOf(material);
    return type != null && !type.placeholder();
  }
}
