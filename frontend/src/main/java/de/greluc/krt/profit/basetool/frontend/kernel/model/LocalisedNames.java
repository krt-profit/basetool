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
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Picks the name a page shows for a catalogue row: the German name on a German page, the name
 * otherwise and whenever no German name exists (REQ-P4K-023).
 */
public final class LocalisedNames {

  /** Non-instantiable holder of static helpers. */
  private LocalisedNames() {}

  /**
   * Returns the name to show.
   *
   * @param name the row's name
   * @param nameDe the row's German name, or {@code null}
   * @param locale the page locale, or {@code null}
   * @return {@code nameDe} when the page language is German and it is not blank, else {@code name}
   */
  @Contract(pure = true)
  public static String display(
      @Nullable String name, @Nullable String nameDe, @Nullable Locale locale) {
    boolean german = locale != null && Locale.GERMAN.getLanguage().equals(locale.getLanguage());
    return german && nameDe != null && !nameDe.isBlank() ? nameDe : name;
  }
}
