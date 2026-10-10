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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
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
 * The differential verdict test of the inventory access policy (plan §5.4, ADR-0236): over one
 * fixture matrix of callers, Lager rows and target members, {@link InventoryAccessPolicy} returns
 * exactly the verdict of the scope hub's inventory gates for the read and edit gates of a Lager row
 * and the on-behalf book-in pre-check.
 *
 * <p>{@link ScopeHubInventoryGates} restates those gates as {@link AccessGateService} decided them
 * before they moved, on the scope kernel's primitives, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, a pinned and an unpinned
 * member, members of zero, one and two units and a Bereich lead reaching a child Staffel through
 * the cascade; rows of either unit, of the child unit, of a foreign unit owned by the caller (the
 * owner escape), ownerless rows of the caller and of another member, and an unknown row; and target
 * members of either unit, of the child unit, of none, and the caller.
 */
class InventoryAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final UUID OTHER_OWNER = UUID.randomUUID();

  private static final UUID TARGET_A = UUID.randomUUID();

  private static final UUID TARGET_B = UUID.randomUUID();

  private static final UUID TARGET_C = UUID.randomUUID();

  private static final UUID TARGET_NONE = UUID.randomUUID();

  private static final int ROW_CASES = 7;

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
  void thePolicyReturnsTheScopeHubsVerdictForEveryCallerRowTargetAndGate() {
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
      for (Map.Entry<String, UUID> row : fixture.rows.entrySet()) {
        for (Gate gate : rowGates()) {
          boolean old = gate.scopeHub().test(fixture.scopeHub, row.getValue());
          boolean now = gate.policy().test(fixture.policy, row.getValue());
          assertThat(now)
              .as("%s on %s for %s", gate.name(), row.getKey(), caller.name())
              .isEqualTo(old);
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

    assertThat(compared).isEqualTo(callers.size() * (ROW_CASES * 2 + TARGET_CASES));
    assertThat(granted).isBetween(1, compared - 1);
  }

  /**
   * One inventory gate, as the restated scope hub and the policy decide it.
   *
   * @param name the gate's method name
   * @param scopeHub the restated scope hub's verdict
   * @param policy the policy's verdict
   */
  private record Gate(
      String name,
      BiPredicate<ScopeHubInventoryGates, UUID> scopeHub,
      BiPredicate<InventoryAccessPolicy, UUID> policy) {}

  private static List<Gate> rowGates() {
    return List.of(
        new Gate(
            "canSeeInventoryItem",
            ScopeHubInventoryGates::canSeeInventoryItem,
            InventoryAccessPolicy::canSeeInventoryItem),
        new Gate(
            "canEditInventoryItem",
            ScopeHubInventoryGates::canEditInventoryItem,
            InventoryAccessPolicy::canEditInventoryItem));
  }

  private static List<Gate> userGates() {
    return List.of(
        new Gate(
            "canManageUserInventory",
            ScopeHubInventoryGates::canManageUserInventory,
            InventoryAccessPolicy::canManageUserInventory));
  }

  /** One caller's freshly wired scope hub and policy over the scenario mocks. */
  private static final class Fixture {

    private final ScopeHubInventoryGates scopeHub;

    private final InventoryAccessPolicy policy;

    private final Map<String, UUID> rows = new LinkedHashMap<>();

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

      InventoryItemRepository repository = mock(InventoryItemRepository.class);
      when(repository.findById(any())).thenReturn(Optional.empty());
      row(repository, "row of A", UNIT_A, OTHER_OWNER);
      row(repository, "row of B", UNIT_B, OTHER_OWNER);
      row(repository, "row of child C", CHILD_C, OTHER_OWNER);
      row(repository, "own row of foreign B", UNIT_B, callerId);
      row(repository, "own ownerless row", null, callerId);
      row(repository, "foreign ownerless row", null, OTHER_OWNER);
      rows.put("unknown row", UUID.randomUUID());

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
          new AccessGateService(resolver, authHelper, mock(ShipRepository.class), memberships);
      OwnerScopeService ownerScopeService =
          new OwnerScopeService(
              resolver,
              accessGateService,
              new OrgUnitStampingService(
                  resolver, accessGateService, authHelper, memberships, orgUnits));
      scopeHub =
          new ScopeHubInventoryGates(
              accessGateService, authHelper, resolver, repository, memberships);
      policy = new InventoryAccessPolicy(ownerScopeService, repository);
    }

    private void row(InventoryItemRepository repository, String label, UUID unit, UUID owner) {
      UUID id = UUID.randomUUID();
      InventoryItem item = new InventoryItem();
      item.setId(id);
      User user = new User();
      user.setId(owner);
      item.setUser(user);
      if (unit != null) {
        Squadron squadron = new Squadron();
        squadron.setId(unit);
        item.setOwningOrgUnit(squadron);
      }
      when(repository.findById(id)).thenReturn(Optional.of(item));
      rows.put(label, id);
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
   * The inventory gates as the scope hub decided them, restated on the kernel's primitives.
   *
   * @param accessGateService the scope hub, for its org-unit gates
   * @param authHelper the caller's identity and roles
   * @param resolver the request scope, for the admin pin
   * @param items the Lager rows
   * @param memberships the members' org-unit memberships
   */
  private record ScopeHubInventoryGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      RequestScopeResolver resolver,
      InventoryItemRepository items,
      OrgUnitMembershipRepository memberships) {

    boolean canSeeInventoryItem(UUID itemId) {
      return items.findById(itemId).map(i -> permits(i, false)).orElse(false);
    }

    boolean canEditInventoryItem(UUID itemId) {
      return items.findById(itemId).map(i -> permits(i, true)).orElse(false);
    }

    boolean canManageUserInventory(UUID targetUserId) {
      return actsOn(targetUserId, true);
    }

    private boolean permits(InventoryItem item, boolean edit) {
      User owner = item.getUser();
      if (isOwner(owner)) {
        return true;
      }
      if (item.getOwningOrgUnit() == null) {
        return (authHelper.isAdmin() && resolver.readActiveSquadronFromHeader().isEmpty())
            || isOwner(owner);
      }
      UUID unit = item.getOwningOrgUnit().getId();
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
