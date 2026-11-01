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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The entity types the quick access searches through the backend (REQ-UI-033).
 *
 * <p>The {@link #key()} is the wire value of the search's {@code kind} parameter, of every hit and
 * of the {@code kind} metric label, so the set is bounded by this enum.
 */
public enum QuickAccessSearchKind {

  /** Missions, opening the mission detail. */
  MISSION("mission", "clipboard-check"),

  /** Operations, opening the operation detail. */
  OPERATION("operation", "clipboard-check"),

  /** Materials of the visible catalogue, opening the material detail. */
  MATERIAL("material", "swap"),

  /** Blueprints of the availability overview, opening it filtered to the product. */
  BLUEPRINT("blueprint", "database"),

  /** Ship types of the unit hangar, opening the hangar filtered to the type. */
  SHIP("ship", "swap"),

  /** Org units of the org chart, opening it with the unit highlighted. */
  ORG_UNIT("org_unit", "users"),

  /** Job orders by their number, opening the order. */
  JOB_ORDER("job_order", "clipboard-check", 1),

  /** Members shown on the org chart, opening it with the person highlighted. */
  MEMBER("member", "user");

  private static final int DEFAULT_MIN_TERM_LENGTH = 2;

  private final String key;
  private final String icon;
  private final int minTermLength;

  QuickAccessSearchKind(@NotNull String key, @NotNull String icon) {
    this(key, icon, DEFAULT_MIN_TERM_LENGTH);
  }

  QuickAccessSearchKind(@NotNull String key, @NotNull String icon, int minTermLength) {
    this.key = key;
    this.icon = icon;
    this.minTermLength = minTermLength;
  }

  /**
   * The lower-case wire value.
   *
   * @return the key, e.g. {@code mission}
   */
  public @NotNull String key() {
    return key;
  }

  /**
   * The sprite symbol of this type's hits, without its {@code krt-icon-} prefix.
   *
   * @return the icon name
   */
  public @NotNull String icon() {
    return icon;
  }

  /**
   * The shortest term this type is searched with.
   *
   * @return the character count, one for a number and two otherwise
   */
  public int minTermLength() {
    return minTermLength;
  }

  /**
   * The message key of this type's name, shown as the hit's breadcrumb.
   *
   * @return the key {@code palette.kind.<key>}
   */
  public @NotNull String labelKey() {
    return "palette.kind." + key;
  }

  /**
   * Resolves a wire value.
   *
   * @param value the value of a {@code kind} parameter, possibly {@code null}
   * @return the type, or empty for a value no type carries
   */
  public static @NotNull Optional<QuickAccessSearchKind> fromKey(@Nullable String value) {
    if (value == null) {
      return Optional.empty();
    }
    String wanted = value.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(k -> k.key.equals(wanted)).findFirst();
  }
}
