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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration coverage for {@link ShipRepository#findByOwnerIdFiltered} against real Postgres
 * (REQ-HANGAR-002). The personal hangar's rich multi-key ordering — including the computed
 * insurance-tier bucket and the {@code cast(insurance as integer)} amount key — and the server-side
 * search are exercised end-to-end here precisely because the {@code CASE}/{@code cast} idiom is
 * Postgres-specific and would not surface in a Mockito unit test; this is the regression guard that
 * the ordering and the cast keep working on the production database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipRepositoryPersonalHangarTest {

  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private LocationRepository locationRepository;

  private User owner;
  private User otherOwner;

  private ShipType avenger;
  private ShipType gladius;
  private ShipType cutlass;
  private Location area18;
  private Location orison;

  @BeforeEach
  void setUp() {
    owner = newUser("owner-773");
    otherOwner = newUser("other-773");

    Manufacturer aegis = newManufacturer("Aegis-773", "AE773");
    Manufacturer drake = newManufacturer("Drake-773", "DR773");

    avenger = newShipType("Avenger-773", aegis);
    gladius = newShipType("Gladius-773", aegis);
    cutlass = newShipType("Cutlass-773", drake);

    area18 = newLocation("Area18-773");
    orison = newLocation("Orison-773");
  }

  @Test
  void findByOwnerIdFiltered_ordersByTheFullMultiKeyComparator() {
    saveShip("s-D", avenger, "120", false, area18);
    saveShip("s-H", cutlass, "LTI", false, area18);
    saveShip("s-A2", avenger, "LTI", true, area18);
    saveShip("s-F", avenger, "0", false, area18);
    saveShip("s-B", avenger, "LTI", true, orison);
    saveShip("s-A", avenger, "LTI", true, area18);
    saveShip("s-E", avenger, "30", false, area18);
    saveShip("s-C", avenger, "LTI", false, area18);
    saveShip("s-G", gladius, "LTI", false, area18);

    Page<Ship> page =
        shipRepository.findByOwnerIdFiltered(owner.getId(), null, PageRequest.of(0, 50));

    assertThat(page.getContent().stream().map(Ship::getName))
        .containsExactly("s-A", "s-A2", "s-C", "s-B", "s-D", "s-E", "s-F", "s-G", "s-H");
  }

  @Test
  void findByOwnerIdFiltered_searchMatchesShipTypeOrManufacturerNameCaseInsensitively() {
    saveShip("s-A", avenger, "LTI", true, area18);
    saveShip("s-G", gladius, "LTI", false, area18);
    saveShip("s-H", cutlass, "LTI", false, area18);

    assertThat(
            shipRepository
                .findByOwnerIdFiltered(owner.getId(), "cutlass-773", PageRequest.of(0, 50))
                .getContent()
                .stream()
                .map(Ship::getName))
        .containsExactly("s-H");

    assertThat(
            shipRepository
                .findByOwnerIdFiltered(owner.getId(), "AEGIS-773", PageRequest.of(0, 50))
                .getContent()
                .stream()
                .map(Ship::getName))
        .containsExactly("s-A", "s-G");

    assertThat(shipRepository.findByOwnerIdFiltered(owner.getId(), null, PageRequest.of(0, 50)))
        .hasSize(3);
  }

  @Test
  void findByOwnerIdFiltered_paginatesAcrossTheWholeOrderedSet() {
    saveShip("s-A", avenger, "LTI", true, area18);
    saveShip("s-A2", avenger, "LTI", true, area18);
    saveShip("s-C", avenger, "LTI", false, area18);
    saveShip("s-D", avenger, "120", false, area18);
    saveShip("s-F", avenger, "0", false, area18);

    Page<Ship> first =
        shipRepository.findByOwnerIdFiltered(owner.getId(), null, PageRequest.of(0, 2));
    assertThat(first.getTotalElements()).isEqualTo(5L);
    assertThat(first.getTotalPages()).isEqualTo(3);
    assertThat(first.getContent().stream().map(Ship::getName)).containsExactly("s-A", "s-A2");

    Page<Ship> second =
        shipRepository.findByOwnerIdFiltered(owner.getId(), null, PageRequest.of(1, 2));
    assertThat(second.getContent().stream().map(Ship::getName)).containsExactly("s-C", "s-D");
  }

  @Test
  void findByOwnerIdFiltered_isPerUserIsolated() {
    saveShip("mine", avenger, "LTI", true, area18);
    saveShipFor(otherOwner, "theirs", avenger, "LTI", true, area18);

    assertThat(
            shipRepository
                .findByOwnerIdFiltered(owner.getId(), null, PageRequest.of(0, 50))
                .getContent()
                .stream()
                .map(Ship::getName))
        .containsExactly("mine");
    assertThat(
            shipRepository
                .findByOwnerIdFiltered(otherOwner.getId(), null, PageRequest.of(0, 50))
                .getContent()
                .stream()
                .map(Ship::getName))
        .containsExactly("theirs");
  }

  private User newUser(String username) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername(username);
    return userRepository.save(u);
  }

  private Manufacturer newManufacturer(String name, String abbreviation) {
    Manufacturer m = new Manufacturer();
    m.setName(name);
    m.setAbbreviation(abbreviation);
    return manufacturerRepository.save(m);
  }

  private ShipType newShipType(String name, Manufacturer manufacturer) {
    ShipType t = new ShipType();
    t.setName(name);
    t.setManufacturer(manufacturer);
    return shipTypeRepository.save(t);
  }

  private Location newLocation(String name) {
    Location l = new Location();
    l.setName(name);
    return locationRepository.save(l);
  }

  private Ship saveShip(
      String name, ShipType type, String insurance, boolean fitted, Location loc) {
    return saveShipFor(owner, name, type, insurance, fitted, loc);
  }

  private Ship saveShipFor(
      User shipOwner, String name, ShipType type, String insurance, boolean fitted, Location loc) {
    Ship s = new Ship();
    s.setName(name);
    s.setShipType(type);
    s.setInsurance(insurance);
    s.setFitted(fitted);
    s.setLocation(loc);
    s.setOwner(shipOwner);
    return shipRepository.save(s);
  }
}
