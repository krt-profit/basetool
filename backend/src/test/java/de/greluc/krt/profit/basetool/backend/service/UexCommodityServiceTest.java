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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UexCommodityServiceTest {

  @Mock private UexClient uexClient;

  @Mock private MaterialRepository materialRepository;

  @Mock private MaterialPriceRepository materialPriceRepository;

  @Mock private TerminalRepository terminalRepository;

  @InjectMocks private UexCommodityService uexCommodityService;

  @Test
  void shouldProcessCommodityDtoAndCreateNewMaterialAndLocation() {
    // Given
    UexCommodityPriceDto dto =
        UexCommodityPriceDto.builder()
            .idCommodity(1)
            .commodityName("Laranite")
            .idTerminal(10)
            .terminalName("Area18")
            .priceBuy(BigDecimal.valueOf(25.5))
            .priceSell(BigDecimal.valueOf(30.0))
            .build();

    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(dto));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Laranite")).thenReturn(Optional.empty());

    Material savedMaterial = new Material();
    savedMaterial.setId(UUID.randomUUID());
    when(materialRepository.save(any(Material.class))).thenReturn(savedMaterial);

    Terminal mockTerminal = new Terminal();
    mockTerminal.setId(UUID.randomUUID());
    mockTerminal.setIdTerminal(10);
    mockTerminal.setCityName("Area18");
    when(terminalRepository.findByIdTerminal(10)).thenReturn(Optional.of(mockTerminal));

    when(materialPriceRepository.findByMaterialIdAndTerminalId(
            savedMaterial.getId(), mockTerminal.getId()))
        .thenReturn(Optional.empty());
    stubPriceSaveAssignsId();

    // When
    uexCommodityService.fetchAndProcessCommoditiesPrices();

    // Then
    ArgumentCaptor<MaterialPrice> priceCaptor = ArgumentCaptor.forClass(MaterialPrice.class);
    verify(materialPriceRepository).save(priceCaptor.capture());

    MaterialPrice savedPrice = priceCaptor.getValue();
    assertEquals(BigDecimal.valueOf(25.5), savedPrice.getPriceBuy());
    assertEquals(BigDecimal.valueOf(30.0), savedPrice.getPriceSell());
  }

  // ─── Commodity catalogue sync (first pass) ──────────────────────────────

  @Test
  void commoditySync_savesRefinedMaterialWithCorrectType() {
    // Given
    UexCommodityDto refined = commodity(1, "Titanium", /*isRefined*/ 1, /*isRefinable*/ 0);
    when(uexClient.getCommodities()).thenReturn(List.of(refined));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Titanium")).thenReturn(Optional.empty());

    // When
    uexCommodityService.fetchAndProcessCommoditiesPrices();

    // Then
    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(1, cap.getValue().getIdCommodity());
    assertEquals("Titanium", cap.getValue().getName());
    assertEquals(MaterialType.REFINED, cap.getValue().getType());
  }

  @Test
  void commoditySync_savesRefinableMaterialAsRawType() {
    UexCommodityDto raw = commodity(2, "Quantanium", 0, 1);
    when(uexClient.getCommodities()).thenReturn(List.of(raw));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(2)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Quantanium")).thenReturn(Optional.empty());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(MaterialType.RAW, cap.getValue().getType());
  }

  @Test
  void commoditySync_savesNonRefinableAsNoRefineType() {
    UexCommodityDto inert = commodity(3, "Stims", 0, 0);
    when(uexClient.getCommodities()).thenReturn(List.of(inert));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(3)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Stims")).thenReturn(Optional.empty());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(MaterialType.NO_REFINE, cap.getValue().getType());
  }

  @Test
  void commoditySync_reusesExistingMaterial_whenIdCommodityMatches() {
    // Given an existing material with id_commodity=4
    UUID existingId = UUID.randomUUID();
    Material existing = new Material();
    existing.setId(existingId);
    existing.setIdCommodity(4);
    existing.setName("Old Name");
    existing.setVersion(7L);

    UexCommodityDto updated = commodity(4, "New Name", 1, 0);
    when(uexClient.getCommodities()).thenReturn(List.of(updated));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(4)).thenReturn(Optional.of(existing));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertSame(existing, cap.getValue(), "Existing entity must be mutated in place");
    assertEquals(existingId, cap.getValue().getId());
    assertEquals(7L, cap.getValue().getVersion());
    verify(materialRepository, never()).findByName(any());
  }

  @Test
  void commoditySync_linksByName_whenIdCommodityIsNewButNameMatches() {
    // Given an existing material with no id_commodity but matching name
    Material existingByName = new Material();
    existingByName.setName("Diamond");
    existingByName.setIdCommodity(null);

    UexCommodityDto fresh = commodity(5, "Diamond", 0, 0);
    when(uexClient.getCommodities()).thenReturn(List.of(fresh));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(5)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Diamond")).thenReturn(Optional.of(existingByName));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertSame(existingByName, cap.getValue());
    assertEquals(
        5, cap.getValue().getIdCommodity(), "id_commodity must be backfilled from the UEX payload");
  }

  @Test
  void commoditySync_flipsManualToUexOnly_whenNameMatchAdoptsManualMaterial() {
    // Given a manually-entered material with source_systems=MANUAL and no id_commodity yet
    Material manual = new Material();
    manual.setName("Raw Ouratite");
    manual.setIdCommodity(null);
    manual.setSourceSystems(MaterialSourceSystem.MANUAL);
    manual.setIsManualRawMaterial(true);

    UexCommodityDto upstream = commodity(42, "Raw Ouratite", 0, 1);
    when(uexClient.getCommodities()).thenReturn(List.of(upstream));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(42)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Raw Ouratite")).thenReturn(Optional.of(manual));

    // When
    uexCommodityService.fetchAndProcessCommoditiesPrices();

    // Then — id_commodity backfilled AND the provenance flips off MANUAL so the manual badge
    // disappears on the next render (the derived isManualEntry wire field follows source_systems).
    // The admin-set isManualRawMaterial override stays intact (UEX may not classify it as raw).
    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertSame(manual, cap.getValue());
    assertEquals(42, cap.getValue().getIdCommodity());
    assertEquals(
        MaterialSourceSystem.UEX_ONLY,
        cap.getValue().getSourceSystems(),
        "MANUAL provenance flips to UEX_ONLY once UEX adopts the commodity");
    assertEquals(
        Boolean.TRUE,
        cap.getValue().getIsManualRawMaterial(),
        "Admin-set isManualRawMaterial override must remain untouched");
  }

  @Test
  void commoditySync_leavesUexOnlyProvenanceUntouched_whenAdoptedByNameMatchOnNonManualRow() {
    // Existing row matched by name but never MANUAL → provenance stays UEX_ONLY (no-op).
    Material existing = new Material();
    existing.setName("Bexalite");
    existing.setIdCommodity(null);
    existing.setSourceSystems(MaterialSourceSystem.UEX_ONLY);

    UexCommodityDto fresh = commodity(77, "Bexalite", 0, 1);
    when(uexClient.getCommodities()).thenReturn(List.of(fresh));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(77)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Bexalite")).thenReturn(Optional.of(existing));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(MaterialSourceSystem.UEX_ONLY, cap.getValue().getSourceSystems());
  }

  @Test
  void commoditySync_promotesWikiOnlyMaterialToBothAndVisible_whenAdoptedByName() {
    // A commodity the Wiki imported first: WIKI_ONLY + invisible (§4.3). UEX now sources it by
    // name-match, which validates it as a real trade commodity — provenance must flip to BOTH and
    // the row must become visible in trading flows (§6.1). This is the M1 regression: the commodity
    // sync previously left adopted Wiki rows stuck at WIKI_ONLY (and hidden).
    Material wikiOnly = new Material();
    wikiOnly.setName("Bluemoon Fungus");
    wikiOnly.setIdCommodity(null);
    wikiOnly.setSourceSystems(MaterialSourceSystem.WIKI_ONLY);
    wikiOnly.setIsVisible(false);

    UexCommodityDto upstream = commodity(314, "Bluemoon Fungus", 0, 1);
    when(uexClient.getCommodities()).thenReturn(List.of(upstream));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(314)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Bluemoon Fungus")).thenReturn(Optional.of(wikiOnly));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertSame(wikiOnly, cap.getValue());
    assertEquals(
        MaterialSourceSystem.BOTH,
        cap.getValue().getSourceSystems(),
        "UEX adoption of a Wiki-only commodity flips provenance to BOTH");
    assertEquals(
        Boolean.TRUE,
        cap.getValue().getIsVisible(),
        "UEX validates the commodity — it becomes visible in trading flows");
  }

  @Test
  void commoditySync_flipsManualToUexOnly_whenAdoptedByName() {
    // R9 Step 1: a MANUAL row adopted by UEX flips to UEX_ONLY (it is now UEX-sourced; it was never
    // in the Wiki, so it does not become BOTH). Contrast the WIKI_ONLY → BOTH promotion above.
    Material manual = new Material();
    manual.setName("Admin Special");
    manual.setIdCommodity(null);
    manual.setSourceSystems(MaterialSourceSystem.MANUAL);
    manual.setIsVisible(true);

    UexCommodityDto upstream = commodity(315, "Admin Special", 0, 0);
    when(uexClient.getCommodities()).thenReturn(List.of(upstream));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(315)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Admin Special")).thenReturn(Optional.of(manual));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(MaterialSourceSystem.UEX_ONLY, cap.getValue().getSourceSystems());
  }

  @Test
  void commoditySync_skipsDtoWithoutIdOrName() {
    UexCommodityDto noId = commodity(null, "Has Name", 0, 0);
    UexCommodityDto noName = commodity(99, null, 0, 0);
    when(uexClient.getCommodities()).thenReturn(List.of(noId, noName));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(materialRepository, never()).save(any());
  }

  @Test
  void commoditySync_swallowsExceptionPerRow_andContinuesBatch() {
    // Given — first row save throws, second must still save
    UexCommodityDto bad = commodity(10, "Bad", 0, 0);
    UexCommodityDto good = commodity(11, "Good", 0, 0);
    when(uexClient.getCommodities()).thenReturn(List.of(bad, good));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(10)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Bad")).thenReturn(Optional.empty());
    when(materialRepository.findByIdCommodity(11)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Good")).thenReturn(Optional.empty());

    when(materialRepository.save(any()))
        .thenThrow(new RuntimeException("DB hiccup"))
        .thenReturn(new Material());

    // When
    assertDoesNotThrow(() -> uexCommodityService.fetchAndProcessCommoditiesPrices());

    // Then — both rows attempted
    verify(materialRepository, times(2)).save(any());
  }

  @Test
  void commoditySync_emptyResponse_stillRunsPriceSync() {
    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(uexClient).getCommodities();
    verify(uexClient).getCommoditiesPricesAll();
    verify(materialRepository, never()).save(any());
  }

  // ─── Price sync (second pass) ───────────────────────────────────────────

  @Test
  void priceSync_updatesExistingPriceRow_inPlace() {
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    UUID priceId = UUID.randomUUID();
    Material material = new Material();
    material.setId(materialId);
    material.setIdCommodity(1);
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(42);

    MaterialPrice existing = new MaterialPrice();
    existing.setId(priceId);
    existing.setMaterial(material);
    existing.setTerminal(terminal);
    existing.setPriceBuy(new BigDecimal("10"));
    existing.setVersion(2L);

    UexCommodityPriceDto fresh =
        new UexCommodityPriceDto(
            1,
            "Gold",
            42,
            "term",
            new BigDecimal("99.00"),
            new BigDecimal("111.00"),
            100,
            50,
            0,
            0,
            1,
            1700000100L);

    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(fresh));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(42)).thenReturn(Optional.of(terminal));
    when(materialPriceRepository.findByMaterialIdAndTerminalId(materialId, terminalId))
        .thenReturn(Optional.of(existing));
    when(materialPriceRepository.save(any(MaterialPrice.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<MaterialPrice> cap = ArgumentCaptor.forClass(MaterialPrice.class);
    verify(materialPriceRepository).save(cap.capture());
    assertSame(existing, cap.getValue());
    assertEquals(priceId, cap.getValue().getId());
    assertEquals(
        2L, cap.getValue().getVersion(), "Version must remain — JPA owns optimistic locking");
    assertEquals(new BigDecimal("99.00"), cap.getValue().getPriceBuy());
    assertEquals(Boolean.FALSE, cap.getValue().getStatusBuy(), "0 maps to false");
    assertEquals(Boolean.TRUE, cap.getValue().getStatusSell(), "1 maps to true");
    assertEquals(Instant.ofEpochSecond(1700000100L), cap.getValue().getDateModified());
  }

  @Test
  void priceSync_skipsRow_whenTerminalUnknown() {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setIdCommodity(1);
    UexCommodityPriceDto orphan =
        new UexCommodityPriceDto(
            1, "X", 9999, "Unknown", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);

    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(orphan));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(9999)).thenReturn(Optional.empty());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(materialPriceRepository, never()).save(any());
  }

  @Test
  void priceSync_skipsRow_whenIdCommodityOrIdTerminalMissing() {
    UexCommodityPriceDto noCommodity =
        new UexCommodityPriceDto(
            null, "X", 1, "T", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 0L);
    UexCommodityPriceDto noTerminal =
        new UexCommodityPriceDto(
            1, "X", null, "T", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 0L);
    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(noCommodity, noTerminal));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(materialPriceRepository, never()).save(any());
  }

  @Test
  void priceSync_createsPlaceholderMaterial_whenIdAndNameUnknown() {
    // Given a price row referencing a commodity we have never seen
    UUID terminalId = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(7);

    UexCommodityPriceDto payload =
        new UexCommodityPriceDto(
            123, "NewStuff", 7, "Terminal", BigDecimal.TEN, BigDecimal.TEN, 1, 1, 1, 1, 1, 1L);

    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(payload));
    when(materialRepository.findByIdCommodity(123)).thenReturn(Optional.empty());
    when(materialRepository.findByName("NewStuff")).thenReturn(Optional.empty());
    when(terminalRepository.findByIdTerminal(7)).thenReturn(Optional.of(terminal));
    when(materialRepository.save(any(Material.class)))
        .thenAnswer(
            invocation -> {
              Material m = invocation.getArgument(0);
              if (m.getId() == null) {
                m.setId(UUID.randomUUID());
              }
              return m;
            });
    when(materialPriceRepository.findByMaterialIdAndTerminalId(any(), any()))
        .thenReturn(Optional.empty());
    stubPriceSaveAssignsId();

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> matCap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(matCap.capture());
    assertEquals(123, matCap.getValue().getIdCommodity());
    assertEquals("NewStuff", matCap.getValue().getName());
    assertEquals(
        MaterialType.NO_REFINE,
        matCap.getValue().getType(),
        "Placeholder materials default to NO_REFINE");

    verify(materialPriceRepository).save(any(MaterialPrice.class));
  }

  @Test
  void priceSync_swallowsExceptionPerRow_andContinuesBatch() {
    UexCommodityPriceDto bad =
        new UexCommodityPriceDto(
            1, "A", 1, "T1", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);
    UexCommodityPriceDto good =
        new UexCommodityPriceDto(
            2, "B", 2, "T2", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);

    Material m1 = new Material();
    m1.setId(UUID.randomUUID());
    m1.setIdCommodity(1);
    Material m2 = new Material();
    m2.setId(UUID.randomUUID());
    m2.setIdCommodity(2);
    Terminal t1 = new Terminal();
    t1.setId(UUID.randomUUID());
    t1.setIdTerminal(1);
    Terminal t2 = new Terminal();
    t2.setId(UUID.randomUUID());
    t2.setIdTerminal(2);

    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(bad, good));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(m1));
    when(materialRepository.findByIdCommodity(2)).thenReturn(Optional.of(m2));
    when(terminalRepository.findByIdTerminal(1)).thenReturn(Optional.of(t1));
    when(terminalRepository.findByIdTerminal(2)).thenReturn(Optional.of(t2));
    when(materialPriceRepository.findByMaterialIdAndTerminalId(any(), any()))
        .thenReturn(Optional.empty());

    when(materialPriceRepository.save(any()))
        .thenThrow(new RuntimeException("first row hiccup"))
        .thenReturn(new MaterialPrice());

    assertDoesNotThrow(() -> uexCommodityService.fetchAndProcessCommoditiesPrices());

    verify(materialPriceRepository, times(2)).save(any());
  }

  // ─── Stale-row cleanup (price-sync postlude) ────────────────────────────

  @Test
  void priceSync_clearsStaleRows_passingExactlyTheSeenIdsToTheRepository() {
    // Given — one DTO that we know will be upserted successfully
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    UUID assignedPriceId = UUID.randomUUID();
    Material material = new Material();
    material.setId(materialId);
    material.setIdCommodity(1);
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(1);

    UexCommodityPriceDto dto =
        new UexCommodityPriceDto(
            1, "Gold", 1, "T1", new BigDecimal("10"), new BigDecimal("20"), 1, 1, 1, 1, 1, 1L);

    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(dto));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(1)).thenReturn(Optional.of(terminal));
    when(materialPriceRepository.findByMaterialIdAndTerminalId(materialId, terminalId))
        .thenReturn(Optional.empty());
    when(materialPriceRepository.save(any(MaterialPrice.class)))
        .thenAnswer(
            invocation -> {
              MaterialPrice mp = invocation.getArgument(0);
              mp.setId(assignedPriceId);
              return mp;
            });

    // When
    uexCommodityService.fetchAndProcessCommoditiesPrices();

    // Then — the cleanup must run with exactly the set of ids we just upserted, so any
    // pre-existing (material, terminal) row that UEX dropped from this run gets its prices
    // nulled. This is the Quantanium regression: terminals that stop listing a commodity used to
    // keep stale priceBuy values forever.
    ArgumentCaptor<Collection<UUID>> idsCap = ArgumentCaptor.captor();
    verify(materialPriceRepository).clearStalePrices(idsCap.capture());
    assertEquals(Set.of(assignedPriceId), Set.copyOf(idsCap.getValue()));
  }

  @Test
  void priceSync_skipsStaleCleanup_whenEveryRowFailsAndSeenSetIsEmpty() {
    // Given — every DTO triggers an exception during upsert (here: unknown terminal, which
    // returns null from processSingleDto, AND unknown commodity that resolveOrCreateMaterial
    // would create — but we make terminal resolution fail to drive savedId = null without
    // raising). The seen-id set ends up empty.
    UexCommodityPriceDto orphan =
        new UexCommodityPriceDto(
            1, "X", 9999, "Unknown", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setIdCommodity(1);

    when(uexClient.getCommodities()).thenReturn(List.of());
    when(uexClient.getCommoditiesPricesAll()).thenReturn(List.of(orphan));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(9999)).thenReturn(Optional.empty());

    // When
    uexCommodityService.fetchAndProcessCommoditiesPrices();

    // Then — refusing to clear-everything when the sync produced nothing is the whole point:
    // a transient burst of failures must not wipe the entire price matrix.
    verify(materialPriceRepository, never()).clearStalePrices(any());
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  /**
   * Stubs {@link MaterialPriceRepository#save} so it assigns a fresh UUID to any transient {@link
   * MaterialPrice}. The service now reads {@code save(...).getId()} to feed the stale-row sweep, so
   * tests that exercise the price upsert path need a non-null saved entity.
   */
  private void stubPriceSaveAssignsId() {
    when(materialPriceRepository.save(any(MaterialPrice.class)))
        .thenAnswer(
            invocation -> {
              MaterialPrice mp = invocation.getArgument(0);
              if (mp.getId() == null) {
                mp.setId(UUID.randomUUID());
              }
              return mp;
            });
  }

  private static UexCommodityDto commodity(
      Integer id, String name, Integer isRefined, Integer isRefinable) {
    return new UexCommodityDto(
        id,
        name,
        /*code*/ "C", /*slug*/
        "s", /*kind*/
        "k", /*type*/
        "t",
        /*weightScu*/ 1.0, /*priceBuy*/
        1.0, /*priceSell*/
        1.0,
        /*isAvailable*/ 1, /*isAvailableLive*/
        1, /*isExtractable*/
        0,
        /*isMineral*/ 0, /*isRaw*/
        0, /*isPure*/
        0,
        isRefinable,
        isRefined,
        /*isHarvestable*/ 0, /*isBuyable*/
        1, /*isSellable*/
        1, /*isTemporary*/
        0,
        /*isIllegal*/ 0, /*isVolatileQt*/
        0, /*isVolatileTime*/
        0,
        /*isInert*/ 0, /*isExplosive*/
        0, /*isBuggy*/
        0, /*isFuel*/
        0);
  }
}
