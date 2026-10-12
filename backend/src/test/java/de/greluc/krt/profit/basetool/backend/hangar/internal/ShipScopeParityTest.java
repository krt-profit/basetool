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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity of the ship list query and the ship read gate (REQ-DATA-010, plan section 5.4): over the
 * caller matrix of the access-policy differential tests and ships of every owner and unit, {@code
 * ShipRepository#findAllScoped} never lists a ship {@link HangarAccessPolicy#canSeeShip} refuses,
 * and the two agree on every ship that another member owns in a unit.
 */
@SpringBootTest
@Transactional
class ShipScopeParityTest {

  @Autowired private ShipRepository shipRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private LocationRepository locationRepository;

  @Test
  void theListAndTheGateWidenTogether() {
    ScopeParityCallers.Units units = ScopeParityCallers.persistUnits(squadronRepository);
    User caller = ScopeParityCallers.persistUser(userRepository, "caller");
    User other = ScopeParityCallers.persistUser(userRepository, "other");
    ShipType type = shipType();
    Location location = location();
    List<ScopeParityCallers.Row> rows =
        List.of(
            row("ship of A", ship(other, units.unitA(), type, location), true),
            row("ship of B", ship(other, units.unitB(), type, location), true),
            row("ship of child C", ship(other, units.childC(), type, location), true),
            row("own ship of B", ship(caller, units.unitB(), type, location), false),
            row("own ownerless ship", ship(caller, null, type, location), false),
            row("foreign ownerless ship", ship(other, null, type, location), false));
    shipRepository.flush();

    ScopeParityCallers.check(
        units,
        caller.getId(),
        true,
        rows,
        wired ->
            shipRepository
                .findAllScoped(
                    wired.predicate().adminAllScope(),
                    wired.predicate().activeOrgUnitId(),
                    wired.predicate().memberOrgUnitIds(),
                    PageRequest.of(0, 500))
                .stream()
                .map(Ship::getId)
                .collect(Collectors.toSet()),
        (wired, id) ->
            new HangarAccessPolicy(wired.ownerScopeService(), shipRepository).canSeeShip(id));
  }

  private static ScopeParityCallers.Row row(String name, Ship ship, boolean exact) {
    return new ScopeParityCallers.Row(name, ship.getId(), exact);
  }

  private Ship ship(User owner, UUID unit, ShipType type, Location location) {
    Ship ship = new Ship();
    ship.setName("parity-" + UUID.randomUUID());
    ship.setShipType(type);
    ship.setInsurance("LTI");
    ship.setLocation(location);
    ship.setOwner(owner);
    ship.setOwningOrgUnit(
        unit == null ? null : (OrgUnit) squadronRepository.getReferenceById(unit));
    return shipRepository.save(ship);
  }

  private ShipType shipType() {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Parity-M-" + UUID.randomUUID());
    manufacturer.setAbbreviation("PM" + UUID.randomUUID().toString().substring(0, 5));
    manufacturerRepository.save(manufacturer);
    ShipType type = new ShipType();
    type.setName("Parity-T-" + UUID.randomUUID());
    type.setManufacturer(manufacturer);
    return shipTypeRepository.save(type);
  }

  private Location location() {
    Location location = new Location();
    location.setName("Parity-L-" + UUID.randomUUID());
    return locationRepository.save(location);
  }
}
