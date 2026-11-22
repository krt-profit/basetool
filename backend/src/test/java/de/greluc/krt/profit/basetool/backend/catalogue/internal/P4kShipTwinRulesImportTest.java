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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * The twin rules besides the Wiki UUID: same variant group and name, Wiki in-game name, UEX full
 * name, the name without the manufacturer word, and a departed record whose successor has a row
 * (REQ-P4K-053).
 */
@SpringBootTest
@Transactional
class P4kShipTwinRulesImportTest {

  private static final UUID BASE = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1f001");
  private static final UUID EDITION = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1f002");
  private static final UUID DEPARTED = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1f0ff");
  private static final String BASE_CLASS = "TEST_Rules_Base";
  private static final String EDITION_CLASS = "TEST_Rules_Edition";
  private static final String NAME = "Rules Test Ship";
  private static final int DEPARTED_UEX_ID = 990_101;
  private static final int UEX_TWIN_ID = 990_102;

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kImportChangeRepository changes;
  @Autowired private ShipTypeRepository ships;
  @Autowired private ManufacturerRepository manufacturers;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void aWikiRowOfAnEditionMergesIntoTheRowOfItsBase() {
    assertMerged(
        twin -> twin.setExternalUuid(EDITION), ShipTypeMergeReason.SAME_VARIANT_GROUP_AND_NAME);
  }

  @Test
  void aRowCarryingAnEditionsClassMergesIntoTheRowOfItsBase() {
    assertMerged(
        twin -> twin.setClassName(EDITION_CLASS), ShipTypeMergeReason.SAME_VARIANT_GROUP_AND_NAME);
  }

  @Test
  void aWikiRowWhoseInGameNameIsARowsNameMergesIntoIt() {
    assertMerged(twin -> twin.setGameName(NAME), ShipTypeMergeReason.WIKI_GAME_NAME_IS_P4K_NAME);
  }

  @Test
  void aWikiRowNamedLikeARowMovingToItsSuccessorMergesInTheSameRun() {
    ShipType moving = new ShipType();
    moving.setName(NAME);
    moving.setP4kUuid(DEPARTED);
    moving.setClassName("TEST_Rules_Old");
    moving.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID movingId = ships.saveAndFlush(moving).getId();
    ShipType twin = new ShipType();
    twin.setName("Rules Test Twin");
    twin.setGameName(NAME);
    twin.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    UUID twinId = ships.saveAndFlush(twin).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(catalogue(), false);
    entityManager.flush();
    entityManager.clear();

    assertThat(count(twinId)).isZero();
    assertThat(ships.findById(movingId).orElseThrow().getP4kUuid()).isEqualTo(BASE);
  }

  @Test
  void aRowOnlyUexWroteWhoseFullNameIsARowsNameMergesIntoIt() {
    assertMerged(
        twin -> {
          twin.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
          twin.setNameFull(NAME);
          twin.setUexVehicleId(UEX_TWIN_ID);
        },
        ShipTypeMergeReason.UEX_FULL_NAME_IS_P4K_NAME);
  }

  @Test
  void aRowBothSourcesWroteIsNoTwinByItsFullName() {
    ships.saveAndFlush(base());
    ShipType other = new ShipType();
    other.setName("Rules Test Other");
    other.setNameFull(NAME);
    other.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID otherId = ships.saveAndFlush(other).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();

    assertThat(count(otherId)).isOne();
    assertThat(merges(runId)).isEmpty();
  }

  @Test
  void aRowWhoseRecordLeftMergesIntoTheRowOfItsSuccessor() {
    ShipType survivor = ships.saveAndFlush(base());
    ShipType departed = new ShipType();
    departed.setName("Rules Test Ship Old");
    departed.setGameName(NAME);
    departed.setP4kUuid(DEPARTED);
    departed.setClassName("TEST_Rules_Old");
    departed.setUexVehicleId(DEPARTED_UEX_ID);
    departed.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID departedId = ships.saveAndFlush(departed).getId();
    UUID ship = insertShip(departedId);
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();
    entityManager.flush();
    entityManager.clear();

    assertThat(count(departedId)).isZero();
    assertThat(jdbc.queryForObject("SELECT ship_type_id FROM ship WHERE id = ?", UUID.class, ship))
        .isEqualTo(survivor.getId());
    ShipType kept = ships.findById(survivor.getId()).orElseThrow();
    assertThat(kept.getP4kUuid()).isEqualTo(BASE);
    assertThat(kept.getUexVehicleId()).isEqualTo(DEPARTED_UEX_ID);
    assertThat(merges(runId)).singleElement().extracting(P4kImportChange::getGuid).isEqualTo(BASE);
  }

