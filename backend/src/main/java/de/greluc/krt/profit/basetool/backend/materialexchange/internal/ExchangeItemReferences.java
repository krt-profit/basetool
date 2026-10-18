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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintProducts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the blueprint product keys of Materialbörse item offers and requests to the game items
 * they name, so the board can show a component's type, size, grade and class (REQ-MARKET-023).
 *
 * <p>A whole page resolves in two queries, whatever its size; a page without an item row issues
 * none.
 */
@Component
@RequiredArgsConstructor
public class ExchangeItemReferences {

  /** Maps product keys to the game item each blueprint product produces. */
  private final BlueprintProducts blueprintProducts;

  /** Loads the resolved game items in one batch. */
  private final GameItemRepository gameItemRepository;

  /**
   * Resolves product keys to game-item references.
   *
   * @param productKeys the product keys of a page's item rows; {@code null} and blank entries are
   *     ignored
   * @return product key to reference; keys that name no catalogued game item are absent
   */
  @NotNull
  public Map<String, GameItemReferenceDto> byProductKey(
      @NotNull Collection<@Nullable String> productKeys) {
    Set<String> keys = new LinkedHashSet<>();
    for (String key : productKeys) {
      if (key != null && !key.isBlank()) {
        keys.add(key);
      }
    }
    if (keys.isEmpty()) {
      return Map.of();
    }
    Map<String, UUID> itemIds = blueprintProducts.outputItemIdsByProductKey(keys);
    if (itemIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, GameItemReferenceDto> byId = new HashMap<>();
    for (GameItem item : gameItemRepository.findAllById(Set.copyOf(itemIds.values()))) {
      byId.put(item.getId(), GameItemReferenceDto.of(item));
    }
    Map<String, GameItemReferenceDto> references = new HashMap<>();
    itemIds.forEach(
        (key, id) -> {
          GameItemReferenceDto reference = byId.get(id);
          if (reference != null) {
            references.put(key, reference);
          }
        });
    return references;
  }

  /**
   * Resolves one product key, for a single detail read.
   *
   * @param productKey the product key, or {@code null}
   * @return the reference, or {@code null} when the key names no catalogued game item
   */
  @Nullable
  public GameItemReferenceDto ofProductKey(@Nullable String productKey) {
    if (productKey == null) {
      return null;
    }
    return byProductKey(Set.of(productKey)).get(productKey);
  }
}
