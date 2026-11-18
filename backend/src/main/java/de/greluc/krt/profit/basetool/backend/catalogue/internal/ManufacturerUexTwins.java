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

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.web.util.HtmlUtils;

/**
 * Finds the manufacturer rows only UEX created beside the row of the same brand that holds a P4K
 * GUID (REQ-DATA-045). Such a twin holds a UEX company and no P4K GUID, SC Wiki UUID, Wiki identity
 * or P4K alias, and its folded name names exactly one row holding a P4K GUID: that row's name or
 * the Wiki name of one of its Wiki identities.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class ManufacturerUexTwins {

  /** The apostrophe entity UEX sends in names, which {@link HtmlUtils} does not decode. */
  private static final String APOSTROPHE_ENTITY = "&apos;";

  /** A run of characters that are neither letters nor digits. */
  private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

  /** The rows holding a P4K GUID by their folded name. */
  @NotNull private final Map<String, Set<UUID>> byName;

  /** The rows holding a P4K GUID by the folded Wiki names of their Wiki identities. */
  @NotNull private final Map<String, Set<UUID>> byWikiName;

  /** The rows that may be a twin, ordered by UEX company id. */
  @NotNull private final List<Row> candidates;

  /**
   * Indexes the rows holding a P4K GUID by name and Wiki name and collects the rows that may be a
   * twin.
   *
   * @param rows every manufacturer row
   * @param wikiNames the Wiki name of every Wiki identity
   * @return the rule over these rows
   */
  @NotNull
  static ManufacturerUexTwins of(
      @NotNull Collection<Row> rows, @NotNull Collection<WikiName> wikiNames) {
    Set<UUID> known = new HashSet<>();
    Map<String, Set<UUID>> byName = new HashMap<>();
    for (Row row : rows) {
      if (row.p4kUuid() != null) {
        known.add(row.id());
        index(byName, row.name(), row.id());
      }
    }
    Set<UUID> withWikiIdentity = new HashSet<>();
    Map<String, Set<UUID>> byWikiName = new HashMap<>();
    for (WikiName wikiName : wikiNames) {
      withWikiIdentity.add(wikiName.manufacturerId());
      if (known.contains(wikiName.manufacturerId())) {
        index(byWikiName, wikiName.name(), wikiName.manufacturerId());
      }
    }
    List<Row> candidates =
        rows.stream()
            .filter(row -> row.mayBeTwin() && !withWikiIdentity.contains(row.id()))
            .sorted(Comparator.comparing(Row::uexCompanyId, Comparator.nullsLast(Integer::compare)))
            .toList();
    return new ManufacturerUexTwins(byName, byWikiName, candidates);
  }

  /**
   * Folds a name for the rule: HTML entities decoded, {@code &} read as "and", every run of
   * characters that are neither letters nor digits one blank, case ignored.
   *
   * @param name the name as stored or served
   * @return the folded name; blank when the name holds no letter or digit
   */
  @NotNull
  static String fold(@NotNull String name) {
    String decoded = HtmlUtils.htmlUnescape(name.replace(APOSTROPHE_ENTITY, "'"));
    String words = SEPARATORS.matcher(decoded.replace("&", " and ")).replaceAll(" ");
    return words.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Finds the single row holding a P4K GUID that a name names.
   *
   * @param name a manufacturer or UEX company name
   * @return the named row and the evidence, or empty when the name names no row or several
   */
  @NotNull
  Optional<Named> named(@NotNull String name) {
    String key = fold(name);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    Set<UUID> byRowName = byName.getOrDefault(key, Set.of());
    Set<UUID> rows = new HashSet<>(byRowName);
    rows.addAll(byWikiName.getOrDefault(key, Set.of()));
    if (rows.size() != 1) {
      return Optional.empty();
    }
    Evidence evidence = byRowName.isEmpty() ? Evidence.WIKI_NAME : Evidence.NAME;
    return Optional.of(new Named(rows.iterator().next(), evidence));
  }

  /**
   * Lists every twin with the row it duplicates.
   *
   * @return the twins, ordered by UEX company id
   */
  @NotNull
  @Unmodifiable
  List<Twin> twins() {
    return candidates.stream().map(this::twin).flatMap(Optional::stream).toList();
  }

  /**
   * Checks whether one row is a twin.
   *
   * @param id the row's id
   * @return the twin, or empty when the row is none
   */
  @NotNull
  Optional<Twin> twinOf(@NotNull UUID id) {
    return candidates.stream().filter(row -> row.id().equals(id)).findFirst().flatMap(this::twin);
  }

  @NotNull
  private Optional<Twin> twin(@NotNull Row row) {
    Integer company = row.uexCompanyId();
    if (company == null) {
      return Optional.empty();
    }
    return named(row.name())
        .map(named -> new Twin(row.id(), named.survivorId(), company, named.evidence()));
  }

  private static void index(Map<String, Set<UUID>> index, String name, UUID id) {
    String key = fold(name);
    if (!key.isEmpty()) {
      index.computeIfAbsent(key, _ -> new HashSet<>()).add(id);
    }
  }

  /** What names the row a twin duplicates. */
  enum Evidence {
    /** The row's own name. */
    NAME,
    /** The Wiki name of one of the row's Wiki identities. */
    WIKI_NAME
  }

  /**
   * One manufacturer row as the rule reads it.
   *
   * @param id the row's id
   * @param name the row's name
   * @param p4kUuid the P4K GUID the row holds, or {@code null}
   * @param scwikiUuid the SC Wiki UUID the row holds, or {@code null}
   * @param uexCompanyId the UEX company the row holds, or {@code null}
   * @param p4kAlias whether a reviewed P4K alias names the row
   */
  record Row(
      @NotNull UUID id,
      @NotNull String name,
      @Nullable UUID p4kUuid,
      @Nullable UUID scwikiUuid,
      @Nullable Integer uexCompanyId,
      boolean p4kAlias) {

    /**
     * Checks the row's own identities: a twin holds a UEX company and no game or Wiki identity.
     *
     * @return whether the row may be a twin, its Wiki identities aside
     */
    boolean mayBeTwin() {
      return uexCompanyId != null && p4kUuid == null && scwikiUuid == null && !p4kAlias;
    }
  }

  /**
   * The Wiki name of one Wiki identity.
   *
   * @param manufacturerId the row the identity belongs to
   * @param name the Wiki's name for the identity
   */
  record WikiName(@NotNull UUID manufacturerId, @NotNull String name) {}

  /**
   * The row holding a P4K GUID that a name names.
   *
   * @param survivorId the named row
   * @param evidence what named it
   */
  record Named(@NotNull UUID survivorId, @NotNull Evidence evidence) {}

  /**
   * A twin and the row it duplicates.
   *
   * @param retiredId the twin
   * @param survivorId the row holding the P4K GUID
   * @param uexCompanyId the UEX company the twin holds
   * @param evidence what named the survivor
   */
  record Twin(
      @NotNull UUID retiredId,
      @NotNull UUID survivorId,
      int uexCompanyId,
      @NotNull Evidence evidence) {}
}
