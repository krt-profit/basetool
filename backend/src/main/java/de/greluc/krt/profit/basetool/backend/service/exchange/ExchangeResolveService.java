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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.kernel.FuzzyNameMatcher;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportSuggestionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Entry;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Result;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Status;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse.Warning;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository.ExchangeBlueprintKeyRow;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository.ExchangeItemKeyRow;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService.NameResolution;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.service.MaterialNameCanonicalizer;
import de.greluc.krt.profit.basetool.backend.service.ShipTypeMatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves external item references to catalogue entries for {@code catalog/resolve}; blueprint
 * names go through the web import's own matching chain (REQ-XCH-012).
 */
@Service
@RequiredArgsConstructor
public class ExchangeResolveService {

  /** Warning code: the name key resolved to no single entry, so the name was tried instead. */
  static final String WARNING_LOC_KEY_UNRESOLVED = "LOC_KEY_UNRESOLVED";

  /** Stands in for an empty key list, which a JPQL {@code IN} cannot take. */
  private static final UUID NO_UUID = new UUID(0L, 0L);

  /** Stands in for an empty UEX-id list; UEX ids are positive. */
  private static final int NO_UEX_ID = -1;

  /** Stands in for an empty text-key list; no catalogue key is empty. */
  private static final String NO_TEXT = "";

  /** The longest display name the published item reference carries. */
  private static final int MAX_NAME = 200;

  private final BlueprintProductService blueprintProductService;
  private final BlueprintImportService blueprintImportService;
  private final BlueprintNameNormalizer normalizer;
  private final FuzzyNameMatcher fuzzyMatcher;
  private final BlueprintRepository blueprintRepository;
  private final GameItemRepository gameItemRepository;
  private final MaterialRepository materialRepository;
  private final MaterialExternalAliasRepository materialAliasRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final MeterRegistry meterRegistry;

  /** Registers the resolution counter for every catalogue and outcome at zero. */
  @PostConstruct
  void registerCounters() {
    for (ExchangeCatalogKind kind : ExchangeCatalogKind.values()) {
      for (Status status : Status.values()) {
        counter(kind, status);
      }
    }
  }

  /**
   * Resolves every reference of a request against its catalogue. A reference takes the first of its
   * fields, in the order {@code bt}, {@code scRecord}, {@code scGuid}, {@code uexId}, {@code
   * locKey}, {@code name}, that resolves to exactly one entry; failing that, the first that
   * resolved to several.
   *
   * @param request the kind and the references
   * @return one result per reference in request order, plus the warnings
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeResolveResponse resolve(@NotNull ExchangeResolveRequest request) {
    List<ExchangeItemRef> refs = request.refs();
    Catalog catalog =
        switch (request.kind()) {
          case BLUEPRINT -> blueprintCatalog(refs);
          case ITEM -> itemCatalog(refs);
          case MATERIAL -> materialCatalog();
          case SHIP_TYPE -> shipTypeCatalog();
        };

    List<Outcome> outcomes = new ArrayList<>(refs.size());
    List<Warning> warnings = new ArrayList<>();
    List<Integer> byNameIndexes = new ArrayList<>();
    List<String> byNameNames = new ArrayList<>();
    for (int i = 0; i < refs.size(); i++) {
      ExchangeItemRef ref = refs.get(i);
      Outcome outcome = byKeys(catalog, ref);
      if (outcome.status() != Status.RESOLVED) {
        if (ref.locKey() != null && warnings.size() < ExchangeResolveResponse.MAX_WARNINGS) {
          warnings.add(new Warning("/refs/" + i + "/locKey", WARNING_LOC_KEY_UNRESOLVED));
        }
        if (ref.name() != null) {
          byNameIndexes.add(i);
          byNameNames.add(ref.name());
        }
      }
      outcomes.add(outcome);
    }
    List<Outcome> byName = byNameNames.isEmpty() ? List.of() : catalog.byNames(byNameNames);
    for (int n = 0; n < byNameIndexes.size(); n++) {
      int i = byNameIndexes.get(n);
      outcomes.set(i, prefer(outcomes.get(i), byName.get(n)));
    }

    List<Result> results = new ArrayList<>(refs.size());
    for (int i = 0; i < outcomes.size(); i++) {
      Outcome outcome = outcomes.get(i);
      counter(request.kind(), outcome.status()).increment();
      results.add(toResult(i, outcome));
    }
    return new ExchangeResolveResponse(results, warnings.isEmpty() ? null : warnings);
  }

  /**
   * Resolves one reference by its key fields, {@code locKey} included and {@code name} excluded.
   *
   * @param catalog the catalogue
   * @param ref the reference
   * @return the first resolved outcome, else the first ambiguous one, else unmatched
   */
  @NotNull
  private static Outcome byKeys(@NotNull Catalog catalog, @NotNull ExchangeItemRef ref) {
    List<Outcome> steps = new ArrayList<>(4);
    if (ref.bt() != null) {
      steps.add(catalog.byBt(ref.bt()));
    }
    if (ref.scRecord() != null) {
      steps.add(Outcome.of(catalog.byScRecord().get(lower(ref.scRecord()))));
    }
    if (ref.scGuid() != null) {
      steps.add(Outcome.of(catalog.byScGuid().get(ref.scGuid())));
    }
    if (ref.uexId() != null) {
      steps.add(Outcome.of(catalog.byUexId().get(ref.uexId())));
    }
    if (ref.locKey() != null) {
      steps.add(Outcome.of(catalog.byLocKey().get(lower(ref.locKey()))));
    }
    Outcome best = Outcome.UNMATCHED;
    for (Outcome step : steps) {
      if (step.status() == Status.RESOLVED) {
        return step;
      }
      if (best.status() == Status.UNMATCHED) {
        best = step;
      }
    }
    return best;
  }

