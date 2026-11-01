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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link JobOrderQueryService#getAssignedJobOrders}, the „Ich bearbeite" read
 * (REQ-ORDERS-040).
 */
@ExtendWith(MockitoExtension.class)
class JobOrderQueryServiceAssignedToMeTest {

  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final PageRequest PAGEABLE = PageRequest.of(0, 10);
  private static final List<JobOrderStatus> ALL_STATUSES = List.of(JobOrderStatus.values());

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private AuthHelperService authHelper;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderStockProjectionService jobOrderStockProjectionService;

  @InjectMocks private JobOrderQueryService queryService;

  @Test
  void readsTheCallersOwnEntriesWithoutTheProfitGate() {
    Page<JobOrder> page = new PageImpl<>(List.of(new JobOrder()), PAGEABLE, 1);
    Page<JobOrderDto> mapped = new PageImpl<>(List.of(), PAGEABLE, 1);
    when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
    when(jobOrderRepository.findAssignedJobOrders(ALL_STATUSES, CALLER_ID, PAGEABLE))
        .thenReturn(page);
    when(jobOrderStockProjectionService.mapPageWithStock(page)).thenReturn(mapped);

    Page<JobOrderDto> result = queryService.getAssignedJobOrders(null, PAGEABLE);

    assertThat(result).isSameAs(mapped);
    verifyNoInteractions(ownerScopeService);
  }

  @Test
  void forwardsTheStatusFilter() {
    Page<JobOrder> page = new PageImpl<>(List.of(), PAGEABLE, 0);
    when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
    when(jobOrderRepository.findAssignedJobOrders(
            List.of(JobOrderStatus.IN_PROGRESS), CALLER_ID, PAGEABLE))
        .thenReturn(page);
    when(jobOrderStockProjectionService.mapPageWithStock(page))
        .thenReturn(new PageImpl<>(List.of(), PAGEABLE, 0));

    queryService.getAssignedJobOrders(List.of(JobOrderStatus.IN_PROGRESS), PAGEABLE);

    verify(jobOrderRepository)
        .findAssignedJobOrders(List.of(JobOrderStatus.IN_PROGRESS), CALLER_ID, PAGEABLE);
  }

  @Test
  void anUnidentifiedCallerGetsAnEmptyPageWithoutQuerying() {
    when(authHelper.currentUserId()).thenReturn(Optional.empty());

    Page<JobOrderDto> result = queryService.getAssignedJobOrders(null, PAGEABLE);

    assertThat(result.getTotalElements()).isZero();
    verifyNoInteractions(jobOrderRepository);
  }
}
