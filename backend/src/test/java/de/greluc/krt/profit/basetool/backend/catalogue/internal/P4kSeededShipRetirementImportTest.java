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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** A seeded NPC ship type nothing references retires itself (REQ-P4K-052). */
@SpringBootTest
@Transactional
class P4kSeededShipRetirementImportTest {

  private static final String HORNET = "Anvil F7C Hornet Mk II";

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private P4kReviewCaseRepository cases;
  @Autowired private ShipTypeRepository ships;
  @Autowired private EntityManager entityManager;

  @Test
  void anUnreferencedSeededNpcShipTypeIsHiddenAndGivesUpItsName() {
    ShipType seeded = ships.saveAndFlush(seededNpc());
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(seeded.getP4kHiddenAt()).isNotNull();
    assertThat(seeded.getName()).isEqualTo(P4kTestCatalogues.AI_SHIP_CLASS);
    assertThat(seededCase()).isFalse();
  }

  @Test
  void aSeededNpcShipTypeAHangarShipUsesStaysACase() {
    assertStaysACase(seeded -> insertShip(seeded.getId()));
  }

  @Test
  void aSeededNpcShipTypeAJournaledShipStateNamesStaysACase() {
    assertStaysACase(seeded -> insertJournal(seeded.getId()));
  }

  @Test
  void aSeededNpcShipTypeAnItemBelongsToStaysACase() {
    assertStaysACase(seeded -> insertLinkedItem(seeded.getId()));
  }

  private void assertStaysACase(Consumer<ShipType> reference) {
    ShipType seeded = ships.saveAndFlush(seededNpc());
    reference.accept(seeded);
    P4kImportRuns runs = new P4kImportRuns(imports, jobs);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(seeded.getP4kHiddenAt()).isNull();
    assertThat(seeded.getName()).isEqualTo(HORNET);
    assertThat(seededCase()).isTrue();
  }

  private boolean seededCase() {
    return cases
        .findByKindAndGuidAndReason(
            P4kKind.SHIP, P4kTestCatalogues.AI_SHIP, P4kReviewReason.SEEDED_NOT_CREATABLE)
        .isPresent();
  }

  private static ShipType seededNpc() {
    ShipType ship = new ShipType();
    ship.setName(HORNET);
    ship.setP4kUuid(P4kTestCatalogues.AI_SHIP);
    ship.setClassName(P4kTestCatalogues.AI_SHIP_CLASS);
    ship.setSourceSystems(GameItemSourceSystem.P4K);
    return ship;
  }

  private UUID insertUser() {
    UUID user = UUID.randomUUID();
    entityManager
        .createNativeQuery("INSERT INTO app_user (id) VALUES (:id)")
        .setParameter("id", user)
        .executeUpdate();
    return user;
  }

  private void insertShip(UUID shipTypeId) {
    entityManager
        .createNativeQuery(
            "INSERT INTO ship (id, ship_type_id, owner_id, version) VALUES (:id, :type, :owner, 0)")
        .setParameter("id", UUID.randomUUID())
        .setParameter("type", shipTypeId)
        .setParameter("owner", insertUser())
        .executeUpdate();
  }

  private void insertJournal(UUID shipTypeId) {
    String state = "{\"name\":\"x\",\"shipType\":\"" + shipTypeId + "\"}";
    entityManager
        .createNativeQuery(
            "INSERT INTO exchange_journal (id, user_id, client_id, installation_key, batch_id,"
                + " resource, entity_key, action, removal, before_state, after_state) VALUES"
                + " (:id, :user, 'client', 'installation', :batch, 'SHIP', :entity,"
                + " 'SHIP_UPSERT', false, :state, :state)")
        .setParameter("id", UUID.randomUUID())
        .setParameter("user", insertUser())
        .setParameter("batch", UUID.randomUUID())
        .setParameter("entity", UUID.randomUUID().toString())
        .setParameter("state", state)
        .executeUpdate();
  }

  private void insertLinkedItem(UUID shipTypeId) {
    entityManager
        .createNativeQuery(
            "INSERT INTO game_item (id, name, kind, linked_ship_type_id, version)"
                + " VALUES (:id, 'Linked Test Item', 'VEHICLE_WEAPON', :type, 0)")
        .setParameter("id", UUID.randomUUID())
        .setParameter("type", shipTypeId)
        .executeUpdate();
  }
}