  /**
   * Combines the key outcome with the name outcome: a resolved name wins over anything short of a
   * resolved key, and an ambiguous key wins over an ambiguous name.
   *
   * @param byKeys the outcome of the key fields
   * @param byName the outcome of the name
   * @return the combined outcome
   */
  @NotNull
  private static Outcome prefer(@NotNull Outcome byKeys, @NotNull Outcome byName) {
    if (byName.status() == Status.RESOLVED || byKeys.status() == Status.UNMATCHED) {
      return byName;
    }
    return byKeys;
  }

  /**
   * Maps an outcome to its wire result.
   *
   * @param index the reference's position in the request
   * @param outcome the outcome
   * @return the result
   */
  @NotNull
  private static Result toResult(int index, @NotNull Outcome outcome) {
    return switch (outcome.status()) {
      case RESOLVED -> new Result(index, Status.RESOLVED, outcome.entries().getFirst(), null);
      case AMBIGUOUS ->
          new Result(
              index,
              Status.AMBIGUOUS,
              null,
              List.copyOf(
                  outcome
                      .entries()
                      .subList(
                          0,
                          Math.min(
                              outcome.entries().size(), ExchangeResolveResponse.MAX_CANDIDATES))));
      case UNMATCHED -> new Result(index, Status.UNMATCHED, null, null);
    };
  }

