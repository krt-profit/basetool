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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemCatalogueDetailsDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemShopAvailabilityDto;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads member catalogue evidence through the canonical, redirect-aware item identity. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemCatalogueDetailsService {
  private final GameItemRepository repository;
  private final ShopRepository shopRepository;

  /**
   * Loads an item's scalar identity, immutable Wiki snapshot and the shops whose known item lists
   * name it.
   *
   * @param id current or redirected local item id
   * @return identity, source evidence and the shops, one per name and place
   */
  public ItemCatalogueDetailsDto get(@NotNull UUID id) {
    var item = Entities.require(repository.findById(id), "Game item not found");
    return new ItemCatalogueDetailsDto(
        item.getId(),
        item.getName(),
        item.getExternalUuid(),
        item.getClassName(),
        item.getWikiMetadata(),
        availableAt(item.getId()));
  }

  /**
   * Lists the shops whose known item list names an item; placements of one shop module at one place
   * read the same to a member, so each is listed once (REQ-P4K-044).
   *
   * @param itemId the item
   * @return the shops in star system and name order
   */
  @NotNull
  private List<ItemShopAvailabilityDto> availableAt(@NotNull UUID itemId) {
    Map<List<String>, ItemShopAvailabilityDto> distinct = new LinkedHashMap<>();
    for (ItemShopAvailabilityDto shop : shopRepository.findAvailability(itemId)) {
      distinct.putIfAbsent(
          Arrays.asList(
              shop.terminalName(),
              shop.registryName() == null ? shop.entityName() : shop.registryName(),
              shop.starSystem(),
              shop.placeName()),
          shop);
    }
    return List.copyOf(distinct.values());
  }
}
