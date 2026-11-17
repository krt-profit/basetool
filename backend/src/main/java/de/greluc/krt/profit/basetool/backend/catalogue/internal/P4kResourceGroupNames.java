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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kResourceGroupDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The English and German names the import writes for the resource groups of one catalogue
 * (REQ-P4K-025): the catalogue's name, except that of groups sharing a name, ignoring case, only
 * the one of the lowest GUID keeps it plain and every other takes its class name in readable form
 * in parentheses ("Hergestellte Produkte (Bulk Supplies)"), so the categories stay distinguishable.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kResourceGroupNames {

  /** A boundary inside a class name: a lower-case letter or digit before an upper-case letter. */
  private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

  /** The written English name per group GUID. */
  @NotNull private final Map<UUID, String> english;

  /** The written German name per group GUID. */
  @NotNull private final Map<UUID, String> german;

  /**
   * Derives the written names of a catalogue's groups.
   *
   * @param groups the catalogue's resource groups
   * @return the names
   */
  @NotNull
  static P4kResourceGroupNames of(@NotNull List<P4kResourceGroupDto> groups) {
    return new P4kResourceGroupNames(
        distinct(groups, P4kResourceGroupDto::displayName),
        distinct(groups, group -> P4kLocalisedText.deOf(group.name())));
  }

  /**
   * Returns the English name to write for a group.
   *
   * @param group a group of the catalogue
   * @return the name, or {@code null} when the group has neither a name nor a class name
   */
  @Nullable
  String english(@NotNull P4kResourceGroupDto group) {
    return written(english, group, group.displayName());
  }

  /**
   * Returns the German name to write for a group.
   *
   * @param group a group of the catalogue
   * @return the name, or {@code null} when the group has none
   */
  @Nullable
  String german(@NotNull P4kResourceGroupDto group) {
    return written(german, group, P4kLocalisedText.deOf(group.name()));
  }

  /**
   * Turns a class name into readable words: underscores become blanks and a lower-case letter or
   * digit followed by an upper-case one is split.
   *
   * @param className the class name, e.g. {@code Bulk_Supplies}
   * @return the readable form, e.g. {@code Bulk Supplies}
   */
  @NotNull
  static String readable(@NotNull String className) {
    return CAMEL_BOUNDARY
        .matcher(className.replace('_', ' '))
        .replaceAll(" ")
        .replaceAll(" +", " ")
        .strip();
  }

  /**
   * Looks a group's written name up, falling back to the catalogue's name for a group without a
   * GUID.
   *
   * @param names the written names per GUID
   * @param group the group
   * @param name the catalogue's name
   * @return the written name
   */
  @Nullable
  private static String written(
      @NotNull Map<UUID, String> names, @NotNull P4kResourceGroupDto group, @Nullable String name) {
    UUID guid = group.parsedGuid();
    return guid == null ? name : names.getOrDefault(guid, name);
  }

  /**
   * Gives every group of a repeated name but the one of the lowest GUID its readable class name in
   * parentheses.
   *
   * @param groups the groups
   * @param name reads a group's name in one language
   * @return the written name per group GUID; groups without a name or a GUID are left out
   */
  @NotNull
  private static Map<UUID, String> distinct(
      @NotNull List<P4kResourceGroupDto> groups,
      @NotNull Function<P4kResourceGroupDto, String> name) {
    Map<String, List<Named>> byName = new LinkedHashMap<>();
    for (P4kResourceGroupDto group : groups) {
      UUID guid = group.parsedGuid();
      String text = name.apply(group);
      if (guid != null && text != null && !text.isBlank()) {
        byName
            .computeIfAbsent(P4kMatchKeys.fold(text), _ -> new ArrayList<>())
            .add(new Named(guid, text, group.className()));
      }
    }
    Map<UUID, String> written = new HashMap<>();
    byName
        .values()
        .forEach(namesakes -> {
          List<Named> ordered =
              namesakes.stream()
                  .sorted(Comparator.comparing(named -> named.guid().toString()))
                  .toList();
          for (int index = 0; index < ordered.size(); index++) {
            Named named = ordered.get(index);
            String className = named.className();
            written.put(
                named.guid(),
                index == 0 || className == null || className.isBlank()
                    ? named.text()
                    : named.text().strip() + " (" + readable(className) + ")");
          }
        });
    return written;
  }

  /**
   * A group's name in one language.
   *
   * @param guid the group's GUID
   * @param text the catalogue's name
   * @param className the group's class name, or {@code null}
   */
  private record Named(@NotNull UUID guid, @NotNull String text, @Nullable String className) {}
}