  /**
   * Builds the blueprint catalogue: products keyed by their normalized product key, the Wiki keys,
   * the blueprint and output-item GUIDs, the output items' UEX ids and the import's name chain.
   *
   * @param refs the references, to load only the key indexes they use
   * @return the catalogue
   */
  @NotNull
  private Catalog blueprintCatalog(@NotNull List<ExchangeItemRef> refs) {
    Map<String, Entry> products = new LinkedHashMap<>();
    Map<String, Entry> byBt = new HashMap<>();
    for (ResolvedProduct product : blueprintProductService.allProducts()) {
      Entry entry = toEntry(product);
      products.putIfAbsent(product.productKey(), entry);
      byBt.putIfAbsent(entry.bt(), entry);
    }

    Map<String, Set<Entry>> byScRecord = new HashMap<>();
    if (refs.stream().anyMatch(r -> r.scRecord() != null)) {
      blueprintProductService
          .scwikiKeyToProductKeys()
          .forEach(
              (tag, keys) -> {
                for (String key : keys) {
                  add(byScRecord, tag, products.get(key));
                }
              });
    }

    Map<UUID, Set<Entry>> byScGuid = new HashMap<>();
    Map<Integer, Set<Entry>> byUexId = new HashMap<>();
    List<UUID> guids = present(refs, ExchangeItemRef::scGuid);
    List<Integer> uexIds = present(refs, ExchangeItemRef::uexId);
    List<String> locKeys = present(refs, r -> lower(r.locKey()));
    Map<String, Set<Entry>> byLocKey = new HashMap<>();
    if (!guids.isEmpty() || !uexIds.isEmpty() || !locKeys.isEmpty()) {
      for (ExchangeBlueprintKeyRow row :
          blueprintRepository.findExchangeKeyRows(
              orElse(guids, NO_UUID), orElse(uexIds, NO_UEX_ID), orElse(locKeys, NO_TEXT))) {
        Entry entry = products.get(normalizer.normalize(row.outputName()));
        add(byScGuid, row.blueprintScwikiUuid(), entry);
        add(byScGuid, row.blueprintP4kUuid(), entry);
        add(byScGuid, row.itemExternalUuid(), entry);
        add(byScGuid, row.itemP4kUuid(), entry);
        add(byUexId, row.itemUexId(), entry);
        add(byLocKey, lower(row.itemNameKey()), entry);
      }
    }

    return new Catalog(
        bt -> Outcome.ofOne(byBt.get(bt)),
        byScRecord,
        byScGuid,
        byUexId,
        byLocKey,
        names ->
            blueprintImportService.resolveNames(names).stream()
                .map(ExchangeResolveService::fromNameResolution)
                .toList());
  }

  /**
   * Maps the web import's resolution of one name: matched is resolved, suggested is ambiguous with
   * the suggestions as candidates.
   *
   * @param resolution the import's resolution
   * @return the outcome
   */
  @NotNull
  private static Outcome fromNameResolution(@NotNull NameResolution resolution) {
    return switch (resolution.status()) {
      case MATCHED, MATCHED_BY_ALIAS, ALREADY_OWNED ->
          Outcome.ofOne(resolution.product() == null ? null : toEntry(resolution.product()));
      case SUGGESTED ->
          Outcome.suggested(
              resolution.suggestions().stream().map(ExchangeResolveService::toEntry).toList());
      case UNMATCHED -> Outcome.UNMATCHED;
    };
  }

  /**
   * Builds the item catalogue from one query over every key the references carry.
   *
   * @param refs the references
   * @return the catalogue
   */
  @NotNull
  private Catalog itemCatalog(@NotNull List<ExchangeItemRef> refs) {
    List<UUID> ids = present(refs, r -> parseUuid(r.bt()));
    List<String> classNames = present(refs, r -> lower(r.scRecord()));
    List<UUID> guids = present(refs, ExchangeItemRef::scGuid);
    List<Integer> uexIds = present(refs, ExchangeItemRef::uexId);
    List<String> names = present(refs, r -> lower(r.name()));
    List<String> locKeys = present(refs, r -> lower(r.locKey()));

    Map<UUID, Set<Entry>> byId = new HashMap<>();
    Map<String, Set<Entry>> byScRecord = new HashMap<>();
    Map<UUID, Set<Entry>> byScGuid = new HashMap<>();
    Map<Integer, Set<Entry>> byUexId = new HashMap<>();
    Map<String, Set<Entry>> byName = new HashMap<>();
    Map<String, Set<Entry>> byLocKey = new HashMap<>();
    if (!(ids.isEmpty()
        && classNames.isEmpty()
        && guids.isEmpty()
        && uexIds.isEmpty()
        && names.isEmpty()
        && locKeys.isEmpty())) {
      for (ExchangeItemKeyRow row :
          gameItemRepository.findExchangeKeyRows(
              orElse(ids, NO_UUID),
              orElse(classNames, NO_TEXT),
              orElse(guids, NO_UUID),
              orElse(uexIds, NO_UEX_ID),
              orElse(names, NO_TEXT),
              orElse(locKeys, NO_TEXT))) {
        Entry entry = new Entry(row.id().toString(), row.name());
        add(byId, row.id(), entry);
        add(byScRecord, lower(row.className()), entry);
        add(byScGuid, row.externalUuid(), entry);
        add(byScGuid, row.p4kUuid(), entry);
        add(byUexId, row.uexId(), entry);
        add(byName, lower(row.name()), entry);
        add(byLocKey, lower(row.nameKey()), entry);
      }
    }
    return new Catalog(
        bt -> Outcome.of(byId.get(parseUuid(bt))),
        byScRecord,
        byScGuid,
        byUexId,
        byLocKey,
        batch -> batch.stream().map(name -> Outcome.of(byName.get(lower(name)))).toList());
  }

