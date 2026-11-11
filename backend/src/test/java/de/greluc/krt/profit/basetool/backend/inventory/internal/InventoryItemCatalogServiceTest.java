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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link InventoryItemCatalogService}, the Lager and item-order picker reads
 * (REQ-INV-029): the Lager read asks for the {@link StockableItemGroup} types, a blank search binds
 * the empty string rather than {@code null}, and results map through {@code
 * InventoryItemMapper.gameItemToReferenceDto}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemCatalogServiceTest {

  @Mock private BlueprintRepository blueprintRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @InjectMocks private InventoryItemCatalogService service;

  @Test
  void findBookableItems_projectsRepositoryPageThroughTheMapper() {
    GameItem railgun = new GameItem();
    railgun.setId(UUID.randomUUID());
    railgun.setName("Scourge Railgun");
    Pageable pageable = PageRequest.of(0, 20);
    Page<GameItem> page = new PageImpl<>(List.of(railgun), pageable, 1);
    InventoryGameItemReferenceDto ref =
        new InventoryGameItemReferenceDto(railgun.getId(), "Scourge Railgun", "Behring", "WEAPON");
    when(stockable(gameItemRepository, "Scourge", pageable)).thenReturn(page);
    when(inventoryItemMapper.gameItemToReferenceDto(railgun)).thenReturn(ref);

    Page<InventoryGameItemReferenceDto> result = service.findBookableItems(" Scourge ", pageable);

    assertThat(result.getContent()).containsExactly(ref);
    assertThat(result.getTotalElements()).isEqualTo(1L);
    stockable(verify(gameItemRepository), "Scourge", pageable);
  }

  @Test
  void findBookableItems_blankSearch_bindsEmptyStringNotNull() {
    Pageable pageable = PageRequest.of(0, 20);
    when(stockable(gameItemRepository, "", pageable)).thenReturn(Page.empty(pageable));

    service.findBookableItems("   ", pageable);
    service.findBookableItems(null, pageable);

    stockable(verify(gameItemRepository, times(2)), "", pageable);
  }

  @Test
  void findBookableItems_escapesLikeMetacharactersInTheSearch() {
    Pageable pageable = PageRequest.of(0, 20);
    when(stockable(gameItemRepository, "50\\%\\_x", pageable)).thenReturn(Page.empty(pageable));

    service.findBookableItems(" 50%_x ", pageable);

    stockable(verify(gameItemRepository), "50\\%\\_x", pageable);
  }

  @Test
  void findOrderableItems_projectsRepositoryPageThroughTheMapperAndNeverTouchesTheBookableQuery() {
    GameItem scope = new GameItem();
    scope.setId(UUID.randomUUID());
    scope.setName("Cirrus Scope");
    Pageable pageable = PageRequest.of(0, 20);
    Page<GameItem> page = new PageImpl<>(List.of(scope), pageable, 1);
    InventoryGameItemReferenceDto ref =
        new InventoryGameItemReferenceDto(scope.getId(), "Cirrus Scope", "Behring", "WEAPON");
    when(blueprintRepository.findOrderableItems("Cirrus", pageable)).thenReturn(page);
    when(inventoryItemMapper.gameItemToReferenceDto(scope)).thenReturn(ref);

    Page<InventoryGameItemReferenceDto> result = service.findOrderableItems(" Cirrus ", pageable);

    assertThat(result.getContent()).containsExactly(ref);
    verifyNoInteractions(gameItemRepository);
  }

  @Test
  void findOrderableItems_escapesLikeMetacharactersAndBindsEmptyForBlank() {
    Pageable pageable = PageRequest.of(0, 20);
    when(blueprintRepository.findOrderableItems("50\\%\\_x", pageable))
        .thenReturn(Page.empty(pageable));
    when(blueprintRepository.findOrderableItems("", pageable)).thenReturn(Page.empty(pageable));

    service.findOrderableItems(" 50%_x ", pageable);
    service.findOrderableItems(null, pageable);

    verify(blueprintRepository).findOrderableItems("50\\%\\_x", pageable);
    verify(blueprintRepository).findOrderableItems("", pageable);
  }

  /**
   * Sends the Lager read the service is expected to send, to a mock for stubbing or to a {@code
   * verify} proxy.
   *
   * @param repository the mock or its verification proxy
   * @param term the escaped search term
   * @param pageable the page request
   * @return the mock's answer
   */
  private static Page<GameItem> stockable(
      GameItemRepository repository, String term, Pageable pageable) {
    return repository.findStockable(
        term, StockableItemGroup.TYPES, StockableItemGroup.EXCLUDED_TYPE_SUB_TYPES, pageable);
  }
}
