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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderHandoverReportService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderHandoverService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemBlueprintOwnersService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemProductionService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialDemandService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.JobOrderInventoryOwnerRedactor;
import java.time.Instant;
import java.time.LocalDateTime;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure-Mockito unit tests for {@link JobOrderController} — no Spring context and no Docker
 * dependency, so they pin the controller-edge contracts in isolation.
 *
 * <p>Four non-pass-through behaviours need explicit coverage because regressing them is silent at
 * the type level:
 *
 * <ul>
 *   <li>{@link JobOrderController#addAssignee} / {@link JobOrderController#removeAssignee} resolve
 *       the self-vs-logistician decision at the HTTP boundary via {@code
 *       authHelperService.isLogisticianOrAbove()}. Self-assignment must always work; assigning a
 *       different user requires LOGISTICIAN-or-above (or higher via role hierarchy). The 403 path
 *       is the spot where moving the check into the service would break the ArchUnit rule.
 *   <li>{@link JobOrderController#downloadHandoverReport} parses the optional {@code
 *       X-User-Time-Zone} header. An invalid IANA zone is silently dropped (and the service falls
 *       back to UTC) — never propagated as a {@code DateTimeException} to the caller. The
 *       PDF/Content-Disposition headers are also pinned because they are the entire response
 *       contract for the download endpoint.
 *   <li>{@link JobOrderController#getAllJobOrders} accepts an explicit status filter list; the
 *       service receives it verbatim and the {@code PageResponse} envelope carries the sort tokens
 *       through {@code PaginationUtil.toSortStrings}. Default-empty filter must reach the service
 *       as {@code null}, not an empty list, otherwise the SQL {@code IN ()} clause yields no rows
 *       and the queue page would always be empty.
 *   <li>{@link JobOrderController#createJobOrder} is annotated {@code permitAll()}; the controller
 *       method itself must never consult the JWT helper or the authHelperService — that decision is
 *       intentional so that an unauthenticated squadron member can file a request via the public
 *       form.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobOrderControllerTest {

  @Mock private JobOrderService jobOrderService;
  @Mock private JobOrderQueryService jobOrderQueryService;
  @Mock private JobOrderMaterialDemandService jobOrderMaterialDemandService;
  @Mock private JobOrderItemBlueprintOwnersService jobOrderItemBlueprintOwnersService;
  @Mock private JobOrderHandoverService jobOrderHandoverService;
  @Mock private JobOrderItemProductionService jobOrderItemProductionService;
  @Mock private JobOrderHandoverReportService jobOrderHandoverReportService;
  @Mock private UserService userService;
  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;

  @InjectMocks private JobOrderController controller;

  private static Jwt jwt(String sub) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(sub)
        .claim("sub", sub)
        .build();
  }

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
        false);
  }

  @Test
  void createJobOrder_passesTheServiceResultThroughUnredacted() {
    CreateJobOrderDto request = new CreateJobOrderDto(null, null, "alice", null, List.of(), null);
    JobOrderDto created = jobOrderDto(UUID.randomUUID());
    when(jobOrderService.createJobOrder(request)).thenReturn(created);

    JobOrderDto result = controller.createJobOrder(request);

    assertThat(result).isSameAs(created);
    verifyNoInteractions(userService, authHelperService);
  }

  @Test
  void getItemBlueprintOwners_delegatesToServiceAndReturnsResult() {
    UUID id = UUID.randomUUID();
    JobOrderItemBlueprintOwnersDto coverage =
        new JobOrderItemBlueprintOwnersDto(List.of(), List.of());
    when(jobOrderItemBlueprintOwnersService.getBlueprintOwners(id)).thenReturn(coverage);

    JobOrderItemBlueprintOwnersDto result = controller.getItemBlueprintOwners(id);

    assertThat(result).isSameAs(coverage);
    verify(jobOrderItemBlueprintOwnersService).getBlueprintOwners(id);
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
            eq(List.of(JobOrderStatus.OPEN)), eq(null), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(List.of(JobOrderStatus.OPEN), null, 0, 20, "priority,asc");

    assertThat(result.content()).containsExactly(dto);
    assertThat(result.sort()).isNotEmpty();
    verify(jobOrderQueryService)
        .getAllJobOrders(eq(List.of(JobOrderStatus.OPEN)), eq(null), any(Pageable.class));
  }

  @Test
  void getAllJobOrders_nullStatusFilter_reachesServiceAsNullNotEmptyList() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAllJobOrders(eq(null), eq(null), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(null, null, 0, 20, "priority,asc");

    assertThat(result.content()).containsExactly(dto);
    verify(jobOrderQueryService).getAllJobOrders(eq(null), eq(null), any(Pageable.class));
  }

  @Test
  void getAllJobOrders_forwardsSquadronIdFilter() {
    JobOrderDto dto = jobOrderDto(UUID.randomUUID());
    List<UUID> squadronIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    Page<JobOrderDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(jobOrderQueryService.getAllJobOrders(
            eq(List.of(JobOrderStatus.OPEN)), eq(squadronIds), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<JobOrderDto> result =
        controller.getAllJobOrders(
            List.of(JobOrderStatus.OPEN), squadronIds, 0, 20, "priority,asc");

    assertThat(result.content()).containsExactly(dto);
    verify(jobOrderQueryService)
        .getAllJobOrders(eq(List.of(JobOrderStatus.OPEN)), eq(squadronIds), any(Pageable.class));
  }

  @Test
  void getMaterialDemand_returnsTheAggregatedOverviewUnchanged() {
    MaterialDemandOverviewDto overview =
        new MaterialDemandOverviewDto(
            List.of(
                new MaterialDemandGroupDto(
                    new SquadronReferenceDto(UUID.randomUUID(), "Iridium", "IRI"), List.of())));
    when(jobOrderMaterialDemandService.getMaterialDemandOverview()).thenReturn(overview);

    MaterialDemandOverviewDto result = controller.getMaterialDemand();

    assertThat(result).isSameAs(overview);
    verify(jobOrderMaterialDemandService).getMaterialDemandOverview();
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

    JobOrderDto result = controller.getJobOrderById(id);

    assertThat(result.withAssignees(dto.assignees())).isEqualTo(dto);
    if (dto.assignees() != null) {
      for (int i = 0; i < dto.assignees().size(); i++) {
        de.greluc.krt.profit.basetool.backend.model.dto.UserDto shaped =
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
    verify(ownerScopeService, never()).canSeeJobOrder(any(UUID.class));
  }

  @Test
  void getJobOrderById_requesterOnlyViewer_redactsProgressAssigneesAndAggregates() {
    UUID id = UUID.randomUUID();
    UUID matLineId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto matLine =
        new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto(
            matLineId, null, 650, 50.0, 999.0, java.util.Collections.singletonList(null), 12.0, 7L);
    JobOrderDto full =
        new JobOrderDto(
            id,
            42,
            null,
            null,
            "alice",
            "deliver to ArcCorp",
            1,
            JobOrderStatus.OPEN,
            JobOrderType.MATERIAL,
            true,
            List.of(matLine),
            java.util.Collections.singletonList(null),
            java.util.Collections.singletonList(null),
            java.util.Collections.singletonList(null),
            java.util.Collections.singletonList(null),
            java.util.Collections.singletonList(null),
            Instant.parse("2026-01-01T00:00:00Z"),
            9L,
            null,
            true);
    when(jobOrderQueryService.getJobOrderById(id)).thenReturn(full);

    JobOrderDto result = controller.getJobOrderById(id);

    assertThat(result.assignees()).isEmpty();
    assertThat(result.aggregatedMaterials()).isEmpty();
    assertThat(result.handovers()).isEmpty();
    assertThat(result.itemHandovers()).isEmpty();
    assertThat(result.materials()).hasSize(1);
    de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto redacted =
        result.materials().get(0);
    assertThat(redacted.id()).isEqualTo(matLineId);
    assertThat(redacted.minQuality()).isEqualTo(650);
    assertThat(redacted.amount()).isEqualTo(50.0);
    assertThat(redacted.version()).isEqualTo(7L);
    assertThat(redacted.currentStock()).isNull();
    assertThat(redacted.openAmount()).isNull();
    assertThat(redacted.claims()).isEmpty();
    assertThat(result.items()).hasSize(1);
    assertThat(result.id()).isEqualTo(id);
    assertThat(result.displayId()).isEqualTo(42);
    assertThat(result.handle()).isEqualTo("alice");
    assertThat(result.comment()).isEqualTo("deliver to ArcCorp");
    assertThat(result.version()).isEqualTo(9L);
    assertThat(result.redacted()).isTrue();
    verify(ownerScopeService, never()).canSeeJobOrder(any(UUID.class));
  }

  private static InventoryItemDto sampleInventoryItem() {
    return new InventoryItemDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        750,
        5.0,
        false,
        java.util.List.of(),
        0.0,
        java.util.List.of(),
        0.0,
        null,
        null,
        1L,
        null,
        null);
  }

  @Test
  void getInventoryItemsForJobOrderMaterial_responsibleSideViewer_delegatesUnredacted() {
    UUID jobOrderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    InventoryItemDto inv = sampleInventoryItem();
    when(jobOrderQueryService.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId))
        .thenReturn(List.of(inv));
    when(ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(true);

    List<InventoryItemDto> result =
        controller.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId);

    assertThat(result).containsExactly(inv);
    verify(inventoryOwnerRedactor, never()).redactInventoryItems(any());
  }

  @Test
  void getInventoryItemsForJobOrderMaterial_requestingSideViewer_redactsOwners() {
    UUID jobOrderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    List<InventoryItemDto> raw = List.of(sampleInventoryItem());
    List<InventoryItemDto> redacted = List.of(sampleInventoryItem());
    when(jobOrderQueryService.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId))
        .thenReturn(raw);
    when(ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(false);
    when(inventoryOwnerRedactor.redactInventoryItems(raw)).thenReturn(redacted);

    List<InventoryItemDto> result =
        controller.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId);

    assertThat(result).isSameAs(redacted);
    verify(inventoryOwnerRedactor).redactInventoryItems(raw);
  }

  @Test
  void getOrphanedLinkedInventory_requestingSideViewer_redactsOwners() {
    UUID jobOrderId = UUID.randomUUID();
    List<InventoryItemDto> raw = List.of(sampleInventoryItem());
    List<InventoryItemDto> redacted = List.of(sampleInventoryItem());
    when(jobOrderQueryService.getOrphanedLinkedInventory(jobOrderId)).thenReturn(raw);
    when(ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(false);
    when(inventoryOwnerRedactor.redactInventoryItems(raw)).thenReturn(redacted);

    List<InventoryItemDto> result = controller.getOrphanedLinkedInventory(jobOrderId);

    assertThat(result).isSameAs(redacted);
    verify(inventoryOwnerRedactor).redactInventoryItems(raw);
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

  @Test
  void unlinkMaterial_delegatesBothPathParameters() {
    UUID jobOrderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();

    controller.unlinkMaterial(jobOrderId, materialId);

    verify(jobOrderService).unlinkMaterial(jobOrderId, materialId);
  }

  @Test
  void unlinkInventoryItem_delegatesBothPathParameters() {
    UUID jobOrderId = UUID.randomUUID();
    UUID inventoryItemId = UUID.randomUUID();

    controller.unlinkInventoryItem(jobOrderId, inventoryItemId);

    verify(jobOrderService).unlinkInventoryItem(jobOrderId, inventoryItemId);
  }

  @Test
  void addAssignee_self_doesNotConsultRoleHelper() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderService.addAssignee(jobOrderId, callerId)).thenReturn(persisted);

    JobOrderDto result = controller.addAssignee(jobOrderId, callerId, jwt);

    assertThat(result).isSameAs(persisted);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

  @Test
  void addAssignee_otherUser_logistician_isAllowed() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(jobOrderService.addAssignee(jobOrderId, targetUserId)).thenReturn(persisted);

    JobOrderDto result = controller.addAssignee(jobOrderId, targetUserId, jwt);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void addAssignee_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertThatThrownBy(() -> controller.addAssignee(jobOrderId, targetUserId, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).addAssignee(any(), any());
  }

  @Test
  void removeAssignee_self_doesNotConsultRoleHelper() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderService.removeAssignee(jobOrderId, callerId)).thenReturn(persisted);

    JobOrderDto result = controller.removeAssignee(jobOrderId, callerId, jwt);

    assertThat(result).isSameAs(persisted);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

  @Test
  void removeAssignee_otherUser_logistician_isAllowed() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(jobOrderService.removeAssignee(jobOrderId, targetUserId)).thenReturn(persisted);

    JobOrderDto result = controller.removeAssignee(jobOrderId, targetUserId, jwt);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void removeAssignee_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertThatThrownBy(() -> controller.removeAssignee(jobOrderId, targetUserId, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).removeAssignee(any(), any());
  }

  @Test
  void setAssigneeNote_self_doesNotConsultRoleHelper() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    JobOrderController.AssigneeNoteRequest body =
        new JobOrderController.AssigneeNoteRequest("works Friday", 2L);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderService.updateAssigneeNote(jobOrderId, callerId, "works Friday", 2L))
        .thenReturn(persisted);

    JobOrderDto result = controller.setAssigneeNote(jobOrderId, callerId, body, jwt);

    assertThat(result).isSameAs(persisted);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

  @Test
  void setAssigneeNote_otherUser_logistician_isAllowed() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    JobOrderController.AssigneeNoteRequest body =
        new JobOrderController.AssigneeNoteRequest("note", null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(jobOrderService.updateAssigneeNote(jobOrderId, targetUserId, "note", null))
        .thenReturn(persisted);

    JobOrderDto result = controller.setAssigneeNote(jobOrderId, targetUserId, body, jwt);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void setAssigneeNote_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderController.AssigneeNoteRequest body =
        new JobOrderController.AssigneeNoteRequest("note", null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertThatThrownBy(() -> controller.setAssigneeNote(jobOrderId, targetUserId, body, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).updateAssigneeNote(any(), any(), any(), any());
  }

  @Test
  void deleteAssigneeNote_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertThatThrownBy(() -> controller.deleteAssigneeNote(jobOrderId, targetUserId, 1L, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).deleteAssigneeNote(any(), any(), any());
  }

  @Test
  void createHandover_delegatesToHandoverServiceWithBody() {
    UUID jobOrderId = UUID.randomUUID();
    JobOrderHandoverCreateDto dto =
        new JobOrderHandoverCreateDto(
            Instant.parse("2026-05-01T12:00:00Z"), "alice", "DAS KARTELL", List.of());
    JobOrderHandoverDto persisted =
        new JobOrderHandoverDto(
            UUID.randomUUID(),
            jobOrderId,
            dto.handoverTime(),
            "alice",
            "DAS KARTELL",
            null,
            null,
            List.of(),
            1L);
    when(jobOrderHandoverService.createHandover(jobOrderId, dto)).thenReturn(persisted);

    JobOrderHandoverDto result = controller.createHandover(jobOrderId, dto);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderHandoverService).createHandover(jobOrderId, dto);
  }

  @Test
  void bookProduction_delegatesToService() {
    UUID jobOrderId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            3,
            7L,
            List.of(),
            List.of(),
            new JobOrderItemProductionCreateDto.BookInDto(
                UUID.randomUUID(), null, null, false, true));
    JobOrderItemDto persisted =
        new JobOrderItemDto(itemId, null, null, 5, 3, 0, null, List.of(), false, 8L);
    when(jobOrderItemProductionService.bookProduction(jobOrderId, itemId, dto))
        .thenReturn(persisted);

    JobOrderItemDto result = controller.bookProduction(jobOrderId, itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderItemProductionService).bookProduction(jobOrderId, itemId, dto);
  }

  @Test
  void downloadHandoverReport_validTimeZone_passedToService() {
    UUID jobOrderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    when(jobOrderHandoverReportService.generateHandoverReport(
            eq(jobOrderId), eq(handoverId), eq(java.time.ZoneId.of("Europe/Berlin"))))
        .thenReturn(pdf);

    ResponseEntity<byte[]> response =
        controller.downloadHandoverReport(
            jobOrderId, handoverId, java.time.ZoneId.of("Europe/Berlin"));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(pdf);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(headers.getContentDisposition().getFilename())
        .isEqualTo("uebergabeprotokoll-" + jobOrderId + ".pdf");
  }

  @Test
  void downloadHandoverReport_nullTimeZoneHeader_passedAsNull() {
    UUID jobOrderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    when(jobOrderHandoverReportService.generateHandoverReport(jobOrderId, handoverId, null))
        .thenReturn(pdf);

    ResponseEntity<byte[]> response =
        controller.downloadHandoverReport(jobOrderId, handoverId, null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(jobOrderHandoverReportService).generateHandoverReport(jobOrderId, handoverId, null);
  }

  @Test
  void previewHandoverReport_returnsPdfWithPreviewFilename() {
    UUID jobOrderId = UUID.randomUUID();
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#42", LocalDateTime.of(2026, 5, 1, 12, 0), "alice", List.of());
    byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    when(jobOrderHandoverReportService.generateHandoverReportPreview(dto)).thenReturn(pdf);

    ResponseEntity<byte[]> response = controller.previewHandoverReport(jobOrderId, dto);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(pdf);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(headers.getContentDisposition().getFilename())
        .isEqualTo("uebergabeprotokoll-vorschau.pdf");
  }
}
