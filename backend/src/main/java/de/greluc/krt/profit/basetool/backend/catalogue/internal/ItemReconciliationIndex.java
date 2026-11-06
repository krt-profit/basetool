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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Matches exact source identities only when both current catalogue censuses admit one candidate.
 */
public final class ItemReconciliationIndex {

  private final Map<String, UUID> uexIdBySlug = new HashMap<>();
  private final Map<String, UUID> uexIdByName = new HashMap<>();
  private final Set<String> eligibleUexNames = new HashSet<>();
  private final Set<String> ambiguousUexNames = new HashSet<>();
  private final Set<UUID> consumed = new HashSet<>();
  private final Map<String, Set<UUID>> wikiIdsByName = new HashMap<>();
  private final Map<String, Set<UUID>> wikiIdsBySlug = new HashMap<>();
  private boolean complete;

  /**
   * Indexes eligible uuid-less rows while counting ambiguities across every UEX source identity.
   *
   * @param eligible uuid-less UEX candidates
   * @param allUexRows every UEX source row, including already linked variants
   */
  ItemReconciliationIndex(@NotNull List<GameItem> eligible, @NotNull List<GameItem> allUexRows) {
    Map<UUID, GameItem> sources = new LinkedHashMap<>();
    for (GameItem row : allUexRows) {
      sources.put(row.getId(), row);
    }
    Set<UUID> eligibleIds = new HashSet<>();
    for (GameItem row : eligible) {
      sources.put(row.getId(), row);
      eligibleIds.add(row.getId());
      String sourceName = StringUtils.hasText(row.getUexName()) ? row.getUexName() : row.getName();
      String key = UexIdentityCensus.nameKey(sourceName);
      if (key != null) {
        eligibleUexNames.add(key);
      }
    }
    Set<String> ambiguousSlugs = new HashSet<>();
    for (GameItem row : sources.values()) {
      String sourceName = StringUtils.hasText(row.getUexName()) ? row.getUexName() : row.getName();
      indexUnique(
          uexIdByName, ambiguousUexNames, UexIdentityCensus.nameKey(sourceName), row.getId());
      indexUnique(
          uexIdBySlug, ambiguousSlugs, UexIdentityCensus.nameKey(row.getUexSlug()), row.getId());
    }
    uexIdByName.values().removeIf(id -> !eligibleIds.contains(id));
    uexIdBySlug.values().removeIf(id -> !eligibleIds.contains(id));
  }

  /**
   * Indexes the full residual census and all kind pools before any name may be claimed.
   *
   * @param residual the unfiltered player-relevant pool
   * @param residualComplete whether that pool's identities were completely enumerated
   * @param passes the fetched kind pools, including the residual pool
   */
  void indexWikiCandidates(
      @NotNull Collection<ScWikiItemDto> residual,
      boolean residualComplete,
      @NotNull Collection<List<ScWikiItemDto>> passes) {
    Set<UUID> residualIds = new HashSet<>();
    for (ScWikiItemDto row : residual) {
      if (row.uuid() != null) {
        residualIds.add(row.uuid());
      }
    }
    complete = residualComplete && !residual.isEmpty();
    for (List<ScWikiItemDto> pass : passes) {
      for (ScWikiItemDto row : pass) {
        if (row.uuid() == null) {
          continue;
        }
        boolean enumeratedByResidual = residualIds.contains(row.uuid());
        complete = complete && enumeratedByResidual;
        String name = UexIdentityCensus.nameKey(row.name());
        if (name != null) {
          wikiIdsByName.computeIfAbsent(name, _ -> new HashSet<>()).add(row.uuid());
        }
        String slug = UexIdentityCensus.nameKey(row.slug());
        if (slug != null) {
          wikiIdsBySlug.computeIfAbsent(slug, _ -> new HashSet<>()).add(row.uuid());
        }
      }
    }
  }

  /**
   * Resolves a name unique on both sources, refusing conflicting slug evidence and consumed rows.
   *
   * @param item Wiki source identity
   * @return the eligible candidate, a bounded ambiguity reason, or neither when no match is
   *     justified
   */
  @NotNull
  Match match(@NotNull ScWikiItemDto item) {
    String name = UexIdentityCensus.nameKey(item.name());
    if (!complete || name == null || !eligibleUexNames.contains(name)) {
      return new Match(null, null);
    }
    int wikiMatches = wikiIdsByName.getOrDefault(name, Set.of()).size();
    if (wikiMatches > 1) {
      return new Match(null, Ambiguity.WIKI_NAME);
    }
    if (wikiMatches == 0) {
      return new Match(null, null);
    }
    if (ambiguousUexNames.contains(name)) {
      return new Match(null, Ambiguity.UEX_NAME);
    }
    UUID byName = uexIdByName.get(name);
    if (byName == null || consumed.contains(byName)) {
      return new Match(null, null);
    }
    String slug = UexIdentityCensus.nameKey(item.slug());
    if (slug != null) {
      UUID bySlug =
          wikiIdsBySlug.getOrDefault(slug, Set.of()).size() == 1 ? uexIdBySlug.get(slug) : null;
      if (bySlug != null && !bySlug.equals(byName)) {
        return new Match(null, Ambiguity.CONFLICTING_SLUG);
      }
    }
    return new Match(byName, null);
  }

  /**
   * Separates a safe match from an ambiguous candidate that requires catalogue curation.
   *
   * @param candidateId eligible local UEX identity, absent unless the match is unique
   * @param ambiguity bounded reason a real name candidate could not be claimed
   */
  record Match(@Nullable UUID candidateId, @Nullable Ambiguity ambiguity) {}

  /** Names the source evidence that prevented a candidate from being uniquely claimed. */
  enum Ambiguity {
    /** Several Wiki assets have the candidate's exact source name. */
    WIKI_NAME,
    /** Several UEX identities have the candidate's exact source name. */
    UEX_NAME,
    /** Unique name and slug claims identify different UEX rows. */
    CONFLICTING_SLUG
  }

  /**
   * Reserves an identity only after its isolated database transaction committed.
   *
   * @param id successfully reconciled UEX row
   */
  void markConsumed(@Nullable UUID id) {
    if (id != null) {
      consumed.add(id);
    }
  }

  private static void indexUnique(
      Map<String, UUID> index, Set<String> ambiguous, @Nullable String key, UUID id) {
    if (key == null || ambiguous.contains(key)) {
      return;
    }
    UUID existing = index.putIfAbsent(key, id);
    if (existing != null && !existing.equals(id)) {
      index.remove(key);
      ambiguous.add(key);
    }
  }
}
