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
 * Read service for the blueprint product search: active recipes grouped by normalized {@code
 * product_key} into products, each with a variant count, an example Wiki key, the manufacturer and
 * an owned-by-caller flag.
 *
 * <p>Grouping happens in memory, because PostgreSQL cannot compute the normalized key.
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
   * Searches blueprint products by case-insensitive name substring, flagging those {@code
   * ownerUserId} already owns.
   *
   * @param query product-name substring; {@code null} / blank returns all products
   * @param limit requested maximum; clamped to {@code [1, MAX_LIMIT]}
   * @param ownerUserId {@code app_user.id} of the caller, for the owned flag
   * @return the matching products, alphabetically by name
   */
  @NotNull
  public List<BlueprintProductDto> searchProducts(
      @Nullable String query, int limit, @NotNull UUID ownerUserId) {
    int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
    String q = query == null ? "" : query.trim();

    List<ProductAccumulator> products = new ArrayList<>(buildProductMap(q).values());
    products.sort(
        Comparator.comparing(
            p -> p.displayName, Comparator.nullsLast(String::compareToIgnoreCase)));
    List<ProductAccumulator> capped = products.size() > cap ? products.subList(0, cap) : products;

    Set<String> owned = ownedKeys(ownerUserId, capped.stream().map(p -> p.productKey).toList());
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
   * Resolves a normalized product key to its canonical product (display name and optional
   * output-item id).
   *
   * @param productKey normalized product key
   * @return the resolved product, or empty for a blank key or one no active blueprint produces
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
   * Resolves a game item to its blueprint product, the identity of a stock-backed Materialbörse
   * item offer (REQ-MARKET-014, ADR-0108).
   *
   * <p>Of the active blueprints producing the item, the lowest normalized product key is chosen, so
   * the result is deterministic and equals that of a free-stated offer for the same item.
   *
   * @param gameItemId the game item to resolve, or {@code null}
   * @return the resolved product, or empty if the id is {@code null} or no active blueprint
   *     produces the item
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
        .sorted()
        .findFirst()
        .flatMap(this::resolveByProductKey);
  }

  /**
   * Resolves a normalized product key to the recipe graph of its representative recipe: build slots
   * with ingredients and per-quality stat modifiers, plus the variant count.
   *
   * <p>The representative is the first recipe of the group in {@code findActiveIdNameRows} order.
   * Must run inside this service's read transaction.
   *
   * @param productKey normalized product key (see {@link BlueprintNameNormalizer})
   * @return the recipe view, or empty if the key is blank or unknown
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
   * Resolves product keys to their representative recipe entities in one pass, picking the same
   * recipe as {@link #resolveRecipe(String)}. The entities are managed; their lazy collections must
   * be read inside this service's read transaction.
   *
   * @param productKeys the normalized product keys (blank/null entries are ignored)
   * @return product key to representative {@link Blueprint}; keys with no active recipe are absent
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
   * Builds the index from lower-cased {@code scwiki_key} to normalized {@code product_key} over
   * every active recipe, backing the scmdb.net import's tag match (REQ-INV-019).
   *
   * <p>A structural key that maps to two different product keys is excluded.
   *
   * @return lower-cased {@code scwiki_key} to {@code product_key}, ambiguous keys removed; never
   *     {@code null}
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
   * Loads the active blueprint rows matching {@code q} and groups them by normalized product key in
   * first-seen order.
   *
   * @param q case-insensitive output-name substring ({@code ""} = no filter)
   * @return product accumulators keyed by normalized product key
   */
  @NotNull
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
   * @param ownerUserId {@code app_user.id} of the owner
   * @param keys the product keys to test
   * @return the owned product keys
   */
  @NotNull
  private Set<String> ownedKeys(UUID ownerUserId, List<String> keys) {
    if (keys.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (PersonalBlueprint pb :
        personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(ownerUserId, keys)) {
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
