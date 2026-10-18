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

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The in-game class of a ship component, keyed by the SC Wiki item {@code class} value
 * (REQ-DATA-024, ADR-0283).
 *
 * <p>The constant names are the values the {@code game_item.item_class} CHECK constraint allows.
 */
@Getter
@RequiredArgsConstructor
public enum ShipComponentClass {

  /** Military-grade component. */
  MILITARY("Military"),

  /** Civilian component. */
  CIVILIAN("Civilian"),

  /** Industrial component. */
  INDUSTRIAL("Industrial"),

  /** Stealth component. */
  STEALTH("Stealth"),

  /** Competition component. */
  COMPETITION("Competition");

  private static final Map<String, ShipComponentClass> BY_WIKI_CLASS =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(
                  c -> c.getWikiClass().toLowerCase(Locale.ROOT), Function.identity()));

  /** The SC Wiki item {@code class} value, matched trimmed and case-insensitively. */
  @NotNull private final String wikiClass;

  /**
   * Resolves an SC Wiki class value to its component class.
   *
   * @param wikiClass the payload's {@code class} value, may be {@code null}
   * @return the class, or {@code null} when the value is absent, blank or unknown
   */
  public static @Nullable ShipComponentClass fromWikiClass(@Nullable String wikiClass) {
    if (wikiClass == null || wikiClass.isBlank()) {
      return null;
    }
    return BY_WIKI_CLASS.get(wikiClass.strip().toLowerCase(Locale.ROOT));
  }
}
