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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiManufacturerDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiVehicleDto;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Verifies typed enrichment, member authorization and isolated writes against the real schema. */
@SpringBootTest
class WikiEnrichmentIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @Autowired private MaterialRepository materials;
  @Autowired private MaterialExternalAliasService aliases;
  @Autowired private ManufacturerRepository manufacturers;
  @Autowired private MaterialMapper materialMapper;
  @Autowired private GameItemRepository items;
  @Autowired private ShipTypeRepository ships;
  @Autowired private SyncChunkWriter writer;
  @Autowired private EntityManager entities;
  @Autowired private WebApplicationContext context;

  @Test
  @Transactional
  void miningAndLocationEvidenceRoundTripsWithoutInflatingMaterialSummaries() {
    ScWikiCommodityDto source =
        JSON.readValue(
            """
            {"uuid":"6c45b24e-bb2d-45f1-9622-f7ef9359dc77","name":"Amioshi Plague",
             "density_g_per_cc":1,"kind":null,"instability":5,"resistance":-0.2,
             "is_mineable":true,"has_ship_mineables":false,"has_harvestables":true,
             "box_sizes_scu":[0.125,1,32],"commodity_groups":["Organic"],
             "methods":["Harvestable"],"systems":["Pyro System"],
             "locations":[{"uuid":"8d7e2b47-d27b-4e0d-9760-7bb502ae02e1",
               "name":"Ashland","display_name":"Ashland","type":"Outpost",
               "entries":[{"group_name":"Remains","resource_kind":"remains",
                 "quality_min":100,"quality_max":600,"entry_count":2}]}]}
            """,
            ScWikiCommodityDto.class);
    Material material = new Material();
    material.setName("Mining evidence " + UUID.randomUUID());
    material.setType(MaterialType.NO_REFINE);
    material.setMiningData(source.miningData());
    UUID id = materials.saveAndFlush(material).getId();
    entities.clear();

    Material loaded = materials.findById(id).orElseThrow();
    var detail = materialMapper.toDetailDto(loaded);

    assertThat(materialMapper.toDto(loaded).miningData()).isNull();
    assertThat(detail.miningData()).isEqualTo(source.miningData());
    assertThat(detail.miningData().boxSizesScu()).containsExactly(0.125, 1.0, 32.0);
    assertThat(detail.miningData().locations().getFirst().entries().getFirst().qualityMax())
        .isEqualTo(600);
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> detail.miningData().commodityGroups().add("changed"));
  }

  @Test
  @Transactional
  void itemEvidenceRoundTripsAndOnlyMemberRolesReadItsEndpoint() throws Exception {
    ScWikiItemDto source =
        JSON.readValue(
            """
            {"uuid":"4577c7a3-76ab-4e37-91de-68862dacb964","name":"A03 Akuma",
             "class_name":"sniper_fixture","version":"4.10.2-LIVE.12881860",
             "is_lootable":true,"tags":["weapon"],"required_tags":["fps"],
             "dimension":{"dimensions":{"width":0.072,"height":0.238,"length":1.184},
               "cargo_dimension":{"width":0.06,"height":0.34,"length":1},
               "ui_dimension":{"width":0.75,"height":0.75,"length":1.5},"volume":0.019}}
            """,
            ScWikiItemDto.class);
    GameItem item = new GameItem();
    item.setName("Metadata evidence " + UUID.randomUUID());
    item.setExternalUuid(source.uuid());
    item.setClassName(source.className());
    item.setWikiMetadata(source.metadata());
    UUID id = items.saveAndFlush(item).getId();
    entities.clear();
    assertThat(items.findById(id).orElseThrow().getWikiMetadata()).isEqualTo(source.metadata());
    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String path = "/api/v1/game-items/" + id + "/catalogue";

    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_NO_ROLE"))))
        .andExpect(status().isForbidden());
    mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.wikiMetadata.source").value("SC_WIKI"))
        .andExpect(jsonPath("$.wikiMetadata.geometry.physical.width").value(0.072))
        .andExpect(jsonPath("$.wikiMetadata.geometry.ui.width").value(0.75));
  }

  @Test
  void aConstraintFailureDoesNotRollBackOtherWikiVehicleRows() {
    UUID first = UUID.randomUUID();
    UUID invalid = UUID.randomUUID();
    UUID last = UUID.randomUUID();
    var rows =
        List.of(
            ScWikiVehicleDto.builder()
                .uuid(first)
                .name("First " + first)
                .vehicleInventory(710000.0)
                .build(),
            ScWikiVehicleDto.builder().uuid(invalid).name("x".repeat(300)).build(),
            ScWikiVehicleDto.builder().uuid(last).name("Last " + last).health(11900.5).build());
    ScWikiClient client = mock(ScWikiClient.class);
    when(client.<ScWikiVehicleDto>fetchAllPagesResult(any(), any(), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(rows));
    ScWikiProperties config =
        BoundProperties.bind(ScWikiProperties.class, Map.of("vehicle-sync-enabled", true));
    var service =
        new ScWikiVehicleSyncService(client, config, ships, mock(SyncReportService.class), writer);
    try {
      assertThat(service.syncVehicles()).isEqualTo(2);
      writer.runInNewTransaction(() -> {
        assertThat(ships.findByExternalUuid(first).orElseThrow().getVehicleInventoryScu())
            .isEqualTo(0.71);
        assertThat(ships.findByExternalUuid(invalid)).isEmpty();
        assertThat(ships.findByExternalUuid(last).orElseThrow().getHealth()).isEqualTo(11900.5);
      });
    } finally {
      writer.runInNewTransaction(() -> {
        for (UUID uuid : List.of(first, invalid, last)) {
          ships.findByExternalUuid(uuid).ifPresent(ships::delete);
        }
      });
    }
  }

  @Test
  void aConstraintFailureDoesNotRollBackOtherWikiCommodityRows() {
    UUID first = UUID.randomUUID();
    UUID invalid = UUID.randomUUID();
    UUID last = UUID.randomUUID();
    List<ScWikiCommodityDto> rows =
        List.of(
            commodity(first, "Commodity first " + first),
            commodity(invalid, "x".repeat(300)),
            commodity(last, "Commodity last " + last));
    ScWikiClient client = mock(ScWikiClient.class);
    when(client.<ScWikiCommodityDto>fetchAllPagesResult(any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(rows));
    ScWikiProperties config =
        BoundProperties.bind(ScWikiProperties.class, Map.of("commodity-sync-enabled", true));
    var service =
        new ScWikiCommoditySyncService(
            client, config, materials, aliases, mock(SyncReportService.class), writer);
    try {
      assertThat(service.syncCommodities()).isEqualTo(2);
      writer.runInNewTransaction(() -> {
        assertThat(materials.findByScwikiUuid(first).orElseThrow().getDensityGramPerCc())
            .isEqualTo(1.0);
        assertThat(materials.findByScwikiUuid(invalid)).isEmpty();
        assertThat(materials.findByScwikiUuid(last).orElseThrow().getName())
            .isEqualTo(rows.getLast().name());
      });
    } finally {
      writer.runInNewTransaction(() -> {
        for (UUID uuid : List.of(first, invalid, last)) {
          materials.findByScwikiUuid(uuid).ifPresent(materials::delete);
        }
      });
    }
  }

  @Test
  void aConstraintFailureDoesNotRollBackOtherWikiManufacturerRows() {
    UUID first = UUID.randomUUID();
    UUID invalid = UUID.randomUUID();
    UUID last = UUID.randomUUID();
    String firstName = "Manufacturer first " + first;
    String invalidName = "Manufacturer invalid " + invalid;
    String lastName = "Manufacturer last " + last;
    List<String> names = List.of(firstName, invalidName, lastName);
    writer.runInNewTransaction(() -> {
      for (String name : names) {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setName(name);
        manufacturer.setAbbreviation("Canonical");
        manufacturers.save(manufacturer);
      }
    });
    List<ScWikiManufacturerDto> rows =
        List.of(
            new ScWikiManufacturerDto(first, firstName, "FIRST"),
            new ScWikiManufacturerDto(invalid, invalidName, "x".repeat(300)),
            new ScWikiManufacturerDto(last, lastName, "LAST"));
    ScWikiClient client = mock(ScWikiClient.class);
    when(client.<ScWikiManufacturerDto>fetchAllPagesResult(any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(rows));
    ScWikiProperties config =
        BoundProperties.bind(ScWikiProperties.class, Map.of("manufacturer-sync-enabled", true));
    var service =
        new ScWikiManufacturerSyncService(
            client, config, manufacturers, mock(SyncReportService.class), writer);
    try {
      assertThat(service.syncManufacturers()).isEqualTo(2);
      writer.runInNewTransaction(() -> {
        assertThat(manufacturers.findByScwikiUuid(first).orElseThrow().getScwikiCode())
            .isEqualTo("FIRST");
        assertThat(manufacturers.findByScwikiUuid(invalid)).isEmpty();
        assertThat(
                manufacturers.findByNameIgnoreCase(invalidName).orElseThrow().getScwikiSyncedAt())
            .isNull();
        assertThat(manufacturers.findByScwikiUuid(last).orElseThrow().getScwikiCode())
            .isEqualTo("LAST");
        assertThat(manufacturers.findByScwikiUuid(last).orElseThrow().getAbbreviation())
            .isEqualTo("Canonical");
      });
    } finally {
      writer.runInNewTransaction(() -> {
        for (String name : names) {
          manufacturers.findByNameIgnoreCase(name).ifPresent(manufacturers::delete);
        }
      });
    }
  }

  @Test
  @Transactional
  void wikiBulkTombstonesAdvanceEachAggregateVersionAndUpdateTimestamp() {
    UUID external = UUID.randomUUID();
    Instant previousSync = Instant.parse("2026-01-01T00:00:00Z");
    Material material = new Material();
    material.setName("Version material " + external);
    material.setType(MaterialType.NO_REFINE);
    material.setScwikiUuid(external);
    material.setScwikiSyncedAt(previousSync);
    materials.saveAndFlush(material);
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Version manufacturer " + external);
    manufacturer.setAbbreviation("VERSION");
    manufacturer.setScwikiUuid(external);
    manufacturer.setScwikiSyncedAt(previousSync);
    manufacturers.saveAndFlush(manufacturer);
    ShipType ship = new ShipType();
    ship.setName("Version ship " + external);
    ship.setExternalUuid(external);
    ship.setScwikiSyncedAt(previousSync);
    ships.saveAndFlush(ship);
    long materialVersion = material.getVersion();
    long manufacturerVersion = manufacturer.getVersion();
    long shipVersion = ship.getVersion();
    UUID materialId = material.getId();
    UUID manufacturerId = manufacturer.getId();
    UUID shipId = ship.getId();
    Instant deletedAt = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MICROS);
    List<UUID> seen = List.of(UUID.randomUUID());
    entities.clear();

    assertThat(materials.markScwikiDeleted(seen, deletedAt)).isPositive();
    assertThat(manufacturers.markScwikiDeletedExcept(seen, deletedAt)).isPositive();
    assertThat(ships.markScwikiDeletedExcept(seen, deletedAt)).isPositive();
    entities.clear();

    Material deletedMaterial = materials.findById(materialId).orElseThrow();
    Manufacturer deletedManufacturer = manufacturers.findById(manufacturerId).orElseThrow();
    ShipType deletedShip = ships.findById(shipId).orElseThrow();
    assertThat(deletedMaterial.getVersion()).isEqualTo(materialVersion + 1);
    assertThat(deletedManufacturer.getVersion()).isEqualTo(manufacturerVersion + 1);
    assertThat(deletedShip.getVersion()).isEqualTo(shipVersion + 1);
    assertThat(deletedMaterial.getScwikiDeletedAt()).isEqualTo(deletedAt);
    assertThat(deletedManufacturer.getScwikiDeletedAt()).isEqualTo(deletedAt);
    assertThat(deletedShip.getScwikiDeletedAt()).isEqualTo(deletedAt);
    assertThat(deletedMaterial.getUpdatedAt()).isEqualTo(deletedAt);
    assertThat(deletedManufacturer.getUpdatedAt()).isEqualTo(deletedAt);
    assertThat(deletedShip.getUpdatedAt()).isEqualTo(deletedAt);
  }

  /** Builds a commodity payload whose imported fields cross the real schema boundary. */
  private static ScWikiCommodityDto commodity(UUID uuid, String name) {
    return JSON.readValue(
        """
        {"uuid":"%s","key":"%s","name":"%s","density_g_per_cc":1}
        """
            .formatted(uuid, uuid, name),
        ScWikiCommodityDto.class);
  }
}
