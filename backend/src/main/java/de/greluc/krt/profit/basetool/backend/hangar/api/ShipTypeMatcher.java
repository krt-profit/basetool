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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipManufacturerWords;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * In-memory {@code ShipType} resolver shared by the hangar import and the exchange's {@code
 * SHIP_TYPE} references (REQ-P4K-032, ADR-0341).
 *
 * <p>The key steps come first: the P4K GUID or Wiki UUID, the class name (else the {@code
 * variantOf} group base), then the UEX vehicle id (else the curated alias of ADR-0307). The name is
 * the last step, in four stages: exact case-insensitive name, normalised name, upload tokens ⊆ ship
 * tokens, ship tokens ⊆ upload tokens. A step that finds several ship types is ambiguous and never
 * resolves to the first. Every step asks the live ship types first and the retired ones
 * (REQ-P4K-013) only when no live one answers, so a retired row never ties with or shadows a live
 * one.
 *
 * <p>The hangar import also matches each ship type's name without its leading manufacturer word and
 * treats the rows whose names agree without it as one ship (REQ-HANGAR-019). Stateless and
 * static-only.
 */
public final class ShipTypeMatcher {

  private ShipTypeMatcher() {}

  /**
   * Builds the lookup index of the hangar import: no key links, and the name without the
   * manufacturer word keyed beside the name.
   *
   * @param shipTypes a snapshot of the {@code ship_type} table, manufacturers reachable
   * @return the populated lookup index
   */
  public static @NotNull ShipTypeIndex buildIndex(@NotNull List<ShipType> shipTypes) {
    return buildIndex(shipTypes, ShipTypeLinks.NONE, ShipManufacturerWords.of(shipTypes));
  }

  /**
   * Builds the lookup index: GUIDs, class names, UEX ids, names, tokens and slugs, every key
   * keeping all the ship types that share it, the live and the retired ship types apart.
   *
   * @param shipTypes a snapshot of the {@code ship_type} table
   * @param links the variant groups and the UEX alias
   * @return the populated lookup index
   */
  public static @NotNull ShipTypeIndex buildIndex(
      @NotNull List<ShipType> shipTypes, @NotNull ShipTypeLinks links) {
    return buildIndex(shipTypes, links, ShipManufacturerWords.NONE);
  }

  /**
   * Builds the lookup index with the given manufacturer words.
   *
   * @param shipTypes a snapshot of the {@code ship_type} table
   * @param links the variant groups and the UEX alias
   * @param words the manufacturer words a short name drops
   * @return the populated lookup index
   */
  private static @NotNull ShipTypeIndex buildIndex(
      @NotNull List<ShipType> shipTypes,
      @NotNull ShipTypeLinks links,
      @NotNull ShipManufacturerWords words) {
    List<ShipType> live = new ArrayList<>();
    List<ShipType> retired = new ArrayList<>();
    for (ShipType shipType : shipTypes) {
      (shipType.isP4kRetired() ? retired : live).add(shipType);
    }
    return new ShipTypeIndex(Keys.of(live, links, words), Keys.of(retired, links, words));
  }

  /**
   * Resolves a hangar-import entry: the name, then, when the name finds no ship or several, the
   * optional slug. The slug is supplied for StarJump FleetViewer, Fleetyards and Hangar Extension
   * JSON entries and {@code null} for the other formats.
   *
   * <p>The name stages run on the full and the short name: exact full, exact short, normalised
   * full, normalised short, then the token subsets of the short name. Every ship type found brings
   * the rows of the same ship along (REQ-HANGAR-019).
   *
   * @param index lookup index produced by {@link #buildIndex(List)}
   * @param rawName trimmed entry name from the upload
   * @param slug source-provided ship slug for the fallback stage, or {@code null}
   * @return the ships found: one resolves the entry, several are the member's choice
   */
  public static @NotNull ImportShipMatch resolveImport(
      @NotNull ShipTypeIndex index, @NotNull String rawName, @Nullable String slug) {
    ImportShipMatch live = importIn(index.live, rawName, slug);
    return live.isEmpty() ? importIn(index.retired, rawName, slug) : live;
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
    return liveFirst(index, keys -> ShipTypeMatch.of(keys.byGuid.get(guid)));
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
    return liveFirst(index, keys -> classNameIn(keys, key));
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
    return liveFirst(
        index,
        keys -> {
          List<ShipType> direct = keys.byUexId.get(uexId);
          return ShipTypeMatch.of(direct != null ? direct : keys.byAlias.get(uexId));
        });
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
    return liveFirst(index, keys -> nameIn(keys, rawName));
  }

