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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link JobOrderQueryService#findByNumberPrefix}, the number lookup of the quick
 * access (REQ-ORDERS-048).
 */
@ExtendWith(MockitoExtension.class)
class JobOrderQueryServiceNumberLookupTest {

  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final UUID UNIT_ID = UUID.randomUUID();

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private AuthHelperService authHelper;
  @Mock private OwnerScopeService ownerScopeService;

  @InjectMocks private JobOrderQueryService queryService;

  /** The hit is the slim projection of the order the repository returned. */
  @Test
  void mapsTheVisibleOrdersToSlimHits() {
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setDisplayId(12);
    order.setStatus(JobOrderStatus.OPEN);
    when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of(UNIT_ID)));
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(jobOrderRepository.findVisibleByNumberPrefix(
            "1", true, false, null, Set.of(UNIT_ID), CALLER_ID, PageRequest.of(0, 5)))
        .thenReturn(List.of(order));

    assertThat(queryService.findByNumberPrefix("1", 5))
        .containsExactly(
            new JobOrderNumberHitDto(order.getId(), 12, JobOrderStatus.OPEN, order.getCreatedAt()));
  }

  /** A caller outside the order workflow is asked with the scoped list switched off. */
  @Test
  void aCallerOutsideTheWorkflowOnlyGetsTheAssigneeGrant() {
    when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);
    when(jobOrderRepository.findVisibleByNumberPrefix(
            any(), anyBoolean(), anyBoolean(), any(), any(), any(), any()))
        .thenReturn(List.of());

    queryService.findByNumberPrefix("7", 5);

    verify(jobOrderRepository)
        .findVisibleByNumberPrefix(
            eq("7"), eq(false), eq(false), eq(null), eq(Set.of()), eq(CALLER_ID), any());
  }

  /** An unidentified caller gets nothing and no query runs. */
  @Test
  void anUnidentifiedCallerGetsNothing() {
    when(authHelper.currentUserId()).thenReturn(Optional.empty());

    assertThat(queryService.findByNumberPrefix("1", 5)).isEmpty();
    verifyNoInteractions(jobOrderRepository);
  }
}
