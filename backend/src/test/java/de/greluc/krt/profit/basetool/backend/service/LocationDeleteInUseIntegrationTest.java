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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.LocationService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.kernel.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-use check of a location delete against Postgres: a location that a ship or a refinery
 * order references is refused with the exact message of its user, ships first, and an unused one is
 * removed. Runs in a rolled-back transaction.
 */
@SpringBootTest
@Transactional
class LocationDeleteInUseIntegrationTest {

  private static final String SHIPS_MESSAGE =
      "Cannot delete location: It is currently used by one or more ships.";
  private static final String ORDERS_MESSAGE =
      "Cannot delete location: It is currently used by one or more refinery orders.";

  @Autowired private LocationService locationService;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;
  @PersistenceContext private EntityManager entityManager;

  private User owner;
  private ShipType shipType;

  @BeforeEach
  void seed() {
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("loc-owner-" + UUID.randomUUID());
    owner.setApprovalStatus(ApprovalStatus.ACTIVE);
    owner.setInKeycloak(false);
    userRepository.saveAndFlush(owner);
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Loc Works " + UUID.randomUUID());
    manufacturer.setAbbreviation("LW" + UUID.randomUUID().toString().substring(0, 4));
    manufacturerRepository.save(manufacturer);
    shipType = new ShipType();
    shipType.setName("Loc Hull " + UUID.randomUUID());
    shipType.setManufacturer(manufacturer);
    shipTypeRepository.saveAndFlush(shipType);
  }

  private Location location() {
    Location location = new Location();
    location.setName("Loc Hub " + UUID.randomUUID());
    return locationRepository.saveAndFlush(location);
  }

  private void ship(Location location) {
    Ship ship = new Ship();
    ship.setName("Loc Ship " + UUID.randomUUID());
    ship.setShipType(shipType);
    ship.setInsurance("LTI");
    ship.setLocation(location);
    ship.setOwner(owner);
    shipRepository.saveAndFlush(ship);
  }

  private void refineryOrder(Location location) {
    UUID orderId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refinery_order (id, owner_id, location_id, status, version)"
            + " VALUES (?, ?, ?, 'OPEN', 0)",
        orderId,
        owner.getId(),
        location.getId());
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void anUnusedLocationIsDeleted() {
    Location location = location();

    locationService.deleteLocation(location.getId());
    entityManager.flush();

    assertThat(locationRepository.existsById(location.getId())).isFalse();
  }

  @Test
  void aLocationOfAShipIsRefusedWithTheShipsMessage() {
    Location location = location();
    ship(location);

    assertThatThrownBy(() -> locationService.deleteLocation(location.getId()))
        .isInstanceOf(EntityInUseException.class)
        .hasMessage(SHIPS_MESSAGE);
    assertThat(locationRepository.existsById(location.getId())).isTrue();
  }

  @Test
  void aLocationOfARefineryOrderIsRefusedWithTheOrdersMessage() {
    Location location = location();
    refineryOrder(location);

    assertThatThrownBy(() -> locationService.deleteLocation(location.getId()))
        .isInstanceOf(EntityInUseException.class)
        .hasMessage(ORDERS_MESSAGE);
    assertThat(locationRepository.existsById(location.getId())).isTrue();
  }

  @Test
  void aLocationOfBothIsRefusedWithTheShipsMessageFirst() {
    Location location = location();
    refineryOrder(location);
    ship(location);

    assertThatThrownBy(() -> locationService.deleteLocation(location.getId()))
        .isInstanceOf(EntityInUseException.class)
        .hasMessage(SHIPS_MESSAGE);
  }
}
