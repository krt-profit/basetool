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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checks and names the catalogue entries of wipe exceptions (REQ-PATCH-011): each exception names
 * one existing game item or material, and no entry is named twice.
 */
@Component
@RequiredArgsConstructor
class WipeExceptionCatalogue {

  private final GameItemRepository gameItems;
  private final MaterialRepository materials;

  /**
   * Checks the exceptions of a patch change's options.
   *
   * @param exceptions the exceptions
   * @throws BadRequestException {@code error.patch_change.wipe_exception_duplicate} when two
   *     exceptions name one entry, {@code error.patch_change.wipe_exception_unknown} when an entry
   *     does not exist
   */
  void requireKnown(@NotNull Set<WipeException> exceptions) {
    long entries =
        exceptions.stream()
            .map(exception -> Map.entry(exception.target(), exception.id()))
            .distinct()
            .count();
    if (entries != exceptions.size()) {
      throw new BadRequestException("error.patch_change.wipe_exception_duplicate");
    }
    Names names = names(exceptions);
    if (exceptions.stream().anyMatch(exception -> names.of(exception) == null)) {
      throw new BadRequestException("error.patch_change.wipe_exception_unknown");
    }
  }

  /**
   * Reads the names of the exceptions' entries, one query per catalogue.
   *
   * @param exceptions the exceptions
   * @return the names
   */
  @NotNull
  Names names(@NotNull Collection<WipeException> exceptions) {
    return new Names(
        gameItems.findAllById(ids(exceptions, WipeExceptionTarget.GAME_ITEM)).stream()
            .collect(Collectors.toUnmodifiableMap(GameItem::getId, GameItem::getName)),
        materials.findAllById(ids(exceptions, WipeExceptionTarget.MATERIAL)).stream()
            .collect(Collectors.toUnmodifiableMap(Material::getId, Material::getName)));
  }

  /**
   * Collects the ids of one catalogue's entries.
   *
   * @param exceptions the exceptions
   * @param target the catalogue
   * @return the ids
   */
  private static @NotNull Set<UUID> ids(
      @NotNull Collection<WipeException> exceptions, @NotNull WipeExceptionTarget target) {
    return exceptions.stream()
        .filter(exception -> exception.target() == target)
        .map(WipeException::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * The names of exceptions' entries.
   *
   * @param gameItemNames the game items' names by id
   * @param materialNames the materials' names by id
   */
  record Names(@NotNull Map<UUID, String> gameItemNames, @NotNull Map<UUID, String> materialNames) {

    /**
     * Returns the name of an exception's entry.
     *
     * @param exception the exception
     * @return the name, or {@code null} when the entry does not exist
     */
    @Nullable
    String of(@NotNull WipeException exception) {
      return switch (exception.target()) {
        case GAME_ITEM -> gameItemNames.get(exception.id());
        case MATERIAL -> materialNames.get(exception.id());
      };
    }
  }
}
