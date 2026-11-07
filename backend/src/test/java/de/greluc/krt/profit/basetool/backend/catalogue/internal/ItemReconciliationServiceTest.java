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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import java.time.Instant;
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
  @Mock private P4kMergeConflictRecorder p4kConflicts;

  @Test
  void differentP4kGuidsAreNeverMergedButGoToTheP4kReview() {
    GameItem retired = item(GameItemSourceSystem.UEX_ONLY);
    retired.setUexItemId(91);
    retired.setP4kUuid(UUID.randomUUID());
    GameItem canonical = item(GameItemSourceSystem.WIKI_ONLY);
    canonical.setExternalUuid(UUID.randomUUID());
    canonical.setP4kUuid(UUID.randomUUID());
    when(items.lockReconciliationRows(any())).thenReturn(List.of(retired, canonical));
    UUID runId = UUID.randomUUID();

    assertThat(service().mergeExisting(canonical, retired.getId(), runId, sameItem())).isNull();

    verify(p4kConflicts).record(retired, retired.getP4kUuid(), canonical, canonical.getP4kUuid());
    verify(reports)
        .logScwikiEvent(
            eq(runId),
            eq(SyncEventType.SOURCE_IDENTITY_CONFLICT),
            eq("game_item"),
            eq(canonical.getExternalUuid()),
            eq("Same Item"),
            any());
    verify(items, never()).saveAndFlush(any());
    verify(items, never()).delete(any());
    verifyNoInteractions(redirects, prices, stats, recipes);
    assertThat(retired.getUexItemId()).isEqualTo(91);
    assertThat(canonical.getUexItemId()).isNull();
  }

  @Test
  void aP4kGuidOnlyTheRetiredRowHoldsMovesOntoTheSurvivor() {
    GameItem retired = item(GameItemSourceSystem.UEX_ONLY);
    retired.setUexItemId(91);
    UUID guid = UUID.randomUUID();
    UUID seenRun = UUID.randomUUID();
    Instant synced = Instant.parse("2026-11-01T10:00:00Z");
    retired.setP4kUuid(guid);
    retired.setNameKey("item_NameSameItem");
    retired.setNameDe("Gleicher Gegenstand");
    retired.setP4kSyncedAt(synced);
    retired.setP4kSeenRunId(seenRun);
    GameItem canonical = item(GameItemSourceSystem.WIKI_ONLY);
    canonical.setExternalUuid(UUID.randomUUID());
    when(items.lockReconciliationRows(any())).thenReturn(List.of(retired, canonical));
    List<UUID> retiredGuidAtFlush = new ArrayList<>();
    when(items.saveAndFlush(retired))
        .thenAnswer(_ -> {
          retiredGuidAtFlush.add(retired.getP4kUuid());
          assertThat(canonical.getP4kUuid()).isNull();
          return retired;
        });

    assertThat(service().mergeExisting(canonical, retired.getId(), UUID.randomUUID(), sameItem()))
        .isSameAs(canonical);

    assertThat(retiredGuidAtFlush).containsExactly((UUID) null);
    assertThat(canonical.getP4kUuid()).isEqualTo(guid);
    assertThat(canonical.getNameKey()).isEqualTo("item_NameSameItem");
    assertThat(canonical.getNameDe()).isEqualTo("Gleicher Gegenstand");
    assertThat(retired.getNameDe()).isNull();
    assertThat(canonical.getP4kSyncedAt()).isEqualTo(synced);
    assertThat(canonical.getP4kSeenRunId()).isEqualTo(seenRun);
    verifyNoInteractions(p4kConflicts);
  }

  @Test
  void aP4kMatchedSurvivorKeepsItsNameAndManufacturer() {
    GameItem retired = item(GameItemSourceSystem.UEX_ONLY);
    retired.setUexItemId(91);
    retired.setName("Same Item");
    GameItem canonical = item(GameItemSourceSystem.BOTH);
    canonical.setExternalUuid(UUID.randomUUID());
    canonical.setP4kUuid(UUID.randomUUID());
    canonical.setName("SAME item");
    when(items.lockReconciliationRows(any())).thenReturn(List.of(retired, canonical));

    assertThat(service().mergeExisting(canonical, retired.getId(), UUID.randomUUID(), sameItem()))
        .isSameAs(canonical);

    assertThat(canonical.getName()).isEqualTo("SAME item");
    assertThat(canonical.getUexName()).isEqualTo("Same Item");
    assertThat(canonical.getUexItemId()).isEqualTo(91);
  }

  @Test
  void aSurvivorWithoutP4kGuidTakesTheUexName() {
    GameItem retired = item(GameItemSourceSystem.UEX_ONLY);
    retired.setUexItemId(91);
    GameItem canonical = item(GameItemSourceSystem.WIKI_ONLY);
    canonical.setExternalUuid(UUID.randomUUID());
    canonical.setName("SAME item");
    when(items.lockReconciliationRows(any())).thenReturn(List.of(retired, canonical));

    service().mergeExisting(canonical, retired.getId(), UUID.randomUUID(), sameItem());

    assertThat(canonical.getName()).isEqualTo("Same Item");
  }

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
    assertThat(service().mergeExisting(canonical, retired.getId(), UUID.randomUUID(), sameItem()))
        .isSameAs(canonical);

    verify(prices).findByGameItemId(retired.getId());
    verify(prices).findByGameItemId(canonical.getId());
    verify(prices, never()).findByGameItemIdAndTerminalId(any(), any());
    assertThat(target).allSatisfy(price -> assertThat(price.getPriceBuy()).isEqualTo(11d));
    assertThat(source.subList(10, 20))
        .allSatisfy(price -> assertThat(price.getGameItem()).isSameAs(canonical));
  }

  private ItemReconciliationService service() {
    return new ItemReconciliationService(
        items, redirects, recipes, prices, stats, List.of(), reports, p4kConflicts);
  }

  private static ItemReconciliationService.WikiIdentity sameItem() {
    return new ItemReconciliationService.WikiIdentity(
        "Same Item", GameItemKind.GENERIC, null, null);
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
