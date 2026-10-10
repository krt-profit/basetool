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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Compares the bank seam's {@code AREA_MEMBERS} cascade check (REQ-BANK-048) with the scope hub's
 * former {@code currentUserIsMemberOfAreaCascade} over the caller matrix of plan §5.4: admin pinned
 * and unpinned, anonymous, members of no unit, of the Bereich, of a child Staffel or
 * Spezialkommando, of a foreign Bereich or its child, and of two units, for every target unit.
 */
class OrgUnitBankAreaCascadeDifferentialTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID BEREICH = UUID.randomUUID();
  private static final UUID CHILD_STAFFEL = UUID.randomUUID();
  private static final UUID CHILD_SK = UUID.randomUUID();
  private static final UUID OTHER_BEREICH = UUID.randomUUID();
  private static final UUID OTHER_STAFFEL = UUID.randomUUID();
  private static final UUID EMPTY_BEREICH = UUID.randomUUID();

  private static final Map<UUID, List<UUID>> CHILDREN =
      Map.of(
          BEREICH, List.of(CHILD_STAFFEL, CHILD_SK),
          OTHER_BEREICH, List.of(OTHER_STAFFEL),
          EMPTY_BEREICH, List.of(),
          CHILD_STAFFEL, List.of(),
          CHILD_SK, List.of(),
          OTHER_STAFFEL, List.of());

  private static final List<Set<UUID>> MEMBERSHIPS =
      List.of(
          Set.of(),
          Set.of(BEREICH),
          Set.of(CHILD_STAFFEL),
          Set.of(CHILD_SK),
          Set.of(OTHER_BEREICH),
          Set.of(OTHER_STAFFEL),
          Set.of(CHILD_STAFFEL, OTHER_STAFFEL),
          Set.of(OTHER_BEREICH, CHILD_SK));

  private static final List<UUID> TARGETS =
      List.of(BEREICH, OTHER_BEREICH, EMPTY_BEREICH, CHILD_STAFFEL, OTHER_STAFFEL);

  @Test
  void seamGivesTheVerdictOfTheFormerHubMethodOverTheWholeMatrix() {
    int verdicts = 0;
    int admitted = 0;
    for (boolean authenticated : List.of(true, false)) {
      for (boolean admin : List.of(false, true)) {
        for (UUID pin : new UUID[] {null, BEREICH, OTHER_STAFFEL}) {
          for (Set<UUID> memberships : MEMBERSHIPS) {
            Fixture fixture = Fixture.of(authenticated, admin, pin, memberships);
            for (UUID target : TARGETS) {
              boolean expected = formerHubVerdict(target, fixture.callerMemberships());
              boolean actual =
                  OrgUnitBankAccessService.isMemberOfAreaCascade(
                      target,
                      fixture.ownerScope().currentDirectMembershipOrgUnitIds(),
                      fixture.orgUnits().findChildOrgUnitIds(target));
              assertThat(actual)
                  .as(
                      "authenticated=%s admin=%s pin=%s memberships=%s target=%s",
                      authenticated, admin, pin, memberships, target)
                  .isEqualTo(expected);
              verdicts++;
              admitted += actual ? 1 : 0;
            }
          }
        }
      }
    }
    assertThat(verdicts).isEqualTo(2 * 2 * 3 * MEMBERSHIPS.size() * TARGETS.size());
    assertThat(admitted).isPositive().isLessThan(verdicts);
  }

  /**
   * The former {@code RequestScopeResolver#currentUserIsMemberOfAreaCascade}, verbatim over the
   * caller's membership rows.
   *
   * @param bereichId the target unit
   * @param memberships the caller's membership rows
   * @return the former hub's verdict
   */
  private static boolean formerHubVerdict(UUID bereichId, List<OrgUnitMembership> memberships) {
    List<UUID> childIds = CHILDREN.get(bereichId);
    return memberships.stream()
        .anyMatch(
            m -> {
              UUID ou = m.getId().getOrgUnitId();
              return ou.equals(bereichId) || childIds.contains(ou);
            });
  }

  /**
   * One caller situation wired into a real {@link RequestScopeResolver} and {@link
   * OwnerScopeService}.
   *
   * @param ownerScope the scope facade over the real resolver
   * @param orgUnits the org-unit repository answering the child queries
   * @param callerMemberships the caller's membership rows
   */
  private record Fixture(
      OwnerScopeService ownerScope,
      OrgUnitRepository orgUnits,
      List<OrgUnitMembership> callerMemberships) {

    private static List<OrgUnitMembership> rows(boolean authenticated, Set<UUID> memberships) {
      List<OrgUnitMembership> rows = new ArrayList<>();
      if (authenticated) {
        for (UUID unit : memberships) {
          OrgUnitMembership row = new OrgUnitMembership();
          row.setId(new OrgUnitMembershipId(CALLER, unit));
          row.setKind(unit.equals(CHILD_SK) ? OrgUnitKind.SPECIAL_COMMAND : OrgUnitKind.SQUADRON);
          rows.add(row);
        }
      }
      return rows;
    }

    static Fixture of(boolean authenticated, boolean admin, UUID pin, Set<UUID> memberships) {
      List<OrgUnitMembership> rows = rows(authenticated, memberships);
      AuthHelperService authHelper = mock(AuthHelperService.class);
      OrgUnitMembershipRepository membershipRepository = mock(OrgUnitMembershipRepository.class);
      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(authHelper.isAuthenticated()).thenReturn(authenticated);
      when(authHelper.isAdmin()).thenReturn(admin);
      when(authHelper.currentUserId())
          .thenReturn(authenticated ? Optional.of(CALLER) : Optional.empty());
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(pin == null ? null : pin.toString());
      when(membershipRepository.findAllByIdUserId(CALLER)).thenReturn(rows);
      when(orgUnits.findChildOrgUnitIds(any(UUID.class)))
          .thenAnswer(inv -> CHILDREN.get(inv.<UUID>getArgument(0)));
      RequestScopeResolver resolver =
          new RequestScopeResolver(
              authHelper,
              membershipRepository,
              orgUnits,
              mock(OrgUnitCascadeService.class),
              mock(StaffelMembershipResolver.class),
              request);
      OwnerScopeService ownerScope =
          new OwnerScopeService(
              resolver, mock(AccessGateService.class), mock(OrgUnitStampingService.class));
      return new Fixture(ownerScope, orgUnits, rows);
    }
  }
}
