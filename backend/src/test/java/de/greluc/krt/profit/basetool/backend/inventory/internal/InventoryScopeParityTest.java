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

import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItem;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import de.greluc.krt.profit.basetool.backend.scope.api.UserAccessPolicy;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity of the Lager list query and the Lager row read gate (REQ-DATA-010, plan section 5.4): over
 * the caller matrix of the access-policy differential tests and rows of every owner and unit,
 * {@code InventoryItemRepository#findByMaterialAndPersonalFalseScoped} never lists a row {@link
 * InventoryAccessPolicy#canSeeInventoryItem} refuses, and the two agree on every row that another
 * member owns in a unit.
 */
@SpringBootTest
@Transactional
class InventoryScopeParityTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  @Test
  void theListAndTheGateWidenTogether() {
    ScopeParityCallers.Units units = ScopeParityCallers.persistUnits(squadronRepository);
    User caller = ScopeParityCallers.persistUser(userRepository, "caller");
    User other = ScopeParityCallers.persistUser(userRepository, "other");
    Material material = material();
    Location location = location();
    List<ScopeParityCallers.Row> rows =
        List.of(
            row("row of A", item(other, units.unitA(), material, location), true),
            row("row of B", item(other, units.unitB(), material, location), true),
            row("row of child C", item(other, units.childC(), material, location), true),
            row("own row of B", item(caller, units.unitB(), material, location), false),
            row("own ownerless row", item(caller, null, material, location), false),
            row("foreign ownerless row", item(other, null, material, location), false));
    inventoryItemRepository.flush();

    ScopeParityCallers.check(
        units,
        caller.getId(),
        true,
        rows,
        wired ->
            inventoryItemRepository
                .findByMaterialAndPersonalFalseScoped(
                    material,
                    wired.predicate().adminAllScope(),
                    wired.predicate().activeOrgUnitId(),
                    wired.predicate().memberOrgUnitIds(),
                    PageRequest.of(0, 500))
                .stream()
                .map(InventoryItem::getId)
                .collect(Collectors.toSet()),
        (wired, id) ->
            new InventoryAccessPolicy(
                    wired.ownerScopeService(),
                    inventoryItemRepository,
                    mock(UserAccessPolicy.class))
                .canSeeInventoryItem(id));
  }

  private static ScopeParityCallers.Row row(String name, InventoryItem item, boolean exact) {
    return new ScopeParityCallers.Row(name, item.getId(), exact);
  }

  private InventoryItem item(User owner, UUID unit, Material material, Location location) {
    InventoryItem item = new InventoryItem();
    item.setUser(owner);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(500);
    item.setAmount(1.0);
    item.setPersonal(false);
    item.setOwningOrgUnit(unit == null ? null : squadronRepository.getReferenceById(unit));
    return inventoryItemRepository.save(item);
  }

  private Material material() {
    Material material = new Material();
    material.setName("Parity-M-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    return materialRepository.save(material);
  }

  private Location location() {
    Location location = new Location();
    location.setName("Parity-L-" + UUID.randomUUID());
    return locationRepository.save(location);
  }
}
