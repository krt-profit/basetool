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

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached index of the active blueprint master grouped into variant families: {@code familyKey ->}
 * the set of concrete normalized product keys that belong to that family. It exists so the
 * family-aware owner drill-down on the org-unit availability overview (#364) can expand a family to
 * its product keys and fetch owners in one bounded {@code IN} query, instead of a per-expand table
 * scan that would violate the drill-down performance contract (REQ-INV-012).
 *
 * <p>The index is built once from the ~1600-row active blueprint master ({@link
 * BlueprintRepository#findActiveProductRows}) and cached as a single entry under {@link
 * CacheConfig#BLUEPRINT_FAMILY_INDEX_CACHE} (master-data TTL). It carries no per-write evict hook,
 * but the SC Wiki sync sweep evicts it on completion (via {@code MasterDataCacheEvictionService},
 * CACHE-DIST-03), so a blueprint sync is reflected on the next read rather than lagging the TTL;
 * the 12-hour TTL is only the backstop. Even at maximum staleness this is an oversight surface,
 * where the live availability count comes from the (always-fresh) owned-row aggregation and only
 * the lazy owner drill-down consults this index.
 */
@Component
@RequiredArgsConstructor
public class BlueprintVariantFamilyCatalog {

  private final BlueprintRepository blueprintRepository;
  private final BlueprintNameNormalizer normalizer;
  private final BlueprintVariantFamilyResolver familyResolver;

  /**
   * Returns the family index: each variant family key mapped to the deeply-immutable set of
   * concrete product keys ({@code normalize(output_name)}) that resolve to it across the active
   * blueprint master. A weapon family holds its base plus every cosmetic variant; a magazine family
   * holds only its single atomic key. The result is cached as one entry and must be treated as
   * read-only.
   *
   * @return an immutable {@code familyKey -> product keys} map; never {@code null}
   */
  @NotNull
  @Cacheable(cacheNames = CacheConfig.BLUEPRINT_FAMILY_INDEX_CACHE)
  @Transactional(readOnly = true)
  public Map<String, Set<String>> familyIndex() {
    Map<String, Set<String>> index = new HashMap<>();
    for (BlueprintProductRow row : blueprintRepository.findActiveProductRows("")) {
      String outputName = row.outputName();
      if (outputName == null) {
        continue;
      }
      String productKey = normalizer.normalize(outputName);
      if (productKey.isEmpty()) {
        continue;
      }
      String familyKey = familyResolver.familyKey(outputName);
      if (familyKey.isEmpty()) {
        continue;
      }
      index.computeIfAbsent(familyKey, k -> new HashSet<>()).add(productKey);
    }
    // Deep-freeze before caching: the cached reference is shared across requests and must not be
    // mutated by any consumer.
    Map<String, Set<String>> frozen = new HashMap<>(index.size());
    index.forEach((family, keys) -> frozen.put(family, Set.copyOf(keys)));
    return Map.copyOf(frozen);
  }
}
