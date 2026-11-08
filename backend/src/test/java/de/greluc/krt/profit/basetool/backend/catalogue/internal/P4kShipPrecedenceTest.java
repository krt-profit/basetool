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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipVehicleKind;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The P4K import leads a matched ship's base data, keeps the stored cargo while a cargo grid is
 * unreadable, never takes a name another ship type holds, keeps the variant groups and cross-checks
 * the store slugs (REQ-P4K-031, REQ-P4K-032).
 */
@SpringBootTest
@Transactional
class P4kShipPrecedenceTest {

  /** The base the fixture's AI Hornet belongs to. */
  private static final UUID HORNET_BASE = UUID.fromString("fb231ba1-dba1-4c9f-823a-746c772ae92f");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ShipTypeP4kVariantRepository variantRepository;
  @Autowired private ExternalSyncReportRepository syncReportRepository;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  /**
   * Stores the Avenger Titan the fixture's player ship matches by class name.
   *
   * @return the stored ship
   */
  private @NotNull ShipType titan() {
    ShipType titan = new ShipType();
    titan.setName("Avenger Titan");
    titan.setClassName("AEGS_Avenger_Titan");
    titan.setScu(new BigDecimal("12.000"));
    titan.setCrewMax(2);
    titan.setMass(1.0);
    return shipTypeRepository.save(titan);
  }

  /**
   * Lists the ship disagreements a run recorded.
   *
   * @param runId the run
   * @return the details of its {@code P4K_DISAGREEMENT} events
   */
  private @NotNull List<String> disagreements(@NotNull UUID runId) {
    return syncReportRepository.findAll().stream()
        .filter(r -> runId.equals(r.getRunId()))
        .filter(r -> r.getEventType() == SyncEventType.P4K_DISAGREEMENT)
        .map(ExternalSyncReport::getDetail)
        .toList();
  }

  @Test
  void aMatchedShipTakesTheCatalogueBaseData() {
    ShipType titan = titan();

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(titan.getName()).isEqualTo("Aegis Avenger Titan");
    assertThat(titan.getCrewMax()).isEqualTo(1);
    assertThat(titan.getRole()).isEqualTo("Light Freight");
    assertThat(titan.getCareer()).isEqualTo("Transporter");
    assertThat(titan.getMass()).isEqualTo(48986.68);
    assertThat(titan.getHealth()).isEqualTo(11900.0);
    assertThat(titan.getSizeClass()).isEqualTo(2);
    assertThat(titan.getVehicleKind()).isEqualTo(ShipVehicleKind.SPACESHIP);
    assertThat(titan.getInsuranceRestoreOnClaim()).isTrue();
    assertThat(titan.getInsuranceShipClassName()).isEqualTo("AEGS_Avenger_Titan");
    assertThat(titan.getInsuranceClaimMinutes()).isEqualTo(4.05);
    assertThat(titan.getInsuranceMandatoryClaimMinutes()).isEqualTo(1.35);
    assertThat(titan.getInsuranceExpediteFee()).isEqualTo(2343L);
    assertThat(titan.getScu()).isEqualByComparingTo("8");
  }

  @Test
  void aShipWithUnreadableCargoGridsKeepsItsCargoAndReportsTheDifference() {
    ShipType titan = titan();
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.PLAYER_SHIP)
        .put("cargoGridsWithIssue", 2);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(root), false).runId();

    assertThat(titan.getScu()).isEqualByComparingTo("12");
    assertThat(disagreements(runId))
        .singleElement()
        .satisfies(d -> assertThat(d).contains("column=scu", "stored=12.000", "source=8.000"));
  }

  @Test
  void aNameAnotherShipTypeHoldsIsReportedAndNotTaken() {
    ShipType titan = titan();
    ShipType holder = new ShipType();
    holder.setName("Aegis Avenger Titan");
    shipTypeRepository.save(holder);

    UUID runId = runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId();

    assertThat(titan.getName()).isEqualTo("Avenger Titan");
    assertThat(titan.getRole()).isEqualTo("Light Freight");
    assertThat(disagreements(runId))
        .singleElement()
        .satisfies(d -> assertThat(d).contains("column=name", "source=Aegis Avenger Titan"));
  }

  @Test
  void aStoreSlugOfAnotherShipIsReportedAndNothingIsWritten() {
    ShipType titan = titan();
    titan.setUrlStore(
        "https://robertsspaceindustries.com/pledge/ships/aegis-avenger/Avenger-Titan");
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode slug = ((ArrayNode) root.withArray("shipStoreSlugs")).addObject();
    slug.put("slug", "Avenger-Titan");
    slug.put("rsiId", 1);
    slug.put("storeName", "Avenger Titan");
    slug.put("className", "AEGS_Javelin");
    slug.put("shipGuid", P4kTestCatalogues.SPECIAL_SHIP.toString());
    slug.put("retired", false);

    UUID runId = runs.apply(P4kTestCatalogues.bytes(root), false).runId();

    assertThat(titan.getUrlStore()).endsWith("/Avenger-Titan");
    assertThat(disagreements(runId))
        .singleElement()
        .satisfies(d ->
            assertThat(d).contains("column=url_store", "source=" + P4kTestCatalogues.SPECIAL_SHIP));
  }

  @Test
  void anApplyKeepsTheVariantGroupsAndACompleteKindDropsStaleOnes() {
    UUID stale = UUID.randomUUID();
    variantRepository.save(new ShipTypeP4kVariant(stale, "TEST_Stale_Variant", HORNET_BASE));

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    entityManager.flush();
    entityManager.clear();

    assertThat(variantRepository.findById(P4kTestCatalogues.AI_SHIP))
        .hasValueSatisfying(v -> {
          assertThat(v.getClassName()).isEqualTo(P4kTestCatalogues.AI_SHIP_CLASS);
          assertThat(v.getBaseUuid()).isEqualTo(HORNET_BASE);
        });
    assertThat(variantRepository.findById(stale)).isEmpty();
  }

  @Test
  void aCompleteKindWithoutAnyVariantKeepsTheStoredVariants() {
    UUID stored = UUID.randomUUID();
    variantRepository.save(new ShipTypeP4kVariant(stored, "TEST_Stored_Variant", HORNET_BASE));
    ObjectNode root = P4kTestCatalogues.tree();
    for (var ship : (ArrayNode) root.get("ships")) {
      ((ObjectNode) ship).remove("variantOf");
    }

    runs.apply(P4kTestCatalogues.bytes(root), false);
    entityManager.flush();
    entityManager.clear();

    assertThat(variantRepository.findById(stored)).isPresent();
  }

  @Test
  void aCatalogueCrewBelowTheStoredMinimumLowersTheMinimum() {
    ShipType titan = titan();
    titan.setCrewMin(2);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(titan.getCrewMax()).isEqualTo(1);
    assertThat(titan.getCrewMin()).isEqualTo(1);
  }

  @Test
  void aVariantClassNameReachesTheShipTypeOfItsGroupBase() {
    ShipType base = new ShipType();
    base.setName("Test Hornet Base");
    base.setP4kUuid(HORNET_BASE);
    shipTypeRepository.save(base);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(shipTypeRepository.findVariantBaseRefs())
        .anySatisfy(ref -> {
          assertThat(ref.getClassName()).isEqualTo(P4kTestCatalogues.AI_SHIP_CLASS);
          assertThat(ref.getId()).isEqualTo(base.getId());
        });
  }
}
