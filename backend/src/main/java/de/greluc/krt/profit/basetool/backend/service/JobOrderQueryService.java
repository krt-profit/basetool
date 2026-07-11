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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query/projection half of the job-order domain, split out of {@link JobOrderService}
 * (audit Thema 7, #14). It owns every caller-visible read — the scoped paged list, the
 * requester-side "Meine Auftr\u00e4ge" list, the reference typeahead, the single-order detail and
 * the two link-inventory pickers — while the create / update / status / assignee / delete writes,
 * the priority reorder and the {@code completeJobOrderWithinTransaction} MANDATORY hop stay in
 * {@link JobOrderService}.
 *
 * <p>Job Orders are a <em>conditionally</em> staffel-scoped aggregate (SK-responsible orders are
 * public, squadron-responsible orders private); every read here pushes {@link
 * OwnerScopeService#currentScopePredicate()} / {@code canViewJobOrders} / {@code canSeeJobOrder}
 * into the query so a caller can never page or drill past their visibility. This is the sole reason
 * the service is on the {@code staffelScopedServicesMustWireOwnerScopeOrAuthHelper} whitelist (it
 * took over that role from {@link JobOrderService}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderQueryService {

  private final JobOrderRepository jobOrderRepository;
  private final MaterialRepository materialRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final OwnerScopeService ownerScopeService;
  private final JobOrderMapper jobOrderMapper;
  private final de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper squadronMapper;
  private final JobOrderItemService jobOrderItemService;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;
  private final de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper
      inventoryItemMapper;

  /**
   * Paged list with optional status filter. Status is the primary discriminator the UI offers as a
   * filter; without it the call returns every status.
   *
   * <p>Delegates to {@link #getAllJobOrders(List, UUID, Pageable)} with a {@code null} squadron
   * display filter — the visibility scope (Phase 3, #343) is always applied regardless.
   *
   * @param statuses optional status filter; null/empty means "all"
   * @param pageable page request
   * @return paged job orders as DTOs
   */
  public Page<JobOrderDto> getAllJobOrders(List<JobOrderStatus> statuses, Pageable pageable) {
    return getAllJobOrders(statuses, null, pageable);
  }

  /**
   * Paged list with optional status filter and an optional squadron display filter, always
   * constrained to the caller's visibility scope (Phase 3, #343).
   *
   * <p>Job Orders are a <em>conditionally</em> staffel-scoped aggregate: an SK-responsible order is
   * public to every squadron, a squadron-responsible order is private to that squadron + admins
   * (the requester does not grant visibility). The scope is resolved from {@link
   * OwnerScopeService#currentScopePredicate()} and pushed into the repository query so a caller can
   * never page past their visibility — admins without a pin see everything, an admin pinned to a
   * squadron (or any non-admin member) sees that scope's private orders plus all SK orders.
   *
   * <p>Layered on top is the viewer-side profit gate ({@link
   * OwnerScopeService#canViewJobOrders()}): a caller who belongs to no profit-eligible org unit
   * (and is not an admin) is not part of the order workflow and receives an empty page — the
   * SK-public union is suppressed for them too.
   *
   * <p>The {@code squadronId} parameter is a pure UI display preference layered on top of the scope
   * (the orders-index "involving my squadron" toggle, matching responsible OR requesting side); it
   * can only narrow the already-scoped result, never widen it.
   *
   * @param statuses optional status filter; null/empty means "all"
   * @param squadronId optional display filter (matches responsible OR requesting); null means "no
   *     display restriction"
   * @param pageable page request
   * @return paged job orders as DTOs, scoped to the caller's visibility
   */
  public Page<JobOrderDto> getAllJobOrders(
      List<JobOrderStatus> statuses, UUID squadronId, Pageable pageable) {
    // Viewer-side profit gate: only members of a profit-eligible org unit (or admins) may see the
    // order queue at all. A non-profit caller gets an empty page instead of the SK-public union, so
    // the list stays invisible to them — the create flow stays open elsewhere. Mirrors the detail
    // gate folded into OwnerScopeService.canSeeJobOrder.
    if (!ownerScopeService.canViewJobOrders()) {
      return Page.empty(pageable);
    }
    // Pass the full enum set when no status filter is requested so the repository's IN clause is
    // never bound with an empty collection (mirrors searchMissions); the boolean-flag alternative
    // would still have to bind an empty list, which JPQL renders inconsistently across dialects.
    List<JobOrderStatus> effectiveStatuses =
        (statuses == null || statuses.isEmpty()) ? List.of(JobOrderStatus.values()) : statuses;
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Page<JobOrder> page =
        jobOrderRepository.findScopedJobOrders(
            effectiveStatuses,
            squadronId,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable);

    // The whole-page per-row enrichment (batched stock + SK claims, REQ-DATA-003) lives in the
    // extracted projection service alongside the single-order path, so both behave identically.
    return jobOrderStockProjectionService.mapPageWithStock(page);
  }

  /**
   * Paged list of the orders the caller's own org unit(s) <em>requested</em> — the requester-side
   * "Meine Auftr&auml;ge" list (REQ-ORDERS-023). Returns every order whose requesting org unit is
   * one the caller is a <em>direct</em> member of, regardless of profit eligibility and independent
   * of the responsible-scoped queue in {@link #getAllJobOrders(List, UUID, Pageable)} (which never
   * grants the requester side visibility). Each returned DTO is redacted for the requester at the
   * controller boundary (no Bearbeiter, no materials summary). An anonymous / memberless caller
   * gets an empty page.
   *
   * @param statuses optional status filter; null/empty means "all"
   * @param pageable page request
   * @return paged job orders the caller's org unit(s) requested, scoped to direct membership
   */
  public Page<JobOrderDto> getRequestedJobOrders(List<JobOrderStatus> statuses, Pageable pageable) {
    Set<UUID> requesterOrgUnitIds = ownerScopeService.currentDirectMembershipOrgUnitIds();
    if (requesterOrgUnitIds.isEmpty()) {
      return Page.empty(pageable);
    }
    List<JobOrderStatus> effectiveStatuses =
        (statuses == null || statuses.isEmpty()) ? List.of(JobOrderStatus.values()) : statuses;
    Page<JobOrder> page =
        jobOrderRepository.findRequestedOrders(effectiveStatuses, requesterOrgUnitIds, pageable);
    return jobOrderStockProjectionService.mapPageWithStock(page);
  }

  /**
   * Lightweight reference projection used by typeaheads and refinery-order pickers (only id +
   * display-id + summary). Filtered to active (non-completed/-rejected) orders and, like the main
   * list endpoint, to the caller's visibility: a non-profit member (no {@code canViewJobOrders()})
   * gets an empty list, and squadron-private orders of other squadrons are filtered out so the
   * typeahead cannot enumerate a foreign squadron's order handle + materials (audit M-2).
   *
   * @return active job orders the caller may see, as reference DTOs
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto>
      findAllActiveReference() {
    // M-2: mirror the list endpoint's controls. Viewer-side profit gate first (a non-profit member
    // sees nothing, not even the SK-public union), then per-row visibility scope on the loaded
    // rows.
    if (!ownerScopeService.canViewJobOrders()) {
      return List.of();
    }
    return jobOrderRepository.findAllActiveWithMaterials().stream()
        .filter(ownerScopeService::canSeeJobOrder)
        .map(
            o ->
                new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto(
                    o.getId(),
                    o.getDisplayId(),
                    o.getHandle(),
                    o.getStatus(),
                    squadronMapper.orgUnitToReferenceDto(o.getRequestingOrgUnit()),
                    o.getMaterials() != null
                        ? o.getMaterials().stream().map(jobOrderMapper::toDto).toList()
                        : List.of(),
                    // Both order kinds: ITEM orders have no job_order_material rows, so the picker
                    // must use the kind-agnostic required-material set to filter correctly (#71
                    // orphan-link fix, REQ-ORDERS-018).
                    List.copyOf(jobOrderItemService.requiredMaterialIds(o))))
        .toList();
  }

  /**
   * Returns the order as a DTO.
   *
   * @param id job order primary key
   * @return the order as a DTO
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public JobOrderDto getJobOrderById(UUID id) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + id));
    JobOrderDto dto = jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
    // Stamp the per-order redaction decision here, computed from the ALREADY-LOADED entity via the
    // managed-entity gate overload — so the controller no longer re-evaluates canSeeJobOrder(id)
    // (review finding 4) and the flag lets the frontend key its detail rendering off THIS order
    // rather than a global capability (review finding 2, REQ-ORDERS-023). The actual field
    // stripping
    // still happens at the HTTP boundary (JobOrderController#cleanupJobOrderForRequester) when the
    // flag is set; a full viewer keeps the complete view.
    return ownerScopeService.canSeeJobOrder(jobOrder) ? dto : dto.withRedacted(true);
  }

  /**
   * Returns the inventory items eligible for linking to a job order's given material. Used by the
   * order-detail page's "link inventory" picker. The eligibility check filters by material and
   * minimum quality declared on the order's material row, and excludes items already linked to
   * another order.
   *
   * @param jobOrderId target job order
   * @param materialId target material on that order
   * @return list of inventory items as DTOs
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto>
      getInventoryItemsForJobOrderMaterial(UUID jobOrderId, UUID materialId) {
    // Existence guards: load only to surface a 404 for an unknown order / material; the query below
    // filters by the ids directly, so the entities themselves are not needed (#1256 review).
    jobOrderRepository
        .findById(jobOrderId)
        .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));
    materialRepository
        .findById(materialId)
        .orElseThrow(() -> new NotFoundException("Material not found: " + materialId));

    return inventoryItemRepository.findByJobOrderIdAndMaterialId(jobOrderId, materialId).stream()
        .map(inventoryItemMapper::toDto)
        .sorted(
            java.util.Comparator.comparing(
                    (de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto item) ->
                        item.user() != null && item.user().effectiveName() != null
                            ? item.user().effectiveName()
                            : "",
                    java.util.Comparator.naturalOrder())
                .thenComparing(
                    item -> item.quality() != null ? item.quality() : 0,
                    java.util.Comparator.reverseOrder())
                .thenComparing(
                    item ->
                        item.location() != null && item.location().name() != null
                            ? item.location().name()
                            : "",
                    java.util.Comparator.naturalOrder())
                .thenComparing(
                    item -> item.amount() != null ? item.amount() : 0.0,
                    java.util.Comparator.reverseOrder()))
        .toList();
  }

  /**
   * Returns the inventory items linked to the order whose material the order does <em>not</em>
   * require — "orphaned" links (REQ-ORDERS-019). Because an order's material view is built only
   * from its requirements, such a link binds stock to the order while staying invisible in every
   * material row; surfacing it lets a logistician spot and undo a mis-assignment (e.g. a material
   * linked from the Lager before the link gate of REQ-ORDERS-018 existed). Each linked item's
   * material is compared against the kind-agnostic required-material set ({@link
   * JobOrderItemService#requiredMaterialIds(JobOrder)}), so it is correct for ITEM orders too.
   *
   * @param jobOrderId the order to inspect.
   * @return the orphaned linked inventory items as DTOs, ordered like the per-material drill-down;
   *     empty when every linked item matches a requirement.
   * @throws NotFoundException when the order does not exist.
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto>
      getOrphanedLinkedInventory(UUID jobOrderId) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));
    Set<UUID> required = jobOrderItemService.requiredMaterialIds(jobOrder);
    return inventoryItemRepository.findByJobOrderIdOrdered(jobOrderId).stream()
        .filter(
            item -> item.getMaterial() == null || !required.contains(item.getMaterial().getId()))
        .map(inventoryItemMapper::toDto)
        .toList();
  }
}