  /**
   * Builds the material catalogue over the visible materials: Wiki key, Wiki and game-file UUID,
   * UEX commodity id, and a name chain of exact name, canonical name, external alias, then fuzzy
   * suggestions.
   *
   * @return the catalogue
   */
  @NotNull
  private Catalog materialCatalog() {
    List<Material> materials =
        materialRepository.findAll().stream()
            .filter(m -> Boolean.TRUE.equals(m.getIsVisible()))
            .sorted(
                Comparator.comparing(Material::getName, Comparator.nullsLast(String::compareTo)))
            .toList();
    Map<UUID, Set<Entry>> byId = new HashMap<>();
    Map<String, Set<Entry>> byScRecord = new HashMap<>();
    Map<UUID, Set<Entry>> byScGuid = new HashMap<>();
    Map<Integer, Set<Entry>> byUexId = new HashMap<>();
    Map<String, Set<Entry>> byName = new HashMap<>();
    Map<String, Set<Entry>> byCanonical = new HashMap<>();
    Map<String, Set<Entry>> byLocKey = new HashMap<>();
    for (Material material : materials) {
      Entry entry = toEntry(material);
      add(byId, material.getId(), entry);
      add(byScRecord, lower(material.getScwikiKey()), entry);
      add(byScGuid, material.getScwikiUuid(), entry);
      add(byScGuid, material.getP4kUuid(), entry);
      add(byUexId, material.getIdCommodity(), entry);
      add(byName, lower(material.getName()), entry);
      add(byLocKey, lower(material.getNameKey()), entry);
      add(byCanonical, MaterialNameCanonicalizer.canonicalCore(material.getName()), entry);
    }
    Map<String, Set<Entry>> byAlias = new HashMap<>();
    for (MaterialExternalAlias alias : materialAliasRepository.findAllByOrderByExternalNameAsc()) {
      Material target = alias.getMaterial();
      if (target != null && byId.containsKey(target.getId())) {
        add(byAlias, lower(alias.getExternalName()), toEntry(target));
      }
    }
    return new Catalog(
        bt -> Outcome.of(byId.get(parseUuid(bt))),
        byScRecord,
        byScGuid,
        byUexId,
        byLocKey,
        names ->
            names.stream()
                .map(name -> materialByName(name, materials, byName, byCanonical, byAlias))
                .toList());
  }

  /**
   * Resolves a material name: exact, canonical, alias, then fuzzy suggestions.
   *
   * @param name the name
   * @param materials the visible materials, the fuzzy candidates
   * @param byName entries by lower-cased name
   * @param byCanonical entries by canonical name
   * @param byAlias entries by lower-cased external alias
   * @return the outcome
   */
  @NotNull
  private Outcome materialByName(
      @NotNull String name,
      @NotNull List<Material> materials,
      @NotNull Map<String, Set<Entry>> byName,
      @NotNull Map<String, Set<Entry>> byCanonical,
      @NotNull Map<String, Set<Entry>> byAlias) {
    for (Set<Entry> hits :
        Arrays.asList(
            byName.get(lower(name)),
            byCanonical.get(MaterialNameCanonicalizer.canonicalCore(name)),
            byAlias.get(lower(name)))) {
      if (hits != null && !hits.isEmpty()) {
        return Outcome.of(hits);
      }
    }
    String fuzzyKey = MaterialNameCanonicalizer.fuzzyKey(name);
    if (fuzzyKey == null || fuzzyKey.isEmpty()) {
      return Outcome.UNMATCHED;
    }
    return Outcome.suggested(
        fuzzyMatcher
            .topMatches(
                fuzzyKey,
                materials,
                m ->
                    Objects.requireNonNullElse(MaterialNameCanonicalizer.fuzzyKey(m.getName()), ""),
                Comparator.comparing(
                    Material::getName, Comparator.nullsLast(String::compareToIgnoreCase)),
                FuzzyNameMatcher.DEFAULT_LIMIT,
                FuzzyNameMatcher.DEFAULT_THRESHOLD)
            .stream()
            .map(scored -> toEntry(scored.candidate()))
            .toList());
  }

