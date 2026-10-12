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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.identity.api.StaffelAssignment;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.StaffelChange;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins that {@link OrgUnitMembershipOperations} is a pure pass-through: each of the six methods
 * forwards its arguments unchanged (the identity-owned records become the equal org-unit request
 * types), returns the very instance the org-unit service returned (callers mutate the managed row),
 * lets the callee's exception through untouched, calls nothing else, and declares no transaction of
 * its own, so the callee's propagation applies.
 */
class OrgUnitMembershipOperationsTest {

  private OrgUnitMembershipService membershipService;
  private OrgUnitMembershipQueryService queryService;
  private OrgUnitMembershipOperations operations;

  @BeforeEach
  void setUp() {
    membershipService = mock(OrgUnitMembershipService.class);
    queryService = mock(OrgUnitMembershipQueryService.class);
    operations = new OrgUnitMembershipOperations(membershipService, queryService);
  }

  @Test
  void findStaffelMembershipOrgUnitIds_forwardsAndReturnsTheSameList() {
    UUID userId = UUID.randomUUID();
    List<UUID> ids = List.of(UUID.randomUUID());
    when(queryService.findStaffelMembershipOrgUnitIds(userId)).thenReturn(ids);

    assertThat(operations.findStaffelMembershipOrgUnitIds(userId)).isSameAs(ids);
    verify(queryService).findStaffelMembershipOrgUnitIds(userId);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void findAllMembershipsForUser_forwardsAndReturnsTheSameList() {
    UUID userId = UUID.randomUUID();
    List<OrgUnitMembership> rows = List.of(new OrgUnitMembership());
    when(queryService.findAllMembershipsForUser(userId)).thenReturn(rows);

    assertThat(operations.findAllMembershipsForUser(userId)).isSameAs(rows);
    verify(queryService).findAllMembershipsForUser(userId);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void reconcileStaffelMemberships_forwardsTheSameUserAndEqualChanges() {
    User user = new User();
    UUID squadronId = UUID.randomUUID();

    operations.reconcileStaffelMemberships(
        user, List.of(new StaffelAssignment(squadronId, true, null)));

    verify(membershipService)
        .reconcileStaffelMemberships(user, List.of(new StaffelChange(squadronId, true, null)));
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void addMember_returnsTheSameManagedRow() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgUnitMembership row = new OrgUnitMembership();
    when(membershipService.addMember(skId, userId)).thenReturn(row);

    assertThat(operations.addMember(skId, userId)).isSameAs(row);
    verify(membershipService).addMember(skId, userId);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void removeMember_forwardsAndLetsTheCalleesExceptionThrough() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    NotFoundException refusal = new NotFoundException("Membership not found");
    org.mockito.Mockito.doThrow(refusal).when(membershipService).removeMember(skId, userId);

    assertThatThrownBy(() -> operations.removeMember(skId, userId)).isSameAs(refusal);
    verify(membershipService).removeMember(skId, userId);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void patchFlags_rebuildsTheSameRequestAndReturnsTheSameRow() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, null, 3L);
    OrgUnitMembership row = new OrgUnitMembership();
    when(membershipService.patchFlags(skId, userId, request)).thenReturn(row);

    assertThat(operations.patchFlags(skId, userId, true, null, 3L)).isSameAs(row);
    verify(membershipService).patchFlags(skId, userId, request);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void declaresNoTransactionOfItsOwn() {
    assertThat(OrgUnitMembershipOperations.class.isAnnotationPresent(Transactional.class))
        .as("class-level @Transactional")
        .isFalse();
    for (Method method : OrgUnitMembershipOperations.class.getDeclaredMethods()) {
      assertThat(method.isAnnotationPresent(Transactional.class))
          .as("@Transactional on %s", method.getName())
          .isFalse();
    }
  }
}
