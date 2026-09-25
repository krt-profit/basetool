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

import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.fetched;
import static de.greluc.krt.profit.basetool.backend.service.UexRefs.pair;
import static de.greluc.krt.profit.basetool.backend.service.UexRefs.ref;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.UexProperties;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexItemPriceDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemPrice;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.GameItemPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UexItemPriceSyncService} — the R7 UEX item-price matrix sync
 * (SC_WIKI_SYNC_PLAN.md §6.7 / §11 R7). Covers the flag gate, empty-feed abort, the
 * upsert-by-(item,terminal) path against the preloaded id maps (BE-PERF-09), skipping unknown items
 * / terminals, the per-row isolation of a failing row, and the non-empty-seen gate on the stale-row
 * sweep.
 */
@ExtendWith(MockitoExtension.class)
class UexItemPriceSyncServiceTest {

  @Mock private UexClient uexClient;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private GameItemPriceRepository gameItemPriceRepository;
  @Mock private TerminalRepository terminalRepository;

  private final RecordingTransactionManager tx = new RecordingTransactionManager();
  private UexProperties properties;

  /** The configured keys, relative to the record's prefix; {@link #rebuild()} binds them. */
  private final Map<String, Object> config = new HashMap<>();

  private UexItemPriceSyncService service;

  @BeforeEach
  void setUp() {
    config.putAll(Map.of("item-price-sync-enabled", true));
    rebuild();
  }

  /**
   * Binds the properties record from {@link #config} and builds the object under test over it. The
   * record is immutable (BE-MOD-04), so a test that changes a key rebuilds.
   */
  private void rebuild() {
    properties = BoundProperties.bind(UexProperties.class, config);
    service =
        new UexItemPriceSyncService(
            uexClient,
            properties,
            gameItemRepository,
            gameItemPriceRepository,
            terminalRepository,
            new SyncChunkWriter(tx));
  }

  @Test
  void syncItemPrices_isNoOp_whenFlagOff() {
    config.put("item-price-sync-enabled", false);
    rebuild();

    service.syncItemPrices();

    verifyNoInteractions(
        uexClient, gameItemRepository, gameItemPriceRepository, terminalRepository);
  }

  @Test
  void syncItemPrices_abortsWithoutSweep_whenFeedEmpty() {
    when(uexClient.getItemPrices()).thenReturn(fetched(List.of()));

    service.syncItemPrices();

    verify(gameItemPriceRepository, never()).save(any());
    verify(gameItemPriceRepository, never()).findIdsWithLivePrices();
    verify(gameItemPriceRepository, never()).clearPricesByIds(any());
  }

