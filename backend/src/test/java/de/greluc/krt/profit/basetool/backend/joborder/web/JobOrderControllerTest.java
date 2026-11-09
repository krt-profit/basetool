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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.internal.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Pure-Mockito unit tests for the queue, read, update and delete endpoints of {@link
 * JobOrderController}.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderControllerTest {

  @Mock private JobOrderService jobOrderService;
  @Mock private JobOrderQueryService jobOrderQueryService;
  @Mock private JobOrderAccessPolicy jobOrderAccessPolicy;

  @InjectMocks private JobOrderController controller;

  private static JobOrderDto jobOrderDto(UUID id) {
    return new JobOrderDto(
        id,
        1,
        null,
        null,
        "alice",
        "deliver to ArcCorp",
        1,
        JobOrderStatus.OPEN,
        JobOrderType.MATERIAL,
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"),
        1L,
        null,
        null,
        false,
        false,
        null,
        false);
  }

  @Test
  void createJobOrder_passesTheServiceResultThroughUnredacted() {
    CreateJobOrderDto request = new CreateJobOrderDto(null, null, "alice", null, List.of(), null);
    JobOrderDto created = jobOrderDto(UUID.randomUUID());
    when(jobOrderService.createJobOrder(request)).thenReturn(created);

    JobOrderDto result = controller.createJobOrder(request);

    assertThat(result).isSameAs(created);
    verifyNoInteractions(jobOrderAccessPolicy);
  }

  @Test
  void getAllJobOrders_forwardsStatusFilterAndPageable() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    Page<JobOrderDto> page =
        new PageImpl<>(
            List.of(dto),
            PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("priority")),
            1);
    when(jobOrderQueryService.getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            eq(null),
            eq(false),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(
            List.of(JobOrderStatus.OPEN), null, false, false, null, 0, 20, "priority,asc", null);

    assertThat(result.content()).containsExactly(dto);
    assertThat(result.sort()).isNotEmpty();
    verify(jobOrderQueryService)
        .getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            eq(null),
            eq(false),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
  }

  @Test
  void getAllJobOrders_nullStatusFilter_reachesServiceAsNullNotEmptyList() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAllJobOrders(
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(null, null, false, false, null, 0, 20, "priority,asc", null);

    assertThat(result.content()).containsExactly(dto);
    verify(jobOrderQueryService)
        .getAllJobOrders(
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
  }

  @Test
  void getAllJobOrders_forwardsSquadronIdFilter() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    List<UUID> squadronIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            eq(squadronIds),
            eq(false),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(
            List.of(JobOrderStatus.OPEN),
            squadronIds,
            false,
            false,
            null,
            0,
            20,
            "priority,asc",
            null);

    assertThat(result.content()).containsExactly(dto);
    verify(jobOrderQueryService)
        .getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            eq(squadronIds),
            eq(false),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
  }

  @Test
  void getAllJobOrders_forwardsToProcessFlag() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            eq(null),
            eq(true),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(
            List.of(JobOrderStatus.OPEN), null, true, false, null, 0, 20, "priority,asc", null);

    assertThat(result.content()).containsExactly(dto);
    verify(jobOrderQueryService)
        .getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            eq(null),
            eq(true),
            eq(null),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
  }

  @Test
  void getAllJobOrders_forwardsTheProcessingOrgUnit() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    UUID unit = UUID.randomUUID();
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAllJobOrders(
            eq(null),
            eq(null),
            eq(true),
            eq(unit),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(null, null, true, false, unit, 0, 20, "priority,asc", null);

    assertThat(result.content()).containsExactly(dto);
  }

  @Test
  void getAllJobOrders_forwardsAssignedToMe() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAssignedJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(
            List.of(JobOrderStatus.OPEN), null, false, true, null, 0, 20, "priority,asc", null);

    assertThat(result.content()).containsExactly(dto);
    verify(jobOrderQueryService)
        .getAssignedJobOrders(
            eq(List.of(JobOrderStatus.OPEN)),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
    verify(jobOrderQueryService, never())
        .getAllJobOrders(
            any(),
            any(),
            anyBoolean(),
            any(),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
  }

  @Test
  void getAllJobOrders_assignedToMeWithToProcess_isRejected() {
    assertThatThrownBy(() ->
            controller.getAllJobOrders(null, null, true, true, null, 0, 20, "priority,asc", null))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(jobOrderQueryService);
  }

  @Test
  void getAllJobOrders_assignedToMeWithSquadronFilter_isRejected() {
    List<UUID> squadronIds = List.of(UUID.randomUUID());

    assertThatThrownBy(() ->
            controller.getAllJobOrders(
                null, squadronIds, false, true, null, 0, 20, "priority,asc", null))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(jobOrderQueryService);
  }

  @Test
  void lookupJobOrders_delegatesToServiceReferenceQuery() {
    JobOrderReferenceDto ref =
        new JobOrderReferenceDto(
            UUID.randomUUID(),
            42,
            "alice",
            JobOrderStatus.OPEN,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    when(jobOrderQueryService.findAllActiveReference(false)).thenReturn(List.of(ref));

    List<JobOrderReferenceDto> result = controller.lookupJobOrders(false);

    assertThat(result).containsExactly(ref);
    verify(jobOrderQueryService).findAllActiveReference(false);
  }

  @Test
  void getJobOrderById_fullViewerKeepsEveryFieldButPeerShapesTheAssignees() {
    UUID id = UUID.randomUUID();
    JobOrderDto dto = jobOrderDto(id);
    when(jobOrderQueryService.getJobOrderById(id)).thenReturn(dto);
    when(jobOrderAccessPolicy.canManageAssignees(id)).thenReturn(true);

    JobOrderDto result = controller.getJobOrderById(id);

    assertThat(result.canManageAssignees()).isTrue();
    assertThat(result.withAssignees(dto.assignees()).withCanManageAssignees(null)).isEqualTo(dto);
    if (dto.assignees() != null) {
      for (int i = 0; i < dto.assignees().size(); i++) {
        de.greluc.krt.profit.basetool.backend.identity.api.UserDto shaped =
            result.assignees().get(i).user();
        if (shaped != null) {
          assertThat(shaped.roles()).isNull();
          assertThat(shaped.permissions()).isNull();
          assertThat(shaped.description()).isNull();
          assertThat(shaped.joinDate()).isNull();
          assertThat(shaped.discordLinked()).isNull();
        }
      }
    }
    verify(jobOrderAccessPolicy, never()).canSeeJobOrder(any(UUID.class));
  }

  @Test
  void getJobOrderById_preservesTheSameCompleteFactsForRequesterReaders() {
    UUID id = UUID.randomUUID();
    JobOrderDto full = jobOrderDto(id);
    when(jobOrderQueryService.getJobOrderById(id)).thenReturn(full);

    JobOrderDto result = controller.getJobOrderById(id);

    assertThat(result.withCanManageAssignees(full.canManageAssignees())).isEqualTo(full);
    assertThat(result.materials()).isEqualTo(full.materials());
    assertThat(result.handovers()).isEqualTo(full.handovers());
  }

  @Test
  void updateJobOrderStatus_forwardsBodyVersionToService() {
    UUID id = UUID.randomUUID();
    UpdateJobOrderStatusDto dto = new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, 7L);
    JobOrderDto persisted = jobOrderDto(id);
    when(jobOrderService.updateJobOrderStatus(id, dto)).thenReturn(persisted);

    JobOrderDto result = controller.updateJobOrderStatus(id, dto);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderService).updateJobOrderStatus(id, dto);
  }

  @Test
  void updateJobOrderPriority_forwardsRequestParamToService() {
    UUID id = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(id);
    when(jobOrderService.updateJobOrderPriority(id, 3)).thenReturn(persisted);

    JobOrderDto result = controller.updateJobOrderPriority(id, 3);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderService).updateJobOrderPriority(id, 3);
  }

  @Test
  void updateBlueprintVariantCounting_forwardsModeAndVersionToService() {
    UUID id = UUID.randomUUID();
    UpdateJobOrderBlueprintCountingDto dto = new UpdateJobOrderBlueprintCountingDto(false, 7L);
    JobOrderDto persisted = jobOrderDto(id);
    when(jobOrderService.updateBlueprintVariantCounting(id, false, 7L)).thenReturn(persisted);

    JobOrderDto result = controller.updateBlueprintVariantCounting(id, dto);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderService).updateBlueprintVariantCounting(id, false, 7L);
  }

  @Test
  void updateJobOrder_forwardsBodyToService() {
    UUID id = UUID.randomUUID();
    CreateJobOrderDto updateDto = new CreateJobOrderDto(null, null, "bob", null, List.of(), 1L);
    JobOrderDto persisted = jobOrderDto(id);
    when(jobOrderService.updateJobOrder(id, updateDto)).thenReturn(persisted);

    JobOrderDto result = controller.updateJobOrder(id, updateDto);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void deleteJobOrder_delegatesToService() {
    UUID id = UUID.randomUUID();

    controller.deleteJobOrder(id);

    verify(jobOrderService).deleteJobOrder(id);
  }
}
