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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * The per-unit Logistician rule (REQ-SEC-005) over the real gate chain: a Logistician grant in unit
 * A gives the Lager, refinery and job-order surfaces of A and nothing of B, pinned or not; grants
 * in A and C give exactly A and C; an admin passes inside the scope.
 */
class LogisticianPerUnitSurfacesTest {

  private static final UUID UNIT_A = UUID.randomUUID();
  private static final UUID UNIT_B = UUID.randomUUID();
  private static final UUID UNIT_C = UUID.randomUUID();

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller is an admin
   * @param grants the units of the caller's Logistician grants
   * @param scope the caller's resolved scope
   * @param reachable the units whose surfaces the caller is expected to handle
   */
  private record Caller(
      String name, boolean admin, Set<UUID> grants, ScopePredicate scope, Set<UUID> reachable) {

    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Caller> callers() {
    ScopePredicate aAndB = new ScopePredicate(false, null, Set.of(UNIT_A, UNIT_B));
    ScopePredicate aAndC = new ScopePredicate(false, null, Set.of(UNIT_A, UNIT_C));
    return Stream.of(
        new Caller(
            "logistician of A, plain member of B, unpinned",
            false,
            Set.of(UNIT_A),
            aAndB,
            Set.of(UNIT_A)),
        new Caller(
            "logistician of A, plain member of B, pinned to B",
            false,
            Set.of(UNIT_A),
            new ScopePredicate(false, UNIT_B, Set.of()),
            Set.of()),
        new Caller(
            "logistician of A, plain member of B, pinned to A",
            false,
            Set.of(UNIT_A),
            new ScopePredicate(false, UNIT_A, Set.of()),
            Set.of(UNIT_A)),
        new Caller(
            "logistician of A and C, unpinned",
            false,
            Set.of(UNIT_A, UNIT_C),
            aAndC,
            Set.of(UNIT_A, UNIT_C)),
        new Caller(
            "logistician of A and C, pinned to C",
            false,
            Set.of(UNIT_A, UNIT_C),
            new ScopePredicate(false, UNIT_C, Set.of()),
            Set.of(UNIT_C)),
        new Caller("plain member of A and B", false, Set.of(), aAndB, Set.of()),
        new Caller(
            "admin, unpinned",
            true,
            Set.of(),
            new ScopePredicate(true, null, Set.of()),
            Set.of(UNIT_A, UNIT_B, UNIT_C)),
        new Caller(
            "admin, pinned to B",
            true,
            Set.of(),
            new ScopePredicate(false, UNIT_B, Set.of()),
            Set.of(UNIT_B)));
  }

  /** The real gate chain for one caller. */
  private static final class Chain {

    final OwnerScopeService ownerScopeService;

    final AuthHelperService authHelper;

    Chain(Caller caller) {
      authHelper = mock(AuthHelperService.class);
      when(authHelper.isAdmin()).thenReturn(caller.admin());
      List<GrantedAuthority> authorities =
          caller.grants().stream()
              .<GrantedAuthority>map(unit -> new OrgUnitContextualAuthority("LOGISTICIAN", unit))
              .toList();
      when(authHelper.currentAuthentication())
          .thenReturn(
              Optional.of(new UsernamePasswordAuthenticationToken("caller", "n/a", authorities)));
      RequestScopeResolver resolver = mock(RequestScopeResolver.class);
      when(resolver.currentScopePredicate()).thenReturn(caller.scope());
      when(resolver.canViewJobOrders()).thenReturn(true);
      ownerScopeService =
          new OwnerScopeService(
              resolver,
              new AccessGateService(resolver, authHelper),
              mock(OrgUnitStampingService.class));
    }
  }

  private static Squadron unit(UUID id) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    return squadron;
  }

