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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Slim {@code GameItem} reference for the Lager item-stock surfaces and the item-catalog picker
 * (REQ-INV-029, REQ-INV-061, REQ-INV-062).
 *
 * <p>Size, grade and class are only set for a ship component, i.e. when {@code componentType} is
 * set.
 *
 * @param id the game item's primary key
 * @param name the item's display name
 * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
 * @param kind the {@code GameItemKind} name, e.g. {@code WEAPON}
 * @param componentType the {@code ShipComponentType} name, or {@code null} for a non-component
 * @param size the component size, or {@code null} when unknown or no component
 * @param grade the component grade, or {@code null} when unknown or no component
 * @param itemClass the {@code ShipComponentClass} name, or {@code null} when unknown or no
 *     component
 * @param noLongerInGame whether the P4K stamped the item as gone from the game or an admin hid it;
 *     an existing reference then shows the "no longer in the game" notice (REQ-P4K-013)
 * @param nameDe the item's German name, or {@code null} (REQ-P4K-023)
 */
public record InventoryGameItemReferenceDto(
    UUID id,
    String name,
    @Nullable String manufacturer,
    @Nullable String kind,
    @Nullable String componentType,
    @Nullable Integer size,
    @Nullable String grade,
    @Nullable String itemClass,
    boolean noLongerInGame,
    @Nullable String nameDe) {

  /**
   * Builds a reference without a German name.
   *
   * @param id the game item's primary key
   * @param name the item's display name
   * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
   * @param kind the {@code GameItemKind} name
   * @param componentType the component type, or {@code null}
   * @param size the component size, or {@code null}
   * @param grade the component grade, or {@code null}
   * @param itemClass the component class, or {@code null}
   * @param noLongerInGame whether the P4K retired the item (REQ-P4K-013)
   */
  public InventoryGameItemReferenceDto(
      UUID id,
      String name,
      @Nullable String manufacturer,
      @Nullable String kind,
      @Nullable String componentType,
      @Nullable Integer size,
      @Nullable String grade,
      @Nullable String itemClass,
      boolean noLongerInGame) {
    this(id, name, manufacturer, kind, componentType, size, grade, itemClass, noLongerInGame, null);
  }

  /**
   * Builds a reference to an item still in the game without component data.
   *
   * @param id the game item's primary key
   * @param name the item's display name
   * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
   * @param kind the {@code GameItemKind} name
   */
  public InventoryGameItemReferenceDto(
      UUID id, String name, @Nullable String manufacturer, @Nullable String kind) {
    this(id, name, manufacturer, kind, false);
  }

  /**
   * Builds a reference without component data, for items that are no ship component.
   *
   * @param id the game item's primary key
   * @param name the item's display name
   * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
   * @param kind the {@code GameItemKind} name
   * @param noLongerInGame whether the P4K retired the item (REQ-P4K-013)
   */
  public InventoryGameItemReferenceDto(
      UUID id,
      String name,
      @Nullable String manufacturer,
      @Nullable String kind,
      boolean noLongerInGame) {
    this(id, name, manufacturer, kind, null, null, null, null, noLongerInGame);
  }
}
