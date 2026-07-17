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

import de.greluc.krt.profit.basetool.backend.mapper.BlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintIdNameRow;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service backing the user-facing blueprint product search (#327). Exposes the SC Wiki
 * blueprint master as a de-duplicated list of <em>products</em> (the unit of ownership): all active
 * recipes whose output name normalizes to the same {@code product_key} collapse into one entry,
 * carrying a variant count, an example Wiki key, the manufacturer (when resolved) and an "already
 * owned by the caller" flag.
 *
 * <p>Grouping happens in memory rather than in SQL because the {@code product_key} is a normalized
 * form of {@code output_name} (see {@link BlueprintNameNormalizer}) that PostgreSQL cannot compute.
 * The active blueprint set is on the order of 1600 rows, so loading the (optionally name-filtered)
 * rows and grouping them in Java is cheap and mirrors the existing UEX location search.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlueprintProductService {

  /** Default number of products returned when the caller does not specify a limit. */
  public static final int DEFAULT_LIMIT = 25;

  /** Hard cap on the number of products returned, regardless of the requested limit. */
  public static final int MAX_LIMIT = 200;

  private final BlueprintRepository blueprintRepository;
  private final PersonalBlueprintRepository personalBlueprintRepository;
  private final BlueprintNameNormalizer normalizer;
  private final BlueprintMapper blueprintMapper;

  /**
   * Searches the blueprint products by a case-insensitive substring of the product name, returning
   * up to {@code limit} (capped at {@link #MAX_LIMIT}) alphabetically sorted products, each flagged
   * with whether {@code ownerSub} already owns it.
   *
   * @param query case-insensitive product-name substring; {@code null} / blank returns all products
   * @param limit requested maximum number of products; clamped to {@code [1, MAX_LIMIT]}
   * @param ownerSub Keycloak {@code sub} of the caller, used to compute the owned flag
   * @return the matching products, alphabetically by name, capped to the effective limit
   */
  @NotNull
  public List<BlueprintProductDto> searchProducts(
      @Nullable String query, int limit, @NotNull String ownerSub) {
    int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
    String q = query == null ? "" : query.trim();

    List<ProductAccumulator> products = new ArrayList<>(buildProductMap(q).values());
    products.sort(
        Comparator.comparing(
            p -> p.displayName, Comparator.nullsLast(String::compareToIgnoreCase)));
    List<ProductAccumulator> capped = products.size() > cap ? products.subList(0, cap) : products;

    Set<String> owned = ownedKeys(ownerSub, capped.stream().map(p -> p.productKey).toList());
    List<BlueprintProductDto> out = new ArrayList<>(capped.size());
    for (ProductAccumulator p : capped) {
      out.add(
          new BlueprintProductDto(
              p.productKey,
              p.displayName,
              p.variantCount,
              p.manufacturerName,
              p.exampleKey,
              owned.contains(p.productKey)));
    }
    return out;
  }

  /**
   * Resolves a normalized product key back to its canonical product (display name + optional
   * resolved output-item id). Used by the add flow (Phase 3) and the import (Phase 4) to stamp a
   * new ownership row. Returns empty for a blank key or one that no active blueprint produces.
   *
   * @param productKey normalized product key
   * @return the resolved product, or empty if unknown
   */
  @NotNull
  public Optional<ResolvedProduct> resolveByProductKey(@Nullable String productKey) {
    if (productKey == null || productKey.isBlank()) {
      return Optional.empty();
    }
    ProductAccumulator p = buildProductMap("").get(productKey);
    return p == null
        ? Optional.empty()
        : Optional.of(new ResolvedProduct(p.productKey, p.displayName, p.outputItemId));
  }

  /**
   * Resolves a game item (a Lager item-stock row's catalog reference) to its blueprint product —
   * the identity bridge for a <b>stock-backed</b> Materialbörse item offer (design §8,
   * REQ-MARKET-014, ADR-0108). A stock row keys on a {@link
   * de.greluc.krt.profit.basetool.backend.model.GameItem}, but an offer keys on the blueprint
   * {@code product_key} (ADR-0087), so a release from item stock derives the key + snapshot name
   * from the row's game item: of the active blueprints that produce it, it normalizes each {@code
   * outputName} to a product key and picks the lowest key — a <b>deterministic</b> choice when a
   * game item has several producing blueprints, since {@code findByOutputItemId} carries no {@code
   * ORDER BY} — then resolves that back to the canonical product — the <em>same</em> {@link
   * ResolvedProduct} a free-stated item offer of the same item would carry, so both flavours share
   * one identity. The item-catalog predicate (REQ-INV-029, {@code findItemsWithActiveBlueprint})
   * guarantees a stocked game item has such a blueprint, so the key resolves; a game item with no
   * (longer any) active blueprint yields empty and the caller rejects the release.
   *
   * @param gameItemId the game item to resolve, or {@code null}
   * @return the resolved blueprint product for that game item, or empty if the id is {@code null}
   *     or the game item is not produced by any active blueprint
   */
  @NotNull
  public Optional<ResolvedProduct> resolveByGameItem(@Nullable UUID gameItemId) {
    if (gameItemId == null) {
      return Optional.empty();
    }
    return blueprintRepository.findByOutputItemId(gameItemId).stream()
        .map(Blueprint::getOutputName)
        .filter(name -> name != null && !name.isBlank())
        .map(normalizer::normalize)
        .filter(key -> !key.isEmpty())
        // Sort the candidate product keys so the pick is deterministic when several active
        // blueprints produce this game item (findByOutputItemId has no ORDER BY); the lowest key
        // keeps the derived identity stable across runs.
        .sorted()
        .findFirst()
        .flatMap(this::resolveByProductKey);
  }

  /**
   * Resolves a normalized product key to the recipe graph of a representative SC Wiki recipe for
   * the Personal Inventory blueprint view (#327): the build slots with their ingredients and
   * per-quality stat modifiers, plus the count of recipe variants collapsing into the product.
   * Returns empty for a blank key or one that no active recipe produces.
   *
   * <p>Resolution mirrors the product grouping used by {@link #searchProducts}: active recipes are
   * grouped by the {@link BlueprintNameNormalizer}-normalized output name; the first recipe (in the
   * deterministic scan order of {@code findActiveIdNameRows}) of the matching group is the
   * representative whose graph is mapped. The mapping touches the lazy recipe collections, so the
   * call must run inside the read transaction this service declares.
   *
   * @param productKey normalized product key (see {@link BlueprintNameNormalizer})
   * @return the representative recipe view, or empty if the key is blank or unknown
   */
  @NotNull
  public Optional<PersonalBlueprintRecipeResponse> resolveRecipe(@Nullable String productKey) {
    if (productKey == null || productKey.isBlank()) {
      return Optional.empty();
    }
    UUID representativeId = null;
    String displayName = null;
    int variantCount = 0;
    for (BlueprintIdNameRow row : blueprintRepository.findActiveIdNameRows()) {
      if (row.outputName() == null || !normalizer.normalize(row.outputName()).equals(productKey)) {
        continue;
      }
      if (representativeId == null) {
        representativeId = row.id();
        displayName = row.outputName();
      }
      variantCount++;
    }
    if (representativeId == null) {
      return Optional.empty();
    }
    Blueprint recipe = blueprintRepository.findById(representativeId).orElse(null);
    if (recipe == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PersonalBlueprintRecipeResponse(
            displayName,
            variantCount,
            blueprintMapper.toGroupDtos(recipe.getRequirementGroups()),
            blueprintMapper.toIngredientDtos(recipe.getIngredients())));
  }

  /**
   * Resolves a batch of normalized product keys to their representative recipe entities in one
   * pass, for the blueprint craftability calculation (#781). Mirrors {@link #resolveRecipe(String)}
   * but scans the active master once for the whole set: the first recipe (in the deterministic
   * {@code findActiveIdNameRows} order) of each matching group is the representative — the
   * <em>same</em> recipe {@code resolveRecipe} picks, so a craftability overlay aligns
   * index-for-index with the recipe view. The returned entities are managed; the caller must touch
   * their lazy recipe collections inside this service's read transaction.
   *
   * @param productKeys the normalized product keys to resolve (blank/null entries are ignored)
   * @return a map from product key to its representative {@link Blueprint}; keys with no active
   *     recipe are absent
   */
  @NotNull
  public Map<String, Blueprint> resolveRepresentativeBlueprints(
      @NotNull Collection<String> productKeys) {
    Set<String> wanted = new HashSet<>();
    for (String key : productKeys) {
      if (key != null && !key.isBlank()) {
        wanted.add(key);
      }
    }
    if (wanted.isEmpty()) {
      return Map.of();
    }
    Map<String, UUID> firstId = new LinkedHashMap<>();
    for (BlueprintIdNameRow row : blueprintRepository.findActiveIdNameRows()) {
      if (row.outputName() == null) {
        continue;
      }
      String key = normalizer.normalize(row.outputName());
      if (wanted.contains(key)) {
        firstId.putIfAbsent(key, row.id());
      }
    }
    if (firstId.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Blueprint> byId = new HashMap<>();
    for (Blueprint blueprint : blueprintRepository.findAllById(firstId.values())) {
      byId.put(blueprint.getId(), blueprint);
    }
    Map<String, Blueprint> resolved = new LinkedHashMap<>();
    firstId.forEach(
        (key, id) -> {
          Blueprint blueprint = byId.get(id);
          if (blueprint != null) {
            resolved.put(key, blueprint);
          }
        });
    return resolved;
  }

  /**
   * Returns every active product as a {@link ResolvedProduct} (normalized key + display name +
   * optional resolved output-item id), de-duplicated by product key. Backs the Phase 4 import
   * matching engine, which needs the full candidate set in memory to score fuzzy suggestions.
   *
   * @return all active products, de-duplicated by normalized product key, in master-scan order
   */
  @NotNull
  public List<ResolvedProduct> allProducts() {
    return buildProductMap("").values().stream()
        .map(p -> new ResolvedProduct(p.productKey, p.displayName, p.outputItemId))
        .toList();
  }

  /**
   * Builds the index from a blueprint's structural key (lower-cased, trimmed {@code scwiki_key}) to
   * its normalized {@code product_key}, over every active recipe. Backs the scmdb.net import's
   * high-confidence <em>tag match</em> (REQ-INV-019): an scmdb.net export entry carries the
   * DataForge blueprint key under {@code tag}, which equals a blueprint's {@code scwiki_key}, so
   * the import can resolve it straight to the owned product — bypassing the name chain and the
   * CIG-mislabel pitfalls the name match has to correct for (REQ-INV-007).
   *
   * <p>The key is lower-cased because the two sources spell the same DataForge identifier with
   * different casing (the Wiki keeps CamelCase like {@code BP_CRAFT_AMRS_LaserCannon_S1}; scmdb.net
   * lower-cases it). A structural key that maps to two <em>different</em> product keys (a duplicate
   * {@code scwiki_key} across recipes with diverging output names — possible because {@code
   * scwiki_key} is not UNIQUE) is <strong>excluded</strong> rather than resolved to an arbitrary
   * one, so the tag match only ever fires when unambiguous and the import falls back to the name
   * chain for that entry.
   *
   * @return structural key (lower-cased {@code scwiki_key}) → normalized {@code product_key}, with
   *     ambiguous keys removed; never {@code null}
   */
  @NotNull
  public Map<String, String> scwikiKeyToProductKeyIndex() {
    Map<String, String> index = new LinkedHashMap<>();
    Set<String> ambiguous = new HashSet<>();
    for (BlueprintProductRow row : blueprintRepository.findActiveProductRows("")) {
      if (row.scwikiKey() == null || row.outputName() == null) {
        continue;
      }
      String tagKey = row.scwikiKey().trim().toLowerCase(Locale.ROOT);
      if (tagKey.isEmpty()) {
        continue;
      }
      String productKey = normalizer.normalize(row.outputName());
      if (productKey.isEmpty()) {
        continue;
      }
      String existing = index.putIfAbsent(tagKey, productKey);
      if (existing != null && !existing.equals(productKey)) {
        ambiguous.add(tagKey);
      }
    }
    ambiguous.forEach(index::remove);
    return index;
  }

  /**
   * Loads the active blueprint rows matching {@code q} and groups them by normalized product key,
   * preserving first-seen order. Each group records the first display name, the recipe count, and
   * the first non-null example key / manufacturer / output-item id.
   *
   * @param q case-insensitive output-name substring ({@code ""} = no filter)
   * @return product accumulators keyed by normalized product key
   */
  private Map<String, ProductAccumulator> buildProductMap(String q) {
    Map<String, ProductAccumulator> map = new LinkedHashMap<>();
    for (BlueprintProductRow row : blueprintRepository.findActiveProductRows(q)) {
      if (row.outputName() == null) {
        continue;
      }
      String key = normalizer.normalize(row.outputName());
      if (key.isEmpty()) {
        continue;
      }
      ProductAccumulator acc =
          map.computeIfAbsent(key, k -> new ProductAccumulator(k, row.outputName()));
      acc.variantCount++;
      if (acc.exampleKey == null && row.scwikiKey() != null) {
        acc.exampleKey = row.scwikiKey();
      }
      if (acc.manufacturerName == null && row.manufacturerName() != null) {
        acc.manufacturerName = row.manufacturerName();
      }
      if (acc.outputItemId == null && row.outputItemId() != null) {
        acc.outputItemId = row.outputItemId();
      }
    }
    return map;
  }

  /**
   * Returns the subset of {@code keys} the owner already owns, via a single bulk lookup.
   *
   * @param ownerSub Keycloak {@code sub} of the owner
   * @param keys the product keys to test
   * @return the owned product keys
   */
  private Set<String> ownedKeys(String ownerSub, List<String> keys) {
    if (keys.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (PersonalBlueprint pb :
        personalBlueprintRepository.findAllByOwnerSubAndProductKeyIn(ownerSub, keys)) {
      out.add(pb.getProductKey());
    }
    return out;
  }

  /** Mutable per-product grouping accumulator used while collapsing recipe rows into products. */
  private static final class ProductAccumulator {
    private final String productKey;
    private final String displayName;
    private int variantCount;
    private String exampleKey;
    private String manufacturerName;
    private UUID outputItemId;

    private ProductAccumulator(String productKey, String displayName) {
      this.productKey = productKey;
      this.displayName = displayName;
    }
  }

  /**
   * A product key resolved back to its canonical product, for stamping a new ownership row.
   *
   * @param productKey normalized product key
   * @param productName canonical display name
   * @param outputItemId resolved output {@code game_item} id, or {@code null} if unresolved
   */
  public record ResolvedProduct(String productKey, String productName, UUID outputItemId) {}
}
