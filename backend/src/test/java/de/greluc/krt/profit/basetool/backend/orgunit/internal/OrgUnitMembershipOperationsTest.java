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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest.StaffelChange;
import de.greluc.krt.profit.basetool.backend.identity.api.StaffelAssignment;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins that {@link OrgUnitMembershipOperations} is a pass-through: each of the six methods forwards
 * its arguments unchanged (the identity-owned records become the equal org-unit request types),
 * sets the initial flags of an added member on the very row the org-unit service returned, lets the
 * callee's exception through untouched, calls nothing else, and declares no transaction of its own,
 * so the callee's propagation applies.
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
  void addMember_setsTheInitialFlagsOnTheManagedRowTheServiceReturned() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgUnitMembership row = new OrgUnitMembership();
    when(membershipService.addMember(skId, userId)).thenReturn(row);

    operations.addMember(skId, userId, true, true);

    assertThat(row.isLogistician()).isTrue();
    assertThat(row.isMissionManager()).isTrue();
    verify(membershipService).addMember(skId, userId);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void addMember_setsOnlyTheFlagsThatAreTrue() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgUnitMembership onlyLogistician = new OrgUnitMembership();
    OrgUnitMembership none = new OrgUnitMembership();
    when(membershipService.addMember(skId, userId)).thenReturn(onlyLogistician, none);

    operations.addMember(skId, userId, true, false);
    operations.addMember(skId, userId, null, null);

    assertThat(onlyLogistician.isLogistician()).isTrue();
    assertThat(onlyLogistician.isMissionManager()).isFalse();
    assertThat(none.isLogistician()).isFalse();
    assertThat(none.isMissionManager()).isFalse();
  }

  @Test
  void addMember_letsTheCalleesExceptionThrough() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    DuplicateEntityException refusal = new DuplicateEntityException("already a member");
    when(membershipService.addMember(skId, userId)).thenThrow(refusal);

    assertThatThrownBy(() -> operations.addMember(skId, userId, true, null)).isSameAs(refusal);
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
  void patchFlags_passesTheValuesOnUnchanged() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    operations.patchFlags(skId, userId, true, null, 3L);

    verify(membershipService).patchFlags(skId, userId, true, null, 3L);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void patchFlags_passesAMissingVersionOnForTheServiceToRefuse() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    operations.patchFlags(skId, userId, null, false, null);

    verify(membershipService).patchFlags(skId, userId, null, false, null);
    verifyNoMoreInteractions(queryService, membershipService);
  }

  @Test
  void flushMembershipChanges_runsTheUsersMembershipReadAndNothingElse() {
    UUID userId = UUID.randomUUID();
    when(queryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    operations.flushMembershipChanges(userId);

    verify(queryService).findAllMembershipsForUser(userId);
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
