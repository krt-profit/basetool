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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The assignee grant of {@link JobOrderAccessPolicy} (REQ-ORDERS-047, ADR-0301): an assignee entry
 * opens the read of that one order to its holder and nothing else.
 *
 * <p>The caller sits outside the order's unit and outside the profit gate, so every {@code true}
 * read below comes from the grant alone.
 */
class JobOrderAccessPolicyTest {

  private final UUID callerId = UUID.randomUUID();

  private final UUID foreignUnitId = UUID.randomUUID();

  private OwnerScopeService ownerScopeService;

  private AuthHelperService authHelper;

  private JobOrderRepository jobOrderRepository;

  private JobOrderAccessPolicy policy;

  private JobOrder order;

  @BeforeEach
  void setUp() {
    ownerScopeService = mock(OwnerScopeService.class);
    authHelper = mock(AuthHelperService.class);
    jobOrderRepository = mock(JobOrderRepository.class);
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);
    when(ownerScopeService.canSeeSquadron(any())).thenReturn(false);
    when(ownerScopeService.canEditSquadron(any())).thenReturn(false);
    when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));
    when(authHelper.isLogisticianOrAbove()).thenReturn(true);
    policy =
        new JobOrderAccessPolicy(
            ownerScopeService,
            authHelper,
            jobOrderRepository,
            mock(JobOrderHandoverRepository.class),
            mock(JobOrderItemHandoverRepository.class));

    Squadron foreign = new Squadron();
    foreign.setId(foreignUnitId);
    order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setResponsibleOrgUnit(foreign);
    order.setRequestingOrgUnit(foreign);
    when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
  }

  @Test
  void assigneeGrant_opensTheOrderOutsideTheUnitAndTheProfitGate() {
    assign(callerId);

    assertThat(policy.canSeeJobOrder(order.getId())).isTrue();
    assertThat(policy.canSeeJobOrder(order)).isTrue();
  }

  @Test
  void assigneeGrant_unitMateWithoutEntryIsDenied() {
    assign(UUID.randomUUID());

    assertThat(policy.canSeeJobOrder(order.getId())).isFalse();
  }

  @Test
  void assigneeGrant_opensNoWrite() {
    assign(callerId);

    assertThat(policy.canEditJobOrder(order.getId())).isFalse();
    assertThat(policy.canEdit(order, JobOrderGate.EDIT)).isFalse();
    assertThat(policy.mayEditJobOrderEarmarks(order.getId())).isFalse();
  }

  @Test
  void assigneeGrant_doesNotCountAsScopeVisibility() {
    assign(callerId);

    assertThat(policy.canSeeJobOrderInScope(order)).isFalse();
    assertThat(policy.canManageAssignees(order.getId())).isFalse();
  }

  @Test
  void assigneeGrant_opensNoOwnerPanel() {
    assign(callerId);

    assertThat(policy.canSeeJobOrderBlueprintOwners(order.getId())).isFalse();
    assertThat(policy.canSeeJobOrderInventoryOwners(order.getId())).isFalse();
  }

  @Test
  void assigneeGrant_survivesAStatusChange() {
    assign(callerId);
    order.setStatus(JobOrderStatus.COMPLETED);

    assertThat(policy.canSeeJobOrder(order.getId())).isTrue();
  }

  @Test
  void assigneeGrant_endsWithTheEntry() {
    assign(callerId);
    order.getAssignees().clear();

    assertThat(policy.canSeeJobOrder(order.getId())).isFalse();
  }

  @Test
  void assigneeGrant_needsAnIdentifiedCaller() {
    assign(callerId);
    when(authHelper.currentUserId()).thenReturn(Optional.empty());

    assertThat(policy.canSeeJobOrder(order.getId())).isFalse();
  }

  @Test
  void canManageAssignees_aLogisticianInTheEditScopeMayManage() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.canEditSquadron(foreignUnitId)).thenReturn(true);

    assertThat(policy.canManageAssignees(order.getId())).isTrue();
  }

  @Test
  void canManageAssignees_aMemberInTheEditScopeWithoutTheRoleMayNot() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.canEditSquadron(foreignUnitId)).thenReturn(true);
    when(authHelper.isLogisticianOrAbove()).thenReturn(false);

    assertThat(policy.canManageAssignees(order.getId())).isFalse();
  }

  private void assign(UUID userId) {
    User user = new User();
    user.setId(userId);
    order.addAssignee(JobOrderAssignee.builder().user(user).build());
  }
}
