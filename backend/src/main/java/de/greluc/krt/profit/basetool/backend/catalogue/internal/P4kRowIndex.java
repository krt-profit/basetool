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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The rows of one kind, loaded once per run and looked up in memory by the keys {@link
 * P4kRowMatcher} matches on (REQ-P4K-007): the P4K GUID, the SC Wiki UUID, the class name and the
 * name, both ignoring case. A lookup answers what the repository query of that key would answer on
 * the rows' current values: an entry whose row has since been given another key is skipped, and
 * {@link #add} indexes a row under the keys it holds after a write or on creation.
 *
 * @param <T> the row type
 */
public final class P4kRowIndex<T extends P4kKeyedRow> {

  private final Function<T, UUID> wikiUuid;
  private final Function<T, String> className;
  private final Function<T, String> name;
  private final Map<UUID, Set<T>> byP4kUuid = new HashMap<>();
  private final Map<UUID, Set<T>> byWikiUuid = new HashMap<>();
  private final Map<String, Set<T>> byClassName = new HashMap<>();
  private final Map<String, Set<T>> byName = new HashMap<>();

  /**
   * Indexes the rows of a kind.
   *
   * @param rows every row of the kind
   * @param wikiUuid reads a row's SC Wiki UUID
   * @param className reads a row's class name; {@code _ -> null} for a kind without one
   * @param name reads a row's name
   */
  public P4kRowIndex(
      @NotNull Collection<T> rows,
      @NotNull Function<T, UUID> wikiUuid,
      @NotNull Function<T, String> className,
      @NotNull Function<T, String> name) {
    this.wikiUuid = wikiUuid;
    this.className = className;
    this.name = name;
    rows.forEach(this::add);
  }

  /**
   * Indexes a row under the keys it holds now; call it after the run created a row or wrote one of
   * its keys. Indexing a row twice under the same key keeps one entry.
   *
   * @param row the row
   */
  public void add(@NotNull T row) {
    put(byP4kUuid, row.getP4kUuid(), row);
    put(byWikiUuid, wikiUuid.apply(row), row);
    put(byClassName, folded(className.apply(row)), row);
    put(byName, folded(name.apply(row)), row);
  }

  /**
   * Finds the row holding a P4K GUID.
   *
   * @param guid the GUID
   * @return the row, if one holds it
   */
  @NotNull
  public Optional<T> byP4kUuid(@NotNull UUID guid) {
    return current(byP4kUuid.get(guid), row -> guid.equals(row.getP4kUuid())).stream().findFirst();
  }

  /**
   * Finds the row holding a SC Wiki UUID.
   *
   * @param uuid the UUID
   * @return the row, if one holds it
   */
  @NotNull
  public Optional<T> byWikiUuid(@NotNull UUID uuid) {
    return current(byWikiUuid.get(uuid), row -> uuid.equals(wikiUuid.apply(row))).stream()
        .findFirst();
  }

  /**
   * Finds the rows of a class name, ignoring case.
   *
   * @param key the class name
   * @return the rows, possibly empty
   */
  @NotNull
  @Unmodifiable
  public List<T> byClassName(@NotNull String key) {
    String folded = key.toUpperCase(Locale.ROOT);
    return current(byClassName.get(folded), row -> folded.equals(folded(className.apply(row))));
  }

  /**
   * Finds the rows of a name, ignoring case.
   *
   * @param key the name
   * @return the rows, possibly empty
   */
  @NotNull
  @Unmodifiable
  public List<T> byName(@NotNull String key) {
    String folded = key.toUpperCase(Locale.ROOT);
    return current(byName.get(folded), row -> folded.equals(folded(name.apply(row))));
  }

  /**
   * Adds a row to a key's entries unless that very row is there already.
   *
   * @param map the key's map
   * @param key the key, or {@code null} when the row holds none
   * @param row the row
   * @param <K> the key type
   * @param <R> the row type
   */
  private static <K, R> void put(@NotNull Map<K, Set<R>> map, @Nullable K key, @NotNull R row) {
    if (key != null) {
      map.computeIfAbsent(key, _ -> Collections.newSetFromMap(new IdentityHashMap<>())).add(row);
    }
  }

  /**
   * Keeps the entries whose row still holds the key.
   *
   * @param rows the key's entries, or {@code null}
   * @param holds whether a row holds the key now
   * @return the rows that hold it
   */
  @NotNull
  @Unmodifiable
  private List<T> current(@Nullable Set<T> rows, @NotNull Predicate<T> holds) {
    return rows == null ? List.of() : rows.stream().filter(holds).toList();
  }

  /**
   * Folds a key for a case-insensitive comparison the way {@code upper(...)} does in the queries.
   *
   * @param key the key, or {@code null}
   * @return the upper-cased key, or {@code null}
   */
  @Nullable
  private static String folded(@Nullable String key) {
    return key == null ? null : key.toUpperCase(Locale.ROOT);
  }
}
