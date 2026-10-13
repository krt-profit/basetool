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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import de.greluc.krt.profit.basetool.backend.scope.api.UserAccessPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity of the refinery list query and the refinery read gate (REQ-DATA-010, plan section 5.4):
 * over the caller matrix of the access-policy differential tests and orders of every owner and
 * unit, {@code RefineryOrderRepository#findByOwnerIdScoped} never lists an order {@link
 * RefineryAccessPolicy#canSeeRefineryOrder(UUID)} refuses, and the two agree on every order that
 * another member owns in a unit.
 */
@SpringBootTest
@Transactional
class RefineryScopeParityTest {

  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  @Test
  void theListAndTheGateWidenTogether() {
    ScopeParityCallers.Units units = ScopeParityCallers.persistUnits(squadronRepository);
    User caller = ScopeParityCallers.persistUser(userRepository, "caller");
    User other = ScopeParityCallers.persistUser(userRepository, "other");
    Location location = location();
    List<ScopeParityCallers.Row> rows =
        List.of(
            row("order of A", order(other, units.unitA(), location), true),
            row("order of B", order(other, units.unitB(), location), true),
            row("order of child C", order(other, units.childC(), location), true),
            row("own order of B", order(caller, units.unitB(), location), false),
            row("own ownerless order", order(caller, null, location), false),
            row("foreign ownerless order", order(other, null, location), false));
    refineryOrderRepository.flush();

    ScopeParityCallers.check(
        units,
        caller.getId(),
        true,
        rows,
        wired -> {
          Set<UUID> ids = new HashSet<>();
          for (User owner : List.of(caller, other)) {
            refineryOrderRepository
                .findByOwnerIdScoped(
                    owner.getId(),
                    wired.predicate().adminAllScope(),
                    wired.predicate().activeOrgUnitId(),
                    wired.predicate().memberOrgUnitIds(),
                    PageRequest.of(0, 500))
                .forEach(order -> ids.add(order.getId()));
          }
          return ids;
        },
        (wired, id) ->
            new RefineryAccessPolicy(
                    wired.ownerScopeService(),
                    refineryOrderRepository,
                    mock(UserAccessPolicy.class))
                .canSeeRefineryOrder(id));
  }

  private static ScopeParityCallers.Row row(String name, RefineryOrder order, boolean exact) {
    return new ScopeParityCallers.Row(name, order.getId(), exact);
  }

  private RefineryOrder order(User owner, UUID unit, Location location) {
    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setLocation(location);
    order.setOwningOrgUnit(unit == null ? null : squadronRepository.getReferenceById(unit));
    return refineryOrderRepository.save(order);
  }

  private Location location() {
    Location location = new Location();
    location.setName("Parity-L-" + UUID.randomUUID());
    return locationRepository.save(location);
  }
}
