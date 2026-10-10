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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
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
 * The differential verdict test of the job-order access policy (plan §5.4, ADR-0236): over one
 * fixture matrix of callers and orders, {@link JobOrderAccessPolicy} returns exactly the verdict of
 * the scope hub's former job-order gates.
 *
 * <p>{@link ScopeHubJobOrderGates} restates those gates as {@link AccessGateService} decided them
 * before they moved, on the scope kernel's primitives, so the comparison outlives their deletion.
 *
 * <p>The matrix covers an admin unpinned and pinned to either unit, members of zero, one and two
 * units, a member pinned to an own and to a foreign unit, a Bereich lead reaching a child Staffel,
 * each with and without the profit eligibility that opens the queue and with and without the
 * logistician role; orders of either unit, of the child unit, of a Spezialkommando (the SK-queue
 * escape) and without a responsible unit, each placed by either unit (the requester escape) with
 * and without a delivery, and an unknown order.
 */
class JobOrderAccessPolicyDifferentialTest {

  private static final UUID UNIT_A = UUID.randomUUID();

  private static final UUID UNIT_B = UUID.randomUUID();

  private static final UUID CHILD_C = UUID.randomUUID();

  private static final UUID BEREICH_X = UUID.randomUUID();

  private static final UUID SK = UUID.randomUUID();

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param memberships the org units the caller is a direct member of
   * @param pin the active-context pin header, or {@code null}
   * @param profitEligible whether one of the caller's units is profit-eligible
   * @param logistician whether the caller is a logistician or above
   */
  private record Caller(
      String name,
      boolean admin,
      List<UUID> memberships,
      UUID pin,
      boolean profitEligible,
      boolean logistician) {}

  /**
   * One gate, as the scope hub and as the policy decide it.
   *
   * @param name the gate's method name
   * @param scopeHub the scope hub's verdict
   * @param policy the policy's verdict
   */
  private record Gate(
      String name,
      BiPredicate<ScopeHubJobOrderGates, UUID> scopeHub,
      BiPredicate<JobOrderAccessPolicy, UUID> policy) {}

  @Test
  void thePolicyReturnsTheScopeHubsVerdictForEveryCallerOrderAndGate() {
    List<Caller> callers = new ArrayList<>();
    for (boolean eligible : List.of(true, false)) {
      for (boolean logistician : List.of(true, false)) {
        String suffix = (eligible ? " eligible" : " ineligible") + (logistician ? " logi" : "");
        callers.add(new Caller("admin" + suffix, true, List.of(), null, eligible, logistician));
        callers.add(
            new Caller("admin pinned B" + suffix, true, List.of(), UNIT_B, eligible, logistician));
        callers.add(
            new Caller("member of none" + suffix, false, List.of(), null, eligible, logistician));
        callers.add(
            new Caller(
                "member of A" + suffix, false, List.of(UNIT_A), null, eligible, logistician));
        callers.add(
            new Caller(
                "member of A and B" + suffix,
                false,
                List.of(UNIT_A, UNIT_B),
                null,
                eligible,
                logistician));
        callers.add(
            new Caller(
                "member of A pinned foreign B" + suffix,
                false,
                List.of(UNIT_A),
                UNIT_B,
                eligible,
                logistician));
        callers.add(
            new Caller(
                "Bereich lead over C" + suffix,
                false,
                List.of(BEREICH_X),
                null,
                eligible,
                logistician));
      }
    }

    int compared = 0;
    int granted = 0;
    for (Caller caller : callers) {
      Fixture fixture = new Fixture(caller);
      for (Map.Entry<String, UUID> order : fixture.orders.entrySet()) {
        for (Gate gate : gates()) {
          boolean old = gate.scopeHub().test(fixture.scopeHub, order.getValue());
          boolean now = gate.policy().test(fixture.policy, order.getValue());
          assertThat(now)
              .as("%s on %s for %s", gate.name(), order.getKey(), caller.name())
              .isEqualTo(old);
          compared++;
          granted += now ? 1 : 0;
        }
      }
    }

    assertThat(compared).isEqualTo(callers.size() * 21 * gates().size());
    assertThat(granted).isBetween(1, compared - 1);
  }

  private static List<Gate> gates() {
    return List.of(
        new Gate(
            "canSeeJobOrder",
            ScopeHubJobOrderGates::canSeeJobOrder,
            JobOrderAccessPolicy::canSeeJobOrder),
        new Gate(
            "canSeeJobOrderBlueprintOwners",
            ScopeHubJobOrderGates::canSeeJobOrderBlueprintOwners,
            JobOrderAccessPolicy::canSeeJobOrderBlueprintOwners),
        new Gate(
            "canSeeJobOrderInventoryOwners",
            ScopeHubJobOrderGates::canSeeJobOrderInventoryOwners,
            JobOrderAccessPolicy::canSeeJobOrderInventoryOwners),
        new Gate(
            "canEditJobOrder",
            ScopeHubJobOrderGates::canEditJobOrder,
            JobOrderAccessPolicy::canEditJobOrder),
        new Gate(
            "mayEditJobOrder",
            ScopeHubJobOrderGates::mayEditJobOrder,
            JobOrderAccessPolicy::mayEditJobOrderEarmarks),
        new Gate(
            "canSeeJobOrderAsRequester",
            ScopeHubJobOrderGates::canSeeJobOrderAsRequester,
            JobOrderAccessPolicy::canSeeJobOrderAsRequester),
        new Gate(
            "canEditJobOrderAsRequester",
            ScopeHubJobOrderGates::canEditJobOrderAsRequester,
            JobOrderAccessPolicy::canEditJobOrderAsRequester));
  }

