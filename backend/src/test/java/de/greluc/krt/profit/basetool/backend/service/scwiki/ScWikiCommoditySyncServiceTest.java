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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.service.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ScWikiCommoditySyncService} — the R3 Wiki commodity merge.
 *
 * <p>Covers the behaviours that drive the merge's correctness: the §8.9 junk filter, the §4.3
 * "looks like an item" carve-out, each step of the §8.1.1 resolution chain, the conflict policy
 * (§4.6 — UEX name/code/kind never overwritten), the {@code UEX_ONLY → BOTH} transition, the
 * canonical multi-match rejection, and the §8.7 orphan-sweep gating.
 */
@ExtendWith(MockitoExtension.class)
class ScWikiCommoditySyncServiceTest {

  @Mock private ScWikiClient scWikiClient;
  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialExternalAliasService aliasService;
  @Mock private SyncReportService syncReportService;

  private ScWikiProperties properties;
  private ScWikiCommoditySyncService service;

  @BeforeEach
  void setUp() {
    properties = new ScWikiProperties();
    properties.setCommoditySyncEnabled(true);
    service =
        new ScWikiCommoditySyncService(
            scWikiClient, properties, materialRepository, aliasService, syncReportService);
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
  }

  // ─── feature flag + empty response ──────────────────────────────────────

  @Test
  void syncCommodities_isNoOp_whenFeatureFlagOff() {
    properties.setCommoditySyncEnabled(false);

    service.syncCommodities();

    verifyNoInteractions(scWikiClient, materialRepository, aliasService);
  }

