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

import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Slim reference to a {@code GameItem} embedded in item-order payloads.
 *
 * <p>Component type, size, grade and class are only set for a ship component (REQ-INV-061,
 * REQ-INV-062, REQ-INV-064).
 *
 * @param id the game item's primary key
 * @param name the item's display name
 * @param kind the {@code GameItemKind} name (e.g. {@code WEAPON}, {@code ARMOR})
 * @param componentType the {@code ShipComponentType} name, or {@code null} for a non-component
 * @param size the component size, or {@code null} when unknown or no component
 * @param grade the component grade, or {@code null} when unknown or no component
 * @param itemClass the {@code ShipComponentClass} name, or {@code null} when unknown or no
 *     component
 */
public record GameItemReferenceDto(
    UUID id,
    String name,
    @Nullable String kind,
    @Nullable String componentType,
    @Nullable Integer size,
    @Nullable String grade,
    @Nullable String itemClass) {

  /**
   * Builds a reference without component data, for items that are no ship component.
   *
   * @param id the game item's primary key
   * @param name the item's display name
   * @param kind the {@code GameItemKind} name
   */
  public GameItemReferenceDto(UUID id, String name, @Nullable String kind) {
    this(id, name, kind, null, null, null, null);
  }

  /**
   * Projects a catalogue item, with its component data when it is a ship component.
   *
   * <p>Reads only columns of the item's own row.
   *
   * @param gameItem the catalogue item
   * @return the reference
   */
  @NotNull
  public static GameItemReferenceDto of(@NotNull GameItem gameItem) {
    String kind = gameItem.getKind() == null ? null : gameItem.getKind().name();
    ShipComponentType componentType = ShipComponentType.fromWikiType(gameItem.getWikiType());
    if (componentType == null) {
      return new GameItemReferenceDto(gameItem.getId(), gameItem.getName(), kind);
    }
    ShipComponentClass itemClass = gameItem.getItemClass();
    return new GameItemReferenceDto(
        gameItem.getId(),
        gameItem.getName(),
        kind,
        componentType.name(),
        gameItem.getSizeClass(),
        StringNormalization.trimToNull(gameItem.getGrade()),
        itemClass == null ? null : itemClass.name());
  }
}
