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

import de.greluc.krt.profit.basetool.backend.identity.api.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssigneeDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.internal.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over the job-order aggregate (the request-and-fulfill queue): create, list, read,
 * update and delete.
 *
 * <p>Reads and mutations are limited to the caller's visibility scope via {@code
 * @accessGateService}: SK-responsible orders are public, squadron-responsible orders private to
 * that squadron and admins. Creation needs a login only (ADR-0149); other mutations require
 * LOGISTICIAN or above, delete requires ADMIN. Handovers live in {@link
 * JobOrderHandoverController}, materials and linked stock in {@link JobOrderMaterialController}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderController {
  private final JobOrderService jobOrderService;
  private final JobOrderQueryService jobOrderQueryService;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;

  /**
   * Creates an order through a current contextual writer of either proposed unit.
   *
   * @param dto create payload
   * @return the persisted DTO
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a new job order",
      description = "Creates a job order through a current writer of either proposed unit.")
  @PreAuthorize("isAuthenticated()")
  public JobOrderDto createJobOrder(@RequestBody @Valid CreateJobOrderDto dto) {
    return jobOrderService.createJobOrder(dto);
  }

  /**
   * Creates a new item-based job order. The required materials are derived from each item's chosen
   * blueprint and snapshotted onto the order.
   *
   * @param dto item-order create payload (ordered finished items + per-material quality choices)
   * @return the persisted DTO
   */
  @PostMapping("/items")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a new item job order",
      description =
          "Creates an item-based job order; the required materials are derived from each ordered"
              + " item's blueprint.")
  @PreAuthorize("isAuthenticated()")
  public JobOrderDto createItemJobOrder(@RequestBody @Valid CreateJobOrderItemRequestDto dto) {
    return jobOrderService.createItemJobOrder(dto);
  }

  /**
   * Paged job-order list, by default sorted by {@code priority,asc}, limited to the caller's
   * visibility scope. {@code squadronId} can only narrow that scope, never widen it.
   *
   * @param status optional status filter (logical OR across values)
   * @param squadronId optional repeatable filter on the responsible or requesting org unit; absent
   *     means the full scoped view
   * @param toProcess when {@code true}, keep only the orders one of the caller's own units
   *     processes, the "Zu bearbeiten" queue (REQ-ORDERS-040); narrows the scope, never widens it
   * @param assignedToMe when {@code true}, list only the orders the caller holds an assignee entry
   *     on, the "Ich bearbeite" segment (REQ-ORDERS-040); combines with neither {@code toProcess}
   *     nor {@code squadronId}
   * @param orgUnitId with {@code toProcess}, keep only the orders this one of the caller's own
   *     units processes; never widens the caller's processing units
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (whitelisted: {@code priority}, {@code createdAt})
   * @param type optional kind filter; absent means all implemented kinds
   * @return paged job-order DTOs visible to the caller
   */
  @GetMapping
  @Operation(
      summary = "Get all job orders",
      description =
          "Returns a paginated list of job orders. toProcess=true keeps only the orders whose"
              + " active Profit-hierarchy responsible unit is one of the caller's own units;"
              + " orgUnitId narrows that to one of them. assignedToMe=true lists the orders the"
              + " caller is an assignee of; it combines with neither toProcess nor squadronId.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public PageResponse<JobOrderDto> getAllJobOrders(
      @RequestParam(required = false) List<JobOrderStatus> status,
      @RequestParam(required = false) List<UUID> squadronId,
      @RequestParam(required = false, defaultValue = "false") boolean toProcess,
      @RequestParam(required = false, defaultValue = "false") boolean assignedToMe,
      @RequestParam(required = false) UUID orgUnitId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "priority,asc") String sort,
      @RequestParam(required = false) List<JobOrderType> type) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("priority", "createdAt"), "priority");
    if (assignedToMe) {
      if (toProcess || (squadronId != null && !squadronId.isEmpty())) {
        throw new BadRequestException(
            "assignedToMe combines with neither toProcess nor squadronId");
      }
      return PageResponse.of(jobOrderQueryService.getAssignedJobOrders(status, type, pageable));
    }
    Page<JobOrderDto> p =
        jobOrderQueryService.getAllJobOrders(
            status, squadronId, toProcess, orgUnitId, type, pageable);
    return PageResponse.of(p);
  }

  /**
   * Paged, complete list of the orders the caller's own org unit(s) requested, the "Meine
   * Auftr&auml;ge" list (REQ-ORDERS-023). Independent of the profit-gated main queue.
   *
   * @param status optional status filter (logical OR across values)
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (whitelisted: {@code priority}, {@code createdAt})
   * @param type optional kind filter; absent means all implemented kinds
   * @return paged, complete job-order DTOs the caller's org unit(s) requested
   */
  @GetMapping("/requested")
  @Operation(
      summary = "Get my requested job orders",
      description =
          "Returns a paginated list of the orders the caller's own org unit(s) requested"
              + " with complete permitted order facts.")
  @PreAuthorize("isAuthenticated() and @requestScopeResolver.canViewOwnJobOrders()")
  @Transactional(readOnly = true)
  public PageResponse<JobOrderDto> getRequestedJobOrders(
      @RequestParam(required = false) List<JobOrderStatus> status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "priority,asc") String sort,
      @RequestParam(required = false) List<JobOrderType> type) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("priority", "createdAt"), "priority");
    Page<JobOrderDto> p = jobOrderQueryService.getRequestedJobOrders(status, type, pageable);
    return PageResponse.of(p);
  }

  /**
   * Returns an id-and-label projection of the non-terminal job orders for typeaheads, optionally
   * with each order's outstanding per-material need (REQ-INV-039).
   *
   * @param withNeeds whether to include each order's outstanding per-material need
   * @return active job orders as reference DTOs
   */
  @GetMapping("/lookup")
  @Operation(
      summary = "Lookup active job orders",
      description =
          "Returns a reference list of active job orders. With withNeeds=true every order also"
              + " carries its outstanding need per material bucket, for the Lager allocation"
              + " pickers.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<JobOrderReferenceDto> lookupJobOrders(
      @RequestParam(required = false, defaultValue = "false") boolean withNeeds) {
    return jobOrderQueryService.findAllActiveReference(withNeeds);
  }

  /**
   * Returns a single job order with its per-material stock totals (linked inventory items summed
   * server-side, so the frontend doesn't have to re-aggregate).
   *
   * @param id job-order id
   * @return the job-order DTO
   */
  @GetMapping("/{id}")
  @Operation(
      summary = "Get job order by ID",
      description = "Returns a job order and calculates the current material stock.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  @Transactional(readOnly = true)
  public JobOrderDto getJobOrderById(@PathVariable UUID id) {
    JobOrderDto dto = jobOrderQueryService.getJobOrderById(id);
    JobOrderDto tiered = dto.withCanManageAssignees(jobOrderAccessPolicy.canManageAssignees(id));
    return tiered.withAssignees(toPeerShapedAssignees(tiered.assignees()));
  }

  /**
   * Updates the status; a transition to a terminal state (COMPLETED, REJECTED) also atomically
   * unlinks every inventory item pointing at the order.
   *
   * @param id job-order id
   * @param dto status payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/{id}/status")
  @Operation(
      summary = "Update job order status",
      description =
          "Updates the status of a job order. For terminal statuses (COMPLETED, REJECTED), all"
              + " linked inventory items are unlinked atomically. Requires the current version for"
              + " optimistic locking.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Status updated successfully"),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role"),
    @ApiResponse(responseCode = "404", description = "Job order not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Conflict – optimistic locking failure (version mismatch)")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public JobOrderDto updateJobOrderStatus(
      @PathVariable UUID id, @RequestBody @Valid UpdateJobOrderStatusDto dto) {
    return jobOrderService.updateJobOrderStatus(id, dto);
  }

  /**
   * Sets the priority and shifts every other order to keep the queue contiguous. The service
   * acquires a pessimistic write lock for the reorder to keep concurrent priority changes
   * consistent.
   *
   * @param id job-order id
   * @param priority new priority slot (lower = earlier in queue)
   * @return the persisted DTO
   */
  @PutMapping("/{id}/priority")
  @Operation(
      summary = "Update job order priority",
      description = "Updates priority and shifts others.")
  @PreAuthorize(
      "hasRole('" + Roles.LOGISTICIAN + "') and @jobOrderAccessPolicy.canEditJobOrder(#id)")
  public JobOrderDto updateJobOrderPriority(@PathVariable UUID id, @RequestParam Integer priority) {
    return jobOrderService.updateJobOrderPriority(id, priority);
  }

  /**
   * Bulk update — replaces details + the material list in one call.
   *
   * @param id job-order id
   * @param dto update payload (same shape as create)
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @Operation(summary = "Update job order", description = "Updates job order details and materials.")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public JobOrderDto updateJobOrder(
      @PathVariable UUID id, @RequestBody @Valid CreateJobOrderDto dto) {
    return jobOrderService.updateJobOrder(id, dto);
  }

  /**
   * Full edit of an item order's ordered-item lines + metadata. Only permitted while the order has
   * no item-handover yet; the required materials are re-derived from each line's blueprint and any
   * claim whose bucket the new lines no longer require is auto-withdrawn.
   *
   * @param id item-order id
   * @param dto the new item lines + metadata (carries the expected version)
   * @return the persisted order with re-derived materials
   */
  @PutMapping("/{id}/items")
  @Operation(
      summary = "Update an item job order",
      description =
          "Replaces the ordered-item lines and metadata of an item order; required materials are"
              + " re-derived from each line's blueprint. Rejected once the order has any handover.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Item order updated"),
    @ApiResponse(
        responseCode = "400",
        description = "Not an item order, already has handovers, or an invalid blueprint choice"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Order, item or blueprint not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Conflict – optimistic locking failure (version mismatch)")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public JobOrderDto updateItemJobOrder(
      @PathVariable UUID id, @RequestBody @Valid CreateJobOrderItemRequestDto dto) {
    return jobOrderService.updateItemJobOrder(id, dto);
  }

  /**
   * Requester-side edit of a MATERIAL order's lines and comment by a member of the requesting org
   * unit, allowed only while nothing is delivered (REQ-ORDERS-023). Notifies the processing unit's
   * leads.
   *
   * @param id job-order id
   * @param dto the new material lines + comment (carries the expected version)
   * @return the complete, persisted DTO
   */
  @NotNull
  @PutMapping("/{id}/requested")
  @Operation(
      summary = "Requester edit of a material order",
      description =
          "Lets a member of the order's requesting org unit change quantities, add/remove"
              + " not-yet-delivered materials and edit the comment. Only while the order has no"
              + " delivery yet.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Order updated"),
    @ApiResponse(
        responseCode = "400",
        description = "Not a material order, or already has a delivery"),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is not a member of the order's requesting org unit"),
    @ApiResponse(responseCode = "404", description = "Order or material not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Conflict - optimistic locking failure (version mismatch)")
  })
  @PreAuthorize("isAuthenticated()")
  public JobOrderDto updateJobOrderAsRequester(
      @PathVariable UUID id, @RequestBody @Valid CreateJobOrderDto dto) {
    return jobOrderService.updateJobOrderAsRequester(id, dto);
  }

  /**
   * Requester-side edit of an ITEM order's lines and comment (REQ-ORDERS-023), re-deriving the
   * required materials; same rules as {@link #updateJobOrderAsRequester}.
   *
   * @param id job-order id
   * @param dto the new item lines + comment (carries the expected version)
   * @return the complete, persisted DTO
   */
  @NotNull
  @PutMapping("/{id}/items/requested")
  @Operation(
      summary = "Requester edit of an item order",
      description =
          "Lets a member of the order's requesting org unit replace the ordered items and edit the"
              + " comment. Only while the order has no delivery yet.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Order updated"),
    @ApiResponse(
        responseCode = "400",
        description = "Not an item order, already has a delivery, or an invalid blueprint choice"),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is not a member of the order's requesting org unit"),
    @ApiResponse(responseCode = "404", description = "Order, item or blueprint not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Conflict - optimistic locking failure (version mismatch)")
  })
  @PreAuthorize("isAuthenticated()")
  public JobOrderDto updateItemJobOrderAsRequester(
      @PathVariable UUID id, @RequestBody @Valid CreateJobOrderItemRequestDto dto) {
    return jobOrderService.updateItemJobOrderAsRequester(id, dto);
  }

  /**
   * Toggles whether an item order's blueprint-coverage view counts cosmetic variants of the ordered
   * items ({@code true}) or only exact-name matches ({@code false}) (REQ-ORDERS-021).
   *
   * @param id item-order id
   * @param dto the requested counting mode + expected version
   * @return the persisted DTO (carries the bumped version when the mode actually changed)
   */
  @PatchMapping("/{id}/blueprint-variant-counting")
  @Operation(
      summary = "Toggle item-order blueprint variant counting",
      description =
          "Sets whether the item order's blueprint-coverage view counts cosmetic variants of the"
              + " ordered items (family matching) or matches blueprints exactly. Item orders only."
              + " Requires the current version for optimistic locking.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Counting mode updated"),
    @ApiResponse(responseCode = "400", description = "Not an item order"),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role"),
    @ApiResponse(responseCode = "404", description = "Job order not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Conflict – optimistic locking failure (version mismatch)")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public JobOrderDto updateBlueprintVariantCounting(
      @PathVariable UUID id, @NotNull @RequestBody @Valid UpdateJobOrderBlueprintCountingDto dto) {
    return jobOrderService.updateBlueprintVariantCounting(
        id, dto.countBlueprintsWithVariants(), dto.version());
  }

  /**
   * Transfers responsibility immediately through the current responsible-unit writer gate.
   *
   * @param id job-order id
   * @param body the target responsible org unit id
   * @return the updated DTO
   */
  @PatchMapping("/{id}/responsible-org-unit")
  @Operation(
      summary = "Reassign responsible org unit",
      description =
          "A current responsible-unit writer transfers immediately to an eligible Profit unit,"
              + " including recovery from invalid responsibility, without moving stock.")
  @PreAuthorize("isAuthenticated()")
  public JobOrderDto reassignResponsibleOrgUnit(
      @PathVariable UUID id, @NotNull @RequestBody @Valid ReassignResponsibleOrgUnitRequest body) {
    return jobOrderService.reassignResponsibleOrgUnit(id, body.responsibleOrgUnitId());
  }

  /**
   * Request body for the responsible-org-unit reassignment endpoint.
   *
   * @param responsibleOrgUnitId the target active Profit-hierarchy org unit id (required)
   */
  public record ReassignResponsibleOrgUnitRequest(
      @jakarta.validation.constraints.NotNull UUID responsibleOrgUnitId) {}

  /**
   * ADMIN-only delete. Surviving orders' priorities shift up to keep the queue contiguous.
   *
   * @param id job-order id
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Delete a job order",
      description = "Deletes a job order and shifts priorities.")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteJobOrder(@PathVariable UUID id) {
    jobOrderService.deleteJobOrder(id);
  }

  /**
   * Peer-shapes every assignee's nested user record of a job order.
   *
   * @param assignees the assignee rows as mapped, or {@code null}
   * @return the same rows with peer-shaped users, or {@code null} for a {@code null} input
   */
  @Contract("null -> null; !null -> !null")
  private static @Nullable List<JobOrderAssigneeDto> toPeerShapedAssignees(
      @Nullable List<JobOrderAssigneeDto> assignees) {
    if (assignees == null) {
      return null;
    }
    return assignees.stream()
        .map(a ->
            new JobOrderAssigneeDto(UserDtoRedaction.toPeerShape(a.user()), a.note(), a.version()))
        .toList();
  }
}
