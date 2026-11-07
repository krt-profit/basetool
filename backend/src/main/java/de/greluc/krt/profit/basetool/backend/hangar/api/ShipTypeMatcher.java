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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory {@code ShipType} resolver shared by the hangar import and the exchange's {@code
 * SHIP_TYPE} references (REQ-P4K-032, ADR-0341).
 *
 * <p>The key steps come first: the P4K GUID or Wiki UUID, the class name (else the {@code
 * variantOf} group base), then the UEX vehicle id (else the curated alias of ADR-0307). The name is
 * the last step, in four stages: exact case-insensitive name, normalised name, upload tokens ⊆ ship
 * tokens, ship tokens ⊆ upload tokens. A step that finds several ship types is ambiguous and never
 * resolves to the first. Stateless and static-only.
 */
public final class ShipTypeMatcher {

  /** Splits a name into alphanumeric tokens. Pre-compiled so the regex is reused per call. */
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

  /** Strips everything outside {@code [a-z0-9]} for the normalised match form. */
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

  private ShipTypeMatcher() {}

  /**
   * Builds the lookup index without key links, as the hangar import uses it.
   *
   * @param shipTypes a snapshot of the {@code ship_type} table
   * @return the populated lookup index
   */
  public static @NotNull ShipTypeIndex buildIndex(@NotNull List<ShipType> shipTypes) {
    return buildIndex(shipTypes, ShipTypeLinks.NONE);
  }

  /**
   * Builds the lookup index: GUIDs, class names, UEX ids, names, tokens and slugs, every key
   * keeping all the ship types that share it.
   *
   * @param shipTypes a snapshot of the {@code ship_type} table
   * @param links the variant groups and the UEX alias
   * @return the populated lookup index
   */
  public static @NotNull ShipTypeIndex buildIndex(
      @NotNull List<ShipType> shipTypes, @NotNull ShipTypeLinks links) {
    ShipTypeIndex idx = new ShipTypeIndex();
    for (ShipType st : shipTypes) {
      idx.byId.put(st.getId(), st);
      add(idx.byGuid, st.getP4kUuid(), st);
      add(idx.byGuid, st.getExternalUuid(), st);
      add(idx.byClassName, lower(st.getClassName()), st);
      add(idx.byUexId, st.getUexVehicleId(), st);
      String name = st.getName();
      if (name == null || name.isBlank()) {
        continue;
      }
      add(idx.byExactLower, name.trim().toLowerCase(Locale.ROOT), st);
      String normalized = normalizeForMatching(name);
      if (!normalized.isEmpty()) {
        add(idx.byNormalized, normalized, st);
      }
      Set<String> tokens = tokenize(name);
      if (!tokens.isEmpty()) {
        idx.tokenized.add(new TokenView(st, tokens));
      }
      String uexSlug = normalizeForMatching(st.getUexSlug());
      if (!uexSlug.isEmpty()) {
        add(idx.byUexSlug, uexSlug, st);
      }
      String scwikiSlug = normalizeForMatching(st.getScwikiSlug());
      if (!scwikiSlug.isEmpty()) {
        add(idx.byScwikiSlug, scwikiSlug, st);
      }
    }
    links
        .variantBaseByClassName()
        .forEach((className, id) -> {
          ShipType base = idx.byId.get(id);
          if (base != null) {
            idx.variantBase.put(className, base);
          }
        });
    links
        .aliasByUexId()
        .forEach((uexId, ids) ->
            idx.byAlias.put(
                uexId, ids.stream().map(idx.byId::get).filter(Objects::nonNull).toList()));
    return idx;
  }

  /**
   * Resolves a hangar-import entry: the name, then, when the name finds none or several, the
   * optional slug. The slug is supplied for StarJump FleetViewer and Fleetyards entries and {@code
   * null} for the Fleetview / HangarXPLOR formats.
   *
   * @param index lookup index produced by {@link #buildIndex(List)}
   * @param rawName trimmed entry name from the upload
   * @param slug source-provided ship slug for the fallback stage, or {@code null}
   * @return the one matching {@code ShipType}, or {@code null} when neither resolves to exactly one
   */
  public static @Nullable ShipType resolve(
      @NotNull ShipTypeIndex index, @NotNull String rawName, @Nullable String slug) {
    ShipType byName = resolveName(index, rawName).single();
    if (byName != null) {
      return byName;
    }
    return resolveBySlug(index, slug).single();
  }

  /**
   * Resolves a P4K GUID or SC Wiki UUID.
   *
   * @param index lookup index
   * @param guid the GUID
   * @return the ship types holding it
   */
  public static @NotNull ShipTypeMatch resolveGuid(
      @NotNull ShipTypeIndex index, @NotNull UUID guid) {
    return ShipTypeMatch.of(index.byGuid.get(guid));
  }

  /**
   * Resolves a class name: the ship types carrying exactly it, else the ship type of its variant
   * group's base.
   *
   * @param index lookup index
   * @param className the class name, compared ignoring case
   * @return the ship types found
   */
  public static @NotNull ShipTypeMatch resolveClassName(
      @NotNull ShipTypeIndex index, @NotNull String className) {
    String key = className.toLowerCase(Locale.ROOT);
    List<ShipType> own = index.byClassName.get(key);
    if (own != null) {
      return ShipTypeMatch.of(own);
    }
    ShipType base = index.variantBase.get(key);
    return base == null ? ShipTypeMatch.NONE : ShipTypeMatch.of(List.of(base));
  }

