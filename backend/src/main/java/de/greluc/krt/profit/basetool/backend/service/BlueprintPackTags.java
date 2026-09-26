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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Removes the class tag a community localisation pack writes into an item name, so a log name like
 * {@code Sth/2/C Cirrus} can be matched to the catalogue's {@code Cirrus} (REQ-INV-050).
 *
 * <p>A closed list of the shapes observed in real packs; the vanilla game writes none of them.
 */
public final class BlueprintPackTags {

  private static final String CLASSES = "civ|mil|ind|sth|cmp";

  private static final List<Pattern> PREFIXES =
      List.of(
          Pattern.compile(
              "^(?:" + CLASSES + ")/\\d{1,2}/[a-d]\\s+(?=\\S)", Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              "^\\[(?:" + CLASSES + "|brk)-s\\d{1,2}-[a-d]]\\s+(?=\\S)", Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              "^\\[(?:e|p|b|d|ir|em|cs)-s\\d{1,2}]\\s+(?=\\S)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("^\\[(?:e|p|b|d|s\\d{1,2})]\\s+(?=\\S)", Pattern.CASE_INSENSITIVE));

  private static final List<Pattern> SUFFIXES =
      List.of(
          Pattern.compile(
              "(?<=\\S)\\s+\\((?:" + CLASSES + ")/\\d{1,2}/[a-d]\\)$", Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              "(?<=\\S)\\s+\\(s\\d{1,2} [a-d]"
                  + " (?:civilian|military|industrial|stealth|competition)\\)$",
              Pattern.CASE_INSENSITIVE));

  private BlueprintPackTags() {}

  /**
   * Returns {@code name} without its pack class tag — at most one leading and one trailing tag — or
   * {@code null} when it carries none.
   *
   * @param name the external product name; may be {@code null}
   * @return the untagged name, or {@code null} when nothing was removed
   */
  @Nullable
  public static String strip(@Nullable String name) {
    if (name == null) {
      return null;
    }
    String trimmed = name.trim();
    String stripped = removeFirst(removeFirst(trimmed, PREFIXES), SUFFIXES).trim();
    return stripped.isEmpty() || stripped.equals(trimmed) ? null : stripped;
  }

  @NotNull
  private static String removeFirst(@NotNull String value, @NotNull List<Pattern> patterns) {
    for (Pattern pattern : patterns) {
      String replaced = pattern.matcher(value).replaceFirst("");
      if (!replaced.equals(value)) {
        return replaced;
      }
    }
    return value;
  }
}
