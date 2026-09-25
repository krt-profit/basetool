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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Mockito unit tests for {@link OrgUnitCascadeService} — the single, shared definition of the
 * org-hierarchy scope cascade (epic #692, REQ-ORG-015). Verifies the exact reach a leadership
 * membership confers downward, the strict-silo isolation between Bereiche, the OL "everything"
 * branch, that an SK-Lead does <em>not</em> cascade (REQ-ORG-017), the per-request memoisation of
 * the hierarchy reads, and — most importantly for the zero-regression mandate — that a caller with
 * no leadership flag is expanded to exactly their direct memberships with no hierarchy read at all.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitCascadeServiceTest {

  @Mock private OrgUnitRepository orgUnitRepository;

  @InjectMocks private OrgUnitCascadeService service;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID BEREICH_A_ID = UUID.randomUUID();
  private static final UUID BEREICH_B_ID = UUID.randomUUID();
  private static final UUID OL_ID = UUID.randomUUID();
  private static final UUID STAFFEL_A1_ID = UUID.randomUUID();
  private static final UUID STAFFEL_A2_ID = UUID.randomUUID();
  private static final UUID SK_A1_ID = UUID.randomUUID();
  private static final UUID STAFFEL_B1_ID = UUID.randomUUID();
  private static final UUID FOREIGN_STAFFEL_ID = UUID.randomUUID();

  /**
   * Builds a membership row of the given kind for {@link #USER_ID} pointing at {@code orgUnitId}.
   */
  private static OrgUnitMembership membership(UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(USER_ID, orgUnitId));
    m.setKind(kind);
    return m;
  }

  @Test
  void plainStaffelMembership_expandsToItselfOnly_noHierarchyRead() {
    OrgUnitMembership staffel = membership(STAFFEL_A1_ID, OrgUnitKind.SQUADRON);

    Set<UUID> reach = service.expandWithDescendants(List.of(staffel));

    assertEquals(Set.of(STAFFEL_A1_ID), reach);
    assertTrue(service.cascadedOfficerReach(List.of(staffel)).isEmpty());
    verify(orgUnitRepository, never()).findChildOrgUnitIds(org.mockito.ArgumentMatchers.any());
    verify(orgUnitRepository, never()).findAllOrgUnitIds();
  }

  @Test
  void emptyMemberships_expandToEmpty() {
    assertTrue(service.expandWithDescendants(List.of()).isEmpty());
    assertTrue(service.cascadedOfficerReach(List.of()).isEmpty());
  }

  @Test
  void bereichsleiter_reachesBereichAndAllItsChildren() {
    OrgUnitMembership lead = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID))
        .thenReturn(List.of(STAFFEL_A1_ID, STAFFEL_A2_ID, SK_A1_ID));

    Set<UUID> reach = service.expandWithDescendants(List.of(lead));

    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID, STAFFEL_A2_ID, SK_A1_ID), reach);
    assertEquals(
        Set.of(BEREICH_A_ID, STAFFEL_A1_ID, STAFFEL_A2_ID, SK_A1_ID),
        service.cascadedOfficerReach(List.of(lead)));
  }

  @Test
  void bereichskoordinatorAndOperatorFlags_alsoCascade() {
    OrgUnitMembership koord = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    koord.setRole(MembershipRole.BEREICHSKOORDINATOR);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));

    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), service.cascadedOfficerReach(List.of(koord)));

    OrgUnitMembership op = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    op.setRole(MembershipRole.BEREICHSOPERATOR);
    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), service.cascadedOfficerReach(List.of(op)));
  }

  @Test
  void flagLessBereichSeat_doesNotCascade() {
    OrgUnitMembership seat = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);

    assertEquals(Set.of(BEREICH_A_ID), service.expandWithDescendants(List.of(seat)));
    assertTrue(service.cascadedOfficerReach(List.of(seat)).isEmpty());
    verify(orgUnitRepository, never()).findChildOrgUnitIds(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void strictSilo_bereichsleiterDoesNotReachOtherBereichOrItsChildren() {
    OrgUnitMembership leadA = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    leadA.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));

    Set<UUID> reach = service.expandWithDescendants(List.of(leadA));

    assertTrue(reach.contains(BEREICH_A_ID));
    assertTrue(reach.contains(STAFFEL_A1_ID));
    org.junit.jupiter.api.Assertions.assertFalse(reach.contains(BEREICH_B_ID));
    org.junit.jupiter.api.Assertions.assertFalse(reach.contains(STAFFEL_B1_ID));
    org.junit.jupiter.api.Assertions.assertFalse(reach.contains(FOREIGN_STAFFEL_ID));
    verify(orgUnitRepository, never()).findChildOrgUnitIds(BEREICH_B_ID);
  }

  @Test
  void olMember_reachesEveryOrgUnit_viaConcreteUnion_notAdminMarker() {
    OrgUnitMembership ol = membership(OL_ID, OrgUnitKind.ORGANISATIONSLEITUNG);
    ol.setRole(MembershipRole.OL_MEMBER);
    when(orgUnitRepository.findAllOrgUnitIds())
        .thenReturn(
            List.of(
                OL_ID,
                BEREICH_A_ID,
                BEREICH_B_ID,
                STAFFEL_A1_ID,
                STAFFEL_B1_ID,
                FOREIGN_STAFFEL_ID));

    Set<UUID> reach = service.expandWithDescendants(List.of(ol));

    assertEquals(
        Set.of(OL_ID, BEREICH_A_ID, BEREICH_B_ID, STAFFEL_A1_ID, STAFFEL_B1_ID, FOREIGN_STAFFEL_ID),
        reach);
    verify(orgUnitRepository, never()).findChildOrgUnitIds(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void multipleBereichLeaderships_unionAllTheirChildren() {
    OrgUnitMembership leadA = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    leadA.setRole(MembershipRole.BEREICHSLEITER);
    OrgUnitMembership leadB = membership(BEREICH_B_ID, OrgUnitKind.BEREICH);
    leadB.setRole(MembershipRole.BEREICHSKOORDINATOR);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_B_ID)).thenReturn(List.of(STAFFEL_B1_ID));

    Set<UUID> reach = service.cascadedOfficerReach(List.of(leadA, leadB));

    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID, BEREICH_B_ID, STAFFEL_B1_ID), reach);
  }

  @Test
  void mixedMemberships_combineDirectAndCascade() {
    OrgUnitMembership ownStaffel = membership(FOREIGN_STAFFEL_ID, OrgUnitKind.SQUADRON);
    OrgUnitMembership lead = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));

    Set<UUID> reach = service.expandWithDescendants(List.of(ownStaffel, lead));

    assertEquals(Set.of(FOREIGN_STAFFEL_ID, BEREICH_A_ID, STAFFEL_A1_ID), reach);
    org.junit.jupiter.api.Assertions.assertFalse(
        service.cascadedOfficerReach(List.of(ownStaffel, lead)).contains(FOREIGN_STAFFEL_ID));
  }

  @Test
  void skLeadMembership_doesNotCascade_keepsSkOnlyReach() {
    OrgUnitMembership skLead = membership(SK_A1_ID, OrgUnitKind.SPECIAL_COMMAND);
    skLead.setRole(MembershipRole.SK_LEAD);

    assertTrue(service.cascadedOfficerReach(List.of(skLead)).isEmpty());
    assertEquals(Set.of(SK_A1_ID), service.expandWithDescendants(List.of(skLead)));
    verify(orgUnitRepository, never()).findChildOrgUnitIds(org.mockito.ArgumentMatchers.any());
    verify(orgUnitRepository, never()).findAllOrgUnitIds();
  }

  @Test
  void cascadedReach_isMemoisedPerRequest_hierarchyReadOnce() {
    OrgUnitMembership lead = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));
    bindRequest();

    Set<UUID> first = service.cascadedOfficerReach(List.of(lead));
    Set<UUID> viaExpand = service.expandWithDescendants(List.of(lead));
    Set<UUID> third = service.cascadedOfficerReach(List.of(lead));

    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), first);
    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), third);
    assertTrue(viaExpand.containsAll(Set.of(BEREICH_A_ID, STAFFEL_A1_ID)));
    verify(orgUnitRepository, times(1)).findChildOrgUnitIds(BEREICH_A_ID);
  }

  @Test
  void memoisedReach_returnsDefensiveCopy_callerMutationDoesNotCorruptCache() {
    OrgUnitMembership lead = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));
    bindRequest();

    Set<UUID> first = service.cascadedOfficerReach(List.of(lead));
    first.clear();

    Set<UUID> second = service.cascadedOfficerReach(List.of(lead));
    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), second);
    second.clear();

    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), service.cascadedOfficerReach(List.of(lead)));
  }

  @Test
  void olReach_isMemoisedPerRequest_findAllOrgUnitIdsReadOnce() {
    OrgUnitMembership ol = membership(OL_ID, OrgUnitKind.ORGANISATIONSLEITUNG);
    ol.setRole(MembershipRole.OL_MEMBER);
    when(orgUnitRepository.findAllOrgUnitIds())
        .thenReturn(List.of(OL_ID, BEREICH_A_ID, STAFFEL_A1_ID));
    bindRequest();

    service.cascadedOfficerReach(List.of(ol));
    service.expandWithDescendants(List.of(ol));
    Set<UUID> third = service.cascadedOfficerReach(List.of(ol));

    assertEquals(Set.of(OL_ID, BEREICH_A_ID, STAFFEL_A1_ID), third);
    verify(orgUnitRepository, times(1)).findAllOrgUnitIds();
  }

  @Test
  void withoutBoundRequest_cascadeDoesNotMemoise_recomputesEachCall() {
    OrgUnitMembership lead = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));

    service.cascadedOfficerReach(List.of(lead));
    service.cascadedOfficerReach(List.of(lead));

    verify(orgUnitRepository, times(2)).findChildOrgUnitIds(BEREICH_A_ID);
  }

  @Test
  void perRequestCache_keyedByMembershipSet_doesNotServeOneReachForAnother() {
    OrgUnitMembership leadA = membership(BEREICH_A_ID, OrgUnitKind.BEREICH);
    leadA.setRole(MembershipRole.BEREICHSLEITER);
    OrgUnitMembership leadB = membership(BEREICH_B_ID, OrgUnitKind.BEREICH);
    leadB.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_A_ID)).thenReturn(List.of(STAFFEL_A1_ID));
    when(orgUnitRepository.findChildOrgUnitIds(BEREICH_B_ID)).thenReturn(List.of(STAFFEL_B1_ID));
    bindRequest();

    Set<UUID> reachA = service.cascadedOfficerReach(List.of(leadA));
    Set<UUID> reachB = service.cascadedOfficerReach(List.of(leadB));

    assertEquals(Set.of(BEREICH_A_ID, STAFFEL_A1_ID), reachA);
    assertEquals(Set.of(BEREICH_B_ID, STAFFEL_B1_ID), reachB);
  }

  /**
   * Clears any servlet request bound to the current thread by the memoisation tests, so it never
   * leaks into a later test sharing the thread (a no-op when nothing is bound).
   */
  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  /** Binds a fresh mock servlet request to the current thread so the per-request cache engages. */
  private static void bindRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }
}
