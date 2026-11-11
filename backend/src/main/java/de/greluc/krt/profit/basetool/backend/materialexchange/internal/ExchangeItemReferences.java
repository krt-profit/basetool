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
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Resolves the items Materialbörse item offers and requests name, by blueprint product key or by
 * catalogue row (ADR-0371), to game-item references, so the board can show a component's type,
 * size, grade and class (REQ-MARKET-023).
 *
 * <p>A whole page resolves in at most three queries, whatever its size; a page without an item row
 * issues none.
 */
@Component
@RequiredArgsConstructor
public class ExchangeItemReferences {

  /** Maps product keys to the game item each blueprint product produces. */
  private final BlueprintProducts blueprintProducts;

  /** Loads the resolved game items in one batch. */
  private final GameItemRepository gameItemRepository;

  /**
   * Resolves the identities of a page's item entries to game-item references.
   *
   * @param productKeys the product keys of the page's item entries; {@code null} and blank entries
   *     are ignored
   * @param gameItemIds the catalogue rows of the page's item entries; {@code null} entries are
   *     ignored
   * @return the references, looked up by each entry's identity
   */
  @NotNull
  public PageItems ofPage(
      @NotNull Collection<@Nullable String> productKeys,
      @NotNull Collection<@Nullable UUID> gameItemIds) {
    Set<String> keys = new LinkedHashSet<>();
    for (String key : productKeys) {
      if (key != null && !key.isBlank()) {
        keys.add(key);
      }
    }
    Set<UUID> rows = new LinkedHashSet<>();
    for (UUID id : gameItemIds) {
      if (id != null) {
        rows.add(id);
      }
    }
    Map<String, UUID> itemIdsByKey =
        keys.isEmpty() ? Map.of() : blueprintProducts.outputItemIdsByProductKey(keys);
    Set<UUID> ids = new LinkedHashSet<>(itemIdsByKey.values());
    ids.addAll(rows);
    if (ids.isEmpty()) {
      return PageItems.EMPTY;
    }
    Map<UUID, GameItemReferenceDto> byId = referencesById(ids);
    Map<String, GameItemReferenceDto> byKey = new HashMap<>();
    itemIdsByKey.forEach((key, id) -> {
      GameItemReferenceDto reference = byId.get(id);
      if (reference != null) {
        byKey.put(key, reference);
      }
    });
    Map<UUID, GameItemReferenceDto> byRow = new HashMap<>();
    for (UUID row : rows) {
      GameItemReferenceDto reference = byId.get(row);
      if (reference != null) {
        byRow.put(row, reference);
      }
    }
    return new PageItems(Map.copyOf(byKey), Map.copyOf(byRow));
  }

  /**
   * Resolves the identity of one item entry, for a single detail read.
   *
   * @param productKey the entry's product key, or {@code null}
   * @param gameItemId the entry's catalogue row, or {@code null}
   * @return the reference, or {@code null} when the identity names no catalogued game item
   */
  @Nullable
  public GameItemReferenceDto ofEntry(@Nullable String productKey, @Nullable UUID gameItemId) {
    Set<String> keys = productKey == null ? Set.of() : Set.of(productKey);
    Set<UUID> rows = gameItemId == null ? Set.of() : Set.of(gameItemId);
    return ofPage(keys, rows).of(productKey, gameItemId);
  }

  /**
   * Loads game-item references by id, following the redirect of an id a catalogue reconciliation
   * retired to its surviving item.
   *
   * @param ids the ids to resolve; not empty
   * @return the resolvable ids' references
   */
  @NotNull
  private Map<UUID, GameItemReferenceDto> referencesById(@NotNull Set<UUID> ids) {
    Map<UUID, GameItemReferenceDto> loaded = new HashMap<>();
    for (GameItem item : gameItemRepository.findAllById(ids)) {
      loaded.put(item.getId(), GameItemReferenceDto.of(item));
    }
    Set<UUID> unresolved = new LinkedHashSet<>(ids);
    unresolved.removeAll(loaded.keySet());
    Map<UUID, UUID> redirects =
        unresolved.isEmpty() ? Map.of() : gameItemRepository.findLocalIdRedirects(unresolved);
    Map<UUID, GameItemReferenceDto> byId = new HashMap<>();
    for (UUID id : ids) {
      GameItemReferenceDto reference = loaded.get(redirects.getOrDefault(id, id));
      if (reference != null) {
        byId.put(id, reference);
      }
    }
    return byId;
  }

  /**
   * The game items of a page's item entries, by product key and by catalogue row.
   *
   * @param byProductKey product key to reference
   * @param byGameItemId catalogue row to reference
   */
  public record PageItems(
      @NotNull @Unmodifiable Map<String, GameItemReferenceDto> byProductKey,
      @NotNull @Unmodifiable Map<UUID, GameItemReferenceDto> byGameItemId) {

    /** The page without item entries. */
    static final PageItems EMPTY = new PageItems(Map.of(), Map.of());

    /**
     * Looks up the reference of one entry's identity.
     *
     * @param productKey the entry's product key, or {@code null}
     * @param gameItemId the entry's catalogue row, or {@code null}
     * @return the reference, or {@code null} when the identity is absent or names no catalogued
     *     game item
     */
    @Nullable
    public GameItemReferenceDto of(@Nullable String productKey, @Nullable UUID gameItemId) {
      if (productKey != null) {
        return byProductKey.get(productKey);
      }
      return gameItemId == null ? null : byGameItemId.get(gameItemId);
    }
  }
}
