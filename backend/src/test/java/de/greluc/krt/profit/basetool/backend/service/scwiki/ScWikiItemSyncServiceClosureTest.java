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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiDimensionDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Unit tests for {@link ScWikiItemSyncService} — the R4 closure-mode item fill. */
@ExtendWith(MockitoExtension.class)
class ScWikiItemSyncServiceClosureTest {

  @Mock private ScWikiClient scWikiClient;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private SyncReportService syncReportService;
  @Mock private ObjectProvider<ScWikiItemSyncService> self;

  private ScWikiProperties properties;
  private ScWikiItemSyncService service;

  @BeforeEach
  void setUp() {
    properties = new ScWikiProperties();
    properties.setItemSyncEnabled(true);
    service =
        new ScWikiItemSyncService(
            scWikiClient,
            properties,
            gameItemRepository,
            blueprintRepository,
            manufacturerRepository,
            syncReportService,
            self);
    lenient().when(self.getObject()).thenReturn(service);
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
  }

  @Test
  void syncItems_isNoOp_whenFeatureFlagOff() {
    properties.setItemSyncEnabled(false);

    int written = service.syncItems();

    verifyNoInteractions(scWikiClient, gameItemRepository, blueprintRepository);
    // A dark sync writes no rows → 0 items, so scwiki_sync's tally reflects only enabled steps
    // (#1041 item 2, SyncZeroItems).
    assertEquals(0, written, "a disabled item sync must report zero written rows");
  }

