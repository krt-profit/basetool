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

package de.greluc.krt.profit.basetool.frontend.kernel.model;

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code GameItemReferenceDto}: a slim reference to an orderable
 * finished item, with a ship component's type, size, grade and class (REQ-INV-064).
 *
 * @param id the game item's id
 * @param name the item's display name
 * @param kind the item kind name (e.g. {@code WEAPON})
 * @param componentType the {@code ShipComponentType} name, or {@code null} for a non-component
 * @param size the component size, or {@code null} when unknown or no component
 * @param grade the component grade, or {@code null} when unknown or no component
 * @param itemClass the {@code ShipComponentClass} name, or {@code null} when unknown or no
 *     component
 */
@DtoMirror
public record GameItemReferenceDto(
    UUID id,
    String name,
    String kind,
    String componentType,
    Integer size,
    String grade,
    String itemClass)
    implements ComponentItemReference {

  /**
   * Builds a reference without component data, for items that are no ship component.
   *
   * @param id the game item's id
   * @param name the item's display name
   * @param kind the item kind name
   */
  public GameItemReferenceDto(UUID id, String name, String kind) {
    this(id, name, kind, null, null, null, null);
  }
}
