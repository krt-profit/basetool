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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The differential verdict test of the operation access policy (plan §5.4, ADR-0236): over one
 * fixture matrix of callers and operations, {@link OperationAccessPolicy} returns exactly the
 * verdict of the scope hub's former operation gates for the read, ledger and edit gates.
 *
 * <p>{@link ScopeHubOperationGates} holds those gates as {@link AccessGateService} decided them
 * before they moved, on the same scope kernel, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, a pinned and an unpinned
 * member, members of zero, one and two units, a Bereich lead reaching a child Staffel through the
 * cascade, a guest below member, the participant escape on every operation, ownerless and unknown
 * operations.
 */
class OperationAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final UUID OP_A = UUID.randomUUID();

  private static final UUID OP_B = UUID.randomUUID();

  private static final UUID OP_C = UUID.randomUUID();

  private static final UUID OP_OWNERLESS = UUID.randomUUID();

  private static final UUID OP_UNKNOWN = UUID.randomUUID();

  private static final List<UUID> OPERATIONS = List.of(OP_A, OP_B, OP_C, OP_OWNERLESS, OP_UNKNOWN);

  private static final int GATES = 3;

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param memberOrAbove whether the caller holds a member-or-above role
   * @param memberships the org units the caller is a direct member of
   * @param pin the active-context pin header, or {@code null}
   * @param participant whether the caller participated in a mission of every operation
   */
  private record Caller(
      String name,
      boolean admin,
      boolean memberOrAbove,
      List<UUID> memberships,
      UUID pin,
      boolean participant) {}

  /**
   * One gate, as the scope hub and as the policy decide it.
   *
   * @param name the gate's method name
   * @param scopeHub the scope hub's verdict
   * @param policy the policy's verdict
   */
  private record Gate(
      String name,
      BiPredicate<ScopeHubOperationGates, UUID> scopeHub,
      BiPredicate<OperationAccessPolicy, UUID> policy) {}

  @Test
  void thePolicyReturnsTheScopeHubsVerdictForEveryCallerOperationAndGate() {
    List<Caller> callers = new ArrayList<>();
    for (boolean participant : List.of(false, true)) {
      callers.add(new Caller("admin unpinned", true, true, List.of(), null, participant));
      callers.add(new Caller("admin pinned A", true, true, List.of(), UNIT_A, participant));
      callers.add(new Caller("admin pinned B", true, true, List.of(), UNIT_B, participant));
      callers.add(new Caller("member of none", false, true, List.of(), null, participant));
      callers.add(new Caller("member of A", false, true, List.of(UNIT_A), null, participant));
      callers.add(
          new Caller("member of A and B", false, true, List.of(UNIT_A, UNIT_B), null, participant));
      callers.add(
          new Caller(
              "member of A and B pinned B",
              false,
              true,
              List.of(UNIT_A, UNIT_B),
              UNIT_B,
              participant));
      callers.add(
          new Caller(
              "member of A pinned foreign B", false, true, List.of(UNIT_A), UNIT_B, participant));
      callers.add(
          new Caller("Bereich lead over C", false, true, List.of(BEREICH_X), null, participant));
      callers.add(new Caller("guest of A", false, false, List.of(UNIT_A), null, participant));
      callers.add(new Caller("guest of none", false, false, List.of(), null, participant));
    }

    int compared = 0;
    int granted = 0;
    for (Caller caller : callers) {
      Fixture fixture = new Fixture(caller);
      for (UUID operation : OPERATIONS) {
        for (Gate gate : gates()) {
          boolean old = gate.scopeHub().test(fixture.scopeHub, operation);
          boolean now = gate.policy().test(fixture.policy, operation);
          assertThat(now)
              .as("%s on %s for %s", gate.name(), label(operation), caller.name())
              .isEqualTo(old);
          compared++;
          granted += now ? 1 : 0;
        }
      }
    }

    assertThat(compared).isEqualTo(callers.size() * OPERATIONS.size() * GATES);
    assertThat(granted).isBetween(1, compared - 1);
  }

  private static List<Gate> gates() {
    return List.of(
        new Gate(
            "canSeeOperation",
            ScopeHubOperationGates::canSeeOperation,
            OperationAccessPolicy::canSeeOperation),
        new Gate(
            "canSeeOperationLedger",
            ScopeHubOperationGates::canSeeOperationLedger,
            OperationAccessPolicy::canSeeOperationLedger),
        new Gate(
            "canEditOperation",
            ScopeHubOperationGates::canEditOperation,
            OperationAccessPolicy::canEditOperation));
  }

  private static String label(UUID operation) {
    if (operation.equals(OP_A)) {
      return "operation of A";
    }
    if (operation.equals(OP_B)) {
      return "operation of B";
    }
    if (operation.equals(OP_C)) {
      return "operation of child C";
    }
    return operation.equals(OP_OWNERLESS) ? "ownerless operation" : "unknown operation";
  }

  /** One caller's freshly wired scope hub and policy over shared scenario mocks. */
  private static final class Fixture {

    private final ScopeHubOperationGates scopeHub;

    private final OperationAccessPolicy policy;

    Fixture(Caller caller) {
      UUID callerId = UUID.randomUUID();
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      when(authHelper.isMemberOrAbove()).thenReturn(caller.memberOrAbove());
      when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));

      List<OrgUnitMembership> rows = new ArrayList<>();
      for (UUID unit : caller.memberships()) {
        OrgUnitMembership row = new OrgUnitMembership();
        row.setId(new OrgUnitMembershipId(callerId, unit));
        row.setKind(unit.equals(BEREICH_X) ? OrgUnitKind.BEREICH : OrgUnitKind.SQUADRON);
        rows.add(row);
      }
      OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
      when(memberships.findAllByIdUserId(callerId)).thenReturn(rows);
      when(memberships.findAllByIdUserIdAndKind(any(), any()))
          .thenReturn(rows.stream().filter(r -> r.getKind() == OrgUnitKind.SQUADRON).toList());

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

      OperationRepository operations = mock(OperationRepository.class);
      when(operations.findById(any())).thenReturn(Optional.empty());
      when(operations.findById(OP_A)).thenReturn(Optional.of(operation(OP_A, UNIT_A)));
      when(operations.findById(OP_B)).thenReturn(Optional.of(operation(OP_B, UNIT_B)));
      when(operations.findById(OP_C)).thenReturn(Optional.of(operation(OP_C, CHILD_C)));
      when(operations.findById(OP_OWNERLESS))
          .thenReturn(Optional.of(operation(OP_OWNERLESS, null)));
      when(operations.existsParticipantUserInOperation(any(), any()))
          .thenReturn(caller.participant());

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
      policy = new OperationAccessPolicy(ownerScopeService, authHelper, operations);
      scopeHub = new ScopeHubOperationGates(accessGateService, authHelper, operations);
    }

    private static Operation operation(UUID id, UUID owner) {
      Operation operation = new Operation();
      operation.setId(id);
      if (owner != null) {
        Squadron unit = new Squadron();
        unit.setId(owner);
        operation.setOwningOrgUnit(unit);
      }
      return operation;
    }
  }

  /**
   * The operation gates as the scope hub decided them, kept for the comparison.
   *
   * @param accessGateService the scope hub, for its org-unit gates
   * @param authHelper the caller's identity and roles
   * @param operationRepository the operations
   */
  private record ScopeHubOperationGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      OperationRepository operationRepository) {

    boolean canSeeOperation(UUID operationId) {
      return operationRepository
          .findById(operationId)
          .map(
              o -> {
                boolean scopeVisible =
                    o.getOwningOrgUnit() == null
                        ? authHelper.isMemberOrAbove()
                        : accessGateService.canSeeSquadron(o.getOwningOrgUnit().getId());
                return scopeVisible || participatedInOperation(operationId);
              })
          .orElse(false);
    }

    boolean canSeeOperationLedger(UUID operationId) {
      return operationRepository
          .findById(operationId)
          .map(
              o ->
                  o.getOwningOrgUnit() == null
                      ? authHelper.isMemberOrAbove()
                      : accessGateService.canSeeSquadron(o.getOwningOrgUnit().getId()))
          .orElse(false);
    }

    boolean canEditOperation(UUID operationId) {
      return operationRepository
          .findById(operationId)
          .map(
              o ->
                  o.getOwningOrgUnit() == null
                      || accessGateService.canEditSquadron(o.getOwningOrgUnit().getId()))
          .orElse(false);
    }

    private boolean participatedInOperation(UUID operationId) {
      return authHelper
          .currentUserId()
          .map(uid -> operationRepository.existsParticipantUserInOperation(operationId, uid))
          .orElse(false);
    }
  }
}