  @Test
  void upsertsNewPrice_forKnownItemAndTerminal_thenRunsSweep() {
    GameItem item = gameItem();
    Terminal terminal = terminal();
    when(uexClient.getItemPrices())
        .thenReturn(fetched(List.of(dto(1, 107, 15461.0, 0.0, 1778763945L))));
    knownItems(ref(1, item.getId()));
    knownTerminals(ref(107, terminal.getId()));
    when(gameItemPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    when(gameItemRepository.getReferenceById(item.getId())).thenReturn(item);
    when(terminalRepository.getReferenceById(terminal.getId())).thenReturn(terminal);
    stubSaveAssigningId();

    service.syncItemPrices();

    ArgumentCaptor<GameItemPrice> saved = ArgumentCaptor.forClass(GameItemPrice.class);
    verify(gameItemPriceRepository).save(saved.capture());
    GameItemPrice price = saved.getValue();
    assertSame(item, price.getGameItem());
    assertSame(terminal, price.getTerminal());
    assertEquals(15461.0, price.getPriceBuy());
    assertEquals(0.0, price.getPriceSell());
    assertEquals(1778763945L, price.getDateModified());
    assertNotNull(price.getUexSyncedAt());
    assertNull(price.getPriceRent());
    assertNull(price.getStatusBuy());
    verify(gameItemPriceRepository).findIdsWithLivePrices();
    verify(gameItemRepository, never()).findByUexItemId(any());
    verify(terminalRepository, never()).findByIdTerminal(any());
  }

  @Test
  void refreshesExistingPrice_inPlace() {
    GameItem item = gameItem();
    Terminal terminal = terminal();
    GameItemPrice existing = new GameItemPrice();
    existing.setId(UUID.randomUUID());
    existing.setGameItem(item);
    existing.setTerminal(terminal);
    existing.setPriceBuy(99.0);
    when(uexClient.getItemPrices()).thenReturn(fetched(List.of(dto(1, 107, 250.0, 300.0, 10L))));
    knownItems(ref(1, item.getId()));
    knownTerminals(ref(107, terminal.getId()));
    when(gameItemPriceRepository.findPriceKeyRefs())
        .thenReturn(List.of(pair(item.getId(), terminal.getId(), existing.getId())));
    when(gameItemPriceRepository.findAllById(List.of(existing.getId())))
        .thenReturn(List.of(existing));
    stubSaveAssigningId();

    service.syncItemPrices();

    ArgumentCaptor<GameItemPrice> saved = ArgumentCaptor.forClass(GameItemPrice.class);
    verify(gameItemPriceRepository).save(saved.capture());
    assertSame(existing, saved.getValue(), "must update the existing row, not insert a new one");
    assertEquals(250.0, existing.getPriceBuy());
    assertEquals(300.0, existing.getPriceSell());
  }

  @Test
  void skipsUnknownItem_andDoesNotSweep_whenNothingProcessed() {
    when(uexClient.getItemPrices()).thenReturn(fetched(List.of(dto(999, 107, 1.0, 2.0, 1L))));
    knownItems();
    knownTerminals(ref(107, UUID.randomUUID()));
    when(gameItemPriceRepository.findPriceKeyRefs()).thenReturn(List.of());

    service.syncItemPrices();

    verify(gameItemPriceRepository, never()).save(any());
    verify(gameItemPriceRepository, never()).findIdsWithLivePrices();
    verify(gameItemPriceRepository, never()).clearPricesByIds(any());
  }

  @Test
  void skipsUnknownTerminal() {
    when(uexClient.getItemPrices()).thenReturn(fetched(List.of(dto(1, 555, 1.0, 2.0, 1L))));
    knownItems(ref(1, UUID.randomUUID()));
    knownTerminals();
    when(gameItemPriceRepository.findPriceKeyRefs()).thenReturn(List.of());

    service.syncItemPrices();

    verify(gameItemPriceRepository, never()).save(any());
    verify(gameItemPriceRepository, never()).findIdsWithLivePrices();
    verify(gameItemPriceRepository, never()).clearPricesByIds(any());
  }

  @Test
  void aPairRepeatedInTheFeed_updatesOneRow_insteadOfInsertingTwo() {
    GameItem item = gameItem();
    Terminal terminal = terminal();
    when(uexClient.getItemPrices())
        .thenReturn(fetched(List.of(dto(1, 107, 1.0, 2.0, 1L), dto(1, 107, 3.0, 4.0, 2L))));
    knownItems(ref(1, item.getId()));
    knownTerminals(ref(107, terminal.getId()));
    when(gameItemPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    when(gameItemRepository.getReferenceById(item.getId())).thenReturn(item);
    when(terminalRepository.getReferenceById(terminal.getId())).thenReturn(terminal);
    stubSaveAssigningId();

    service.syncItemPrices();

    ArgumentCaptor<GameItemPrice> saved = ArgumentCaptor.forClass(GameItemPrice.class);
    verify(gameItemPriceRepository).save(saved.capture());
    assertEquals(3.0, saved.getValue().getPriceBuy(), "the later feed row wins");
  }

  @Test
  void syncItemPrices_isolatesAFailingRow_andSweepsOnlySavedIds() {
    GameItem item1 = gameItem();
    GameItem item2 = gameItem();
    GameItem item3 = gameItem();
    Terminal terminal = terminal();
    UUID id1 = UUID.randomUUID();
    UUID id3 = UUID.randomUUID();
    UUID staleId = UUID.randomUUID();
    when(uexClient.getItemPrices())
        .thenReturn(
            fetched(
                List.of(
                    dto(1, 107, 10.0, 0.0, 1L),
                    dto(2, 107, 20.0, 0.0, 2L),
                    dto(3, 107, 30.0, 0.0, 3L))));
    knownItems(ref(1, item1.getId()), ref(2, item2.getId()), ref(3, item3.getId()));
    knownTerminals(ref(107, terminal.getId()));
    when(gameItemPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    when(gameItemRepository.getReferenceById(item1.getId())).thenReturn(item1);
    when(gameItemRepository.getReferenceById(item2.getId())).thenReturn(item2);
    when(gameItemRepository.getReferenceById(item3.getId())).thenReturn(item3);
    when(terminalRepository.getReferenceById(terminal.getId())).thenReturn(terminal);
    when(gameItemPriceRepository.save(any(GameItemPrice.class)))
        .thenAnswer(
            inv -> {
              GameItemPrice p = inv.getArgument(0);
              if (p.getGameItem() == item2) {
                throw new IllegalStateException("simulated save failure for the middle row");
              }
              p.setId(p.getGameItem() == item1 ? id1 : id3);
              return p;
            });
    when(gameItemPriceRepository.findIdsWithLivePrices()).thenReturn(List.of(id1, id3, staleId));

    service.syncItemPrices();

    verify(gameItemPriceRepository, times(5)).save(any(GameItemPrice.class));
    assertEquals(2, tx.rolledBack, "the chunk and the failing row roll back");
    ArgumentCaptor<Collection<UUID>> sweep = ArgumentCaptor.captor();
    verify(gameItemPriceRepository).clearPricesByIds(sweep.capture());
    assertEquals(Set.of(staleId), Set.copyOf(sweep.getValue()));
  }

  private void knownItems(de.greluc.krt.profit.basetool.backend.repository.UexKeyRef... refs) {
    when(gameItemRepository.findUexItemRefs()).thenReturn(List.of(refs));
  }

  private void knownTerminals(de.greluc.krt.profit.basetool.backend.repository.UexKeyRef... refs) {
    when(terminalRepository.findUexTerminalRefs()).thenReturn(List.of(refs));
  }

  private void stubSaveAssigningId() {
    lenient()
        .when(gameItemPriceRepository.save(any(GameItemPrice.class)))
        .thenAnswer(
            inv -> {
              GameItemPrice p = inv.getArgument(0);
              if (p.getId() == null) {
                p.setId(UUID.randomUUID());
              }
              return p;
            });
  }

  private static UexItemPriceDto dto(
      Integer idItem, Integer idTerminal, Double buy, Double sell, Long dateModified) {
    return new UexItemPriceDto(idItem, idTerminal, buy, sell, dateModified);
  }

  private static GameItem gameItem() {
    GameItem g = new GameItem();
    g.setId(UUID.randomUUID());
    return g;
  }

  private static Terminal terminal() {
    Terminal t = new Terminal();
    t.setId(UUID.randomUUID());
    return t;
  }
}
