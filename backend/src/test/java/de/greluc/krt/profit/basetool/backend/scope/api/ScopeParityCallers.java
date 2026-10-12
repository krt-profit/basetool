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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The caller matrix of the scope-fragment parity tests (REQ-DATA-010), wired on the real scope
 * kernel: the nine callers of the access-policy differential tests, each with the {@link
 * ScopePredicate} the list queries receive and the {@link OwnerScopeService} the access policies
 * ask.
 *
 * <p>The units are real rows of the test schema, so a list query can match them; the Bereich that
 * reaches the child Staffel through the cascade exists only in the memberships.
 */
public final class ScopeParityCallers {

  private ScopeParityCallers() {}

  /**
   * The org units the matrix refers to.
   *
   * @param unitA the first Staffel
   * @param unitB the second Staffel
   * @param childC a Staffel below the Bereich
   * @param bereichX the Bereich whose lead reaches {@code childC}
   */
  public record Units(UUID unitA, UUID unitB, UUID childC, UUID bereichX) {}

  /**
   * One caller of the matrix.
   *
   * @param name the scenario label
   * @param admin whether the caller holds ROLE_ADMIN
   * @param memberships the org units the caller is a direct member of
   * @param pin the active-context pin header, or {@code null}
   */
  public record Caller(String name, boolean admin, List<UUID> memberships, UUID pin) {}

  /**
   * One caller wired on the scope kernel.
   *
   * @param caller the scenario
   * @param predicate the scope vector the list queries receive
   * @param ownerScopeService the hub the access policies ask
   * @param authHelper the caller's identity and roles
   */
  public record Wired(
      Caller caller,
      ScopePredicate predicate,
      OwnerScopeService ownerScopeService,
      AuthHelperService authHelper) {}

  /**
   * The nine callers: an admin unpinned and pinned to either unit, members of zero, one and two
   * units, a pinned member, a member pinned to a foreign unit and a Bereich lead.
   *
   * @param units the units the callers refer to
   * @return the callers in a fixed order
   */
  public static List<Caller> matrix(Units units) {
    return List.of(
        new Caller("admin unpinned", true, List.of(), null),
        new Caller("admin pinned A", true, List.of(), units.unitA()),
        new Caller("admin pinned B", true, List.of(), units.unitB()),
        new Caller("member of none", false, List.of(), null),
        new Caller("member of A", false, List.of(units.unitA()), null),
        new Caller("member of A and B", false, List.of(units.unitA(), units.unitB()), null),
        new Caller(
            "member of A and B pinned B",
            false,
            List.of(units.unitA(), units.unitB()),
            units.unitB()),
        new Caller("member of A pinned foreign B", false, List.of(units.unitA()), units.unitB()),
        new Caller("Bereich lead over C", false, List.of(units.bereichX()), null));
  }