  /**
   * Runs a step against the live ship types, and against the retired ones when it finds none.
   *
   * @param index lookup index
   * @param step the step over one partition
   * @return what the step found among the live ship types, else among the retired ones
   */
  private static @NotNull ShipTypeMatch liveFirst(
      @NotNull ShipTypeIndex index, @NotNull Function<Keys, ShipTypeMatch> step) {
    ShipTypeMatch live = step.apply(index.live);
    return live.isEmpty() ? step.apply(index.retired) : live;
  }

  /**
   * Resolves a hangar-import entry within one partition: the name, then the slug when the name
   * finds no ship or several.
   *
   * @param keys one partition
   * @param rawName trimmed entry name from the upload
   * @param slug source-provided ship slug, or {@code null}
   * @return the single ship of the name or the slug, else the name's ships, else the slug's
   */
  private static @NotNull ImportShipMatch importIn(
      @NotNull Keys keys, @NotNull String rawName, @Nullable String slug) {
    ImportShipMatch byName = keys.ships(importNameIn(keys, rawName));
    if (byName.single() != null) {
      return byName;
    }
    ImportShipMatch bySlug = keys.ships(resolveBySlug(keys, slug).candidates());
    if (bySlug.single() != null || byName.isEmpty()) {
      return bySlug;
    }
    return byName;
  }

  /**
   * Runs the import's name stages within one partition; the first stage that finds a ship type
   * answers.
   *
   * @param keys one partition
   * @param rawName the name
   * @return the ship types the answering stage found
   */
  private static @NotNull List<ShipType> importNameIn(@NotNull Keys keys, @NotNull String rawName) {
    List<ShipType> found = keys.names.exact(rawName);
    if (found.isEmpty()) {
      found = keys.shortNames.exact(rawName);
    }
    if (found.isEmpty()) {
      found = keys.names.normalized(rawName);
    }
    if (found.isEmpty()) {
      found = keys.shortNames.normalized(rawName);
    }
    if (found.isEmpty()) {
      found = keys.shortNames.tokens(rawName);
    }
    return found;
  }

  /**
   * Resolves a lower-cased class name within one partition.
   *
   * @param keys one partition
   * @param key the lower-cased class name
   * @return the ship types carrying it, else its variant group's base
   */
  private static @NotNull ShipTypeMatch classNameIn(@NotNull Keys keys, @NotNull String key) {
    List<ShipType> own = keys.byClassName.get(key);
    if (own != null) {
      return ShipTypeMatch.of(own);
    }
    ShipType base = keys.variantBase.get(key);
    return base == null ? ShipTypeMatch.NONE : ShipTypeMatch.of(List.of(base));
  }

  /**
   * Resolves a name through the four name stages within one partition.
   *
   * @param keys one partition
   * @param rawName the name
   * @return the ship types the answering stage found
   */
  private static @NotNull ShipTypeMatch nameIn(@NotNull Keys keys, @NotNull String rawName) {
    List<ShipType> found = keys.names.exact(rawName);
    if (found.isEmpty()) {
      found = keys.names.normalized(rawName);
    }
    if (found.isEmpty()) {
      found = keys.names.tokens(rawName);
    }
    return ShipTypeMatch.of(found);
  }