  /**
   * Resolves a UEX vehicle id: the ship types holding it, else those carrying the class name the
   * curated alias ties it to.
   *
   * @param index lookup index
   * @param uexId the UEX vehicle id
   * @return the ship types found
   */
  public static @NotNull ShipTypeMatch resolveUexId(
      @NotNull ShipTypeIndex index, @NotNull Integer uexId) {
    List<ShipType> direct = index.byUexId.get(uexId);
    return ShipTypeMatch.of(direct != null ? direct : index.byAlias.get(uexId));
  }

  /**
   * Resolves a name through the four name stages; the first stage that finds a ship type answers.
   *
   * @param index lookup index
   * @param rawName the name
   * @return the ship types the answering stage found, several when the name is ambiguous
   */
  public static @NotNull ShipTypeMatch resolveName(
      @NotNull ShipTypeIndex index, @NotNull String rawName) {
    List<ShipType> exact = index.byExactLower.get(rawName.trim().toLowerCase(Locale.ROOT));
    if (exact != null) {
      return ShipTypeMatch.of(exact);
    }
    String normalized = normalizeForMatching(rawName);
    if (!normalized.isEmpty()) {
      List<ShipType> byNormalized = index.byNormalized.get(normalized);
      if (byNormalized != null) {
        return ShipTypeMatch.of(byNormalized);
      }
    }
    Set<String> fvTokens = tokenize(rawName);
    if (fvTokens.isEmpty()) {
      return ShipTypeMatch.NONE;
    }
    List<ShipType> wider = new ArrayList<>();
    List<ShipType> narrower = new ArrayList<>();
    for (TokenView tv : index.tokenized) {
      if (tv.tokens().containsAll(fvTokens)) {
        wider.add(tv.shipType());
      } else if (fvTokens.containsAll(tv.tokens())) {
        narrower.add(tv.shipType());
      }
    }
    return ShipTypeMatch.of(wider.isEmpty() ? narrower : wider);
  }

  /**
   * Resolves a normalised source slug against {@code ShipType.uexSlug}, then {@code
   * ShipType.scwikiSlug}. A {@code null}, blank or empty-after-normalisation slug finds nothing.
   *
   * @param index lookup index
   * @param slug source-provided ship slug, or {@code null}
   * @return the ship types found
   */
  private static @NotNull ShipTypeMatch resolveBySlug(
      @NotNull ShipTypeIndex index, @Nullable String slug) {
    String normSlug = normalizeForMatching(slug);
    if (normSlug.isEmpty()) {
      return ShipTypeMatch.NONE;
    }
    List<ShipType> match = index.byUexSlug.get(normSlug);
    return ShipTypeMatch.of(match != null ? match : index.byScwikiSlug.get(normSlug));
  }

  /**
   * Folds a ship name to lower-case ASCII alphanumerics, e.g. {@code "L-21 Wolf"} to {@code
   * "l21wolf"}.
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
   * Splits a ship name into an alphanumeric token set. Empty tokens (from leading, trailing or
   * multiple separators) are dropped.
   *
   * @param name raw name (nullable)
   * @return the token set (never null; empty set for null/empty input)
   */
  private static @NotNull Set<String> tokenize(@Nullable String name) {
    if (name == null || name.isBlank()) {
      return Set.of();
    }
    return TOKEN_SPLIT
        .splitAsStream(name.toLowerCase(Locale.ROOT))
        .filter(t -> !t.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * Lower-cases a key.
   *
   * @param value the key, or {@code null}
   * @return the lower-cased key, or {@code null}
   */
  private static @Nullable String lower(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
  }

  /**
   * Adds a ship type under a key, skipping a {@code null} key.
   *
   * @param index the index
   * @param key the key, or {@code null}
   * @param shipType the ship type
   * @param <K> the key type
   */
  private static <K> void add(
      @NotNull Map<K, List<ShipType>> index, @Nullable K key, @NotNull ShipType shipType) {
    if (key != null) {
      index.computeIfAbsent(key, _ -> new ArrayList<>()).add(shipType);
    }
  }

  /**
   * Pre-computed multi-key view over the {@code ship_type} table. Built once per import or request
   * via {@link #buildIndex(List, ShipTypeLinks)} so per-entry resolution touches only in-memory
   * structures.
   */
  public static final class ShipTypeIndex {
    private final Map<UUID, ShipType> byId = new HashMap<>();
    private final Map<UUID, List<ShipType>> byGuid = new HashMap<>();
    private final Map<String, List<ShipType>> byClassName = new HashMap<>();
    private final Map<String, ShipType> variantBase = new HashMap<>();
    private final Map<Integer, List<ShipType>> byUexId = new HashMap<>();
    private final Map<Integer, List<ShipType>> byAlias = new HashMap<>();
    private final Map<String, List<ShipType>> byExactLower = new HashMap<>();
    private final Map<String, List<ShipType>> byNormalized = new HashMap<>();
    private final List<TokenView> tokenized = new ArrayList<>();
    private final Map<String, List<ShipType>> byUexSlug = new HashMap<>();
    private final Map<String, List<ShipType>> byScwikiSlug = new HashMap<>();
  }

  /**
   * Pairing of a {@code ShipType} with its tokenised alphanumeric form, for the token-subset
   * stages.
   *
   * @param shipType the ship type
   * @param tokens its alphanumeric tokens
   */
  private record TokenView(@NotNull ShipType shipType, @NotNull Set<String> tokens) {}
}
