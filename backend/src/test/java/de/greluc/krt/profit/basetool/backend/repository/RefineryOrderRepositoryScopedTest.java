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
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link RefineryOrderRepository#findByOwnerIdScoped} against real Postgres,
 * verifying that the {@code owning_org_unit_id} predicate filters an owner's orders by org unit.
 *
 * <p>The fixture owner holds orders in two Staffeln (REQ-ORG-017). Each test rolls back and asserts
 * only on its own order ids.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RefineryOrderRepositoryScopedTest {

  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * A caller scoped to one Staffel receives only the target's orders stamped to that Staffel, not
   * those of the target's other Staffel.
   */
  @Test
  void findByOwnerIdScoped_singleStaffelScope_excludesForeignStaffelOrder() {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Squadron foreign = createSecondSquadron();
    User owner = createUser();
    Location location = createLocation();
    RefineryOrder inScope = createOrder(owner, location, iridium);
    RefineryOrder foreignOrder = createOrder(owner, location, foreign);
    flushAndClear();

    List<UUID> ids =
        refineryOrderRepository
            .findByOwnerIdScoped(
                owner.getId(), false, null, Set.of(Squadron.IRIDIUM_ID), PageRequest.of(0, 50))
            .map(RefineryOrder::getId)
            .getContent();

    assertThat(ids)
        .as("an order stamped to the caller's own Staffel is in scope")
        .contains(inScope.getId());
    assertThat(ids)
        .as(
            "an order stamped to the target's other Staffel (which the caller cannot see) is"
                + " hidden")
        .doesNotContain(foreignOrder.getId());
  }

  /** An admin with no active pin ({@code adminAllScope=true}) sees every order the target owns. */
  @Test
  void findByOwnerIdScoped_adminAllScope_returnsBothStaffelnOrders() {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Squadron foreign = createSecondSquadron();
    User owner = createUser();
    Location location = createLocation();
    RefineryOrder iridiumOrder = createOrder(owner, location, iridium);
    RefineryOrder foreignOrder = createOrder(owner, location, foreign);
    flushAndClear();

    List<UUID> ids =
        refineryOrderRepository
            .findByOwnerIdScoped(owner.getId(), true, null, Set.of(), PageRequest.of(0, 50))
            .map(RefineryOrder::getId)
            .getContent();

    assertThat(ids).contains(iridiumOrder.getId(), foreignOrder.getId());
  }

  /**
   * An active pin narrows the result to the pinned Staffel, excluding the target's other Staffel.
   */
  @Test
  void findByOwnerIdScoped_activePin_narrowsToPinnedStaffel() {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Squadron foreign = createSecondSquadron();
    User owner = createUser();
    Location location = createLocation();
    RefineryOrder iridiumOrder = createOrder(owner, location, iridium);
    RefineryOrder foreignOrder = createOrder(owner, location, foreign);
    flushAndClear();

    List<UUID> ids =
        refineryOrderRepository
            .findByOwnerIdScoped(
                owner.getId(), false, Squadron.IRIDIUM_ID, Set.of(), PageRequest.of(0, 50))
            .map(RefineryOrder::getId)
            .getContent();

    assertThat(ids).contains(iridiumOrder.getId()).doesNotContain(foreignOrder.getId());
  }

  /**
   * Persists a fresh {@link Squadron} with a unique name/shorthand so the test owns a second
   * Staffel to stamp a foreign order to.
   *
   * @return the persisted second Staffel.
   */
  private Squadron createSecondSquadron() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("ScopeTest Refinery " + suffix);
    squadron.setShorthand("STR" + suffix);
    return squadronRepository.saveAndFlush(squadron);
  }

  /**
   * Persists a user with a unique username; this is the common owner of both seeded orders.
   *
   * @return the persisted user.
   */
  private User createUser() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("refscope-" + UUID.randomUUID());
    return userRepository.save(user);
  }

  /**
   * Persists a minimal {@link Location} (only the unique name is required) to satisfy the
   * refinery-order {@code location_id} NOT NULL constraint.
   *
   * @return the persisted location.
   */
  private Location createLocation() {
    Location location = new Location();
    location.setName("RefScope-Hub-" + UUID.randomUUID());
    return locationRepository.save(location);
  }

  /**
   * Persists an {@code OPEN} refinery order owned by {@code owner}, at {@code location}, stamped to
   * {@code owningOrgUnit}.
   *
   * @param owner the owning user; never {@code null}.
   * @param location the order's refinery location; never {@code null}.
   * @param owningOrgUnit the stamped owning org unit; never {@code null} for this fixture.
   * @return the persisted refinery order.
   */
  private RefineryOrder createOrder(User owner, Location location, OrgUnit owningOrgUnit) {
    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setLocation(location);
    order.setOwningOrgUnit(owningOrgUnit);
    return refineryOrderRepository.save(order);
  }

  /** Flushes the seeded rows then clears the context so the read hits the DB, not the L1 cache. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