  /**
   * Resolves a normalised source slug against {@code ShipType.uexSlug}, then {@code
   * ShipType.scwikiSlug}, within one partition. A {@code null}, blank or empty-after-normalisation
   * slug finds nothing.
   *
   * @param keys one partition
   * @param slug source-provided ship slug, or {@code null}
   * @return the ship types found
   */
  private static @NotNull ShipTypeMatch resolveBySlug(@NotNull Keys keys, @Nullable String slug) {
    String normSlug = normalizeForMatching(slug);
    if (normSlug.isEmpty()) {
      return ShipTypeMatch.NONE;
    }
    List<ShipType> match = keys.byUexSlug.get(normSlug);
    return ShipTypeMatch.of(match != null ? match : keys.byScwikiSlug.get(normSlug));
  }

  /**
   * Folds a ship name to lower-case ASCII alphanumerics, e.g. {@code "L-21 Wolf"} to {@code
   * "l21wolf"}.
   *
   * @param name raw name (nullable)
   * @return the normalised form (never null; empty string for null/empty input)
   */
  public static @NotNull String normalizeForMatching(@Nullable String name) {
    return ShipManufacturerWords.normalize(name);
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
   * Pre-computed multi-key view over the {@code ship_type} table, the live and the retired ship
   * types apart. Built once per import or request via {@link #buildIndex(List, ShipTypeLinks)} so
   * per-entry resolution touches only in-memory structures.
   */
  public static final class ShipTypeIndex {

    /** The ship types neither swept nor hidden. */
    private final Keys live;

    /** The ship types the P4K sweep stamped or an admin hid. */
    private final Keys retired;

    /**
     * Pairs the two partitions.
     *
     * @param live the live ship types' keys
     * @param retired the retired ship types' keys
     */
    private ShipTypeIndex(@NotNull Keys live, @NotNull Keys retired) {
      this.live = live;
      this.retired = retired;
    }
  }

  /**
   * The keys of one partition of the ship types; a variant base, an alias or a row of the same ship
   * resolves only to a ship type of the same partition.
   */
  private static final class Keys {
    private final Map<UUID, ShipType> byId = new HashMap<>();
    private final Map<UUID, List<ShipType>> byGuid = new HashMap<>();
    private final Map<String, List<ShipType>> byClassName = new HashMap<>();
    private final Map<String, ShipType> variantBase = new HashMap<>();
    private final Map<Integer, List<ShipType>> byUexId = new HashMap<>();
    private final Map<Integer, List<ShipType>> byAlias = new HashMap<>();
    private final Map<String, List<ShipType>> byUexSlug = new HashMap<>();
    private final Map<String, List<ShipType>> byScwikiSlug = new HashMap<>();

    /** The name stages over the full names. */
    private final ShipNameKeys names = new ShipNameKeys();

    /** The name stages over the short names; {@link #names} itself when no word is known. */
    private final ShipNameKeys shortNames;

    /** The rows of one ship, keyed by the normalised name without the manufacturer word. */
    private final Map<String, List<ShipType>> bySameShip = new HashMap<>();

    /** The manufacturer words a short name drops. */
    private final ShipManufacturerWords words;

    /**
     * Prepares an empty partition.
     *
     * @param words the manufacturer words a short name drops
     */
    private Keys(@NotNull ShipManufacturerWords words) {
      this.words = words;
      this.shortNames = words.isEmpty() ? names : new ShipNameKeys();
    }

    /**
     * Indexes one partition of the ship types.
     *
     * @param shipTypes the partition
     * @param links the variant groups and the UEX alias
     * @param words the manufacturer words a short name drops
     * @return the partition's keys
     */
    private static @NotNull Keys of(
        @NotNull List<ShipType> shipTypes,
        @NotNull ShipTypeLinks links,
        @NotNull ShipManufacturerWords words) {
      Keys keys = new Keys(words);
      shipTypes.forEach(keys::add);
      links
          .variantBaseByClassName()
          .forEach((className, id) -> {
            ShipType base = keys.byId.get(id);
            if (base != null) {
              keys.variantBase.put(className, base);
            }
          });
      links
          .aliasByUexId()
          .forEach((uexId, ids) -> {
            List<ShipType> aliased =
                ids.stream().map(keys.byId::get).filter(Objects::nonNull).toList();
            if (!aliased.isEmpty()) {
              keys.byAlias.put(uexId, aliased);
            }
          });
      return keys;
    }

    /**
     * Adds one ship type under every key it carries.
     *
     * @param st the ship type
     */
    private void add(@NotNull ShipType st) {
      byId.put(st.getId(), st);
      ShipTypeMatcher.add(byGuid, st.getP4kUuid(), st);
      ShipTypeMatcher.add(byGuid, st.getExternalUuid(), st);
      ShipTypeMatcher.add(byClassName, lower(st.getClassName()), st);
      ShipTypeMatcher.add(byUexId, st.getUexVehicleId(), st);
      String name = st.getName();
      if (name == null || name.isBlank()) {
        return;
      }
      names.add(st, name);
      String shortName = words.strip(name);
      if (shortNames != names) {
        shortNames.add(st, shortName);
      }
      String sameShip = normalizeForMatching(shortName);
      if (!sameShip.isEmpty()) {
        ShipTypeMatcher.add(bySameShip, sameShip, st);
      }
      String uexSlug = normalizeForMatching(st.getUexSlug());
      if (!uexSlug.isEmpty()) {
        ShipTypeMatcher.add(byUexSlug, uexSlug, st);
      }
      String scwikiSlug = normalizeForMatching(st.getScwikiSlug());
      if (!scwikiSlug.isEmpty()) {
        ShipTypeMatcher.add(byScwikiSlug, scwikiSlug, st);
      }
    }

    /**
     * Groups the ship types a step found into ships, each with every row of this partition that
     * holds the same ship. A row without a name, which only a slug finds, is a ship of its own.
     *
     * @param found the ship types found
     * @return the named ships ordered by their canonical row's name, then the unnamed rows
     */
    private @NotNull ImportShipMatch ships(@NotNull Collection<ShipType> found) {
      Map<String, ImportShipMatch.Candidate> byShip = new LinkedHashMap<>();
      List<ImportShipMatch.Candidate> unnamed = new ArrayList<>();
      for (ShipType shipType : found) {
        String key = sameShipKey(shipType);
        if (key.isEmpty()) {
          unnamed.add(new ImportShipMatch.Candidate(List.of(shipType)));
        } else {
          byShip.computeIfAbsent(
              key,
              sameShip ->
                  new ImportShipMatch.Candidate(
                      canonicalFirst(bySameShip.getOrDefault(sameShip, List.of(shipType)))));
        }
      }
      List<ImportShipMatch.Candidate> ships =
          new ArrayList<>(
              byShip.values().stream()
                  .sorted(
                      Comparator.comparing(
                          (ImportShipMatch.Candidate candidate) -> candidate.canonical().getName(),
                          String.CASE_INSENSITIVE_ORDER))
                  .toList());
      ships.addAll(unnamed);
      return new ImportShipMatch(ships);
    }

    /**
     * Returns the key of the ship a row holds.
     *
     * @param shipType the row
     * @return its normalised name without the manufacturer word, empty for a blank name
     */
    private @NotNull String sameShipKey(@NotNull ShipType shipType) {
      String name = shipType.getName();
      return name == null || name.isBlank() ? "" : normalizeForMatching(words.strip(name));
    }

    /**
     * Orders the rows of one ship: the rows whose name carries the manufacturer word first, then by
     * name.
     *
     * @param rows the rows of one ship
     * @return the rows, canonical first
     */
    private @NotNull List<ShipType> canonicalFirst(@NotNull List<ShipType> rows) {
      return rows.stream()
          .sorted(
              Comparator.comparing(
                      (ShipType row) -> words.strip(row.getName()).equals(row.getName().strip()))
                  .thenComparing(ShipType::getName, String.CASE_INSENSITIVE_ORDER))
          .toList();
    }
  }

  /**
   * The name stages of {@link ShipTypeMatcher} over one name form of the ship types: the exact
   * case-insensitive name, the normalised name, then the token subsets in both directions.
   */
  private static final class ShipNameKeys {

    /** Splits a name into alphanumeric tokens. */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private final Map<String, List<ShipType>> byExactLower = new HashMap<>();
    private final Map<String, List<ShipType>> byNormalized = new HashMap<>();
    private final List<TokenView> tokenized = new ArrayList<>();

    /**
     * Indexes a ship type under one of its name forms; a blank name is skipped.
     *
     * @param shipType the ship type
     * @param name the name form to index it under
     */
    void add(@NotNull ShipType shipType, @NotNull String name) {
      if (name.isBlank()) {
        return;
      }
      byExactLower
          .computeIfAbsent(name.trim().toLowerCase(Locale.ROOT), _ -> new ArrayList<>())
          .add(shipType);
      String normalized = ShipTypeMatcher.normalizeForMatching(name);
      if (!normalized.isEmpty()) {
        byNormalized.computeIfAbsent(normalized, _ -> new ArrayList<>()).add(shipType);
      }
      Set<String> tokens = tokenize(name);
      if (!tokens.isEmpty()) {
        tokenized.add(new TokenView(shipType, tokens));
      }
    }

    /**
     * Finds the ship types whose name equals the given one, ignoring case and surrounding blanks.
     *
     * @param rawName the name
     * @return the ship types, empty for none
     */
    @NotNull
    @Unmodifiable
    List<ShipType> exact(@NotNull String rawName) {
      return List.copyOf(
          byExactLower.getOrDefault(rawName.trim().toLowerCase(Locale.ROOT), List.of()));
    }

    /**
     * Finds the ship types whose name equals the given one after {@link
     * ShipTypeMatcher#normalizeForMatching} folding.
     *
     * @param rawName the name
     * @return the ship types, empty for none or for a name that folds to nothing
     */
    @NotNull
    @Unmodifiable
    List<ShipType> normalized(@NotNull String rawName) {
      String normalized = ShipTypeMatcher.normalizeForMatching(rawName);
      return normalized.isEmpty()
          ? List.of()
          : List.copyOf(byNormalized.getOrDefault(normalized, List.of()));
    }

    /**
     * Finds the ship types whose tokens hold every token of the name, else those whose tokens the
     * name holds.
     *
     * @param rawName the name
     * @return the ship types of the first direction that finds any, empty for none
     */
    @NotNull
    @Unmodifiable
    List<ShipType> tokens(@NotNull String rawName) {
      Set<String> nameTokens = tokenize(rawName);
      if (nameTokens.isEmpty()) {
        return List.of();
      }
      List<ShipType> wider = new ArrayList<>();
      List<ShipType> narrower = new ArrayList<>();
      for (TokenView view : tokenized) {
        if (view.tokens().containsAll(nameTokens)) {
          wider.add(view.shipType());
        } else if (nameTokens.containsAll(view.tokens())) {
          narrower.add(view.shipType());
        }
      }
      return List.copyOf(wider.isEmpty() ? narrower : wider);
    }

    /**
     * Splits a name into its alphanumeric tokens; empty tokens from leading, trailing or repeated
     * separators are dropped.
     *
     * @param name the name, or {@code null}
     * @return the tokens, empty for a {@code null} or blank name
     */
    @NotNull
    private static Set<String> tokenize(@Nullable String name) {
      if (name == null || name.isBlank()) {
        return Set.of();
      }
      return TOKEN_SPLIT
          .splitAsStream(name.toLowerCase(Locale.ROOT))
          .filter(token -> !token.isEmpty())
          .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * A ship type with the tokens of the name form it is indexed under.
     *
     * @param shipType the ship type
     * @param tokens its alphanumeric tokens
     */
    private record TokenView(@NotNull ShipType shipType, @NotNull Set<String> tokens) {}
  }
}