  /**
   * Builds the ship-type catalogue: class name, Wiki UUID, UEX vehicle id, and names through the
   * hangar import's {@link ShipTypeMatcher}.
   *
   * @return the catalogue
   */
  @NotNull
  private Catalog shipTypeCatalog() {
    List<ShipType> shipTypes = shipTypeRepository.findAll();
    Map<UUID, Set<Entry>> byId = new HashMap<>();
    Map<String, Set<Entry>> byScRecord = new HashMap<>();
    Map<UUID, Set<Entry>> byScGuid = new HashMap<>();
    Map<Integer, Set<Entry>> byUexId = new HashMap<>();
    Map<String, Set<Entry>> byLocKey = new HashMap<>();
    for (ShipType shipType : shipTypes) {
      Entry entry = toEntry(shipType);
      add(byId, shipType.getId(), entry);
      add(byScRecord, lower(shipType.getClassName()), entry);
      add(byScGuid, shipType.getExternalUuid(), entry);
      add(byUexId, shipType.getUexVehicleId(), entry);
      add(byLocKey, lower(shipType.getNameKey()), entry);
    }
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(shipTypes);
    return new Catalog(
        bt -> Outcome.of(byId.get(parseUuid(bt))),
        byScRecord,
        byScGuid,
        byUexId,
        byLocKey,
        names ->
            names.stream()
                .map(name -> ShipTypeMatcher.resolve(index, name, null))
                .map(hit -> Outcome.ofOne(hit == null ? null : toEntry(hit)))
                .toList());
  }

  /**
   * Returns the resolution counter of one catalogue and outcome.
   *
   * @param kind the catalogue
   * @param status the outcome
   * @return the counter
   */
  @NotNull
  private Counter counter(@NotNull ExchangeCatalogKind kind, @NotNull Status status) {
    return meterRegistry.counter(
        MetricNames.EXCHANGE_RESOLVE_REFS,
        MetricNames.TAG_KIND,
        lower(kind.name()),
        MetricNames.TAG_STATUS,
        status.wire());
  }

  /**
   * Builds a blueprint's entry with the key the blueprint feed issues as {@code bt}: the product
   * key, or its hash when it is longer than the published limit (REQ-XCH-015).
   *
   * @param productKey the normalized product key
   * @param productName the display name
   * @return the entry, its name cut to the published limit
   */
  @NotNull
  private static Entry blueprintEntry(@NotNull String productKey, @NotNull String productName) {
    return new Entry(
        ExchangeBlueprintFeedService.keyOf(productKey),
        productName.length() > MAX_NAME ? productName.substring(0, MAX_NAME) : productName);
  }

  /**
   * Maps a blueprint product to its entry.
   *
   * @param product the product
   * @return the entry keyed by the product key
   */
  @NotNull
  private static Entry toEntry(@NotNull ResolvedProduct product) {
    return blueprintEntry(product.productKey(), product.productName());
  }

  /**
   * Maps a fuzzy blueprint suggestion to its entry.
   *
   * @param suggestion the suggestion
   * @return the entry keyed by the product key
   */
  @NotNull
  private static Entry toEntry(@NotNull BlueprintImportSuggestionDto suggestion) {
    return blueprintEntry(suggestion.productKey(), suggestion.productName());
  }

  /**
   * Maps a material to its entry.
   *
   * @param material the material
   * @return the entry keyed by the material id
   */
  @NotNull
  private static Entry toEntry(@NotNull Material material) {
    return new Entry(material.getId().toString(), material.getName());
  }

  /**
   * Maps a ship type to its entry.
   *
   * @param shipType the ship type
   * @return the entry keyed by the ship type id
   */
  @NotNull
  private static Entry toEntry(@NotNull ShipType shipType) {
    return new Entry(shipType.getId().toString(), shipType.getName());
  }

  /**
   * Adds an entry under a key, skipping a {@code null} key or entry.
   *
   * @param index the index
   * @param key the key
   * @param entry the entry
   * @param <K> the key type
   */
  private static <K> void add(
      @NotNull Map<K, Set<Entry>> index, @Nullable K key, @Nullable Entry entry) {
    if (key != null && entry != null) {
      index.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(entry);
    }
  }

