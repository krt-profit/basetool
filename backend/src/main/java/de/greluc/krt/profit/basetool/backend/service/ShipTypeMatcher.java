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

import de.greluc.krt.profit.basetool.backend.model.ShipType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The tolerant {@code ShipType} resolver behind the hangar import: it folds a snapshot of the
 * {@code ship_type} table into a multi-key lookup index once, then resolves an upload entry's name
 * (and optional slug) against it through five progressively-looser stages. Extracted verbatim from
 * {@code HangarImportService} (audit L-tier import-engine split, #16) so the service keeps the
 * transactional orchestration while this holds the pure, in-memory matching that has no repository,
 * transaction or security concern.
 *
 * <p>The five stages, in order (see {@link #resolve}): (1) exact case-insensitive {@code
 * ShipType.name}; (2) normalised (lowercased, non-alphanumeric stripped) name; (3) upload-tokens ⊆
 * uex-tokens, uniquely; (4) uex-tokens ⊆ upload-tokens, uniquely; (5) slug fallback (source slug
 * folded and matched against {@code uexSlug} then {@code scwikiSlug}). Stages 3 and 4 require a
 * <em>unique</em> candidate — ambiguity leaves the entry unresolved on purpose rather than guessing
 * a variant. All stages read the pre-built {@link ShipTypeIndex}, so per-entry resolution is
 * in-memory with no N+1 database round-trips.
 *
 * <p>Stateless and static-only; {@link #normalizeForMatching} is public because the import's
 * custom-name echo heuristic reuses the exact same alphanumeric folding.
 */
public final class ShipTypeMatcher {

  /** Splits a name into alphanumeric tokens. Pre-compiled so the regex is reused per call. */
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

  /** Strips everything outside {@code [a-z0-9]} for the normalised match form. */
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

  private ShipTypeMatcher() {}

  /**
   * Loads every {@code ShipType} form into the multi-key lookup index in a single pass: exact
   * case-insensitive key, normalised key, an alphanumeric token set used by the token-subset
   * stages, and the normalised UEX / SC Wiki slug keys used by the slug-fallback stage. The hash
   * maps use {@code putIfAbsent} so that if two ship types collapse to the same key (very unlikely
   * — the {@code name} column has a unique constraint and slugs are effectively unique) the first
   * one encountered wins deterministically.
   *
   * @param shipTypes a snapshot of the {@code ship_type} table (typically {@code
   *     shipTypeRepository.findAll()})
   * @return the populated lookup index
   */
  public static @NotNull ShipTypeIndex buildIndex(@NotNull List<ShipType> shipTypes) {
    ShipTypeIndex idx = new ShipTypeIndex();
    for (ShipType st : shipTypes) {
      String name = st.getName();
      if (name == null || name.isBlank()) {
        continue;
      }
      idx.byExactLower.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), st);
      String normalized = normalizeForMatching(name);
      if (!normalized.isEmpty()) {
        idx.byNormalized.putIfAbsent(normalized, st);
      }
      Set<String> tokens = tokenize(name);
      if (!tokens.isEmpty()) {
        idx.tokenized.add(new TokenView(st, tokens));
      }
      String uexSlug = normalizeForMatching(st.getUexSlug());
      if (!uexSlug.isEmpty()) {
        idx.byUexSlug.putIfAbsent(uexSlug, st);
      }
      String scwikiSlug = normalizeForMatching(st.getScwikiSlug());
      if (!scwikiSlug.isEmpty()) {
        idx.byScwikiSlug.putIfAbsent(scwikiSlug, st);
      }
    }
    return idx;
  }

  /**
   * Resolves an upload entry against the pre-built index: first by name through the four tolerant
   * name stages, then — only if those all miss or stay ambiguous — by the optional slug fallback.
   * The slug is supplied for StarJump FleetViewer and Fleetyards entries and {@code null} for the
   * Fleetview / HangarXPLOR formats, for which this behaves exactly like the name-only resolution.
   *
   * @param index lookup index produced by {@link #buildIndex(List)}
   * @param rawName trimmed entry name from the upload
   * @param slug source-provided ship slug for the fallback stage, or {@code null}
   * @return the matching {@code ShipType} or {@code null} if neither name nor slug produces a hit
   */
  public static @Nullable ShipType resolve(
      @NotNull ShipTypeIndex index, @NotNull String rawName, @Nullable String slug) {
    ShipType byName = resolveByName(index, rawName);
    if (byName != null) {
      return byName;
    }
    return resolveBySlug(index, slug);
  }

  /**
   * Slug-fallback stage (StarJump FleetViewer and Fleetyards). Folds the source slug with the same
   * alphanumeric normalisation used for names and matches it against {@code ShipType.uexSlug}
   * first, then {@code ShipType.scwikiSlug}. Exact-equality only — these slug schemes diverge from
   * UEX's enough that fuzzy slug matching would be unsafe. A {@code null}/blank slug or an empty
   * normalised form short-circuits to {@code null} so the slugless formats (Fleetview, HangarXPLOR)
   * skip the stage cleanly.
   *
   * @param index lookup index produced by {@link #buildIndex(List)}
   * @param slug source-provided ship slug, or {@code null}
   * @return the matching {@code ShipType} or {@code null} if no slug index entry matches
   */
  private static @Nullable ShipType resolveBySlug(
      @NotNull ShipTypeIndex index, @Nullable String slug) {
    String normSlug = normalizeForMatching(slug);
    if (normSlug.isEmpty()) {
      return null;
    }
    ShipType match = index.byUexSlug.get(normSlug);
    if (match != null) {
      return match;
    }
    return index.byScwikiSlug.get(normSlug);
  }

  /**
   * Four-stage name resolution against the pre-built index. The earlier stages are deterministic
   * one-shot map lookups; the later (token-subset) stages scan the tokenised view and require the
   * candidate to be unique so an ambiguous abbreviation cannot silently map to the wrong variant.
   *
   * @param index lookup index produced by {@link #buildIndex(List)}
   * @param rawName trimmed entry name from the upload
   * @return the matching {@code ShipType} or {@code null} if no stage produces a unique hit
   */
  private static @Nullable ShipType resolveByName(
      @NotNull ShipTypeIndex index, @NotNull String rawName) {
    // Stage 1: exact case-insensitive
    ShipType match = index.byExactLower.get(rawName.toLowerCase(Locale.ROOT));
    if (match != null) {
      return match;
    }

    // Stage 2: normalised (lowercase + non-alphanumeric stripped)
    String normalized = normalizeForMatching(rawName);
    if (!normalized.isEmpty()) {
      match = index.byNormalized.get(normalized);
      if (match != null) {
        return match;
      }
    }

    // Stages 3 + 4: token-subset matches, both directions, each requiring uniqueness.
    Set<String> fvTokens = tokenize(rawName);
    if (fvTokens.isEmpty()) {
      return null;
    }

    // Stage 3: fv ⊆ uex — fv is an abbreviation of a uex name. Skip on ambiguity, do NOT fall
    // through to stage 4 — multiple uex candidates contain the fv tokens means the fv string is
    // genuinely ambiguous (e.g. "F7C-M Super Hornet" between Mk I / Heartseeker / Mk II).
    ShipType uniqueSubset = findUniqueWhereFvSubsetOfUex(index.tokenized, fvTokens);
    if (uniqueSubset != null) {
      return uniqueSubset;
    }
    if (anyFvSubsetOfUex(index.tokenized, fvTokens)) {
      return null;
    }

    // Stage 4: uex ⊆ fv — uex name is a shorter canonical form of the longer fv export name.
    return findUniqueWhereUexSubsetOfFv(index.tokenized, fvTokens);
  }

  /**
   * Returns the single {@code ShipType} whose token set contains the entire {@code fvTokens} set,
   * or {@code null} if zero or more than one candidate satisfies the predicate.
   *
   * @param tokenized tokenised view of every ship type
   * @param fvTokens token set parsed from the upload entry name
   * @return the unique {@code ShipType} or {@code null} (no match or ambiguous)
   */
  private static @Nullable ShipType findUniqueWhereFvSubsetOfUex(
      @NotNull List<TokenView> tokenized, @NotNull Set<String> fvTokens) {
    ShipType found = null;
    for (TokenView tv : tokenized) {
      if (tv.tokens.containsAll(fvTokens)) {
        if (found != null) {
          return null;
        }
        found = tv.shipType;
      }
    }
    return found;
  }

  /**
   * Cheap pre-check used to short-circuit Stage 4 when Stage 3 already saw multiple candidates.
   * Returns {@code true} as soon as any ship-type token set contains the fv token set — i.e. the
   * upload entry is a strict abbreviation of at least one canonical name.
   *
   * @param tokenized tokenised view of every ship type
   * @param fvTokens token set parsed from the upload entry name
   * @return {@code true} iff at least one candidate satisfies fv ⊆ uex
   */
  private static boolean anyFvSubsetOfUex(
      @NotNull List<TokenView> tokenized, @NotNull Set<String> fvTokens) {
    for (TokenView tv : tokenized) {
      if (tv.tokens.containsAll(fvTokens)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the single {@code ShipType} whose token set is contained in {@code fvTokens}, or {@code
   * null} if zero or more than one candidate satisfies the predicate. Reverse-direction counterpart
   * of {@link #findUniqueWhereFvSubsetOfUex(List, Set)} — handles upload names that are
   * <em>longer</em> than UEX's canonical short form (e.g. {@code "Ursa Rover"} → {@code "Ursa"}).
   *
   * @param tokenized tokenised view of every ship type
   * @param fvTokens token set parsed from the upload entry name
   * @return the unique {@code ShipType} or {@code null} (no match or ambiguous)
   */
  private static @Nullable ShipType findUniqueWhereUexSubsetOfFv(
      @NotNull List<TokenView> tokenized, @NotNull Set<String> fvTokens) {
    ShipType found = null;
    for (TokenView tv : tokenized) {
      if (!tv.tokens.isEmpty() && fvTokens.containsAll(tv.tokens)) {
        if (found != null) {
          return null;
        }
        found = tv.shipType;
      }
    }
    return found;
  }

  /**
   * Folds a ship name to a case-insensitive, punctuation-free comparison form. {@code "L-21 Wolf"}
   * and {@code "L21 Wolf"} both collapse to {@code "l21wolf"}; {@code "Cyclone-AA"} and {@code
   * "Cyclone AA"} both collapse to {@code "cycloneaa"}. ASCII-only by design — Star Citizen ship
   * names contain no diacritics, so we deliberately strip anything outside {@code [a-z0-9]}. Public
   * so the import's custom-name echo heuristic can reuse the identical folding.
   *
   * @param name raw name (nullable)
   * @return the normalised form (never null; empty string for null/empty input)
   */
  public static @NotNull String normalizeForMatching(@Nullable String name) {
    if (name == null) {
      return "";
    }
    return NON_ALNUM.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  /**
   * Splits a ship name into an alphanumeric token set. Used by the token-subset match stages. Empty
   * tokens (from leading/trailing/multiple separators) are dropped.
   *
   * @param name raw name (nullable)
   * @return the token set (never null; empty set for null/empty input)
   */
  private static @NotNull Set<String> tokenize(@Nullable String name) {
    if (name == null || name.isBlank()) {
      return Set.of();
    }
    Set<String> tokens = new HashSet<>();
    for (String t : TOKEN_SPLIT.split(name.toLowerCase(Locale.ROOT))) {
      if (!t.isEmpty()) {
        tokens.add(t);
      }
    }
    return tokens;
  }

  /**
   * Pre-computed multi-key view over the {@code ship_type} table. Built once per import via {@link
   * #buildIndex(List)} so per-entry resolution touches only in-memory structures.
   */
  public static final class ShipTypeIndex {
    private final Map<String, ShipType> byExactLower = new HashMap<>();
    private final Map<String, ShipType> byNormalized = new HashMap<>();
    private final List<TokenView> tokenized = new ArrayList<>();
    private final Map<String, ShipType> byUexSlug = new HashMap<>();
    private final Map<String, ShipType> byScwikiSlug = new HashMap<>();
  }

  /**
   * Pairing of a {@code ShipType} with its tokenised alphanumeric form. Used by the Stage 3 / 4
   * token-subset comparisons. Records are not used here because Lombok is overkill for a
   * one-field-pair holder.
   */
  private static final class TokenView {
    private final ShipType shipType;
    private final Set<String> tokens;

    private TokenView(@NotNull ShipType shipType, @NotNull Set<String> tokens) {
      this.shipType = shipType;
      this.tokens = tokens;
    }
  }
}
