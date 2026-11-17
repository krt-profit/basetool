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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A second ship type of a record's ship is merged into the row of its GUID (REQ-P4K-053). */
@SpringBootTest
@Transactional
class P4kShipTwinsImportTest {

  private static final String SURVIVOR_NAME = "Aegis Avenger Titan";
  private static final String TWIN_NAME = "Avenger Titan";
  private static final UUID BASE = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1e001");
  private static final UUID EDITION = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1e002");
  private static final String EDITION_NAME = "Twin Test Ship";

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private ShipTypeRepository ships;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void anApplyMergesTheWikiRowIntoTheRowOfItsGuid() {
    ShipType survivor = ships.saveAndFlush(survivor());
    ShipType twin = ships.saveAndFlush(twin());
    UUID ship = insertShip(twin.getId());
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    entityManager.flush();
    entityManager.clear();

    assertThat(count(twin.getId())).isZero();
    assertThat(jdbc.queryForObject("SELECT ship_type_id FROM ship WHERE id = ?", UUID.class, ship))
        .isEqualTo(survivor.getId());
    assertThat(ships.findById(twin.getId())).map(ShipType::getId).contains(survivor.getId());
    assertThat(ships.findById(survivor.getId()).orElseThrow().getExternalUuid())
        .isEqualTo(P4kTestCatalogues.PLAYER_SHIP);
    assertThat(merges(runId))
        .singleElement()
        .satisfies(change -> {
          assertThat(change.getOldValue()).isEqualTo(TWIN_NAME);
          assertThat(change.getGuid()).isEqualTo(P4kTestCatalogues.PLAYER_SHIP);
        });
  }

  @Test
  void aTwinOfTheEditionARowLeavesForItsBaseMergesAndTheRowKeepsItsWikiUuid() {
    ShipType stamped = new ShipType();
    stamped.setName(EDITION_NAME);
    stamped.setP4kUuid(EDITION);
    stamped.setClassName("TEST_Twin_Edition");
    stamped.setExternalUuid(BASE);
    stamped.setSourceSystems(GameItemSourceSystem.BOTH);
    ShipType survivor = ships.saveAndFlush(stamped);
    ShipType editionTwin = new ShipType();
    editionTwin.setName("Twin Test Edition");
    editionTwin.setExternalUuid(EDITION);
    editionTwin.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    UUID twinId = ships.saveAndFlush(editionTwin).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(
        P4kTestCatalogues.bytes(
            P4kTestCatalogues.withEdition(
                BASE, "TEST_Twin_Base", EDITION, "TEST_Twin_Edition", EDITION_NAME)),
        false);
    entityManager.flush();
    entityManager.clear();

    assertThat(count(twinId)).isZero();
    ShipType kept = ships.findById(survivor.getId()).orElseThrow();
    assertThat(kept.getP4kUuid()).isEqualTo(BASE);
    assertThat(kept.getExternalUuid()).isEqualTo(BASE);
  }

  @Test
  void aPreviewReportsTheMergeAndKeepsBothRows() {
    ships.saveAndFlush(survivor());
    ShipType twin = ships.saveAndFlush(twin());
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    List<P4kImportChange> report =
        runs.previewWithReport(P4kTestCatalogues.fixtureBytes()).changes();

    assertThat(report)
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.MERGED
                && P4kTestCatalogues.PLAYER_SHIP.equals(change.getGuid()));
    assertThat(count(twin.getId())).isOne();
  }

  @Test
  void aSecondApplyFindsNothingToMerge() {
    ships.saveAndFlush(survivor());
    ships.saveAndFlush(twin());
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    UUID second = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();

    assertThat(merges(second)).isEmpty();
  }

  private List<P4kImportChange> merges(UUID runId) {
    return P4kImportRuns.report(changes, runId).stream()
        .filter(change ->
            change.getChangeType() == P4kChangeType.MERGED && change.getKind() == P4kKind.SHIP)
        .toList();
  }

  private int count(UUID shipTypeId) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM ship_type WHERE id = ?", Integer.class, shipTypeId);
    return found == null ? 0 : found;
  }

  private UUID insertShip(UUID shipTypeId) {
    UUID owner = UUID.randomUUID();
    jdbc.update("INSERT INTO app_user (id) VALUES (?)", owner);
    UUID ship = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, ship_type_id, owner_id, insurance, version)"
            + " VALUES (?, ?, ?, 'LTI', 0)",
        ship,
        shipTypeId,
        owner);
    return ship;
  }

  private static ShipType survivor() {
    ShipType ship = new ShipType();
    ship.setName(SURVIVOR_NAME);
    ship.setP4kUuid(P4kTestCatalogues.PLAYER_SHIP);
    ship.setClassName("AEGS_Avenger_Titan");
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }

  private static ShipType twin() {
    ShipType ship = new ShipType();
    ship.setName(TWIN_NAME);
    ship.setExternalUuid(P4kTestCatalogues.PLAYER_SHIP);
    ship.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    return ship;
  }
}
