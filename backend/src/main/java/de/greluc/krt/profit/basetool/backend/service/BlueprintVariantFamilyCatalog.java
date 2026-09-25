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
 * Cached index of the active blueprint master grouped into variant families ({@code familyKey ->}
 * product keys), backing the family-aware owner drill-down of the org-unit availability overview
 * (REQ-INV-012).
 *
 * <p>Cached under {@link CacheConfig#BLUEPRINT_FAMILY_INDEX_CACHE}; evicted when the SC Wiki sync
 * completes, with the master-data TTL as backstop.
 */
@Component
@RequiredArgsConstructor
public class BlueprintVariantFamilyCatalog {

  private final BlueprintRepository blueprintRepository;
  private final BlueprintNameNormalizer normalizer;
  private final BlueprintVariantFamilyResolver familyResolver;

  /**
   * Returns the family index: each variant family key mapped to the immutable set of product keys
   * that resolve to it. The cached result must be treated as read-only.
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
    Map<String, Set<String>> frozen = new HashMap<>(index.size());
    index.forEach((family, keys) -> frozen.put(family, Set.copyOf(keys)));
    return Map.copyOf(frozen);
  }
}