  @Test
  void syncItems_pullsOnlyExistingUuidsPlusBlueprintRefs_neverEnumeratesFullList() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of(a));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of(b));
    when(scWikiClient.fetchOne(any(), eq(ScWikiItemDto.class), any())).thenReturn(null);

    service.syncItems();

    // Closure mode hits GET /api/items/{uuid} exactly for the two scoped uuids — never a list walk.
    verify(scWikiClient).fetchOne(eq("/api/items/" + a), eq(ScWikiItemDto.class), any());
    verify(scWikiClient).fetchOne(eq("/api/items/" + b), eq(ScWikiItemDto.class), any());
    verify(scWikiClient, never()).fetchAllPagesResult(any(), any(), any());
  }

  @Test
  void syncItems_fillsWikiColumnsAndFlipsUexOnlyToBoth() {
    UUID uuid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setId(UUID.randomUUID());
    existing.setExternalUuid(uuid);
    existing.setName("Venture Helmet");
    existing.setKind(GameItemKind.ARMOR);
    existing.setSourceSystems(GameItemSourceSystem.UEX_ONLY);

    ScWikiItemDto dto =
        new ScWikiItemDto(
            uuid,
            "venture-helmet-white-2",
            "Venture Helmet White",
            "rsi_explorer_helmet",
            "FPS.Armor.Helmet",
            "Helmet",
            "Char_Armor",
            "Armor",
            "Helmet",
            "Helmet",
            "1",
            "A",
            "common",
            2.5,
            new ScWikiDimensionDto(0.3, 0.4, 0.35),
            null,
            Map.of("en_EN", "An explorer helmet.", "de_DE", "Ein Forscherhelm."),
            true,
            false,
            "4.8.0-LIVE");

    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of(uuid));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOne(eq("/api/items/" + uuid), eq(ScWikiItemDto.class), any()))
        .thenReturn(dto);
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    int written = service.syncItems();

    // One existing row filled → one written row, the count fed to scwiki_sync's item tally (#1041
    // item 2).
    assertEquals(1, written, "filling one existing row must report one written row");
    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    GameItem result = saved.getValue();
    assertEquals(GameItemSourceSystem.BOTH, result.getSourceSystems());
    assertEquals("venture-helmet-white-2", result.getScwikiSlug());
    assertEquals("FPS.Armor.Helmet", result.getClassification());
    assertEquals(2.5, result.getMass());
    assertEquals(0.3, result.getDimensionX());
    assertEquals("An explorer helmet.", result.getDescriptionEn());
    assertEquals("Ein Forscherhelm.", result.getDescriptionDe());
    assertEquals(1, result.getSizeClass());
    // UEX-canonical fields untouched.
    assertEquals("Venture Helmet", result.getName());
    assertEquals(GameItemKind.ARMOR, result.getKind());
  }

  @Test
  void syncItems_createsWikiOnlyRow_forBlueprintRefNotInGameItem() {
    UUID uuid = UUID.randomUUID();
    ScWikiItemDto dto =
        new ScWikiItemDto(
            uuid,
            "hadanite",
            "Hadanite",
            "hadanite_cls",
            "Mineral",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of("en_EN", "A mineral."),
            false,
            true,
            "4.8");
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of());
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of(uuid));
    when(scWikiClient.fetchOne(eq("/api/items/" + uuid), eq(ScWikiItemDto.class), any()))
        .thenReturn(dto);
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    GameItem created = saved.getValue();
    assertEquals(uuid, created.getExternalUuid());
    assertEquals("Hadanite", created.getName());
    assertEquals(GameItemKind.GENERIC, created.getKind());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, created.getSourceSystems());
  }

  @Test
  void syncItems_emitsWikiMissing_whenFetchReturnsNull() {
    UUID uuid = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of(uuid));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOne(any(), eq(ScWikiItemDto.class), any())).thenReturn(null);

    service.syncItems();

    verify(syncReportService)
        .logScwikiEvent(
            any(), eq(SyncEventType.WIKI_MISSING), eq("game_item"), eq(uuid), any(), any());
    verify(gameItemRepository, never()).save(any());
  }

  @Test
  void syncItems_noTargets_isNoOp() {
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of());
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());

    service.syncItems();

    verify(scWikiClient, never()).fetchOne(any(), any(), any());
  }

  @Test
  void syncItems_closure_isolatesPerItem_oneDeadlockDoesNotAbortTheRest() {
    // Regression for the production deadlock cascade: the closure loop used to run in ONE
    // transaction, so a deadlock on any row aborted the tx and every later item failed with
    // "current transaction is aborted" (25P02). With per-item REQUIRES_NEW isolation, only the
    // deadlocked item rolls back and the rest still persist.
    UUID deadlocked = UUID.randomUUID();
    UUID firstGood = UUID.randomUUID();
    UUID secondGood = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids())
        .thenReturn(List.of(deadlocked, firstGood, secondGood));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOne(any(), eq(ScWikiItemDto.class), any()))
        .thenAnswer(
            inv -> {
              String uri = inv.getArgument(0);
              return itemDto(UUID.fromString(uri.substring(uri.lastIndexOf('/') + 1)));
            });
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class)))
        .thenAnswer(
            inv -> {
              GameItem candidate = inv.getArgument(0);
              if (deadlocked.equals(candidate.getExternalUuid())) {
                throw new CannotAcquireLockException(
                    "deadlock detected while updating tuple in relation \"game_item\"");
              }
              return candidate;
            });

    service.syncItems();

    // Every item is attempted (the loop never aborts) and the two healthy rows still persist.
    verify(gameItemRepository, times(3)).save(any(GameItem.class));
    verify(gameItemRepository).save(argThat(g -> firstGood.equals(g.getExternalUuid())));
    verify(gameItemRepository).save(argThat(g -> secondGood.equals(g.getExternalUuid())));
  }

  @Test
  void syncItems_closure_defersOnOptimisticLock_oneCollisionDoesNotAbortTheRest() {
    // A concurrent sync (e.g. the parallel UEX game_item sync) can bump a row's @Version between
    // this REQUIRES_NEW transaction's read and commit. That collision is expected and benign: the
    // colliding item is deferred (WARN, not ERROR) and the rest of the batch still persists.
    UUID collided = UUID.randomUUID();
    UUID firstGood = UUID.randomUUID();
    UUID secondGood = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids())
        .thenReturn(List.of(collided, firstGood, secondGood));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOne(any(), eq(ScWikiItemDto.class), any()))
        .thenAnswer(
            inv -> {
              String uri = inv.getArgument(0);
              return itemDto(UUID.fromString(uri.substring(uri.lastIndexOf('/') + 1)));
            });
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class)))
        .thenAnswer(
            inv -> {
              GameItem candidate = inv.getArgument(0);
              if (collided.equals(candidate.getExternalUuid())) {
                throw new ObjectOptimisticLockingFailureException(GameItem.class, collided);
              }
              return candidate;
            });

    service.syncItems();

    // Every item is attempted (the loop never aborts) and the two healthy rows still persist.
    verify(gameItemRepository, times(3)).save(any(GameItem.class));
    verify(gameItemRepository).save(argThat(g -> firstGood.equals(g.getExternalUuid())));
    verify(gameItemRepository).save(argThat(g -> secondGood.equals(g.getExternalUuid())));
  }

  @Test
  void syncItems_closure_fetchesBeforeOpeningTheWriteTransaction() {
    UUID uuid = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of(uuid));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOne(any(), eq(ScWikiItemDto.class), any())).thenReturn(itemDto(uuid));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    // The Wiki fetch completes before the per-item write transaction opens, so no game_item lock is
    // ever held across the HTTP round-trip.
    InOrder ordered = inOrder(scWikiClient, gameItemRepository);
    ordered.verify(scWikiClient).fetchOne(eq("/api/items/" + uuid), eq(ScWikiItemDto.class), any());
    ordered.verify(gameItemRepository).save(any(GameItem.class));
  }

  @Test
  void fillClosureItemWithinTransaction_opensItsOwnTransaction() throws NoSuchMethodException {
    Transactional tx =
        ScWikiItemSyncService.class
            .getMethod(
                "fillClosureItemWithinTransaction",
                UUID.class,
                UUID.class,
                ScWikiItemDto.class,
                Instant.class)
            .getAnnotation(Transactional.class);

    assertNotNull(tx, "the per-item DB write must be transactional");
    assertEquals(
        Propagation.REQUIRES_NEW,
        tx.propagation(),
        "the per-item write must run in its own transaction so a deadlock isolates to one item");
  }

  private static ScWikiItemDto itemDto(UUID uuid) {
    return new ScWikiItemDto(
        uuid,
        "slug-" + uuid,
        "Item " + uuid,
        "class_name",
        "classif",
        "classifLabel",
        "Cargo",
        "typeLabel",
        "subType",
        "subTypeLabel",
        "1",
        "A",
        "common",
        1.0,
        null,
        null,
        Map.of("en_EN", "desc"),
        Boolean.TRUE,
        Boolean.FALSE,
        "4.8.0-LIVE");
  }
}
