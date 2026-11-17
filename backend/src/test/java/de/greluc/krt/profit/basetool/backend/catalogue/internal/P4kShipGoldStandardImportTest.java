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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The row of a record a {@code _GS} record supersedes merges into that record's row. */
@SpringBootTest
@Transactional
class P4kShipGoldStandardImportTest {

  private static final UUID OLD = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1b001");
  private static final UUID GS = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1b002");
  private static final String OLD_CLASS = "TEST_Gold_Ship";
  private static final String NAME = "Gold Test Ship";
  private static final int OLD_UEX_ID = 990_301;

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private ShipTypeRepository ships;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void theRowOfASupersededRecordMergesIntoTheRowOfItsGsRecord() {
    ShipType old = row("Gold Test Ship Old", OLD, OLD_CLASS);
    old.setUexVehicleId(OLD_UEX_ID);
    UUID oldId = ships.saveAndFlush(old).getId();
    ShipType gold = ships.saveAndFlush(row(NAME, GS, OLD_CLASS + "_GS"));
    UUID ship = insertShip(oldId);
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID first = runs.apply(catalogue(), false).runId();
    UUID second = runs.apply(catalogue(), false).runId();
    entityManager.flush();
    entityManager.clear();

    assertThat(count(oldId)).isZero();
    assertThat(jdbc.queryForObject("SELECT ship_type_id FROM ship WHERE id = ?", UUID.class, ship))
        .isEqualTo(gold.getId());
    assertThat(ships.findById(gold.getId()).orElseThrow().getUexVehicleId()).isEqualTo(OLD_UEX_ID);
    assertThat(changesOf(first, P4kChangeType.MERGED))
        .singleElement()
        .extracting(P4kImportChange::getGuid)
        .isEqualTo(GS);
    assertThat(changesOf(second, P4kChangeType.MERGED)).isEmpty();
    assertThat(changesOf(second, P4kChangeType.COVERED))
        .anyMatch(change -> OLD.equals(change.getGuid()));
    assertThat(cases.findAll()).noneMatch(review -> OLD.equals(review.getGuid()));
  }

  @Test
  void theRowOfASupersededRecordStaysWhileNoRowHoldsItsGsRecord() {
    UUID oldId = ships.saveAndFlush(row(NAME, OLD, OLD_CLASS)).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();

    assertThat(count(oldId)).isOne();
    assertThat(changesOf(runId, P4kChangeType.MERGED)).isEmpty();
  }

  private List<P4kImportChange> changesOf(UUID runId, P4kChangeType type) {
    return P4kImportRuns.report(changes, runId).stream()
        .filter(change -> change.getChangeType() == type && change.getKind() == P4kKind.SHIP)
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

  private static ShipType row(String name, UUID guid, String className) {
    ShipType ship = new ShipType();
    ship.setName(name);
    ship.setP4kUuid(guid);
    ship.setClassName(className);
    ship.setExternalUuid(guid);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }

  /** Builds the fixture with the player ship as the old record and a {@code _GS} copy of it. */
  private static byte[] catalogue() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode old = P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.PLAYER_SHIP);
    old.put("guid", OLD.toString()).put("className", OLD_CLASS).putNull("variantOf");
    ((ObjectNode) old.get("name")).put("en", NAME);
    ObjectNode gold = old.deepCopy();
    gold.put("guid", GS.toString())
        .put("className", OLD_CLASS + "_GS")
        .put("recordPath", "libs/foundry/records/entities/spaceships/test_gold_ship_gs");
    ((ArrayNode) root.get("ships")).add(gold);
    for (var node : (ArrayNode) root.get("loadouts")) {
      if (P4kTestCatalogues.PLAYER_SHIP.toString().equals(node.get("entityGuid").asString())) {
        ((ObjectNode) node).put("entityGuid", GS.toString());
      }
    }
    return P4kTestCatalogues.bytes(root);
  }
}
