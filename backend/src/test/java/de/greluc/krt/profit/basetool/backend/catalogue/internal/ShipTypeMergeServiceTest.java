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
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Two ship types of one game ship merge without breaking a reference (REQ-DATA-042). */
@SpringBootTest
@Transactional
class ShipTypeMergeServiceTest {

  private static final int TWIN_UEX_ID = 990_001;

  @Autowired private ShipTypeMergeService merges;
  @Autowired private ShipTypeRepository ships;
  @Autowired private ShipTypeRedirectRepository redirects;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void everyReferenceMovesAndTheMergedIdResolvesToTheSurvivor() {
    UUID guid = UUID.randomUUID();
    ShipType survivor = ships.saveAndFlush(shipType("Aegis Idris-M", guid, null));
    ShipType twin = ships.saveAndFlush(wikiTwin("Idris-M", guid));
    UUID twinId = twin.getId();
    UUID ship = insertShip(twinId);
    UUID unit = insertMissionUnit(twinId);
    UUID template = insertTemplate(twinId);
    UUID item = insertLinkedItem(twinId);

    assertThat(
            merges.merge(twinId, survivor.getId(), guid, ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID))
        .isTrue();
    entityManager.flush();
    entityManager.clear();

    UUID survivorId = survivor.getId();
    assertThat(shipTypeOf("SELECT ship_type_id FROM ship WHERE id = ?", ship))
        .isEqualTo(survivorId);
    assertThat(shipTypeOf("SELECT ship_type_id FROM mission_unit WHERE id = ?", unit))
        .isEqualTo(survivorId);
    assertThat(
            shipTypeOf(
                "SELECT ship_type_id FROM mission_template_unit WHERE template_id = ?", template))
        .isEqualTo(survivorId);
    assertThat(shipTypeOf("SELECT linked_ship_type_id FROM game_item WHERE id = ?", item))
        .isEqualTo(survivorId);
    assertThat(count("SELECT count(*) FROM ship_type WHERE id = ?", twinId)).isZero();
    assertThat(ships.findById(twinId)).map(ShipType::getId).contains(survivorId);
    assertThat(ships.existsById(twinId)).isTrue();
    assertThat(ships.findAllById(List.of(twinId)))
        .extracting(ShipType::getId)
        .containsExactly(survivorId);
    assertThat(ships.findLocalIdRedirects(List.of(twinId, survivorId)))
        .containsExactlyEntriesOf(Map.of(twinId, survivorId));
    ShipType merged = ships.findById(survivorId).orElseThrow();
    assertThat(merged.getExternalUuid()).isEqualTo(guid);
    assertThat(merged.getUexVehicleId()).isEqualTo(TWIN_UEX_ID);
    assertThat(auditTypes())
        .contains(
            "HANGAR_SHIP_CATALOGUE_REASSIGNED",
            "MISSION_CATALOGUE_REASSIGNED",
            "CATALOGUE_SHIP_TYPE_MERGED");
  }