  /**
   * Collects the distinct non-null values one field takes across the references.
   *
   * @param refs the references
   * @param field the field
   * @param <T> the value type
   * @return the values
   */
  @NotNull
  private static <T> List<T> present(
      @NotNull List<ExchangeItemRef> refs, @NotNull Function<ExchangeItemRef, T> field) {
    return refs.stream().map(field).filter(Objects::nonNull).distinct().toList();
  }

  /**
   * Returns the values, or a one-element list of the stand-in when there are none.
   *
   * @param values the values
   * @param none the stand-in
   * @param <T> the value type
   * @return a non-empty collection
   */
  @NotNull
  private static <T> Collection<T> orElse(@NotNull List<T> values, @NotNull T none) {
    return values.isEmpty() ? List.of(none) : values;
  }

  /**
   * Parses a UUID key.
   *
   * @param value the text
   * @return the UUID, or {@code null} when the text is absent or not a UUID
   */
  @Nullable
  private static UUID parseUuid(@Nullable String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  /**
   * Lower-cases a text key.
   *
   * @param value the text
   * @return the lower-cased text, or {@code null} for {@code null}
   */
  @Nullable
  private static String lower(@Nullable String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  /**
   * A catalogue's lookups for one request.
   *
   * @param byBtLookup resolves the Basetool's own key
   * @param byScRecord entries by lower-cased DataForge record name
   * @param byScGuid entries by game GUID
   * @param byUexId entries by UEX id
   * @param byLocKey entries by lower-cased {@code global.ini} name key
   * @param byNamesLookup resolves a batch of names, one outcome per name in order
   */
  private record Catalog(
      @NotNull Function<String, Outcome> byBtLookup,
      @NotNull Map<String, Set<Entry>> byScRecord,
      @NotNull Map<UUID, Set<Entry>> byScGuid,
      @NotNull Map<Integer, Set<Entry>> byUexId,
      @NotNull Map<String, Set<Entry>> byLocKey,
      @NotNull Function<List<String>, List<Outcome>> byNamesLookup) {

    /**
     * Resolves the Basetool's own key.
     *
     * @param bt the key
     * @return the outcome
     */
    @NotNull
    Outcome byBt(@NotNull String bt) {
      return byBtLookup.apply(bt);
    }

    /**
     * Resolves a batch of names.
     *
     * @param names the names
     * @return one outcome per name, in order
     */
    @NotNull
    List<Outcome> byNames(@NotNull List<String> names) {
      return byNamesLookup.apply(names);
    }
  }

  /**
   * How one reference resolved.
   *
   * @param status the outcome
   * @param entries the one resolved entry, or the candidates
   */
  private record Outcome(@NotNull Status status, @NotNull List<Entry> entries) {

    /** No entry. */
    static final Outcome UNMATCHED = new Outcome(Status.UNMATCHED, List.of());

    /**
     * Classifies exact matches: none is unmatched, one resolved, several ambiguous.
     *
     * @param matches the matches, or {@code null} for none
     * @return the outcome
     */
    @NotNull
    static Outcome of(@Nullable Collection<Entry> matches) {
      if (matches == null || matches.isEmpty()) {
        return UNMATCHED;
      }
      List<Entry> distinct = List.copyOf(new LinkedHashSet<>(matches));
      return new Outcome(distinct.size() == 1 ? Status.RESOLVED : Status.AMBIGUOUS, distinct);
    }

    /**
     * Classifies one exact match.
     *
     * @param match the match, or {@code null} for none
     * @return resolved, or unmatched
     */
    @NotNull
    static Outcome ofOne(@Nullable Entry match) {
      return match == null ? UNMATCHED : new Outcome(Status.RESOLVED, List.of(match));
    }

    /**
     * Classifies fuzzy suggestions, which never resolve on their own.
     *
     * @param suggestions the suggestions, best first
     * @return ambiguous, or unmatched when there are none
     */
    @NotNull
    static Outcome suggested(@NotNull List<Entry> suggestions) {
      return suggestions.isEmpty()
          ? UNMATCHED
          : new Outcome(Status.AMBIGUOUS, List.copyOf(new LinkedHashSet<>(suggestions)));
    }
  }
}