  /** One caller's freshly wired scope hub and policy over the scenario mocks. */
  private static final class Fixture {

    private final ScopeHubJobOrderGates scopeHub;

    private final JobOrderAccessPolicy policy;

    private final Map<String, UUID> orders = new LinkedHashMap<>();

    Fixture(Caller caller) {
      UUID callerId = UUID.randomUUID();
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      when(authHelper.isMemberOrAbove()).thenReturn(true);
      when(authHelper.isLogisticianOrAbove()).thenReturn(caller.logistician());
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
      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      when(orgUnits.countProfitEligibleByIdIn(anyCollection()))
          .thenReturn(caller.profitEligible() ? 1L : 0L);

      JobOrderRepository repository = mock(JobOrderRepository.class);
      JobOrderHandoverRepository handovers = mock(JobOrderHandoverRepository.class);
      JobOrderItemHandoverRepository itemHandovers = mock(JobOrderItemHandoverRepository.class);
      when(repository.findById(any())).thenReturn(Optional.empty());
      int n = 0;
      for (String responsible : List.of("A", "B", "child C", "SK", "none")) {
        for (String requesting : List.of("A", "B")) {
          for (boolean delivered : List.of(false, true)) {
            UUID id = UUID.randomUUID();
            JobOrder order = new JobOrder();
            order.setId(id);
            order.setResponsibleOrgUnit(unit(responsible));
            order.setRequestingOrgUnit(unit(requesting));
            when(repository.findById(id)).thenReturn(Optional.of(order));
            when(handovers.existsByJobOrderId(id)).thenReturn(delivered && n % 2 == 0);
            when(itemHandovers.existsByJobOrderId(id)).thenReturn(delivered && n % 2 == 1);
            orders.put(
                "order of "
                    + responsible
                    + " placed by "
                    + requesting
                    + (delivered ? " delivered" : ""),
                id);
            n++;
          }
        }
      }
      orders.put("unknown order", UUID.randomUUID());

      MockHttpServletRequest request = new MockHttpServletRequest();
      if (caller.pin() != null) {
        request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, caller.pin().toString());
      }
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
          new ScopeHubJobOrderGates(
              accessGateService, authHelper, resolver, repository, handovers, itemHandovers);
      policy =
          new JobOrderAccessPolicy(
              ownerScopeService, authHelper, repository, handovers, itemHandovers);
    }

    private static de.greluc.krt.profit.basetool.backend.model.OrgUnit unit(String label) {
      return switch (label) {
        case "A" -> squadron(UNIT_A);
        case "B" -> squadron(UNIT_B);
        case "child C" -> squadron(CHILD_C);
        case "SK" -> {
          SpecialCommand sk = new SpecialCommand();
          sk.setId(SK);
          yield sk;
        }
        default -> null;
      };
    }

    private static Squadron squadron(UUID id) {
      Squadron squadron = new Squadron();
      squadron.setId(id);
      return squadron;
    }
  }

  /**
   * The job-order gates as the scope hub decided them, restated on the kernel's primitives.
   *
   * @param accessGateService the scope hub, for its org-unit gates
   * @param authHelper the caller's roles
   * @param resolver the request scope, for the queue capability and the caller's memberships
   * @param orders the job orders
   * @param handovers the material handovers
   * @param itemHandovers the item handovers
   */
  private record ScopeHubJobOrderGates(
      AccessGateService accessGateService,
      AuthHelperService authHelper,
      RequestScopeResolver resolver,
      JobOrderRepository orders,
      JobOrderHandoverRepository handovers,
      JobOrderItemHandoverRepository itemHandovers) {

    boolean canSeeJobOrder(UUID id) {
      return orders.findById(id).map(o -> row(o, false)).orElse(false);
    }

    boolean canEditJobOrder(UUID id) {
      return orders.findById(id).map(o -> row(o, true)).orElse(false);
    }

    boolean mayEditJobOrder(UUID id) {
      return authHelper.isLogisticianOrAbove() && canEditJobOrder(id);
    }

    boolean canSeeJobOrderBlueprintOwners(UUID id) {
      return responsibleVisible(id);
    }

    boolean canSeeJobOrderInventoryOwners(UUID id) {
      return responsibleVisible(id);
    }

    boolean canSeeJobOrderAsRequester(UUID id) {
      return orders.findById(id).map(this::requester).orElse(false);
    }

    boolean canEditJobOrderAsRequester(UUID id) {
      return orders
          .findById(id)
          .map(
              o ->
                  requester(o)
                      && !(handovers.existsByJobOrderId(o.getId())
                          || itemHandovers.existsByJobOrderId(o.getId())))
          .orElse(false);
    }

    private boolean row(JobOrder order, boolean edit) {
      if (!resolver.canViewJobOrders()) {
        return false;
      }
      var responsible = order.getResponsibleOrgUnit();
      if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
        return true;
      }
      return edit
          ? accessGateService.canEditSquadron(responsible.getId())
          : accessGateService.canSeeSquadron(responsible.getId());
    }

    private boolean responsibleVisible(UUID id) {
      return orders
          .findById(id)
          .map(JobOrder::getResponsibleOrgUnit)
          .map(responsible -> accessGateService.canSeeSquadron(responsible.getId()))
          .orElse(false);
    }

    private boolean requester(JobOrder order) {
      var requesting = order.getRequestingOrgUnit();
      return requesting != null && resolver.currentUserIsMemberOfOrgUnit(requesting.getId());
    }
  }
}