  @Test
  void aUexIdTheSurvivorDoesNotTakeIsKeptAsAMergedListing() {
    ShipType survivor = shipType("Aegis Retaliator", UUID.randomUUID(), null);
    survivor.setUexVehicleId(TWIN_UEX_ID + 1);
    survivor = ships.saveAndFlush(survivor);
    ShipType twin = ships.saveAndFlush(wikiTwin("Retaliator", null));

    merges.merge(
        twin.getId(),
        survivor.getId(),
        survivor.getP4kUuid(),
        ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
    entityManager.flush();

    assertThat(ships.isMergedUexVehicleId(TWIN_UEX_ID)).isTrue();
    assertThat(ships.isMergedUexVehicleId(TWIN_UEX_ID + 1)).isFalse();
  }

  @Test
  void aSurvivorTakingTheListingAMergeLeftBehindLeavesItsOwnBehindInstead() {
    ShipType survivor = shipType("RSI Constellation Mk IV Phoenix", UUID.randomUUID(), null);
    survivor.setUexVehicleId(TWIN_UEX_ID + 1);
    survivor = ships.saveAndFlush(survivor);
    ShipType twin = ships.saveAndFlush(wikiTwin("Constellation Mk IV Phoenix", null));
    merges.merge(
        twin.getId(),
        survivor.getId(),
        survivor.getP4kUuid(),
        ShipTypeMergeReason.SUPERSEDED_BY_GS_RECORD);
    entityManager.flush();
    UUID survivorId = survivor.getId();

    assertThat(redirects.existsByCanonicalShipTypeIdAndUexVehicleId(survivorId, TWIN_UEX_ID))
        .isTrue();
    assertThat(redirects.handOverUexVehicleId(survivorId, TWIN_UEX_ID, TWIN_UEX_ID + 1)).isOne();

    assertThat(redirects.existsByCanonicalShipTypeIdAndUexVehicleId(survivorId, TWIN_UEX_ID))
        .isFalse();
    assertThat(ships.isMergedUexVehicleId(TWIN_UEX_ID)).isFalse();
    assertThat(ships.isMergedUexVehicleId(TWIN_UEX_ID + 1)).isTrue();
  }

  @Test
  void aRowHoldingAP4kGuidIsNeverMergedAway() {
    ShipType survivor = ships.saveAndFlush(shipType("Aegis Idris-M", UUID.randomUUID(), null));
    ShipType stamped = ships.saveAndFlush(shipType("Idris-M", UUID.randomUUID(), null));

    assertThat(
            merges.merge(
                stamped.getId(),
                survivor.getId(),
                survivor.getP4kUuid(),
                ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID))
        .isFalse();
    assertThat(count("SELECT count(*) FROM ship_type WHERE id = ?", stamped.getId())).isOne();
  }

  @Test
  void aSurvivorMergedLaterTakesTheEarlierAliasAlong() {
    ShipType first = ships.saveAndFlush(shipType("Idris-M A", null, null));
    ShipType second = ships.saveAndFlush(shipType("Idris-M B", null, null));
    ShipType last = ships.saveAndFlush(shipType("Aegis Idris-M", UUID.randomUUID(), null));

    merges.merge(first.getId(), second.getId(), null, ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
    merges.merge(
        second.getId(), last.getId(), last.getP4kUuid(), ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
    entityManager.flush();
    entityManager.clear();

    assertThat(ships.findById(first.getId())).map(ShipType::getId).contains(last.getId());
  }

  @Test
  void aSurvivorMovingToAnotherGuidKeepsItsOwnWikiUuid() {
    UUID own = UUID.randomUUID();
    UUID edition = UUID.randomUUID();
    ShipType survivor = ships.saveAndFlush(shipType("Anvil Carrack", edition, own));
    ShipType twin = ships.saveAndFlush(wikiTwin("Carrack", edition));

    merges.merge(twin.getId(), survivor.getId(), own, ShipTypeMergeReason.WIKI_UUID_IS_P4K_GUID);
    entityManager.flush();
    entityManager.clear();

    assertThat(ships.findById(survivor.getId()).orElseThrow().getExternalUuid()).isEqualTo(own);
  }

  private static ShipType shipType(String name, UUID p4kUuid, UUID externalUuid) {
    ShipType ship = new ShipType();
    ship.setName(name);
    ship.setP4kUuid(p4kUuid);
    ship.setExternalUuid(externalUuid);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }

  private static ShipType wikiTwin(String name, UUID wikiUuid) {
    ShipType twin = shipType(name, null, wikiUuid);
    twin.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    twin.setScwikiSyncedAt(Instant.parse("2026-11-09T13:27:00Z"));
    twin.setUexVehicleId(TWIN_UEX_ID);
    return twin;
  }

  private UUID shipTypeOf(String sql, UUID id) {
    return jdbc.queryForObject(sql, UUID.class, id);
  }

  private int count(String sql, UUID id) {
    Integer found = jdbc.queryForObject(sql, Integer.class, id);
    return found == null ? 0 : found;
  }

  private List<String> auditTypes() {
    return jdbc.queryForList("SELECT event_type FROM audit_event", String.class);
  }

  private UUID insertUser() {
    UUID user = UUID.randomUUID();
    jdbc.update("INSERT INTO app_user (id) VALUES (?)", user);
    return user;
  }

  private UUID insertShip(UUID shipTypeId) {
    UUID ship = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, ship_type_id, owner_id, insurance, version)"
            + " VALUES (?, ?, ?, 'LTI', 0)",
        ship,
        shipTypeId,
        insertUser());
    return ship;
  }

  private UUID insertMissionUnit(UUID shipTypeId) {
    UUID mission = UUID.randomUUID();
    jdbc.update("INSERT INTO mission (id, name, version) VALUES (?, 'Op', 0)", mission);
    UUID unit = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_unit (id, version, mission_id, ship_type_id, name)"
            + " VALUES (?, 0, ?, ?, 'Alpha')",
        unit,
        mission,
        shipTypeId);
    return unit;
  }

  private UUID insertTemplate(UUID shipTypeId) {
    UUID owner = jdbc.queryForObject("SELECT id FROM org_unit ORDER BY id LIMIT 1", UUID.class);
    UUID template = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_template (id, version, owning_org_unit_id, name)"
            + " VALUES (?, 0, ?, ?)",
        template,
        owner,
        "Template " + template);
    jdbc.update(
        "INSERT INTO mission_template_unit (template_id, order_index, name, ship_type_id,"
            + " high_value_unit) VALUES (?, 0, 'Alpha', ?, false)",
        template,
        shipTypeId);
    return template;
  }

  private UUID insertLinkedItem(UUID shipTypeId) {
    UUID item = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, linked_ship_type_id, version)"
            + " VALUES (?, ?, 'VEHICLE_WEAPON', ?, 0)",
        item,
        "Linked Test Item " + item,
        shipTypeId);
    return item;
  }
}
