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

import de.greluc.krt.profit.basetool.backend.mapper.OperationMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceSummaryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationFinanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationFinanceSummaryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutStatusUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutSummaryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OperationFinanceService;
import de.greluc.krt.profit.basetool.backend.service.OperationPayoutService;
import de.greluc.krt.profit.basetool.backend.service.OperationService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over the Operation aggregate: CRUD plus aggregated finance and payout endpoints.
 * Mutations require MISSION_MANAGER; delete is ADMIN-only.
 *
 * <p>Status changes follow {@code OperationStatus.canTransitionTo}; admins may bypass it, resolved
 * here and passed to the service as a boolean.
 */
@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
@Tag(
    name = "Operations",
    description =
        "Operation aggregate: groups multiple missions under one umbrella and exposes "
            + "aggregated finance and payout views.")
@Transactional
public class OperationController {

  private static final Set<String> ALLOWED_SORT =
      Set.of("id", "name", "status", "description", "createdAt", "updatedAt");

  private final OperationService operationService;
  private final OperationPayoutService operationPayoutService;
  private final OperationMapper operationMapper;
  private final OperationFinanceService operationFinanceService;
  private final AuthHelperService authHelperService;

  /**
   * Returns paged operation DTOs (whitelist-enforced sort, {@code id} appended as tiebreaker).
   *
   * @return paged operation DTOs (whitelist-enforced sort, {@code id} appended as tiebreaker)
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "List all operations (paginated)",
      description =
          "Returns operations ordered by `sort` (default `createdAt,desc`). "
              + "Allowed sort fields: id, name, status, description, createdAt, updatedAt. "
              + "`id` is appended automatically as a stable tiebreaker.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of operations."),
    @ApiResponse(responseCode = "400", description = "Unsupported sort field."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated.")
  })
  @Transactional(readOnly = true)
  public PageResponse<OperationDto> getAllOperations(
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "10") Integer size,
      @RequestParam(required = false, defaultValue = "createdAt,desc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "createdAt");
    Page<OperationDto> dtoPage =
        operationService.getAllOperations(pageable).map(operationMapper::toDto);
    return PageResponse.of(dtoPage);
  }

  /**
   * Filtered, paged operation search by text, status and time range. {@code start} and {@code end}
   * bound the planned start of the earliest and the planned end of the latest linked mission; an
   * empty {@code status} means every status.
   *
   * @param query free-text name/description fragment
   * @param start inclusive lower bound on the earliest linked mission's planned start (ISO-8601)
   * @param end inclusive upper bound on the latest linked mission's planned end (ISO-8601)
   * @param status status filter (one or more)
   * @param page zero-based page index
   * @param size page size
   * @param sort sort token (default {@code createdAt,desc})
   * @return paged operation DTOs
   */
  @GetMapping("/search")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Search operations (paginated)",
      description =
          "Returns operations matching the supplied filters (free-text query + status list + time"
              + " range). Operations have no `plannedStartTime` of their own - that field lives on"
              + " the underlying missions - so `start` filters on the earliest linked mission's"
              + " planned start and `end` on the latest linked mission's planned end. Whitelisted"
              + " sort fields: id, name, status, description, createdAt, updatedAt. `id` is"
              + " appended automatically as a stable tiebreaker.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of operations."),
    @ApiResponse(responseCode = "400", description = "Unsupported sort field."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated.")
  })
  @Transactional(readOnly = true)
  public PageResponse<OperationDto> searchOperations(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant end,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "10") Integer size,
      @RequestParam(required = false, defaultValue = "createdAt,desc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "createdAt");
    Page<OperationDto> dtoPage =
        operationService
            .searchOperations(query, start, end, status, pageable)
            .map(operationMapper::toDto);
    return PageResponse.of(dtoPage);
  }

  /**
   * Slim id + name projection of every operation visible to the caller, sorted by name, for the
   * mission-detail operation picker.
   *
   * @return slim reference DTOs for the operation picker
   */
  @GetMapping("/lookup")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Lookup operations",
      description =
          "Returns a slim id + name reference list of every operation in the caller's squadron"
              + " scope, sorted by name. Designed for dropdowns and typeaheads where the full"
              + " OperationDto payload is overkill.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Reference list returned."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated.")
  })
  @Transactional(readOnly = true)
  public List<OperationReferenceDto> lookupOperations() {
    return operationService.findAllReference();
  }

  /**
   * Returns the operation DTO.
   *
   * @param id operation id
   * @return the operation DTO
   */
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeOperation(#id)")
  @Operation(
      summary = "Get operation by ID",
      description =
          "Returns the operation DTO. The `payoutPreliminary` field is authoritative on this"
              + " endpoint: it is `true` when at least one mission of the operation still lacks an"
              + " `actualStartTime` or `actualEndTime` — the operation-detail page reads this to"
              + " render a 'payout figures are preliminary' warning above the payout table. List"
              + " and create/update responses leave the field `null`.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Operation found."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "404", description = "Operation not found.")
  })
  @Transactional(readOnly = true)
  public OperationDto getOperationById(@PathVariable UUID id) {
    OperationDto dto = operationMapper.toDto(operationService.getOperationById(id));
    return dto.withPayoutPreliminary(operationService.hasUnfinishedMissions(id));
  }

  /**
   * Full finance roll-up across all missions of the operation, with every finance entry and
   * refinery order embedded per mission. For the lightweight variant see {@link
   * #getOperationFinanceSummary}.
   *
   * @param id operation id
   * @return finance summary DTO with the full per-mission breakdown
   */
  @GetMapping("/{id}/finances")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeOperationLedger(#id)")
  @Operation(
      summary = "Get aggregated finances for an operation (full breakdown)",
      description =
          "Sums income, expenses and refinery profit/loss across all missions "
              + "that belong to the operation, embedding each mission's full finance-entry + "
              + "refinery-order breakdown. Refinery order profit is calculated as "
              + "`oreSales - expenses - otherExpenses`; null values are treated as 0. This is the "
              + "heavy full-detail variant; prefer `/finance-summary` (roll-up totals) plus "
              + "`/finances/{missionId}` (per-mission detail on demand) for the interactive page.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Finance summary returned."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "404", description = "Operation not found.")
  })
  @Transactional(readOnly = true)
  public OperationFinanceDto getOperationFinances(@PathVariable UUID id) {
    return operationFinanceService.getOperationFinances(id);
  }

  /**
   * Lightweight finance roll-up: the operation-wide total plus one total line per mission, from
   * grouped SQL aggregates (ADR-0078). Per-mission detail loads via {@link
   * #getMissionFinanceDetail}.
   *
   * @param id operation id
   * @return the operation-wide total plus the capped per-mission roll-up lines
   */
  @GetMapping("/{id}/finance-summary")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeOperationLedger(#id)")
  @Operation(
      summary = "Get the finance roll-up for an operation (totals only)",
      description =
          "Returns the operation-wide signed total and one total line per mission (id + name + "
              + "signed result), computed from grouped SQL aggregates instead of materializing "
              + "every finance entry / refinery order. The per-mission breakdown is capped at 500 "
              + "missions (`truncated=true` when clipped). Pair it with `/finances/{missionId}` to "
              + "load a single mission's per-entry detail on demand.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Finance roll-up returned."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "404", description = "Operation not found.")
  })
  @Transactional(readOnly = true)
  public OperationFinanceSummaryDto getOperationFinanceSummary(@PathVariable UUID id) {
    return operationFinanceService.getOperationFinanceSummary(id);
  }

  /**
   * One mission's full finance detail (entries + refinery orders) for the operation finance panel.
   * Authorized at the operation scope; the mission must belong to the operation.
   *
   * @param id operation id (authorization scope)
   * @param missionId the mission whose finance detail to load; must belong to the operation
   * @return the mission's finance detail (entries + refinery orders + recomputed total)
   */
  @GetMapping("/{id}/finances/{missionId}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeOperationLedger(#id)")
  @Operation(
      summary = "Get one mission's finance detail within an operation",
      description =
          "Returns a single mission's finance entries and refinery orders (plus its recomputed "
              + "signed total) for the lazy per-mission breakdown of the operation finance panel. "
              + "The mission must be one of the operation's child missions. Authorized at the "
              + "operation scope, so it needs no separate mission-scope check.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Mission finance detail returned."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(
        responseCode = "404",
        description = "Operation not found, or the mission is not part of the operation.")
  })
  @Transactional(readOnly = true)
  public MissionFinanceSummaryDto getMissionFinanceDetail(
      @PathVariable UUID id, @PathVariable UUID missionId) {
    return operationFinanceService.getMissionFinanceDetail(id, missionId);
  }

  /**
   * Per-participant payout breakdown: time share, payout amount (expense reimbursement + pool
   * share) and the paid-out flag. DONATE in any sub-mission applies to the whole operation.
   *
   * @param id operation id
   * @return payout rows sorted by participant name
   */
  @GetMapping("/{id}/payouts")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeOperation(#id)")
  @Operation(
      summary = "Get participation payout breakdown with amounts and paid-out status",
      description =
          "For each participant across all missions of the operation, returns the time-share "
              + "(percent), the personal out-of-pocket reimbursement (mission EXPENSE entries "
              + "they own + refinery `expenses + otherExpenses` they own), the per-share amount "
              + "(totalSum × percentage / 100, 0 for DONATE), the in-game banking transfer fee "
              + "deducted from the gross payout (`transferFee`, rate from the runtime-editable "
              + "`operation.transfer_fee_rate` system setting, default 0.005 = 0.5%) and the "
              + "resulting net payout amount (`payoutAmount = round(personalExpenses + "
              + "shareAmount − transferFee)`, HALF_UP to whole aUEC because Star Citizen's "
              + "mobiGlas does not accept fractional credits in a transfer). Also includes the "
              + "paid-out flag set by mission managers "
              + "(`paidOut`, `paidOutAt`, `paidOutByName`) — absent flag rows are treated as "
              + "`paidOut=false`. A participant who chose DONATE in any mission is treated as "
              + "DONATE for the whole operation; their reimbursement is still paid (it is their "
              + "own money returned) but their share is contributed to the org. The response wraps "
              + "the payout rows in an object that also carries `totalDonations` — the "
              + "operation-wide sum of every DONATE participant's contributed share "
              + "(`donatedAmount` per row), shown centrally and never redistributed to PAYOUT "
              + "participants.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Payout breakdown returned."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "404", description = "Operation not found.")
  })
  @Transactional(readOnly = true)
  public OperationPayoutSummaryDto getOperationPayouts(@PathVariable UUID id) {
    return operationPayoutService.getOperationPayoutSummary(id);
  }

  /**
   * Toggles a participant's paid-out flag on the operation; mission managers and above only.
   *
   * @param id operation id
   * @param dto participant key and new paid-out value
   * @return the participant's refreshed paid-out status block
   */
  @PutMapping("/{id}/payouts/paid-out")
  @PreAuthorize(
      "hasRole('"
          + Roles.MISSION_MANAGER
          + "') and @ownerScopeService.canEditOperation(#id) and (#dto.paidOut() or hasAnyRole('"
          + Roles.ADMIN
          + "', '"
          + Roles.OFFICER
          + "'))")
  @Operation(
      summary = "Toggle the per-participant paid-out flag for an operation",
      description =
          "Records that a mission manager has marked the participant as paid out (or unset "
              + "it). Setting `paidOut=true` requires the MISSION_MANAGER role (admins and "
              + "officers satisfy it via the role hierarchy). Setting `paidOut=false` is "
              + "reserved for ADMIN and OFFICER — a plain mission manager cannot undo a "
              + "paid-out confirmation. Last-writer-wins: no client-supplied version is "
              + "required because the field is a boolean. The audit fields (`paidOutAt`, "
              + "`paidOutByUser`) are always refreshed when `paidOut=true`; setting "
              + "`paidOut=false` keeps the last audit fields as a historical record. The "
              + "participantKey matches the opaque key returned by `/payouts` (real user UUID "
              + "stringified, `guest_<name>` for an external participant, or "
              + "`deleted_<participantId>` for a participant whose account was deleted). Returns "
              + "only the participant's paid-out status "
              + "block (flag + audit trace) — a toggle never changes any amount, so the payout "
              + "computation is not re-run.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paid-out flag updated."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(
        responseCode = "403",
        description =
            "Caller lacks the MISSION_MANAGER role, or attempted to clear paidOut without"
                + " ADMIN/OFFICER."),
    @ApiResponse(
        responseCode = "404",
        description = "Operation not found, or participantKey is not part of the operation.")
  })
  public OperationPayoutStatusDto setPayoutStatus(
      @PathVariable UUID id, @NotNull @Valid @RequestBody OperationPayoutStatusUpdateDto dto) {
    return operationPayoutService.setPayoutStatus(id, dto.participantKey(), dto.paidOut());
  }

  /**
   * Creates a new operation.
   *
   * @param createDto create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_MISSION_MANAGER)
  @Operation(summary = "Create a new operation")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Operation created."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "403", description = "Caller lacks the MISSION_MANAGER role.")
  })
  public OperationDto createOperation(@Valid @RequestBody OperationCreateDto createDto) {
    de.greluc.krt.profit.basetool.backend.model.Operation operation =
        operationMapper.toEntity(createDto);
    return operationMapper.toDto(
        operationService.createOperation(operation, createDto.owningOrgUnitId()));
  }

  /**
   * Updates an operation with optimistic-lock and state-machine validation; the admin role is
   * resolved here and passed to the service as a boolean.
   *
   * @param id operation id
   * @param updateDto update payload (carries expected version + new status)
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(
      "hasRole('" + Roles.MISSION_MANAGER + "') and @ownerScopeService.canEditOperation(#id)")
  @Operation(
      summary = "Update an existing operation",
      description =
          "Requires the current `version` field in the body for optimistic locking. "
              + "A stale version triggers a 409 Conflict. Status changes are validated against "
              + "the state machine PLANNED -> {ACTIVE, CANCELED}, ACTIVE -> {COMPLETED, CANCELED}; "
              + "COMPLETED and CANCELED are terminal. Callers with ROLE_ADMIN bypass that gate.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Operation updated."),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed or invalid status transition."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "403", description = "Caller lacks the MISSION_MANAGER role."),
    @ApiResponse(responseCode = "404", description = "Operation not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict — fetch and retry.")
  })
  public OperationDto updateOperation(
      @PathVariable UUID id, @Valid @RequestBody OperationUpdateDto updateDto) {
    boolean canOverrideStatus = authHelperService.isAdmin();
    return operationMapper.toDto(
        operationService.updateOperation(id, updateDto, canOverrideStatus));
  }

  /**
   * Deletes the operation but keeps its missions alive (sets {@code mission.operation=null}).
   * ADMIN-only.
   *
   * @param id operation id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "') and @ownerScopeService.canEditOperation(#id)")
  @Operation(
      summary = "Delete an operation",
      description =
          "Unlinks every mission that belongs to the operation (sets "
              + "`mission.operation` to null) and then deletes the operation. Missions and "
              + "all their references (participants, finance entries, inventory items, "
              + "refinery orders) survive intact.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Operation deleted."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated."),
    @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN role."),
    @ApiResponse(responseCode = "404", description = "Operation not found.")
  })
  public ResponseEntity<Void> deleteOperation(@PathVariable UUID id) {
    operationService.deleteOperation(id);
    return ResponseEntity.noContent().build();
  }
}
