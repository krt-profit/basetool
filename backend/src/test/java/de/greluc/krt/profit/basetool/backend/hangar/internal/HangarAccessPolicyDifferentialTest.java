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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The differential verdict test of the hangar access policy (plan §5.4, ADR-0236): over one fixture
 * matrix of callers and ships, {@link HangarAccessPolicy} returns exactly the verdict of the scope
 * hub's ship gates for reading and editing a ship.
 *
 * <p>{@link ScopeHubShipGates} restates those gates as {@link AccessGateService} decided them
 * before they moved, on the scope kernel's primitives, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, a pinned and an unpinned
 * member, members of zero, one and two units and a Bereich lead reaching a child Staffel through
 * the cascade; and ships of either unit, of the child unit, of a foreign unit owned by the caller
 * (the owner escape), ownerless ships of the caller and of another member, and an unknown ship.
 */
class HangarAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final UUID OTHER_OWNER = UUID.randomUUID();

  private static final int SHIP_CASES = 7;

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param memberships the org units the caller is a direct member of
   * @param pin the active-context pin header, or {@code null}
   */
  private record Caller(String name, boolean admin, List<UUID> memberships, UUID pin) {}

  @Test
  void thePolicyReturnsTheScopeHubsVerdictForEveryCallerShipAndGate() {
    List<Caller> callers =
        List.of(
            new Caller("admin unpinned", true, List.of(), null),
            new Caller("admin pinned A", true, List.of(), UNIT_A),
            new Caller("admin pinned B", true, List.of(), UNIT_B),
            new Caller("member of none", false, List.of(), null),
            new Caller("member of A", false, List.of(UNIT_A), null),
            new Caller("member of A and B", false, List.of(UNIT_A, UNIT_B), null),
            new Caller("member of A and B pinned B", false, List.of(UNIT_A, UNIT_B), UNIT_B),
            new Caller("member of A pinned foreign B", false, List.of(UNIT_A), UNIT_B),
            new Caller("Bereich lead over C", false, List.of(BEREICH_X), null));

    int compared = 0;
    int granted = 0;
    for (Caller caller : callers) {
      Fixture fixture = new Fixture(caller);
      for (Map.Entry<String, UUID> ship : fixture.ships.entrySet()) {
        for (Gate gate : gates()) {
          boolean restated = gate.scopeHub().test(fixture.scopeHub, ship.getValue());
          boolean now = gate.policy().test(fixture.policy, ship.getValue());
          assertThat(now)
              .as("%s on %s for %s", gate.name(), ship.getKey(), caller.name())
              .isEqualTo(restated);
          compared++;
          granted += now ? 1 : 0;
        }
      }
    }

    assertThat(compared).isEqualTo(callers.size() * SHIP_CASES * 2);
    assertThat(granted).isBetween(1, compared - 1);
  }

  /**
   * One ship gate, as the restated scope hub and the policy decide it.
   *
   * @param name the gate's method name
   * @param scopeHub the restated scope hub's verdict
   * @param policy the policy's verdict
   */
  private record Gate(
      String name,
      BiPredicate<ScopeHubShipGates, UUID> scopeHub,
      BiPredicate<HangarAccessPolicy, UUID> policy) {}

  private static List<Gate> gates() {
    return List.of(
        new Gate("canSeeShip", ScopeHubShipGates::canSeeShip, HangarAccessPolicy::canSeeShip),
        new Gate("canEditShip", ScopeHubShipGates::canEditShip, HangarAccessPolicy::canEditShip));
  }

  /** One caller's freshly wired scope hub and policy over the scenario mocks. */
  private static final class Fixture {

    private final ScopeHubShipGates scopeHub;

    private final HangarAccessPolicy policy;

    private final Map<String, UUID> ships = new LinkedHashMap<>();

    Fixture(Caller caller) {
      UUID callerId = UUID.randomUUID();
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      when(authHelper.isMemberOrAbove()).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));

      OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
      List<OrgUnitMembership> callerRows = rows(callerId, caller.memberships());
      when(memberships.findAllByIdUserId(any())).thenReturn(List.of());
      when(memberships.findAllByIdUserId(callerId)).thenReturn(callerRows);
      when(memberships.findAllByIdUserIdAndKind(any(), any()))
          .thenReturn(
              callerRows.stream().filter(r -> r.getKind() == OrgUnitKind.SQUADRON).toList());

      OrgUnitCascadeService cascade = mock(OrgUnitCascadeService.class);
      when(cascade.expandWithDescendants(anyCollection()))
          .thenAnswer(
              invocation -> {
                Collection<OrgUnitMembership> given = invocation.getArgument(0);
                Set<UUID> ids = new LinkedHashSet<>();
                for (OrgUnitMembership row : given) {
                  ids.add(row.getId().getOrgUnitId());
                  if (row.getId().getOrgUnitId().equals(BEREICH_X)) {
                    ids.add(CHILD_C);
                  }
                }
                return ids;
              });

      ShipRepository repository = mock(ShipRepository.class);
      when(repository.findById(any())).thenReturn(Optional.empty());
      ship(repository, "ship of A", UNIT_A, OTHER_OWNER);
      ship(repository, "ship of B", UNIT_B, OTHER_OWNER);
      ship(repository, "ship of child C", CHILD_C, OTHER_OWNER);
      ship(repository, "own ship of foreign B", UNIT_B, callerId);
      ship(repository, "own ownerless ship", null, callerId);
      ship(repository, "foreign ownerless ship", null, OTHER_OWNER);
      ships.put("unknown ship", UUID.randomUUID());

      MockHttpServletRequest request = new MockHttpServletRequest();
      if (caller.pin() != null) {
        request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, caller.pin().toString());
      }
      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      RequestScopeResolver resolver =
          new RequestScopeResolver(
              authHelper,
              memberships,
              orgUnits,
              cascade,
              new StaffelMembershipResolver(mock(SquadronRepository.class), orgUnits),
              request);
      AccessGateService accessGateService =
          new AccessGateService(resolver, authHelper, memberships);
      OwnerScopeService ownerScopeService =
          new OwnerScopeService(
              resolver,
              accessGateService,
              new OrgUnitStampingService(
                  resolver, accessGateService, authHelper, memberships, orgUnits));
      scopeHub = new ScopeHubShipGates(accessGateService, authHelper, resolver, repository);
      policy = new HangarAccessPolicy(ownerScopeService, repository);
    }

    private void ship(ShipRepository repository, String label, UUID unit, UUID owner) {
      UUID id = UUID.randomUUID();
      Ship ship = new Ship();
      ship.setId(id);
      User user = new User();
      user.setId(owner);
      ship.setOwner(user);
      if (unit != null) {
        Squadron squadron = new Squadron();
        squadron.setId(unit);
        ship.setOwningOrgUnit(squadron);
      }
      when(repository.findById(id)).thenReturn(Optional.of(ship));
      ships.put(label, id);
    }

    private static List<OrgUnitMembership> rows(UUID userId, List<UUID> units) {
      List<OrgUnitMembership> rows = new ArrayList<>();
      for (UUID unit : units) {
        OrgUnitMembership row = new OrgUnitMembership();
        row.setId(new OrgUnitMembershipId(userId, unit));
        row.setKind(unit.equals(BEREICH_X) ? OrgUnitKind.BEREICH : OrgUnitKind.SQUADRON);
        rows.add(row);
      }
      return rows;
    }
  }

  /**
   * The ship gates as the scope hub decided them, restated on the kernel's primitives.
   *
   * @param accessGateService the scope hub, for its org-unit gates
   * @param authHelper the caller's identity and roles
   * @param resolver the request scope, for the admin pin
   * @param ships the ships
   */
  private record ScopeHubShipGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      RequestScopeResolver resolver,
      ShipRepository ships) {

    boolean canSeeShip(UUID shipId) {
      return ships.findById(shipId).map(s -> permits(s, false)).orElse(false);
    }

    boolean canEditShip(UUID shipId) {
      return ships.findById(shipId).map(s -> permits(s, true)).orElse(false);
    }

    private boolean permits(Ship ship, boolean edit) {
      User owner = ship.getOwner();
      if (isOwner(owner)) {
        return true;
      }
      if (ship.getOwningOrgUnit() == null) {
        return (authHelper.isAdmin() && resolver.readActiveSquadronFromHeader().isEmpty())
            || isOwner(owner);
      }
      UUID unit = ship.getOwningOrgUnit().getId();
      return edit
          ? accessGateService.canEditSquadron(unit)
          : accessGateService.canSeeSquadron(unit);
    }

    private boolean isOwner(User owner) {
      return owner != null
          && owner.getId() != null
          && authHelper.currentUserId().map(owner.getId()::equals).orElse(false);
    }
  }
}
