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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Picks the name a page shows for a catalogue row under the game-text language policy
 * (REQ-P4K-023): every page shows the English name.
 */
public final class LocalisedNames {

  /** The game-text language every page shows (REQ-P4K-023). */
  static final GameTextLanguage SHOWN = GameTextLanguage.ENGLISH;

  /** Non-instantiable holder of static helpers. */
  private LocalisedNames() {}

  /**
   * Returns the name to show under the policy {@link #SHOWN}.
   *
   * @param name the row's name
   * @param nameDe the row's German name, or {@code null}
   * @param locale the page locale, or {@code null}
   * @return the name {@link #display(String, String, Locale, GameTextLanguage)} picks
   */
  @Contract(pure = true)
  public static String display(
      @Nullable String name, @Nullable String nameDe, @Nullable Locale locale) {
    return display(name, nameDe, locale, SHOWN);
  }

  /**
   * Returns the name to show under a given game-text language.
   *
   * @param name the row's name
   * @param nameDe the row's German name, or {@code null}
   * @param locale the page locale, or {@code null}
   * @param language the game-text language
   * @return {@code name} under {@link GameTextLanguage#ENGLISH}; under {@link
   *     GameTextLanguage#PAGE_LANGUAGE} {@code nameDe} when the page language is German and it is
   *     not blank, else {@code name}
   */
  @Contract(pure = true)
  static String display(
      @Nullable String name,
      @Nullable String nameDe,
      @Nullable Locale locale,
      @NotNull GameTextLanguage language) {
    return switch (language) {
      case ENGLISH -> name;
      case PAGE_LANGUAGE -> {
        boolean german = locale != null && Locale.GERMAN.getLanguage().equals(locale.getLanguage());
        yield german && nameDe != null && !nameDe.isBlank() ? nameDe : name;
      }
    };
  }

  /** The language a page shows the game's texts in. */
  enum GameTextLanguage {
    /** Every page shows the English game text, whatever its own language. */
    ENGLISH,
    /** A German page shows the German game text where one exists, every other page English. */
    PAGE_LANGUAGE
  }
}
