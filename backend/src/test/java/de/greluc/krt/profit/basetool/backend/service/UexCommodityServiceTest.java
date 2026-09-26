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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UexCommodityServiceTest {

  @Mock private UexClient uexClient;

  @Mock private MaterialRepository materialRepository;

  @Mock private MaterialPriceRepository materialPriceRepository;

  @Mock private TerminalRepository terminalRepository;

  /** A real chunk writer, so the rows are actually written through its callbacks (BE-PERF-09). */
  @Spy private SyncChunkWriter chunkWriter = new SyncChunkWriter(new RecordingTransactionManager());

  @InjectMocks private UexCommodityService uexCommodityService;

  @Test
  void shouldProcessCommodityDtoAndCreateNewMaterialAndLocation() {
    UexCommodityPriceDto dto =
        UexCommodityPriceDto.builder()
            .idCommodity(1)
            .commodityName("Laranite")
            .idTerminal(10)
            .terminalName("Area18")
            .priceBuy(BigDecimal.valueOf(25.5))
            .priceSell(BigDecimal.valueOf(30.0))
            .build();

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(dto)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Laranite")).thenReturn(Optional.empty());

    Material savedMaterial = new Material();
    savedMaterial.setId(UUID.randomUUID());
    when(materialRepository.save(any(Material.class))).thenReturn(savedMaterial);

    Terminal mockTerminal = new Terminal();
    mockTerminal.setId(UUID.randomUUID());
    mockTerminal.setIdTerminal(10);
    mockTerminal.setCityName("Area18");
    when(terminalRepository.findUexTerminalRefs())
        .thenReturn(List.of(ref(10, mockTerminal.getId())));
    when(materialPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    stubPriceSaveAssignsId();

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<MaterialPrice> priceCaptor = ArgumentCaptor.forClass(MaterialPrice.class);
    verify(materialPriceRepository).save(priceCaptor.capture());

    MaterialPrice savedPrice = priceCaptor.getValue();
    assertEquals(BigDecimal.valueOf(25.5), savedPrice.getPriceBuy());
    assertEquals(BigDecimal.valueOf(30.0), savedPrice.getPriceSell());
  }

  @Test
  void commoditySync_savesRefinedMaterialWithCorrectType() {
    UexCommodityDto refined = commodity(1, "Titanium", 1, 0);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(refined)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Titanium")).thenReturn(Optional.empty());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(1, cap.getValue().getIdCommodity());
    assertEquals("Titanium", cap.getValue().getName());
    assertEquals(MaterialType.REFINED, cap.getValue().getType());
  }

  @Test
  void commoditySync_savesRefinableMaterialAsRawType() {
    UexCommodityDto raw = commodity(2, "Quantanium", 0, 1);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(raw)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
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
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(inert)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
    when(materialRepository.findByIdCommodity(3)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Stims")).thenReturn(Optional.empty());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(MaterialType.NO_REFINE, cap.getValue().getType());
  }

  @Test
  void commoditySync_reusesExistingMaterial_whenIdCommodityMatches() {
    UUID existingId = UUID.randomUUID();
    Material existing = new Material();
    existing.setId(existingId);
    existing.setIdCommodity(4);
    existing.setName("Old Name");
    existing.setVersion(7L);

    UexCommodityDto updated = commodity(4, "New Name", 1, 0);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(updated)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
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
    Material existingByName = new Material();
    existingByName.setName("Diamond");
    existingByName.setIdCommodity(null);

    UexCommodityDto fresh = commodity(5, "Diamond", 0, 0);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(fresh)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
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
    Material manual = new Material();
    manual.setName("Raw Ouratite");
    manual.setIdCommodity(null);
    manual.setSourceSystems(MaterialSourceSystem.MANUAL);
    manual.setIsManualRawMaterial(true);

    UexCommodityDto upstream = commodity(42, "Raw Ouratite", 0, 1);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(upstream)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
    when(materialRepository.findByIdCommodity(42)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Raw Ouratite")).thenReturn(Optional.of(manual));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

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
    Material existing = new Material();
    existing.setName("Bexalite");
    existing.setIdCommodity(null);
    existing.setSourceSystems(MaterialSourceSystem.UEX_ONLY);

    UexCommodityDto fresh = commodity(77, "Bexalite", 0, 1);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(fresh)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
    when(materialRepository.findByIdCommodity(77)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Bexalite")).thenReturn(Optional.of(existing));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertEquals(MaterialSourceSystem.UEX_ONLY, cap.getValue().getSourceSystems());
  }

  @Test
  void commoditySync_promotesWikiOnlyMaterialToBothAndVisible_whenAdoptedByName() {
    Material wikiOnly = new Material();
    wikiOnly.setName("Bluemoon Fungus");
    wikiOnly.setIdCommodity(null);
    wikiOnly.setSourceSystems(MaterialSourceSystem.WIKI_ONLY);
    wikiOnly.setIsVisible(false);

    UexCommodityDto upstream = commodity(314, "Bluemoon Fungus", 0, 1);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(upstream)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
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
    Material manual = new Material();
    manual.setName("Admin Special");
    manual.setIdCommodity(null);
    manual.setSourceSystems(MaterialSourceSystem.MANUAL);
    manual.setIsVisible(true);

    UexCommodityDto upstream = commodity(315, "Admin Special", 0, 0);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(upstream)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
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
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(noId, noName)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(materialRepository, never()).save(any());
  }

  @Test
  void commoditySync_swallowsExceptionPerRow_andContinuesBatch() {
    UexCommodityDto bad = commodity(10, "Bad", 0, 0);
    UexCommodityDto good = commodity(11, "Good", 0, 0);
    when(uexClient.getCommodities()).thenReturn(fetched(List.of(bad, good)));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));
    when(materialRepository.findByIdCommodity(10)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Bad")).thenReturn(Optional.empty());
    when(materialRepository.findByIdCommodity(11)).thenReturn(Optional.empty());
    when(materialRepository.findByName("Good")).thenReturn(Optional.empty());

    when(materialRepository.save(any()))
        .thenAnswer(
            invocation -> {
              Material m = invocation.getArgument(0);
              if ("Bad".equals(m.getName())) {
                throw new RuntimeException("DB hiccup");
              }
              return m;
            });

    assertDoesNotThrow(() -> uexCommodityService.fetchAndProcessCommoditiesPrices());

    ArgumentCaptor<Material> saved = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository, times(3)).save(saved.capture());
    assertEquals("Good", saved.getAllValues().getLast().getName());
  }

  @Test
  void commoditySync_emptyResponse_stillRunsPriceSync() {
    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of()));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(uexClient).getCommodities();
    verify(uexClient).getCommoditiesPricesAll();
    verify(materialRepository, never()).save(any());
  }

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

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(fresh)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of(ref(1, materialId)));
    when(terminalRepository.findUexTerminalRefs()).thenReturn(List.of(ref(42, terminalId)));
    when(materialPriceRepository.findPriceKeyRefs())
        .thenReturn(List.of(pair(materialId, terminalId, priceId)));
    when(materialPriceRepository.findAllById(List.of(priceId))).thenReturn(List.of(existing));
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
    verify(materialRepository, never()).findByIdCommodity(any());
    verify(terminalRepository, never()).findByIdTerminal(any());
    verify(materialPriceRepository, never()).findByMaterialIdAndTerminalId(any(), any());
  }

  @Test
  void priceSync_skipsRow_whenTerminalUnknown() {
    UexCommodityPriceDto orphan =
        new UexCommodityPriceDto(
            1, "X", 9999, "Unknown", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(orphan)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of(ref(1, UUID.randomUUID())));
    when(terminalRepository.findUexTerminalRefs()).thenReturn(List.of());
    when(materialPriceRepository.findPriceKeyRefs()).thenReturn(List.of());

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
    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(noCommodity, noTerminal)));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(materialPriceRepository, never()).save(any());
  }

  @Test
  void priceSync_createsPlaceholderMaterial_whenIdAndNameUnknown() {
    UUID terminalId = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(7);

    UexCommodityPriceDto payload =
        new UexCommodityPriceDto(
            123, "NewStuff", 7, "Terminal", BigDecimal.TEN, BigDecimal.TEN, 1, 1, 1, 1, 1, 1L);

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(payload)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of());
    when(terminalRepository.findUexTerminalRefs()).thenReturn(List.of(ref(7, terminalId)));
    when(materialPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    when(materialRepository.findByIdCommodity(123)).thenReturn(Optional.empty());
    when(materialRepository.findByName("NewStuff")).thenReturn(Optional.empty());
    when(materialRepository.save(any(Material.class)))
        .thenAnswer(
            invocation -> {
              Material m = invocation.getArgument(0);
              if (m.getId() == null) {
                m.setId(UUID.randomUUID());
              }
              return m;
            });
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
    verify(materialRepository).getReferenceById(matCap.getValue().getId());
  }

  @Test
  void priceSync_isolatesAFailingRow_andTheOtherRowStillCommits() {
    UexCommodityPriceDto bad =
        new UexCommodityPriceDto(
            1, "A", 1, "T1", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);
    UexCommodityPriceDto good =
        new UexCommodityPriceDto(
            2, "B", 2, "T2", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);
    UUID m1 = UUID.randomUUID();
    UUID m2 = UUID.randomUUID();
    Material badMaterial = new Material();
    badMaterial.setId(m1);

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(bad, good)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of(ref(1, m1), ref(2, m2)));
    when(terminalRepository.findUexTerminalRefs())
        .thenReturn(List.of(ref(1, UUID.randomUUID()), ref(2, UUID.randomUUID())));
    when(materialPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    when(materialRepository.getReferenceById(m1)).thenReturn(badMaterial);
    when(materialPriceRepository.save(any()))
        .thenAnswer(
            invocation -> {
              MaterialPrice mp = invocation.getArgument(0);
              if (mp.getMaterial() == badMaterial) {
                throw new RuntimeException("row refused");
              }
              mp.setId(UUID.randomUUID());
              return mp;
            });

    assertDoesNotThrow(() -> uexCommodityService.fetchAndProcessCommoditiesPrices());

    verify(materialPriceRepository, times(3)).save(any());
    verify(materialPriceRepository).findIdsWithLivePrices();
  }

  @Test
  void priceSync_clearsStaleRows_passingExactlyTheSeenIdsToTheRepository() {
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    UUID assignedPriceId = UUID.randomUUID();
    UUID stalePriceId = UUID.randomUUID();

    UexCommodityPriceDto dto =
        new UexCommodityPriceDto(
            1, "Gold", 1, "T1", new BigDecimal("10"), new BigDecimal("20"), 1, 1, 1, 1, 1, 1L);

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(dto)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of(ref(1, materialId)));
    when(terminalRepository.findUexTerminalRefs()).thenReturn(List.of(ref(1, terminalId)));
    when(materialPriceRepository.findPriceKeyRefs()).thenReturn(List.of());
    when(materialPriceRepository.save(any(MaterialPrice.class)))
        .thenAnswer(
            invocation -> {
              MaterialPrice mp = invocation.getArgument(0);
              mp.setId(assignedPriceId);
              return mp;
            });
    when(materialPriceRepository.findIdsWithLivePrices())
        .thenReturn(List.of(assignedPriceId, stalePriceId));

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    ArgumentCaptor<Collection<UUID>> idsCap = ArgumentCaptor.captor();
    verify(materialPriceRepository).clearPricesByIds(idsCap.capture());
    assertEquals(Set.of(stalePriceId), Set.copyOf(idsCap.getValue()));
  }

  @Test
  void priceSync_skipsStaleCleanup_whenEveryRowFailsAndSeenSetIsEmpty() {
    UexCommodityPriceDto orphan =
        new UexCommodityPriceDto(
            1, "X", 9999, "Unknown", BigDecimal.ONE, BigDecimal.ONE, 0, 0, 0, 0, 0, 1L);

    when(uexClient.getCommodities()).thenReturn(fetched(List.of()));
    when(uexClient.getCommoditiesPricesAll()).thenReturn(fetched(List.of(orphan)));
    when(materialRepository.findUexCommodityRefs()).thenReturn(List.of(ref(1, UUID.randomUUID())));
    when(terminalRepository.findUexTerminalRefs()).thenReturn(List.of());
    when(materialPriceRepository.findPriceKeyRefs()).thenReturn(List.of());

    uexCommodityService.fetchAndProcessCommoditiesPrices();

    verify(materialPriceRepository, never()).findIdsWithLivePrices();
    verify(materialPriceRepository, never()).clearPricesByIds(any());
  }

  /**
   * Stubs {@link MaterialPriceRepository#save} to assign a fresh UUID to any transient {@link
   * MaterialPrice}, as the price upsert reads the saved id.
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
        "C",
        "k",
        1.0,
        1.0,
        1.0,
        1,
        1,
        0,
        0,
        0,
        0,
        isRefinable,
        isRefined,
        0,
        1,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0);
  }
}
