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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Pending ship repairs require real base data and complete dependent archive inputs. */
@SpringBootTest
@Transactional
class P4kShipBaseRepairImportTest {

  private static final UUID BASE = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1c001");
  private static final UUID VARIANT = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1c002");
  private static final String BASE_CLASS = "TEST_Repair_Base";
  private static final String VARIANT_CLASS = "TEST_Repair_Variant";
  private static final int UEX_VEHICLE_ID = 987_778;

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private ShipTypeRepository ships;
  @Autowired private EntityManager entityManager;

  @Test
  void aCompleteArchiveRepairsTheSameShipAndFinishesTheOriginalCase() {
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);
    P4kReviewCase pending = pending(runs);
    byte[] payload = P4kTestCatalogues.bytes(catalogue());

    assertThat(runs.previewWithReport(payload).changes())
        .anyMatch(P4kShipBaseRepairImportTest::repairedGuid);
    ShipType ship = ships.findByExternalUuid(BASE).orElseThrow();
    assertThat(ship.getP4kUuid()).isEqualTo(VARIANT);
    assertThat(pending.isShipRepairPending()).isTrue();

    P4kImportResultDto result = runs.apply(payload, false);

    assertThat(ship.getP4kUuid()).isEqualTo(BASE);
    assertThat(ship.getClassName()).isEqualTo(BASE_CLASS);
    assertThat(ship.getRole()).isEqualTo("Base Role");
    assertThat(ship.getUexVehicleId()).isEqualTo(UEX_VEHICLE_ID);
    assertThat(ship.getExternalUuid()).isEqualTo(BASE);
    assertThat(pending.isShipRepairPending()).isFalse();
    assertThat(pending.getResolution()).isEqualTo(P4kReviewResolution.APPLIED);
    assertThat(pending.getResolvedBy()).isEqualTo(P4kImportRuns.ADMIN);
    assertThat(P4kImportRuns.report(changes, result.runId()))
        .anyMatch(P4kShipBaseRepairImportTest::repairedGuid);
  }

  @Test
  void missingLoadoutAndMalformedBaseLeaveTheRepairPending() {
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);
    P4kReviewCase pending = pending(runs);
    ObjectNode missingLoadout = catalogue();
    missingLoadout.putArray("loadouts");
    assertThat(runs.previewWithReport(P4kTestCatalogues.bytes(missingLoadout)).changes())
        .noneMatch(P4kShipBaseRepairImportTest::repairedGuid);

    ObjectNode malformed = catalogue();
    P4kTestCatalogues.record(malformed, "ships", BASE).put("vehicleKind", "unknown");
    runs.apply(P4kTestCatalogues.bytes(malformed), false);

    ShipType ship = ships.findByExternalUuid(BASE).orElseThrow();
    assertThat(ship.getP4kUuid()).isEqualTo(VARIANT);
    assertThat(ship.getClassName()).isEqualTo(VARIANT_CLASS);
    assertThat(pending.isShipRepairPending()).isTrue();
    assertThat(pending.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    assertThat(pending.getResolvedAt()).isNull();
  }

  @Test
  void competingProjectedNameAndUexAliasEachBlockRepair() {
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);
    P4kReviewCase pending = pending(runs);
    ObjectNode contested = catalogue();
    ObjectNode competitor =
        P4kTestCatalogues.record(contested, "ships", P4kTestCatalogues.SPECIAL_SHIP);
    ((ObjectNode) competitor.get("name")).put("en", "Repair Test Base");
    assertThat(runs.previewWithReport(P4kTestCatalogues.bytes(contested)).changes())
        .noneMatch(P4kShipBaseRepairImportTest::repairedGuid);

    ShipType aliased = new ShipType();
    aliased.setName("Competing UEX Ship");
    aliased.setUexVehicleId(UEX_VEHICLE_ID + 1);
    ships.saveAndFlush(aliased);
    entityManager.persist(new ShipTypeUexAlias(UEX_VEHICLE_ID + 1, BASE_CLASS));
    entityManager.flush();
    assertThat(runs.previewWithReport(P4kTestCatalogues.bytes(catalogue())).changes())
        .noneMatch(P4kShipBaseRepairImportTest::repairedGuid);
    assertThat(pending.isShipRepairPending()).isTrue();
  }

  private P4kReviewCase pending(P4kImportRuns runs) {
    UUID runId = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();
    ShipType ship = new ShipType();
    ship.setName("Repair Test Variant");
    ship.setClassName(VARIANT_CLASS);
    ship.setP4kUuid(VARIANT);
    ship.setExternalUuid(BASE);
    ship.setUexVehicleId(UEX_VEHICLE_ID);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    ship.setRole("Variant Role");
    ships.saveAndFlush(ship);

    P4kReviewCase review = new P4kReviewCase();
    review.setKind(P4kKind.SHIP);
    review.setGuid(BASE);
    review.setReason(P4kReviewReason.GUID_CONFLICT);
    review.setFirstRunId(runId);
    review.setLastRunId(runId);
    review.queueShipRepair(ship.getId(), VARIANT, VARIANT_CLASS, BASE_CLASS, P4kImportRuns.ADMIN);
    return cases.saveAndFlush(review);
  }

  private static ObjectNode catalogue() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode base = P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.PLAYER_SHIP);
    base.put("guid", BASE.toString()).put("className", BASE_CLASS).putNull("variantOf");
    ((ObjectNode) base.get("name")).put("en", "Repair Test Base");
    base.putObject("role").put("en", "Base Role");

    ObjectNode variant = P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.AI_SHIP);
    variant
        .put("guid", VARIANT.toString())
        .put("className", VARIANT_CLASS)
        .put("variantOf", BASE.toString());
    ((ObjectNode) variant.get("name")).put("en", "Repair Test Base");

    ArrayNode loadouts = (ArrayNode) root.get("loadouts");
    for (var node : loadouts) {
      if (P4kTestCatalogues.PLAYER_SHIP.toString().equals(node.get("entityGuid").asString())) {
        ((ObjectNode) node).put("entityGuid", BASE.toString());
      }
    }
    return root;
  }

  private static boolean repairedGuid(P4kImportChange change) {
    return change.getKind() == P4kKind.SHIP
        && change.getChangeType() == P4kChangeType.CHANGED
        && BASE.equals(change.getGuid())
        && "p4k_uuid".equals(change.getFieldName())
        && VARIANT.toString().equals(change.getOldValue())
        && BASE.toString().equals(change.getNewValue());
  }
}