  private static void assertReaches(Caller caller, Predicate<UUID> surfaceOfUnit) {
    for (UUID unit : List.of(UNIT_A, UNIT_B, UNIT_C)) {
      assertThat(surfaceOfUnit.test(unit))
          .as("unit %s for %s", unit, caller.name())
          .isEqualTo(caller.reachable().contains(unit));
    }
  }

  @ParameterizedTest
  @MethodSource("callers")
  void inventoryRowsOfAUnitNeedALogisticianGrantInThatUnit(Caller caller) {
    InventoryItemRepository items = mock(InventoryItemRepository.class);
    InventoryAccessPolicy policy =
        new InventoryAccessPolicy(new Chain(caller).ownerScopeService, items, null);

    assertReaches(
        caller,
        unit -> {
          UUID id = UUID.randomUUID();
          InventoryItem item = new InventoryItem();
          item.setOwningOrgUnit(unit(unit));
          when(items.findById(id)).thenReturn(Optional.of(item));
          return policy.isLogisticianOfItem(id);
        });
  }

  @ParameterizedTest
  @MethodSource("callers")
  void refineryOrdersOfAUnitNeedALogisticianGrantInThatUnit(Caller caller) {
    RefineryOrderRepository orders = mock(RefineryOrderRepository.class);
    RefineryAccessPolicy policy =
        new RefineryAccessPolicy(new Chain(caller).ownerScopeService, orders, null);

    assertReaches(
        caller,
        unit -> {
          UUID id = UUID.randomUUID();
          RefineryOrder order = new RefineryOrder();
          order.setOwningOrgUnit(unit(unit));
          when(orders.findById(id)).thenReturn(Optional.of(order));
          return policy.isLogisticianOfOrder(id);
        });
  }

  @ParameterizedTest
  @MethodSource("callers")
  void jobOrdersOfAUnitNeedALogisticianGrantInTheResponsibleUnit(Caller caller) {
    JobOrderRepository orders = mock(JobOrderRepository.class);
    Chain chain = new Chain(caller);
    JobOrderAccessPolicy policy =
        new JobOrderAccessPolicy(
            chain.ownerScopeService,
            chain.authHelper,
            orders,
            mock(JobOrderHandoverRepository.class),
            mock(JobOrderItemHandoverRepository.class));

    assertReaches(
        caller,
        unit -> {
          UUID id = UUID.randomUUID();
          JobOrder order = new JobOrder();
          order.setId(id);
          order.setResponsibleOrgUnit(unit(unit));
          when(orders.findById(id)).thenReturn(Optional.of(order));
          return policy.canEditJobOrder(id) && policy.mayEditJobOrderEarmarks(id);
        });
  }

  @ParameterizedTest
  @MethodSource("callers")
  void anOwnerlessRowIsReservedToAdmins(Caller caller) {
    OwnerScopeService scope = new Chain(caller).ownerScopeService;

    assertThat(scope.canActAsLogisticianInOwner(null)).isEqualTo(caller.admin());
  }

  @ParameterizedTest
  @MethodSource("callers")
  void theListScopeHoldsExactlyTheLogisticianUnitsInsideTheActiveScope(Caller caller) {
    OwnerScopeService scope = new Chain(caller).ownerScopeService;

    ScopePredicate listScope = scope.currentLogisticianScope();

    if (caller.admin()) {
      assertThat(listScope).isEqualTo(caller.scope());
    } else {
      assertThat(listScope.adminAllScope()).isFalse();
      assertThat(listScope.activeOrgUnitId()).isNull();
      assertThat(listScope.memberOrgUnitIds()).isEqualTo(caller.reachable());
    }
  }

  @Test
  void aGrantOutsideTheScopeIsNotListed() {
    Caller caller =
        new Caller(
            "grant in B, scope A only",
            false,
            Set.of(UNIT_B),
            new ScopePredicate(false, null, Set.of(UNIT_A)),
            Set.of());

    assertThat(new Chain(caller).ownerScopeService.currentLogisticianOrgUnitIds()).isEmpty();
  }
}
