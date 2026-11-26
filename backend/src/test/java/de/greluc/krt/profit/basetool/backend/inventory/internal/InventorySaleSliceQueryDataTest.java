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
import static org.assertj.core.groups.Tuple.tuple;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
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
 * Verifies {@link InventorySaleSliceQueries#findSaleSlices} against the real schema (REQ-INV-072):
 * it sums only the member's own marked material entries, per material and „gestohlen" marker, with
 * the material's unit.
 */
@SpringBootTest
@Transactional
class InventorySaleSliceQueryDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;

  @PersistenceContext private EntityManager entityManager;

  private User member;
  private User otherMember;
  private Location location;
  private Material laranite;
  private Material hadanite;

  @BeforeEach
  void seed() {
    member = user("sale-slice-");
    otherMember = user("sale-slice-other-");
    location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(TestPlaces.onOwnCity(location, cityRepository));
    laranite = material("Laranite-", QuantityType.SCU);
    hadanite = material("Hadanite-", QuantityType.PIECE);
  }

  @Test
  void sumsTheMembersMarkedEntriesPerMaterialAndMarker() {
    UUID first = row(member, laranite, 10.0, false);
    UUID second = row(member, laranite, 2.5, false);
    UUID stolen = row(member, laranite, 4.0, true);
    UUID piece = row(member, hadanite, 3.0, false);
    row(member, laranite, 100.0, false);
    entityManager.flush();

    List<InventorySaleSlice> slices =
        inventoryItemRepository.findSaleSlices(
            member.getId(), List.of(first, second, stolen, piece));

    assertThat(slices)
        .extracting(
            InventorySaleSlice::materialId,
            InventorySaleSlice::quantityType,
            InventorySaleSlice::stolen,
            InventorySaleSlice::amount)
        .containsExactlyInAnyOrder(
            tuple(laranite.getId(), QuantityType.SCU, false, 12.5),
            tuple(laranite.getId(), QuantityType.SCU, true, 4.0),
            tuple(hadanite.getId(), QuantityType.PIECE, false, 3.0));
  }

  @Test
  void anotherMembersEntryAndAnUnknownIdAddNothing() {
    UUID foreign = row(otherMember, laranite, 50.0, false);
    entityManager.flush();

    assertThat(
            inventoryItemRepository.findSaleSlices(
                member.getId(), List.of(foreign, UUID.randomUUID())))
        .isEmpty();
  }

  private User user(String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    return userRepository.save(user);
  }

  private Material material(String prefix, QuantityType quantityType) {
    Material material = new Material();
    material.setName(prefix + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(quantityType);
    return materialRepository.save(material);
  }

  private UUID row(User owner, Material material, double amount, boolean stolen) {
    InventoryItem item = new InventoryItem();
    item.setUser(owner);
    item.setLocation(location);
    item.setMaterial(material);
    item.setQuality(600);
    item.setAmount(amount);
    item.setPersonal(true);
    item.setStolen(stolen);
    return inventoryItemRepository.save(item).getId();
  }
}