  @Test
  void aRowWhoseRecordLeftMergesIntoTheRowOfAVariantOfItsName() {
    ships.saveAndFlush(base());
    ShipType edition = new ShipType();
    edition.setName("Rules Test Ship Edition");
    edition.setP4kUuid(EDITION);
    edition.setClassName(EDITION_CLASS);
    edition.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID editionId = ships.saveAndFlush(edition).getId();
    ShipType departed = new ShipType();
    departed.setName("Rules Test Ship Edition Old");
    departed.setGameName("Rules Test Ship Edition");
    departed.setP4kUuid(DEPARTED);
    departed.setClassName("TEST_Rules_Edition_Old");
    departed.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID departedId = ships.saveAndFlush(departed).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(namedEditionCatalogue(), false);
    entityManager.flush();
    entityManager.clear();

    assertThat(count(departedId)).isZero();
    assertThat(ships.findById(departedId)).map(ShipType::getId).contains(editionId);
  }

  @Test
  void aVanishedEditionOfAShipMergesIntoTheShipsRow() {
    ShipType survivor = ships.saveAndFlush(base());
    ShipType edition = new ShipType();
    edition.setName(NAME + " Emerald");
    edition.setP4kUuid(DEPARTED);
    edition.setClassName(BASE_CLASS + "_Emerald");
    edition.setUexVehicleId(DEPARTED_UEX_ID);
    edition.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID editionId = ships.saveAndFlush(edition).getId();
    UUID ship = insertShip(editionId);
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();
    entityManager.flush();
    entityManager.clear();

    assertThat(count(editionId)).isZero();
    assertThat(jdbc.queryForObject("SELECT ship_type_id FROM ship WHERE id = ?", UUID.class, ship))
        .isEqualTo(survivor.getId());
    assertThat(merges(runId)).singleElement().extracting(P4kImportChange::getGuid).isEqualTo(BASE);
    assertThat(ships.findById(survivor.getId()).orElseThrow().getUexVehicleId()).isNull();
    assertThat(ships.isMergedUexVehicleId(DEPARTED_UEX_ID)).isTrue();
  }

  @Test
  void aVanishedRowNamedLikeNoShipStays() {
    ships.saveAndFlush(base());
    ShipType other = new ShipType();
    other.setName("Another Test Ship");
    other.setP4kUuid(DEPARTED);
    other.setClassName(BASE_CLASS + "_Other");
    other.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID otherId = ships.saveAndFlush(other).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();

    assertThat(count(otherId)).isOne();
    assertThat(merges(runId)).isEmpty();
  }

  @Test
  void aPreviewReportsADepartedRowsMergeAndKeepsIt() {
    ships.saveAndFlush(base());
    ShipType departed = new ShipType();
    departed.setName("Rules Test Ship Old");
    departed.setGameName(NAME);
    departed.setP4kUuid(DEPARTED);
    departed.setSourceSystems(GameItemSourceSystem.BOTH);
    UUID departedId = ships.saveAndFlush(departed).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    List<P4kImportChange> report = runs.previewWithReport(catalogue()).changes();

    assertThat(report)
        .anyMatch(change ->
            change.getChangeType() == P4kChangeType.MERGED && BASE.equals(change.getGuid()));
    assertThat(count(departedId)).isOne();
  }

  @Test
  void aRowNamedLikeARowWithoutItsManufacturerWordMergesIntoIt() {
    assertMerged(
        survivor -> survivor.setManufacturer(rulesShipworks()),
        twin -> twin.setName("Test Ship"),
        ShipTypeMergeReason.NAME_WITHOUT_MANUFACTURER_IS_P4K_NAME);
  }

