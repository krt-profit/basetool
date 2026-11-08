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

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * A game-item reference that carries a ship component's type, size, grade and class (REQ-INV-061,
 * REQ-INV-062, REQ-INV-064).
 */
public interface ComponentItemReference {

  /**
   * The item's name, the key search and matching use.
   *
   * @return the name
   */
  String name();

  /**
   * The name a page shows for the item (REQ-P4K-023).
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  String displayName(@Nullable Locale locale);

  /**
   * The {@code ShipComponentType} name.
   *
   * @return the type, or {@code null} for a non-component
   */
  String componentType();

  /**
   * The component size.
   *
   * @return the size, or {@code null} when unknown or no component
   */
  Integer size();

  /**
   * The component grade.
   *
   * @return the grade, or {@code null} when unknown or no component
   */
  String grade();

  /**
   * The {@code ShipComponentClass} name.
   *
   * @return the class, or {@code null} when unknown or no component
   */
  String itemClass();
}
