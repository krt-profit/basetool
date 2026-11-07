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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocalisedNames;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code PurchasingRefDto}; must match it field for field.
 *
 * @param id the id
 * @param name the name
 * @param nameDe the German name of a catalogue entry, or {@code null} (REQ-P4K-023)
 */
@DtoMirror
public record PurchasingRefDto(UUID id, String name, @Nullable String nameDe) {

  /**
   * Builds a reference without a German name.
   *
   * @param id the id
   * @param name the name
   */
  public PurchasingRefDto(UUID id, String name) {
    this(id, name, null);
  }

  /**
   * Returns the name the page shows (REQ-P4K-023).
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String displayName(@Nullable Locale locale) {
    return LocalisedNames.display(name, nameDe, locale);
  }
}
