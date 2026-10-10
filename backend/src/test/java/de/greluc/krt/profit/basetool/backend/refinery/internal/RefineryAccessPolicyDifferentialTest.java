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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.service.AccessGateService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.RequestScopeResolver;
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
 * The differential verdict test of the refinery access policy (plan §5.4, ADR-0236): over one
 * fixture matrix of callers, orders and target members, {@link RefineryAccessPolicy} returns
 * exactly the verdict of the scope hub's former refinery gates for the read and edit gates of an
 * order and the two on-behalf pre-checks.
 *
 * <p>{@link ScopeHubRefineryGates} restates those gates as {@link AccessGateService} decided them
 * before they moved, on the scope kernel's primitives, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, a pinned and an unpinned
 * member, members of zero, one and two units and a Bereich lead reaching a child Staffel through
 * the cascade; orders of either unit, of the child unit, of a foreign unit owned by the caller (the
 * owner escape), ownerless orders of the caller and of another member, and an unknown order; and
 * target members of either unit, of the child unit, of none, and the caller.
 */
class RefineryAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final UUID OTHER_OWNER = UUID.randomUUID();

  private static final UUID TARGET_A = UUID.randomUUID();

  private static final UUID TARGET_B = UUID.randomUUID();

  private static final UUID TARGET_C = UUID.randomUUID();

  private static final UUID TARGET_NONE = UUID.randomUUID();

  private static final int ORDER_CASES = 7;

  private static final int TARGET_CASES = 5;

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
  void thePolicyReturnsTheScopeHubsVerdictForEveryCallerOrderTargetAndGate() {
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
      for (Map.Entry<String, UUID> order : fixture.orders.entrySet()) {
        for (String gate : List.of("canSeeRefineryOrder", "canEditRefineryOrder")) {
          boolean old =
              gate.equals("canSeeRefineryOrder")
                  ? fixture.scopeHub.canSeeRefineryOrder(order.getValue())
                  : fixture.scopeHub.canEditRefineryOrder(order.getValue());
          boolean now =
              gate.equals("canSeeRefineryOrder")
                  ? fixture.policy.canSeeRefineryOrder(order.getValue())
                  : fixture.policy.canEditRefineryOrder(order.getValue());
          assertThat(now).as("%s on %s for %s", gate, order.getKey(), caller.name()).isEqualTo(old);
          compared++;
          granted += now ? 1 : 0;
        }
      }
      for (Map.Entry<String, UUID> target : fixture.targets.entrySet()) {
        for (Gate gate : userGates()) {
          boolean old = gate.scopeHub().test(fixture.scopeHub, target.getValue());
          boolean now = gate.policy().test(fixture.policy, target.getValue());
          assertThat(now)
              .as("%s on %s for %s", gate.name(), target.getKey(), caller.name())
              .isEqualTo(old);
          compared++;
          granted += now ? 1 : 0;
        }
      }
    }

    assertThat(compared).isEqualTo(callers.size() * (ORDER_CASES * 2 + TARGET_CASES * 2));
    assertThat(granted).isBetween(1, compared - 1);
  }

  /**
   * One on-behalf gate, as the scope hub and as the policy decide it.
   *
   * @param name the gate's method name
   * @param scopeHub the scope hub's verdict
   * @param policy the policy's verdict
   */
  private record Gate(
      String name,
      BiPredicate<ScopeHubRefineryGates, UUID> scopeHub,
      BiPredicate<RefineryAccessPolicy, UUID> policy) {}

  private static List<Gate> userGates() {
    return List.of(
        new Gate(
            "canViewUserRefineryOrders",
            ScopeHubRefineryGates::canViewUserRefineryOrders,
            RefineryAccessPolicy::canViewUserRefineryOrders),
        new Gate(
            "canManageUserRefineryOrders",
            ScopeHubRefineryGates::canManageUserRefineryOrders,
            RefineryAccessPolicy::canManageUserRefineryOrders));
  }

  /** One caller's freshly wired scope hub and policy over the scenario mocks. */
  private static final class Fixture {

    private final ScopeHubRefineryGates scopeHub;

    private final RefineryAccessPolicy policy;

    private final Map<String, UUID> orders = new LinkedHashMap<>();

    private final Map<String, UUID> targets = new LinkedHashMap<>();

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
      when(memberships.findAllByIdUserId(TARGET_A)).thenReturn(rows(TARGET_A, List.of(UNIT_A)));
      when(memberships.findAllByIdUserId(TARGET_B)).thenReturn(rows(TARGET_B, List.of(UNIT_B)));
      when(memberships.findAllByIdUserId(TARGET_C)).thenReturn(rows(TARGET_C, List.of(CHILD_C)));
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

      RefineryOrderRepository repository = mock(RefineryOrderRepository.class);
      when(repository.findById(any())).thenReturn(Optional.empty());
      order(repository, "order of A", UNIT_A, OTHER_OWNER);
      order(repository, "order of B", UNIT_B, OTHER_OWNER);
      order(repository, "order of child C", CHILD_C, OTHER_OWNER);
      order(repository, "own order of foreign B", UNIT_B, callerId);
      order(repository, "own ownerless order", null, callerId);
      order(repository, "foreign ownerless order", null, OTHER_OWNER);
      orders.put("unknown order", UUID.randomUUID());

      targets.put("member of A", TARGET_A);
      targets.put("member of B", TARGET_B);
      targets.put("member of child C", TARGET_C);
      targets.put("member of none", TARGET_NONE);
      targets.put("the caller", callerId);

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
          new AccessGateService(
              resolver,
              authHelper,
              mock(InventoryItemRepository.class),
              mock(ShipRepository.class),
              memberships);
      OwnerScopeService ownerScopeService =
          new OwnerScopeService(
              resolver,
              accessGateService,
              new OrgUnitStampingService(
                  resolver, accessGateService, authHelper, memberships, orgUnits));
      scopeHub =
          new ScopeHubRefineryGates(
              accessGateService, authHelper, resolver, repository, memberships);
      policy = new RefineryAccessPolicy(ownerScopeService, repository);
    }

    private void order(RefineryOrderRepository repository, String label, UUID unit, UUID owner) {
      UUID id = UUID.randomUUID();
      RefineryOrder order = new RefineryOrder();
      order.setId(id);
      User user = new User();
      user.setId(owner);
      order.setOwner(user);
      if (unit != null) {
        Squadron squadron = new Squadron();
        squadron.setId(unit);
        order.setOwningOrgUnit(squadron);
      }
      when(repository.findById(id)).thenReturn(Optional.of(order));
      orders.put(label, id);
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
   * The refinery gates as the scope hub decided them, restated on the kernel's primitives.
   *
   * @param accessGateService the scope hub, for its org-unit gates
   * @param authHelper the caller's identity and roles
   * @param resolver the request scope, for the admin pin
   * @param orders the refinery orders
   * @param memberships the members' org-unit memberships
   */
  private record ScopeHubRefineryGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      RequestScopeResolver resolver,
      RefineryOrderRepository orders,
      OrgUnitMembershipRepository memberships) {

    boolean canSeeRefineryOrder(UUID orderId) {
      return orders.findById(orderId).map(o -> permits(o, false)).orElse(false);
    }

    boolean canEditRefineryOrder(UUID orderId) {
      return orders.findById(orderId).map(o -> permits(o, true)).orElse(false);
    }

    boolean canViewUserRefineryOrders(UUID targetUserId) {
      return actsOn(targetUserId, false);
    }

    boolean canManageUserRefineryOrders(UUID targetUserId) {
      return actsOn(targetUserId, true);
    }

    private boolean permits(RefineryOrder order, boolean edit) {
      User owner = order.getOwner();
      if (isOwner(owner)) {
        return true;
      }
      if (order.getOwningOrgUnit() == null) {
        return (authHelper.isAdmin() && resolver.readActiveSquadronFromHeader().isEmpty())
            || isOwner(owner);
      }
      UUID unit = order.getOwningOrgUnit().getId();
      return edit
          ? accessGateService.canEditSquadron(unit)
          : accessGateService.canSeeSquadron(unit);
    }

    private boolean isOwner(User owner) {
      return owner != null
          && owner.getId() != null
          && authHelper.currentUserId().map(owner.getId()::equals).orElse(false);
    }

    private boolean actsOn(UUID targetUserId, boolean edit) {
      if (authHelper.isAdmin()) {
        return true;
      }
      if (authHelper.currentUserId().map(targetUserId::equals).orElse(false)) {
        return true;
      }
      return memberships.findAllByIdUserId(targetUserId).stream()
          .map(m -> m.getId().getOrgUnitId())
          .anyMatch(
              unit ->
                  edit
                      ? accessGateService.canEditSquadron(unit)
                      : accessGateService.canSeeSquadron(unit));
    }
  }
}
