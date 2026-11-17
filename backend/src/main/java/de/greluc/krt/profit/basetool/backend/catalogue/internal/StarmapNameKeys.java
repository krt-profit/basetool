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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The other names a UEX location row may match a starmap object by (REQ-P4K-035): its name without
 * a trailing {@code (<star system>)} or {@code (<star system> system)}, its UEX nickname, its name
 * with the letter {@code o} read as the digit {@code 0} where the name holds a digit, and a name
 * that is another one plus whole trailing words.
 */
final class StarmapNameKeys {

  /** A name ending in a parenthesised group: the rest, then the group's content. */
  private static final Pattern TRAILING_GROUP = Pattern.compile("^(.+?)\\s*\\(([^()]*)\\)\\s*$");

  /** The word UEX puts after a star system's name in such a group, {@code (Stanton system)}. */
  private static final String SYSTEM_WORD = " system";

  /** A digit. */
  private static final Pattern DIGIT = Pattern.compile("\\p{N}");

  /** Non-instantiable holder of static helpers. */
  private StarmapNameKeys() {}

  /**
   * Lists the names a row is matched by, in order: its UEX name, the name without its star-system
   * group, its nickname; a name that normalises like an earlier one is left out.
   *
   * @param row the row
   * @return the names, empty when the row has none
   */
  @NotNull
  @Unmodifiable
  static List<String> names(@NotNull StarmapPlaceRow row) {
    Set<String> seen = new HashSet<>();
    List<String> names = new ArrayList<>();
    Stream.of(row.name(), withoutSystemGroup(row.name(), row.starSystem()), row.nickname())
        .forEach(name -> {
          String normalised = StarmapNames.normalise(name);
          if (name != null && normalised != null && seen.add(normalised)) {
            names.add(name);
          }
        });
    return List.copyOf(names);
  }

  /**
   * Strips a trailing {@code (<star system>)} or {@code (<star system> system)} from a name, as UEX
   * names a jump-point station by the system it sits in ({@code Pyro Gateway (Stanton)}).
   *
   * @param name the name, or {@code null}
   * @param starSystem the name of the row's star system, or {@code null}
   * @return the name without the group, or {@code null} when it ends in no such group
   */
  @Contract(pure = true)
  @Nullable
  static String withoutSystemGroup(@Nullable String name, @Nullable String starSystem) {
    String system = StarmapNames.normalise(starSystem);
    if (name == null || system == null) {
      return null;
    }
    Matcher matcher = TRAILING_GROUP.matcher(name);
    if (!matcher.matches()) {
      return null;
    }
    String group = StarmapNames.normalise(matcher.group(2));
    return system.equals(group) || (system + SYSTEM_WORD).equals(group) ? matcher.group(1) : null;
  }

  /**
   * Folds a name that holds a digit: compact, with every {@code o} read as {@code 0}, as UEX and
   * the game spell mining-base codes apart ({@code #4RO-NVX}, {@code #4R0-NVX}).
   *
   * @param name the name, or {@code null}
   * @return the folded name, or {@code null} when the name holds no digit
   */
  @Contract(pure = true)
  @Nullable
  static String folded(@Nullable String name) {
    String compact = StarmapNames.compact(name);
    return compact == null || !DIGIT.matcher(compact).find() ? null : compact.replace('o', '0');
  }

  /**
   * Whether one normalised name is the other plus whole trailing words, as in {@code Deakins
   * Research} and {@code Deakins Research Outpost}.
   *
   * @param one a normalised name
   * @param other another normalised name
   * @return {@code true} when either starts with the other followed by a space
   */
  @Contract(pure = true)
  static boolean extendsByWords(@NotNull String one, @NotNull String other) {
    return one.startsWith(other + " ") || other.startsWith(one + " ");
  }
}
