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
 * (REQ-INV-029, REQ-INV-061).
 *
 * <p>Size and grade are only set for a ship component, i.e. when {@code componentType} is set.
 *
 * @param id the game item's primary key
 * @param name the item's display name
 * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
 * @param kind the {@code GameItemKind} name, e.g. {@code WEAPON}
 * @param componentType the {@code ShipComponentType} name, or {@code null} for a non-component
 * @param size the component size, or {@code null} when unknown or no component
 * @param grade the component grade, or {@code null} when unknown or no component
 */
public record InventoryGameItemReferenceDto(
    UUID id,
    String name,
    @Nullable String manufacturer,
    @Nullable String kind,
    @Nullable String componentType,
    @Nullable Integer size,
    @Nullable String grade) {

  /**
   * Builds a reference without component data, for items that are no ship component.
   *
   * @param id the game item's primary key
   * @param name the item's display name
   * @param manufacturer the manufacturer's display name, or {@code null} when unresolved
   * @param kind the {@code GameItemKind} name
   */
  public InventoryGameItemReferenceDto(
      UUID id, String name, @Nullable String manufacturer, @Nullable String kind) {
    this(id, name, manufacturer, kind, null, null, null);
  }
}
