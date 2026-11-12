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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintProducts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The game-item references of Materialbörse item rows (REQ-MARKET-023, ADR-0372). */
@ExtendWith(MockitoExtension.class)
class ExchangeItemReferencesTest {

  @Mock private BlueprintProducts blueprintProducts;
  @Mock private GameItemRepository gameItemRepository;

  private ExchangeItemReferences references;
  private GameItem shield;
  private GameItem paint;
  private GameItem railgun;

  @BeforeEach
  void setUp() {
    references = new ExchangeItemReferences(blueprintProducts, gameItemRepository);
    shield = new GameItem();
    shield.setId(UUID.randomUUID());
    shield.setName("FR-66");
    shield.setKind(GameItemKind.VEHICLE_ITEM);
    shield.setWikiType("Shield");
    shield.setSizeClass(1);
    shield.setGrade("A");
    shield.setItemClass(ShipComponentClass.MILITARY);
    paint = new GameItem();
    paint.setId(UUID.randomUUID());
    paint.setName("Paint");
    paint.setKind(GameItemKind.VEHICLE_ITEM);
    paint.setWikiType("Paints");
    paint.setGrade("A");
    railgun = new GameItem();
    railgun.setId(UUID.randomUUID());
    railgun.setName("Scourge Railgun");
    railgun.setKind(GameItemKind.WEAPON);
    railgun.setWikiType("WeaponPersonal");
  }

  @Test
  void aWholePageResolvesInOneBatchWithTheComponentDataOfAComponentOnly() {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      keys.add(i % 2 == 0 ? "fr-66" : "paint");
    }
    keys.add("ghost");
    keys.add(null);
    when(blueprintProducts.outputItemIdsByProductKey(anyCollection()))
        .thenReturn(Map.of("fr-66", shield.getId(), "paint", paint.getId()));
    when(gameItemRepository.findAllById(any())).thenReturn(List.of(shield, paint, railgun));

    ExchangeItemReferences.PageItems resolved =
        references.ofPage(keys, Arrays.asList(railgun.getId(), null, railgun.getId()));

    assertThat(resolved.byProductKey())
        .containsOnly(
            Map.entry(
                "fr-66",
                new GameItemReferenceDto(
                    shield.getId(), "FR-66", "VEHICLE_ITEM", "SHIELD", 1, "A", "MILITARY")),
            Map.entry("paint", new GameItemReferenceDto(paint.getId(), "Paint", "VEHICLE_ITEM")));
    assertThat(resolved.byGameItemId())
        .containsOnly(Map.entry(railgun.getId(), GameItemReferenceDto.of(railgun)));
    verify(blueprintProducts, times(1)).outputItemIdsByProductKey(anyCollection());
    verify(gameItemRepository, times(1)).findAllById(any());
  }

  @Test
  void aPageWithoutAnItemRowIssuesNoQuery() {
    assertThat(references.ofPage(Arrays.asList(null, " "), Arrays.asList((UUID) null)))
        .isEqualTo(ExchangeItemReferences.PageItems.EMPTY);
    assertThat(references.ofEntry(null, null)).isNull();
    verifyNoInteractions(blueprintProducts, gameItemRepository);
  }

  @Test
  void aPageOfCatalogueRowsOnlyLooksUpNoProduct() {
    when(gameItemRepository.findAllById(any())).thenReturn(List.of(railgun));

    assertThat(references.ofEntry(null, railgun.getId()))
        .isEqualTo(GameItemReferenceDto.of(railgun));
    verifyNoInteractions(blueprintProducts);
  }

  @Test
  void aKeyWithoutAnOutputItemLoadsNoGameItem() {
    when(blueprintProducts.outputItemIdsByProductKey(anyCollection())).thenReturn(Map.of());

    assertThat(references.ofEntry("ghost", null)).isNull();
    verify(gameItemRepository, never()).findAllById(any());
  }

  @Test
  void aProductReadThatRacesWithReconciliationKeepsItsResolvedItemReference() {
    UUID retiredId = UUID.randomUUID();
    when(blueprintProducts.outputItemIdsByProductKey(anyCollection()))
        .thenReturn(Map.of("fr-66", retiredId));
    when(gameItemRepository.findAllById(any())).thenReturn(List.of(shield));
    when(gameItemRepository.findLocalIdRedirects(anyCollection()))
        .thenReturn(Map.of(retiredId, shield.getId()));

    assertThat(references.ofEntry("fr-66", null)).isEqualTo(GameItemReferenceDto.of(shield));
  }

  @Test
  void aCatalogueRowTheReconciliationRetiredResolvesToItsSurvivor() {
    UUID retiredId = UUID.randomUUID();
    when(gameItemRepository.findAllById(any())).thenReturn(List.of(railgun));
    when(gameItemRepository.findLocalIdRedirects(anyCollection()))
        .thenReturn(Map.of(retiredId, railgun.getId()));

    assertThat(references.ofEntry(null, retiredId)).isEqualTo(GameItemReferenceDto.of(railgun));
  }
}