  @Test
  void aRowNamedLikeARowsGermanNameWithoutItsManufacturerWordMergesIntoIt() {
    assertMerged(
        survivor -> {
          survivor.setManufacturer(rulesShipworks());
          survivor.setNameDe("Rules Kurzschiff");
        },
        twin -> {
          twin.setName("Kurzschiff");
          twin.setSourceSystems(GameItemSourceSystem.BOTH);
        },
        ShipTypeMergeReason.NAME_WITHOUT_MANUFACTURER_IS_P4K_NAME);
  }

  @Test
  void aRowWhoseNameWithoutTheManufacturerWordAgreesWithTwoRowsStays() {
    Manufacturer maker = rulesShipworks();
    ShipType survivor = base();
    survivor.setManufacturer(maker);
    ships.saveAndFlush(survivor);
    ShipType edition = new ShipType();
    edition.setName(NAME + " Edition");
    edition.setNameDe(NAME);
    edition.setP4kUuid(EDITION);
    edition.setClassName(EDITION_CLASS);
    edition.setManufacturer(maker);
    edition.setSourceSystems(GameItemSourceSystem.BOTH);
    ships.saveAndFlush(edition);
    ShipType other = new ShipType();
    other.setName("Test Ship");
    other.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    UUID otherId = ships.saveAndFlush(other).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();

    assertThat(count(otherId)).isOne();
    assertThat(merges(runId)).isEmpty();
  }

  /**
   * Saves the manufacturer whose name opens with the word the fixture's ship names open with.
   *
   * @return the manufacturer
   */
  private Manufacturer rulesShipworks() {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Rules Shipworks");
    manufacturer.setAbbreviation("RLSW");
    return manufacturers.saveAndFlush(manufacturer);
  }

  private void assertMerged(Consumer<ShipType> identity, ShipTypeMergeReason reason) {
    assertMerged(_ -> {}, identity, reason);
  }

  private void assertMerged(
      Consumer<ShipType> survivorSetup, Consumer<ShipType> identity, ShipTypeMergeReason reason) {
    ShipType base = base();
    survivorSetup.accept(base);
    ShipType survivor = ships.saveAndFlush(base);
    ShipType twin = new ShipType();
    twin.setName("Rules Test Twin");
    twin.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    identity.accept(twin);
    String twinName = twin.getName();
    UUID twinId = ships.saveAndFlush(twin).getId();
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    UUID runId = runs.apply(catalogue(), false).runId();
    entityManager.flush();
    entityManager.clear();

    assertThat(count(twinId)).isZero();
    assertThat(ships.findById(twinId)).map(ShipType::getId).contains(survivor.getId());
    assertThat(ships.findById(survivor.getId()).orElseThrow().getExternalUuid()).isEqualTo(BASE);
    assertThat(merges(runId))
        .singleElement()
        .extracting(P4kImportChange::getOldValue)
        .isEqualTo(twinName);
    assertThat(
            jdbc.queryForList(
                "SELECT details FROM audit_event WHERE event_type = 'CATALOGUE_SHIP_TYPE_MERGED'",
                String.class))
        .anyMatch(details -> details.contains(reason.name()));
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

  private static ShipType base() {
    ShipType ship = new ShipType();
    ship.setName(NAME);
    ship.setP4kUuid(BASE);
    ship.setClassName(BASE_CLASS);
    ship.setExternalUuid(BASE);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }

  private static byte[] catalogue() {
    return P4kTestCatalogues.bytes(
        P4kTestCatalogues.withEdition(BASE, BASE_CLASS, EDITION, EDITION_CLASS, NAME));
  }

  /** Builds the fixture whose edition carries a name of its own. */
  private static byte[] namedEditionCatalogue() {
    ObjectNode root = P4kTestCatalogues.withEdition(BASE, BASE_CLASS, EDITION, EDITION_CLASS, NAME);
    ObjectNode edition = P4kTestCatalogues.record(root, "ships", EDITION);
    ((ObjectNode) edition.get("name")).put("en", "Rules Test Ship Edition");
    return P4kTestCatalogues.bytes(root);
  }
}
