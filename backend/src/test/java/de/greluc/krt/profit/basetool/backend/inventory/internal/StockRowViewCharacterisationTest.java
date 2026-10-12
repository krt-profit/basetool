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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Characterises what the foreign modules see of a Lager row through the published stock API (plan
 * section 7.6, P4-1 part 3): every entry the exchange, the job order and the Materialbörse use
 * returns the managed row, and the values they read from it are those the row was stored with.
 *
 * <p>The values are read with the getters the foreign classes call, so the test describes the rows
 * independently of the Java type that carries them; only the reservation sums go through the helper
 * methods at the bottom, which are the one place that names how reservations are read.
 */
@SpringBootTest
@Transactional
class StockRowViewCharacterisationTest {

  @Autowired private StockCommands stockCommands;
  @Autowired private StockReads stockReads;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private MissionRepository missionRepository;
  @PersistenceContext private EntityManager entityManager;

  private User owner;
  private Squadron unit;
  private Material material;
  private Location location;
  private InventoryItem row;
  private UUID orderId;

  @BeforeEach
  void setUp() {
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("row-view-" + UUID.randomUUID());
    userRepository.saveAndFlush(owner);
    unit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    material = new Material();
    material.setName("row-view-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.saveAndFlush(material);
    location = new Location();
    location.setName("row-view-" + UUID.randomUUID());
    locationRepository.saveAndFlush(location);
    JobOrder order = new JobOrder();
    order.setResponsibleOrgUnit(unit);
    order.setRequestingOrgUnit(unit);
    order.setHandle("row-view-" + UUID.randomUUID());
    orderId = jobOrderRepository.saveAndFlush(order).getId();

    row = new InventoryItem();
    row.setUser(owner);
    row.setOwningOrgUnit(unit);
    row.setMaterial(material);
    row.setLocation(location);
    row.setQuality(612);
    row.setAmount(7.5);
    row.setPersonal(false);
    row.setStolen(true);
    InventoryAllocations.addJobOrder(row, orderId, 2.5, false);
    Mission mission = new Mission();
    mission.setName("row-view-" + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setOwningOrgUnit(unit);
    InventoryAllocations.addMission(row, missionRepository.saveAndFlush(mission).getId(), 1.25);
    row = inventoryItemRepository.saveAndFlush(row);
    entityManager.clear();
  }

  @Test
  void everyEntryTheForeignModulesUseReturnsTheRowWithTheValuesItWasStoredWith() {
    var locked = stockCommands.lockRow(row.getId()).orElseThrow();
    var found = stockReads.findRow(row.getId()).orElseThrow();
    var withMaterial = stockReads.findRowsWithMaterial(List.of(row.getId())).getFirst();
    var lotRows =
        stockCommands.lockLotRows(owner.getId(), new StockLot(material, null, location, 612, true));
    var releasable = stockReads.releasableRows(owner.getId(), null, true, false, 10);

    assertThat(lotRows).hasSize(1);
    assertThat(releasable).extracting(r -> r.getId()).contains(row.getId());

    assertThat(locked).isSameAs(found).isSameAs(withMaterial).isSameAs(lotRows.getFirst());
    assertThat(locked.getId()).isEqualTo(row.getId());
    assertThat(locked.getVersion()).isEqualTo(row.getVersion());
    assertThat(locked.getUser().getId()).isEqualTo(owner.getId());
    assertThat(locked.getOwningOrgUnit().getId()).isEqualTo(unit.getId());
    assertThat(locked.getMaterial().getId()).isEqualTo(material.getId());
    assertThat(locked.getGameItem()).isNull();
    assertThat(locked.getLocation().getId()).isEqualTo(location.getId());
    assertThat(locked.getQuality()).isEqualTo(612);
    assertThat(locked.getAmount()).isEqualTo(7.5);
    assertThat(locked.getStolen()).isTrue();
    assertThat(jobOrderReserved(locked)).isEqualTo(2.5);
    assertThat(missionReserved(locked)).isEqualTo(1.25);
    assertThat(jobOrderSliceAmount(locked, orderId)).isEqualTo(2.5);
    assertThat(jobOrderSliceAmount(locked, UUID.randomUUID())).isNull();
  }

  @Test
  void aCommandIsSeenOnTheSameRowInstanceTheCallerAlreadyHolds() {
    var held = stockCommands.lockRow(row.getId()).orElseThrow();

    stockCommands.releaseJobOrderEarmark(rowReference(held), orderId);

    assertThat(jobOrderReserved(held)).isZero();
    assertThat(jobOrderSliceAmount(held, orderId)).isNull();
    assertThat(missionReserved(held)).isEqualTo(1.25);
    assertThat(stockReads.findRow(row.getId()).orElseThrow()).isSameAs(held);
  }

  private static double jobOrderReserved(InventoryItem item) {
    return InventoryAllocations.sumJobOrder(item);
  }

  private static double missionReserved(InventoryItem item) {
    return InventoryAllocations.sumMission(item);
  }

  private static Double jobOrderSliceAmount(InventoryItem item, UUID jobOrderId) {
    var slice = InventoryAllocations.jobOrderSlice(item, jobOrderId);
    return slice == null ? null : slice.getAmount();
  }

  private static InventoryItem rowReference(InventoryItem item) {
    return item;
  }
}