  @Test
  void syncCommodities_emptyResponse_skipsOrphanSweep() {
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));

    int written = service.syncCommodities();

    verify(materialRepository, never()).markScwikiDeleted(any(), any());
    verify(materialRepository, never()).save(any());
    // An empty Wiki response writes no rows → 0 items, the signal SyncZeroItems watches (#1041
    // item 2).
    assertEquals(0, written, "an empty Wiki response must report zero written rows");
  }

  @Test
  void syncCommodities_notModified_reportsLiveCount_andSkipsMergeAndSweep() {
    // A 304 (unchanged) catalogue merges nothing, but is healthy — it must report the live linked-
    // material count, NOT 0, so an all-304 run is not read as a zero-item outage (#1182).
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.unchanged());
    when(materialRepository.countLiveScwikiMaterials()).thenReturn(1234L);

    int written = service.syncCommodities();

    assertEquals(
        1234, written, "a 304 (unchanged) catalogue must report the live linked-material count");
    verify(materialRepository, never()).save(any());
    verify(materialRepository, never()).markScwikiDeleted(any(), any());
    // No merge run is opened on a 304 — it short-circuits before beginRun().
    verify(syncReportService, never()).beginRun();
  }

  // ─── junk filter ────────────────────────────────────────────────────────

  @Test
  void isCommodityHardJunk_dropsHtmlUnderscorePlaceholderAndAtmosphere() {
    assertTrue(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("<= PLACEHOLDER =>")));
    assertTrue(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("CO<font>2</font>")));
    assertTrue(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Vlk_Fang")));
    assertTrue(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Power:")));
    assertTrue(
        ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Ship Ammunition - Size 8")));
    assertTrue(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Oxygen")));
    assertTrue(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Cooler")));
  }

  @Test
  void isCommodityHardJunk_keepsRealHarvestablesThatHaveNoFlags() {
    // §4.3 verification: these have no flags but ARE real commodities — must survive the filter.
    assertFalse(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Uncut SLAM")));
    assertFalse(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Blue Bilva")));
    assertFalse(ScWikiCommoditySyncService.isCommodityHardJunk(commodity("Agricium")));
  }

  @Test
  void syncCommodities_junkRow_emitsSkipJunkAndNeverSaves() {
    ScWikiCommodityDto junk = commodity("<= PLACEHOLDER =>");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(junk)));

    service.syncCommodities();

    verify(syncReportService)
        .logCommodityEvent(
            any(), eq(SyncEventType.SKIP_JUNK), any(), eq("<= PLACEHOLDER =>"), any());
    verify(materialRepository, never()).save(any());
  }

  // ─── canonical name helper ──────────────────────────────────────────────

  @Test
  void canonicalName_stripsQualifiersAndParentheticals() {
    assertEquals("silicon", ScWikiCommoditySyncService.canonicalName("Raw Silicon"));
    assertEquals("silicon", ScWikiCommoditySyncService.canonicalName("Silicon (Raw)"));
    assertEquals("silicon", ScWikiCommoditySyncService.canonicalName("Silicon"));
    assertEquals("stileron", ScWikiCommoditySyncService.canonicalName("Stileron (Ore)"));
    assertNull(ScWikiCommoditySyncService.canonicalName(null));
  }

  // ─── resolution chain ───────────────────────────────────────────────────

  @Test
  void resolve_byScwikiUuid_isTheFastPath() {
    ScWikiCommodityDto dto = commodity("Agricium");
    Material existing = uexMaterial("Agricium");
    existing.setScwikiUuid(dto.uuid());
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.of(existing));
    when(materialRepository.findAll()).thenReturn(List.of(existing));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncCommodities();

    verify(materialRepository).findByScwikiUuid(dto.uuid());
    verify(aliasService, never()).resolveMaterialByAlias(any(), any());
    verify(materialRepository, never()).findByName(any());
  }

  @Test
  void resolve_viaAlias_emitsLinkedViaAliasAndDoesNotOverwriteUexName() {
    ScWikiCommodityDto dto = commodity("Raw Silicon");
    Material uex = uexMaterial("Silicon (Raw)");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, "Raw Silicon"))
        .thenReturn(uex);
    when(materialRepository.findAll()).thenReturn(List.of(uex));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncCommodities();

    ArgumentCaptor<Material> saved = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(saved.capture());
    Material result = saved.getValue();
    assertEquals("Silicon (Raw)", result.getName(), "UEX name must NOT be overwritten by Wiki");
    assertEquals(dto.uuid(), result.getScwikiUuid(), "Wiki UUID must be written");
    assertEquals(MaterialSourceSystem.BOTH, result.getSourceSystems());
    verify(syncReportService)
        .logCommodityEvent(any(), eq(SyncEventType.LINKED_VIA_ALIAS), any(), any(), any());
  }

  @Test
  void resolve_byExactName_flipsUexOnlyToBoth() {
    ScWikiCommodityDto dto = commodity("Agricium");
    Material uex = uexMaterial("Agricium");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName("Agricium")).thenReturn(Optional.of(uex));
    when(materialRepository.findAll()).thenReturn(List.of(uex));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncCommodities();

    ArgumentCaptor<Material> saved = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(saved.capture());
    assertEquals(MaterialSourceSystem.BOTH, saved.getValue().getSourceSystems());
    assertEquals(dto.uuid(), saved.getValue().getScwikiUuid());
  }

  @Test
  void resolve_canonicalMultiMatch_emitsAmbiguousAndSkips() {
    ScWikiCommodityDto dto = commodity("Iron");
    Material a = uexMaterial("Iron Ore");
    Material b = uexMaterial("Iron (Refined)");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName("Iron")).thenReturn(Optional.empty());
    when(materialRepository.findAll()).thenReturn(List.of(a, b));

    service.syncCommodities();

    verify(syncReportService)
        .logCommodityEvent(
            any(), eq(SyncEventType.MULTI_MATCH_AMBIGUOUS), any(), eq("Iron"), any());
    // Ambiguous → skipped: no row saved, no orphan sweep (seen set empty).
    verify(materialRepository, never()).save(any());
    verify(materialRepository, never()).markScwikiDeleted(any(), any());
  }

  // ─── WIKI_ONLY creation ─────────────────────────────────────────────────

  @Test
  void resolve_noMatch_createsWikiOnlyInvisibleRow_andEmitsCreatedWikiOnly() {
    ScWikiCommodityDto dto = commodity("Bluemoon Fungus");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName("Bluemoon Fungus")).thenReturn(Optional.empty());
    when(materialRepository.findAll()).thenReturn(List.of());
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncCommodities();

    ArgumentCaptor<Material> saved = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(saved.capture());
    Material created = saved.getValue();
    assertEquals("Bluemoon Fungus", created.getName());
    assertEquals(MaterialSourceSystem.WIKI_ONLY, created.getSourceSystems());
    assertFalse(created.getIsVisible(), "Wiki-only rows must be invisible until admin review");
    assertEquals(dto.uuid(), created.getScwikiUuid());
    verify(syncReportService)
        .logCommodityEvent(
            any(), eq(SyncEventType.CREATED_WIKI_ONLY), any(), eq("Bluemoon Fungus"), any());
  }

  @Test
  void looksLikeItem_emitsLooksLikeItemEvent_andStaysInvisible() {
    ScWikiCommodityDto dto = commodity("MedGel");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName("MedGel")).thenReturn(Optional.empty());
    when(materialRepository.findAll()).thenReturn(List.of());
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncCommodities();

    ArgumentCaptor<Material> saved = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(saved.capture());
    assertFalse(saved.getValue().getIsVisible());
    verify(syncReportService)
        .logCommodityEvent(any(), eq(SyncEventType.LOOKS_LIKE_ITEM), any(), eq("MedGel"), any());
  }

  // ─── orphan sweep ───────────────────────────────────────────────────────

  @Test
  void orphanSweep_runs_whenAtLeastOneCommodityMerged() {
    ScWikiCommodityDto dto = commodity("Agricium");
    Material uex = uexMaterial("Agricium");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("commodities")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(materialRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName("Agricium")).thenReturn(Optional.of(uex));
    when(materialRepository.findAll()).thenReturn(List.of(uex));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
    when(materialRepository.markScwikiDeleted(any(), any())).thenReturn(0);

    service.syncCommodities();

    verify(materialRepository).markScwikiDeleted(any(), any());
    verify(syncReportService).pruneRuns(SyncSourceSystem.SCWIKI);
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  private ScWikiCommodityDto commodity(String name) {
    return new ScWikiCommodityDto(
        UUID.randomUUID(), name, name, name.toLowerCase().replace(' ', '-'), "", 1.0, false, false);
  }

  private Material uexMaterial(String name) {
    Material m = new Material();
    m.setId(UUID.randomUUID());
    m.setName(name);
    m.setSourceSystems(MaterialSourceSystem.UEX_ONLY);
    m.setIsVisible(true);
    return m;
  }
}
