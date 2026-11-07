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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The crafting records of catalogue version 2 a blueprint's recipe refers to: the stats its
 * modifiers name, the dismantle processes and the resources a dismantle never returns
 * (REQ-P4K-027). The pools, quality distributions, quantizations and crafters are not read.
 *
 * @param gameplayProperties the stats by GUID
 * @param dismantleProcesses the dismantle processes by GUID
 * @param dismantleExcludedResources the {@code ResourceType} GUIDs a dismantle never returns
 */
public record P4kCraftingReferences(
    @NotNull @Unmodifiable Map<UUID, P4kGameplayPropertyDto> gameplayProperties,
    @NotNull @Unmodifiable Map<UUID, P4kDismantleProcessDto> dismantleProcesses,
    @NotNull @Unmodifiable Set<UUID> dismantleExcludedResources) {

  /**
   * Copies every collection.
   *
   * @param gameplayProperties the stats by GUID
   * @param dismantleProcesses the dismantle processes by GUID
   * @param dismantleExcludedResources the resources a dismantle never returns
   */
  public P4kCraftingReferences {
    gameplayProperties = Map.copyOf(gameplayProperties);
    dismantleProcesses = Map.copyOf(dismantleProcesses);
    dismantleExcludedResources = Set.copyOf(dismantleExcludedResources);
  }

  /**
   * Indexes the records the reader bound.
   *
   * @param properties the {@code gameplayProperties} entries
   * @param processes the {@code dismantleProcesses} entries
   * @param global the {@code craftingGlobal} object, or {@code null}
   * @return the references; an entry without a usable GUID is left out
   */
  @NotNull
  public static P4kCraftingReferences of(
      @NotNull List<P4kGameplayPropertyDto> properties,
      @NotNull List<P4kDismantleProcessDto> processes,
      @Nullable P4kCraftingGlobalDto global) {
    Set<UUID> excluded = new HashSet<>();
    if (global != null && global.dismantleExcludedResourceGuids() != null) {
      for (String guid : global.dismantleExcludedResourceGuids()) {
        UUID parsed = P4kGuids.parse(guid);
        if (parsed != null) {
          excluded.add(parsed);
        }
      }
    }
    return new P4kCraftingReferences(
        byGuid(properties, P4kGameplayPropertyDto::guid),
        byGuid(processes, P4kDismantleProcessDto::guid),
        excluded);
  }

  /**
   * Indexes entries by their GUID, the first entry winning a repeated one.
   *
   * @param entries the entries
   * @param guid reads an entry's GUID text
   * @param <T> the entry type
   * @return the entries by parsed GUID
   */
  @NotNull
  private static <T> Map<UUID, T> byGuid(
      @NotNull List<T> entries, @NotNull Function<T, String> guid) {
    return entries.stream()
        .filter(entry -> P4kGuids.parse(guid.apply(entry)) != null)
        .collect(
            Collectors.toMap(
                entry -> P4kGuids.parse(guid.apply(entry)),
                Function.identity(),
                (first, _) -> first));
  }
}