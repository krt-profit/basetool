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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies bounded price reads and collision choices through the real reconciliation service. */
@ExtendWith(MockitoExtension.class)
class ItemReconciliationServiceTest {
  @Mock private GameItemRepository items;
  @Mock private GameItemRedirectRepository redirects;
  @Mock private CatalogueItemReassignmentRepository recipes;
  @Mock private GameItemPriceRepository prices;
  @Mock private GameItemStatRepository stats;
  @Mock private SyncReportService reports;

  @Test
  void manyPricePairsAreLoadedInTwoReadsAndKeepTheirNewestValues() {
    GameItem retired = item(GameItemSourceSystem.UEX_ONLY);
    retired.setUexItemId(91);
    GameItem canonical = item(GameItemSourceSystem.WIKI_ONLY);
    canonical.setExternalUuid(UUID.randomUUID());
    when(items.lockReconciliationRows(any())).thenReturn(List.of(retired, canonical));
    when(redirects.snapshot(retired.getId(), canonical.getId())).thenReturn("{}");
    List<GameItemPrice> source = new ArrayList<>();
    List<GameItemPrice> target = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      Terminal terminal = new Terminal();
      terminal.setId(UUID.randomUUID());
      source.add(price(retired, terminal, 11, 20));
      if (index < 10) {
        target.add(price(canonical, terminal, 22, 10));
      }
    }
    when(prices.findByGameItemId(retired.getId())).thenReturn(source);
    when(prices.findByGameItemId(canonical.getId())).thenReturn(target);
    var service =
        new ItemReconciliationService(items, redirects, recipes, prices, stats, List.of(), reports);

    assertThat(
            service.mergeExisting(
                canonical,
                retired.getId(),
                UUID.randomUUID(),
                new ItemReconciliationService.WikiIdentity(
                    "Same Item", GameItemKind.GENERIC, null, null)))
        .isSameAs(canonical);

    verify(prices).findByGameItemId(retired.getId());
    verify(prices).findByGameItemId(canonical.getId());
    verify(prices, never()).findByGameItemIdAndTerminalId(any(), any());
    assertThat(target).allSatisfy(price -> assertThat(price.getPriceBuy()).isEqualTo(11d));
    assertThat(source.subList(10, 20))
        .allSatisfy(price -> assertThat(price.getGameItem()).isSameAs(canonical));
  }

  private static GameItem item(GameItemSourceSystem source) {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName("Same Item");
    item.setSourceSystems(source);
    return item;
  }

  private static GameItemPrice price(
      GameItem item, Terminal terminal, double value, long modified) {
    GameItemPrice price = new GameItemPrice();
    price.setId(UUID.randomUUID());
    price.setGameItem(item);
    price.setTerminal(terminal);
    price.setPriceBuy(value);
    price.setDateModified(modified);
    return price;
  }
}
