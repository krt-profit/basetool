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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCompanyDto;
import de.greluc.krt.profit.basetool.backend.testcontext.LeafServiceMockTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A UEX manufacturer sync merges the rows only UEX created into the P4K row of their brand and
 * creates no new twin (REQ-DATA-045). The sync commits each company and each merge in a transaction
 * of its own, so the rows are committed and removed again after each test.
 */
@LeafServiceMockTest
class ManufacturerUexTwinSyncIntegrationTest {

  @Autowired private UexClient uexClient;
  @Autowired private UexManufacturerService sync;
  @Autowired private ManufacturerRepository manufacturers;
  @Autowired private ManufacturerUexCompanyRepository uexCompanies;
  @Autowired private ManufacturerScwikiIdentityRepository wikiIdentities;
  @Autowired private GameItemRepository items;
  @Autowired private ShipTypeRepository shipTypes;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> manufacturerIds = new ArrayList<>();
  private final List<UUID> itemIds = new ArrayList<>();
  private final List<UUID> shipTypeIds = new ArrayList<>();
  private final List<Integer> companyIds = new ArrayList<>();
  private String suffix;

  @BeforeEach
  void setUp() {
    suffix = UUID.randomUUID().toString().substring(0, 8);
  }

  @AfterEach
  void tearDown() {
    for (UUID id : itemIds) {
      jdbc.update("DELETE FROM game_item WHERE id = ?", id);
    }
    for (UUID id : shipTypeIds) {
      jdbc.update("DELETE FROM ship_type WHERE id = ?", id);
    }
    for (UUID id : manufacturerIds) {
      jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id);
    }
    for (Integer company : companyIds) {
      jdbc.update("DELETE FROM manufacturer_uex_company WHERE uex_company_id = ?", company);
    }
    for (UUID id : manufacturerIds) {
      jdbc.update("DELETE FROM manufacturer WHERE id = ?", id);
    }
  }

  @Test
  void aTwinMergesIntoTheP4kRowWithItsReferences_andASecondSyncCreatesNothing() {
    Manufacturer survivor = p4kRow("Vanduul " + suffix, "Vanduul Clans " + suffix);
    int company = company();
    Manufacturer twin = new Manufacturer();
    twin.setName("Vanduul Clans " + suffix);
    twin.setAbbreviation("VC" + suffix);
    twin.setUexCompanyId(company);
    twin = saved(twin);
    uexCompanies.save(new ManufacturerUexCompany(company, twin));
    GameItem item = new GameItem();
    item.setName("Twin Merge Item " + suffix);
    item.setManufacturer(twin);
    item.setP4kManufacturer(twin);
    itemIds.add(items.save(item).getId());
    ShipType ship = new ShipType();
    ship.setName("Twin Merge Ship " + suffix);
    ship.setManufacturer(twin);
    shipTypeIds.add(shipTypes.save(ship).getId());
    when(uexClient.getCompanies())
        .thenReturn(UexClient.FetchResult.of(List.of(companyDto(company, twin.getName()))));

    sync.syncManufacturers();

    assertThat(manufacturers.findById(twin.getId())).isEmpty();
    assertThat(uexCompanies.findManufacturerByUexCompanyId(company))
        .hasValueSatisfying(brand -> assertThat(brand.getId()).isEqualTo(survivor.getId()));
    assertThat(manufacturers.findById(survivor.getId()))
        .hasValueSatisfying(row -> {
          assertThat(row.getName()).isEqualTo("Vanduul " + suffix);
          assertThat(row.getUexCompanyId()).isEqualTo(company);
        });
    assertThat(
            jdbc.queryForMap(
                "SELECT manufacturer_id, p4k_manufacturer_id FROM game_item WHERE id = ?",
                itemIds.getFirst()))
        .containsEntry("manufacturer_id", survivor.getId())
        .containsEntry("p4k_manufacturer_id", survivor.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT manufacturer_id FROM ship_type WHERE id = ?",
                UUID.class,
                shipTypeIds.getFirst()))
        .isEqualTo(survivor.getId());
    assertThat(
            jdbc.queryForList(
                "SELECT details FROM audit_event"
                    + " WHERE event_type = 'CATALOGUE_MANUFACTURER_MERGED' AND subject_id = ?",
                String.class,
                survivor.getId()))
        .containsExactly(
            "retiredManufacturer="
                + twin.getId()
                + " rule=WIKI_NAME uexCompanyId="
                + company
                + " items=1 shipTypes=1");

    sync.syncManufacturers();

    assertThat(namedLike("Vanduul%" + suffix)).containsExactly(Map.of("id", survivor.getId()));
    assertThat(uexCompanies.findManufacturerByUexCompanyId(company))
        .hasValueSatisfying(brand -> assertThat(brand.getId()).isEqualTo(survivor.getId()));
  }

  @Test
  void aNewCompanyNamingAP4kRowJoinsIt_withoutCreatingATwin() {
    Manufacturer brand = p4kRow("Talon " + suffix, "Talon Weapons Systems " + suffix);
    int company = company();
    when(uexClient.getCompanies())
        .thenReturn(
            UexClient.FetchResult.of(
                List.of(companyDto(company, "Talon Weapons Systems " + suffix))));

    sync.syncManufacturers();

    assertThat(namedLike("Talon%" + suffix)).containsExactly(Map.of("id", brand.getId()));
    assertThat(uexCompanies.findManufacturerByUexCompanyId(company))
        .hasValueSatisfying(row -> assertThat(row.getId()).isEqualTo(brand.getId()));
    assertThat(manufacturers.findById(brand.getId()))
        .hasValueSatisfying(row -> {
          assertThat(row.getName()).isEqualTo("Talon " + suffix);
          assertThat(row.getUexCompanyId()).isEqualTo(company);
        });
  }

  private Manufacturer p4kRow(String name, String wikiName) {
    Manufacturer row = new Manufacturer();
    row.setName(name);
    row.setAbbreviation("P" + suffix);
    row.setP4kUuid(UUID.randomUUID());
    row = saved(row);
    wikiIdentities.save(
        new ManufacturerScwikiIdentity(UUID.randomUUID(), row, wikiName, null, Instant.now()));
    return row;
  }

  private Manufacturer saved(Manufacturer row) {
    Manufacturer saved = manufacturers.save(row);
    manufacturerIds.add(saved.getId());
    return saved;
  }

  private int company() {
    int company = 900_000 + ThreadLocalRandom.current().nextInt(90_000);
    companyIds.add(company);
    return company;
  }

  private UexCompanyDto companyDto(int company, String name) {
    return UexCompanyDto.builder().id(company).name(name).nickname("N" + suffix).build();
  }

  private List<Map<String, Object>> namedLike(String pattern) {
    return jdbc.queryForList("SELECT id FROM manufacturer WHERE name LIKE ?", pattern);
  }
}