  /**
   * Wires one caller on the real request-scope resolver and access gate over mocked memberships.
   *
   * @param caller the scenario
   * @param units the units the scenario refers to
   * @param callerId the id the caller acts as
   * @param memberOrAbove whether the caller counts as a member or above
   * @return the wired caller
   */
  public static Wired wire(Caller caller, Units units, UUID callerId, boolean memberOrAbove) {
    AuthHelperService authHelper = mock(AuthHelperService.class);
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(caller.admin());
    when(authHelper.isMemberOrAbove()).thenReturn(memberOrAbove || caller.admin());
    when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));

    List<OrgUnitMembership> rows = new ArrayList<>();
    for (UUID unit : caller.memberships()) {
      OrgUnitMembership row = new OrgUnitMembership();
      row.setId(new OrgUnitMembershipId(callerId, unit));
      row.setKind(unit.equals(units.bereichX()) ? OrgUnitKind.BEREICH : OrgUnitKind.SQUADRON);
      rows.add(row);
    }
    OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
    when(memberships.findAllByIdUserId(any())).thenReturn(List.of());
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
                if (row.getId().getOrgUnitId().equals(units.bereichX())) {
                  ids.add(units.childC());
                }
              }
              return ids;
            });

    MockHttpServletRequest request = new MockHttpServletRequest();
    if (caller.pin() != null) {
      request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, caller.pin().toString());
    }
    OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
    when(orgUnits.countProfitEligibleByIdIn(anyCollection())).thenReturn(1L);
    RequestScopeResolver resolver =
        new RequestScopeResolver(
            authHelper,
            memberships,
            orgUnits,
            cascade,
            new StaffelMembershipResolver(mock(SquadronRepository.class), orgUnits),
            request);
    AccessGateService gate = new AccessGateService(resolver, authHelper);
    OwnerScopeService ownerScopeService =
        new OwnerScopeService(resolver, gate, mock(OrgUnitStampingService.class));
    return new Wired(caller, resolver.currentScopePredicate(), ownerScopeService, authHelper);
  }

  /**
   * One seeded row of an aggregate.
   *
   * @param name the row's label
   * @param id the row's id
   * @param exact whether the list query and the row gate must agree both ways; {@code false} for
   *     rows where the gate adds an escape the list does not carry (the owner escape, the ownerless
   *     rule), for which the list must only never show more than the gate allows
   */
  public record Row(String name, UUID id, boolean exact) {}

  /**
   * Runs the parity check: for every caller and every row, what the list query returns is never
   * more than the row gate allows, and for the exact rows both agree. It also requires that some
   * row is listed and some is refused, so the matrix cannot pass vacuously.
   *
   * @param units the units of the matrix
   * @param callerId the id every caller acts as
   * @param memberOrAbove whether the callers count as a member or above
   * @param rows the seeded rows
   * @param listed the ids the aggregate's list query returns for a wired caller
   * @param gate the aggregate's row gate for a wired caller and a row id
   */
  public static void check(
      Units units,
      UUID callerId,
      boolean memberOrAbove,
      List<Row> rows,
      Function<Wired, Set<UUID>> listed,
      BiPredicate<Wired, UUID> gate) {
    int shown = 0;
    int refused = 0;
    for (Caller caller : matrix(units)) {
      Wired wired = wire(caller, units, callerId, memberOrAbove);
      Set<UUID> ids = listed.apply(wired);
      for (Row row : rows) {
        boolean inList = ids.contains(row.id());
        boolean allowed = gate.test(wired, row.id());
        if (inList) {
          assertThat(allowed)
              .as("%s: the list shows %s, so the gate must allow it", caller.name(), row.name())
              .isTrue();
          shown++;
        }
        if (!allowed) {
          refused++;
        }
        if (row.exact()) {
          assertThat(inList)
              .as("%s: list and gate agree on %s", caller.name(), row.name())
              .isEqualTo(allowed);
        }
      }
    }
    assertThat(shown).as("rows shown by some list").isPositive();
    assertThat(refused).as("rows refused by some gate").isPositive();
  }

  /**
   * Persists three Staffeln and names a Bereich for the matrix.
   *
   * @param squadrons the Staffel repository
   * @return the units of the matrix
   */
  public static Units persistUnits(SquadronRepository squadrons) {
    return new Units(
        persistSquadron(squadrons, "A").getId(),
        persistSquadron(squadrons, "B").getId(),
        persistSquadron(squadrons, "C").getId(),
        UUID.randomUUID());
  }

  /**
   * Persists one Staffel with a unique name and shorthand.
   *
   * @param squadrons the Staffel repository
   * @param tag a short label
   * @return the persisted Staffel
   */
  public static Squadron persistSquadron(SquadronRepository squadrons, String tag) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Parity " + tag + " " + suffix);
    squadron.setShorthand("P" + tag + suffix.substring(0, 3));
    return squadrons.saveAndFlush(squadron);
  }

  /**
   * Persists one user with a unique name.
   *
   * @param users the user repository
   * @param name a short label
   * @return the persisted user
   */
  public static User persistUser(UserRepository users, String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("parity-" + name + "-" + UUID.randomUUID());
    return users.save(user);
  }
}
