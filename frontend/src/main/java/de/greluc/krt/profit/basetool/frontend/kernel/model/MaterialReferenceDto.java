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
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Material Reference payload.
 *
 * @param id the material's id
 * @param name its name
 * @param quantityType its quantity type
 * @param nameDe its German name, or {@code null} (REQ-P4K-023)
 */
@DtoMirror
public record MaterialReferenceDto(
    UUID id, String name, @BackendEnumAsString String quantityType, @Nullable String nameDe) {

  /**
   * Creates a reference without a German name.
   *
   * @param id the material's id
   * @param name its name
   * @param quantityType its quantity type
   */
  public MaterialReferenceDto(UUID id, String name, String quantityType) {
    this(id, name, quantityType, null);
  }

  /**
   * Returns the name the page shows.
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String displayName(@Nullable Locale locale) {
    return LocalisedNames.display(name, nameDe, locale);
  }
}
