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

import de.greluc.krt.profit.basetool.backend.model.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ItemDerivationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderHandoverReportService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderHandoverService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemBlueprintOwnersService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemHandoverReportService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemHandoverService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemProductionService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialDemandService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.JobOrderInventoryOwnerRedactor;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import de.greluc.krt.profit.basetool.backend.web.PdfResponses;
import de.greluc.krt.profit.basetool.backend.web.UserZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * REST surface over the job-order aggregate (the request-and-fulfill queue).
 *
 * <p>Reads and mutations are limited to the caller's visibility scope via {@code
 * @ownerScopeService}: SK-responsible orders are public, squadron-responsible orders private to
 * that squadron and admins. Creation needs a login only (ADR-0149); other mutations require
 * LOGISTICIAN or above, delete requires ADMIN.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderController {
  private final JobOrderService jobOrderService;
  private final JobOrderQueryService jobOrderQueryService;
  private final JobOrderMaterialDemandService jobOrderMaterialDemandService;
  private final OwnerScopeService ownerScopeService;
  private final JobOrderItemService jobOrderItemService;
  private final JobOrderItemBlueprintOwnersService jobOrderItemBlueprintOwnersService;
  private final JobOrderItemHandoverService jobOrderItemHandoverService;
  private final JobOrderItemProductionService jobOrderItemProductionService;
  private final JobOrderItemHandoverReportService jobOrderItemHandoverReportService;
  private final JobOrderHandoverService jobOrderHandoverService;
  private final JobOrderHandoverReportService jobOrderHandoverReportService;
  private final UserService userService;
  private final AuthHelperService authHelperService;
  private final JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;

  /**
   * Records a materials handover for the job order, unlinking the handed-over items with a single
   * bulk update after the per-item loop.
   *
   * @param id job-order id
   * @param dto handover create payload (per-item quantities)
   * @return the persisted handover DTO
   */
  @PostMapping("/{id}/handovers")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a job order handover",
      description = "Logs a handover of materials for this job order.")
  @PreAuthorize(
      "(hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')) and @ownerScopeService.canEditJobOrder(#id)")
  public JobOrderHandoverDto createHandover(
      @PathVariable UUID id, @RequestBody @Valid JobOrderHandoverCreateDto dto) {
    return jobOrderHandoverService.createHandover(id, dto);
  }

  /**
   * Records an item handover for an item order: increments each ordered line's delivered count and
   * auto-completes the order once every line is fully delivered. Same authorisation as the material
   * handover (LOGISTICIAN+).
   *
   * @param id job-order id
   * @param dto item-handover payload (per-line delivered quantities)
   * @return the persisted item-handover DTO
   */
  @PostMapping("/{id}/item-handovers")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create an item handover",
      description = "Logs a handover of produced items for this item order.")
  @PreAuthorize(
      "(hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')) and @ownerScopeService.canEditJobOrder(#id)")
  public JobOrderItemHandoverDto createItemHandover(
      @PathVariable UUID id, @RequestBody @Valid JobOrderItemHandoverCreateDto dto) {
    return jobOrderItemHandoverService.createItemHandover(id, dto);
  }

  /**
   * Books a production run ("Herstellung") against one ordered item line: records the manufactured
   * units and consumes the linked inventory (REQ-ORDERS-025).
   *
   * <p>The consumption plan must exactly cover the per-material demand (422 {@code
   * PRODUCTION_ALLOCATION}); a stale version gives 409. An optional {@code bookIn} block also books
   * the produced units in as Lager item stock in the same transaction (REQ-INV-032).
   *
   * @param id job-order id
   * @param itemId ordered item-line id
   * @param dto production payload (amount, line version, per-entry consumption, optional book-in)
   * @return the refreshed ordered item-line DTO
   */
  @PostMapping("/{id}/items/{itemId}/production")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Book production for an item line",
      description =
          "Records how many units of an ordered item were manufactured and reduces the linked"
              + " inventory consumed. The consumption must exactly cover the required material"
              + " demand. An optional bookIn block additionally books the produced units into the"
              + " Lager as item stock (location, owner, owning org unit) in the same transaction,"
              + " by default earmarked for this order; without it the booking behaves exactly as"
              + " before.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Production booked."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Not an item order / invalid consumption / invalid book-in (personal combined with the"
                + " order earmark, or an org-unit picker output that is invalid for the owner)."),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role."),
    @ApiResponse(
        responseCode = "404",
        description = "Order, item line, inventory entry, or book-in owner/location not found."),
    @ApiResponse(
        responseCode = "409",
        description = "Conflict – optimistic locking failure (version mismatch)."),
    @ApiResponse(
        responseCode = "422",
        description = "Amount exceeds remaining-to-manufacture or consumption misses the demand.")
  })
  @PreAuthorize(
      "(hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')) and @ownerScopeService.canEditJobOrder(#id)")
  public JobOrderItemDto bookProduction(
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @RequestBody @Valid JobOrderItemProductionCreateDto dto) {
    return jobOrderItemProductionService.bookProduction(id, itemId, dto);
  }

  /**
   * Renders a persisted item handover as a downloadable PDF delivery note. The optional {@code
   * X-User-Time-Zone} header overrides UTC for the document timestamps; an invalid IANA zone is
   * silently dropped (the service falls back to UTC). Same authorisation as the material report.
   *
   * @param jobOrderId job-order id
   * @param handoverId item-handover id
   * @param userZone the resolved {@code X-User-Time-Zone} zone, or {@code null} for UTC
   * @return PDF body with {@code application/pdf} and attachment Content-Disposition
   */
  @GetMapping("/{jobOrderId}/item-handovers/{handoverId}/report")
  @Operation(
      summary = "Download item-handover report PDF",
      description = "Generates and downloads a PDF delivery note for a persisted item handover.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "PDF generated successfully"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Job order or item handover not found")
  })
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.LOGISTICIAN
          + "', '"
          + Roles.OFFICER
          + "', '"
          + Roles.ADMIN
          + "') and @ownerScopeService.canSeeJobOrder(#jobOrderId)")
  @Parameter(
      name = "X-User-Time-Zone",
      in = ParameterIn.HEADER,
      required = false,
      schema = @Schema(type = "string"))
  public ResponseEntity<byte[]> downloadItemHandoverReport(
      @PathVariable UUID jobOrderId, @PathVariable UUID handoverId, @UserZone ZoneId userZone) {
    byte[] pdf =
        jobOrderItemHandoverReportService.generateItemHandoverReport(
            jobOrderId, handoverId, userZone);
    String filename = "uebergabeprotokoll-" + jobOrderId + ".pdf";
    return PdfResponses.pdfAttachment(pdf, filename);
  }

  /**
   * Renders a persisted handover as a downloadable PDF. The optional {@code X-User-Time-Zone}
   * header overrides UTC for the timestamps in the document; an invalid IANA zone is silently
   * dropped (the service falls back to UTC) rather than failing the request.
   *
   * @param jobOrderId job-order id
   * @param handoverId handover id
   * @param userZone the resolved {@code X-User-Time-Zone} zone, or {@code null} for UTC
   * @return PDF body with {@code application/pdf} and attachment Content-Disposition
   */
  @GetMapping("/{jobOrderId}/handovers/{handoverId}/report")
  @Operation(
      summary = "Download handover report PDF",
      description = "Generates and downloads a PDF handover report for a persisted handover.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "PDF generated successfully"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Job order or handover not found")
  })
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.LOGISTICIAN
          + "', '"
          + Roles.OFFICER
          + "', '"
          + Roles.ADMIN
          + "') and @ownerScopeService.canSeeJobOrder(#jobOrderId)")
  @Parameter(
      name = "X-User-Time-Zone",
      in = ParameterIn.HEADER,
      required = false,
      schema = @Schema(type = "string"))
  public ResponseEntity<byte[]> downloadHandoverReport(
      @PathVariable UUID jobOrderId, @PathVariable UUID handoverId, @UserZone ZoneId userZone) {
    byte[] pdf =
        jobOrderHandoverReportService.generateHandoverReport(jobOrderId, handoverId, userZone);
    String filename = "uebergabeprotokoll-" + jobOrderId + ".pdf";
    return PdfResponses.pdfAttachment(pdf, filename);
  }

  /**
   * Renders a preview PDF from unsaved handover data — used by the create-handover form to show the
   * document before commit. Nothing is persisted.
   *
   * @param jobOrderId job-order id
   * @param dto unsaved handover data
   * @return PDF body with {@code application/pdf} and attachment Content-Disposition
   */
  @PostMapping("/{jobOrderId}/handovers/report/preview")
  @Operation(
      summary = "Preview handover report PDF",
      description = "Generates a PDF handover report preview from unsaved handover data.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "PDF generated successfully"),
    @ApiResponse(responseCode = "400", description = "Invalid request data"),
    @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.LOGISTICIAN
          + "', '"
          + Roles.OFFICER
          + "', '"
          + Roles.ADMIN
          + "') and @ownerScopeService.canEditJobOrder(#jobOrderId)")
  public ResponseEntity<byte[]> previewHandoverReport(
      @PathVariable UUID jobOrderId, @RequestBody @Valid HandoverReportPreviewRequestDto dto) {
    byte[] pdf = jobOrderHandoverReportService.generateHandoverReportPreview(dto);
    String filename = "uebergabeprotokoll-vorschau.pdf";
    return PdfResponses.pdfAttachment(pdf, filename);
  }

  /**
   * Creates a new job order; any logged-in member may file one, and its visibility follows {@code
   * canSeeJobOrder} (REQ-ORG-003).
   *
   * @param dto create payload
   * @return the persisted DTO
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a new job order",
      description = "Creates a job order. Requires an authenticated member (ADR-0149).")
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
   * Paged picker of orderable items (blueprint outputs with at least one resolvable material) for
   * the item-order create form.
   *
   * @param search optional case-insensitive item-name filter
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (only {@code name} is whitelisted)
   * @return paged orderable item references
   */
  @GetMapping("/item-catalog")
  @Operation(
      summary = "List orderable items",
      description =
          "Returns a paginated list of items that can be ordered (blueprint outputs with at least"
              + " one resolvable material).")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public PageResponse<GameItemReferenceDto> getOrderableItems(
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "name,asc") String sort) {
    Pageable pageable = PaginationUtil.createPageRequest(page, size, sort, Set.of("name"), "name");
    Page<GameItemReferenceDto> p = jobOrderItemService.findOrderableItems(search, pageable);
    return PageResponse.of(p);
  }

  /**
   * Lists the blueprints that produce a given orderable item, for the create form's blueprint
   * picker.
   *
   * @param gameItemId the orderable item
   * @return blueprint references producing that item
   */
  @GetMapping("/item-catalog/{gameItemId}/blueprints")
  @Operation(
      summary = "List blueprints for an orderable item",
      description = "Returns the blueprints that produce the given item.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<BlueprintReferenceDto> getBlueprintsForItem(@PathVariable UUID gameItemId) {
    return jobOrderItemService.blueprintsForItem(gameItemId);
  }

  /**
   * Previews the material derivation for a chosen blueprint at a given amount: resolved materials
   * (with default quality), adoptable sub-assembly suggestions, and unresolved-ingredient names for
   * the create-form warning banner.
   *
   * @param blueprintId the chosen blueprint
   * @param amount the whole-unit amount to scale by (defaults to 1)
   * @return the derivation preview
   */
  @GetMapping("/item-catalog/blueprints/{blueprintId}/derivation")
  @Operation(
      summary = "Preview blueprint material derivation",
      description =
          "Returns the materials, sub-assembly suggestions and unresolved ingredients derived from"
              + " a blueprint at the given amount.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public ItemDerivationDto getBlueprintDerivation(
      @PathVariable UUID blueprintId,
      @RequestParam(required = false, defaultValue = "1") int amount) {
    return jobOrderItemService.deriveForPreview(blueprintId, amount);
  }

  /**
   * Redacts a job-order DTO for a requester-only viewer (REQ-ORDERS-023): drops assignees, the
   * materials summary, handovers and per-line collection progress, and keeps the editable lines,
   * comment, org units, status and {@code version}.
   *
   * @param dto the full job-order DTO
   * @return the redacted DTO safe for a requester-only viewer
   */
  @NotNull
  private JobOrderDto cleanupJobOrderForRequester(@NotNull JobOrderDto dto) {
    return new JobOrderDto(
        dto.id(),
        dto.displayId(),
        dto.responsibleOrgUnit(),
        dto.requestingOrgUnit(),
        dto.handle(),
        dto.comment(),
        dto.priority(),
        dto.status(),
        dto.type(),
        dto.countBlueprintsWithVariants(),
        redactMaterialProgress(dto.materials()),
        dto.items(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        dto.createdAt(),
        dto.version(),
        dto.canEdit(),
        true);
  }

  /**
   * Strips {@code currentStock}, {@code claims} and {@code openAmount} from each material line,
   * keeping id, material, min-quality, amount and version.
   *
   * @param materials the material lines, possibly {@code null}
   * @return the material lines with their progress fields nulled/emptied; never {@code null}
   */
  private static List<JobOrderMaterialDto> redactMaterialProgress(
      List<JobOrderMaterialDto> materials) {
    if (materials == null) {
      return Collections.emptyList();
    }
    return materials.stream()
        .map(
            m ->
                new JobOrderMaterialDto(
                    m.id(),
                    m.material(),
                    m.minQuality(),
                    m.amount(),
                    null,
                    Collections.emptyList(),
                    null,
                    m.version()))
        .toList();
  }

  /**
   * Paged job-order list, by default sorted by {@code priority,asc}, limited to the caller's
   * visibility scope. {@code squadronId} can only narrow that scope, never widen it.
   *
   * @param status optional status filter (logical OR across values)
   * @param squadronId optional repeatable filter on the responsible or requesting org unit; absent
   *     means the full scoped view
   * @return paged job-order DTOs visible to the caller
   */
  @GetMapping
  @Operation(
      summary = "Get all job orders",
      description = "Returns a paginated list of job orders.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public PageResponse<JobOrderDto> getAllJobOrders(
      @RequestParam(required = false) List<JobOrderStatus> status,
      @RequestParam(required = false) List<UUID> squadronId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "priority,asc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("priority", "createdAt"), "priority");
    Page<JobOrderDto> p = jobOrderQueryService.getAllJobOrders(status, squadronId, pageable);
    return PageResponse.of(p);
  }

  /**
   * Aggregates the outstanding material of every visible {@code OPEN} / {@code IN_PROGRESS} job
   * order into one row per responsible org unit, material and quality (REQ-ORDERS-034).
   *
   * <p>Unpaged, since paging would truncate the sums (ADR-0104).
   *
   * @return the aggregated demand, empty when the caller may see no non-terminal order
   */
  @GetMapping("/material-demand")
  @Operation(
      summary = "Get the cross-order material demand",
      description =
          "Returns the material still to be gathered across all open / in-progress job orders"
              + " visible to the caller, grouped by responsible org unit.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MaterialDemandOverviewDto getMaterialDemand() {
    return jobOrderMaterialDemandService.getMaterialDemandOverview();
  }

  /**
   * Paged, requester-redacted list of the orders the caller's own org unit(s) requested, the "Meine
   * Auftr&auml;ge" list (REQ-ORDERS-023). Independent of the profit-gated main queue.
   *
   * @param status optional status filter (logical OR across values)
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (whitelisted: {@code priority}, {@code createdAt})
   * @return paged, requester-redacted job-order DTOs the caller's org unit(s) requested
   */
  @GetMapping("/requested")
  @Operation(
      summary = "Get my requested job orders",
      description =
          "Returns a paginated list of the orders the caller's own org unit(s) requested"
              + " (Auftraggeber view), redacted (no Bearbeiter / no materials summary).")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canViewOwnJobOrders()")
  @Transactional(readOnly = true)
  public PageResponse<JobOrderDto> getRequestedJobOrders(
      @RequestParam(required = false) List<JobOrderStatus> status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "priority,asc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("priority", "createdAt"), "priority");
    Page<JobOrderDto> p = jobOrderQueryService.getRequestedJobOrders(status, pageable);
    return PageResponse.of(p.map(this::cleanupJobOrderForRequester));
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
  @PreAuthorize(
      "isAuthenticated() and (@ownerScopeService.canSeeJobOrder(#id) or"
          + " @ownerScopeService.canSeeJobOrderAsRequester(#id))")
  @Transactional(readOnly = true)
  public JobOrderDto getJobOrderById(@PathVariable UUID id) {
    JobOrderDto dto = jobOrderQueryService.getJobOrderById(id);
    JobOrderDto tiered = dto.redacted() ? cleanupJobOrderForRequester(dto) : dto;
    return tiered.withAssignees(UserDtoRedaction.toPeerShapedAssignees(tiered.assignees()));
  }

  /**
   * Shows which members of the order's responsible org unit own the blueprints for the ordered
   * items. Visible only to members of that unit and admins; empty for {@code MATERIAL} orders.
   *
   * @param id job-order id
   * @return the blueprint-coverage view (required products with owner counts + owning members)
   */
  @GetMapping("/{id}/item-blueprint-owners")
  @Operation(
      summary = "Get item-order blueprint coverage",
      description =
          "Returns which members of the responsible squadron/SK own the blueprints for the"
              + " order's required items. Members of the responsible org unit only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Blueprint coverage for the item order."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is not a member of the order's responsible squadron/SK."),
    @ApiResponse(responseCode = "404", description = "Job order not found.")
  })
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrderBlueprintOwners(#id)")
  @Transactional(readOnly = true)
  public JobOrderItemBlueprintOwnersDto getItemBlueprintOwners(@PathVariable UUID id) {
    return jobOrderItemBlueprintOwnersService.getBlueprintOwners(id);
  }

  /**
   * Returns every inventory item linked to a specific material of a job order. Drives the
   * per-material drill-down in the order detail view. The owner/location of each item is blanked
   * for a requesting-side viewer of an SK-public order ({@code canSeeJobOrderInventoryOwners} is
   * {@code false}, REQ-ORDERS-029 / ADR-0107).
   *
   * @param id job-order id
   * @param matId material id
   * @return inventory-item DTOs, owner/location redacted for requesting-side viewers
   */
  @GetMapping("/{id}/materials/{matId}/inventory")
  @Operation(
      summary = "Get inventory items for a job order material",
      description = "Returns all inventory items linked to a specific material in a job order.")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)")
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getInventoryItemsForJobOrderMaterial(
      @PathVariable UUID id, @PathVariable UUID matId) {
    List<InventoryItemDto> items =
        jobOrderQueryService.getInventoryItemsForJobOrderMaterial(id, matId);
    return ownerScopeService.canSeeJobOrderInventoryOwners(id)
        ? items
        : inventoryOwnerRedactor.redactInventoryItems(items);
  }

  /**
   * Returns the inventory items linked to the order whose material the order does not require —
   * "orphaned" links surfaced as a warning on the order detail (REQ-ORDERS-019). Such links bind
   * stock to the order while staying invisible in every material row. The owner/location of each
   * item is blanked for a requesting-side viewer of an SK-public order ({@code
   * canSeeJobOrderInventoryOwners} is {@code false}, REQ-ORDERS-029 / ADR-0107).
   *
   * @param id job-order id
   * @return orphaned inventory-item DTOs (empty when every linked item matches a requirement),
   *     owner/location redacted for requesting-side viewers
   */
  @GetMapping("/{id}/inventory/orphaned")
  @Operation(
      summary = "Get orphaned linked inventory for a job order",
      description =
          "Returns inventory items linked to the order whose material is not among the order's"
              + " requirements (invisible orphaned links).")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)")
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getOrphanedLinkedInventory(@PathVariable UUID id) {
    List<InventoryItemDto> items = jobOrderQueryService.getOrphanedLinkedInventory(id);
    return ownerScopeService.canSeeJobOrderInventoryOwners(id)
        ? items
        : inventoryOwnerRedactor.redactInventoryItems(items);
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
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)")
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
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)")
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
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)")
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
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)")
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
   * @return the redacted, persisted DTO
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
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditJobOrderAsRequester(#id)")
  public JobOrderDto updateJobOrderAsRequester(
      @PathVariable UUID id, @RequestBody @Valid CreateJobOrderDto dto) {
    return cleanupJobOrderForRequester(jobOrderService.updateJobOrderAsRequester(id, dto));
  }

  /**
   * Requester-side edit of an ITEM order's lines and comment (REQ-ORDERS-023), re-deriving the
   * required materials; same rules as {@link #updateJobOrderAsRequester}.
   *
   * @param id job-order id
   * @param dto the new item lines + comment (carries the expected version)
   * @return the redacted, persisted DTO
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
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditJobOrderAsRequester(#id)")
  public JobOrderDto updateItemJobOrderAsRequester(
      @PathVariable UUID id, @RequestBody @Valid CreateJobOrderItemRequestDto dto) {
    return cleanupJobOrderForRequester(jobOrderService.updateItemJobOrderAsRequester(id, dto));
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
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)")
  public JobOrderDto updateBlueprintVariantCounting(
      @PathVariable UUID id, @NotNull @RequestBody @Valid UpdateJobOrderBlueprintCountingDto dto) {
    return jobOrderService.updateBlueprintVariantCounting(
        id, dto.countBlueprintsWithVariants(), dto.version());
  }

  /**
   * Reassigns the responsible (processing) org unit of an order. Admins may reassign freely to any
   * profit-eligible org unit; a squadron logistician/officer may only escalate their own squadron's
   * order to a Spezialkommando. The detailed permission rule is enforced in the service.
   *
   * @param id job-order id
   * @param body the target responsible org unit id
   * @return the updated DTO
   */
  @PatchMapping("/{id}/responsible-org-unit")
  @Operation(
      summary = "Reassign responsible org unit",
      description =
          "Changes which org unit processes the order. Admin: free to any profit-eligible org unit;"
              + " squadron logistician/officer: escalate own squadron's order to an SK only.")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)")
  public JobOrderDto reassignResponsibleOrgUnit(
      @PathVariable UUID id, @NotNull @RequestBody @Valid ReassignResponsibleOrgUnitRequest body) {
    return jobOrderService.reassignResponsibleOrgUnit(id, body.responsibleOrgUnitId());
  }

  /**
   * Request body for the responsible-org-unit reassignment endpoint.
   *
   * @param responsibleOrgUnitId the target profit-eligible org unit id (required)
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
   * Removes a material from the order and atomically unlinks every inventory item that pointed at
   * it.
   *
   * @param jobOrderId job-order id
   * @param materialId material id
   */
  @DeleteMapping("/{jobOrderId}/materials/{materialId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Unlink a material from a job order",
      description =
          "Removes the link between a material and a job order, and unlinks all associated"
              + " inventory items.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Material successfully unlinked"),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role"),
    @ApiResponse(responseCode = "404", description = "Job order or material not found")
  })
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.LOGISTICIAN
          + "', '"
          + Roles.OFFICER
          + "', '"
          + Roles.ADMIN
          + "') and @ownerScopeService.canEditJobOrder(#jobOrderId)")
  public void unlinkMaterial(@PathVariable UUID jobOrderId, @PathVariable UUID materialId) {
    jobOrderService.unlinkMaterial(jobOrderId, materialId);
  }

  /**
   * Removes a single inventory item from the order — sets {@code jobOrderId=null} via Hibernate
   * dirty-checking (no bulk update, so the surrounding aggregate stays managed).
   *
   * @param jobOrderId job-order id
   * @param inventoryItemId inventory-item id
   */
  @DeleteMapping("/{jobOrderId}/inventory/{inventoryItemId}/unlink")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Unlink a single inventory item from a job order",
      description =
          "Removes the link between a single inventory item and a job order by setting jobOrderId"
              + " to null. Uses Hibernate dirty-checking (no bulk update).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Inventory item successfully unlinked"),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role"),
    @ApiResponse(responseCode = "404", description = "Job order or inventory item not found")
  })
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.LOGISTICIAN
          + "', '"
          + Roles.OFFICER
          + "', '"
          + Roles.ADMIN
          + "') and @ownerScopeService.canEditJobOrder(#jobOrderId)")
  public void unlinkInventoryItem(
      @PathVariable UUID jobOrderId, @PathVariable UUID inventoryItemId) {
    jobOrderService.unlinkInventoryItem(jobOrderId, inventoryItemId);
  }

  /**
   * Adds a user as assignee. Self-assignment works for everyone; assigning someone else requires
   * LOGISTICIAN or above (enforced by {@link #verifyAssigneeAccess} at the HTTP boundary so the
   * service stays free of {@code SecurityContextHolder} reads).
   *
   * @param id job-order id
   * @param userId target user id
   * @param jwt caller's JWT
   * @return the persisted DTO
   */
  @PostMapping("/{id}/assignees/{userId}")
  @Operation(summary = "Add an assignee", description = "Adds a user to the job order.")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)")
  public JobOrderDto addAssignee(
      @PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, userId);
    return jobOrderService.addAssignee(id, userId);
  }

  /**
   * Removes a user as assignee. Same self-or-logistician rule as {@link #addAssignee}.
   *
   * @param id job-order id
   * @param userId target user id
   * @param jwt caller's JWT
   * @return the persisted DTO
   */
  @DeleteMapping("/{id}/assignees/{userId}")
  @Operation(summary = "Remove an assignee", description = "Removes a user from the job order.")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)")
  public JobOrderDto removeAssignee(
      @PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, userId);
    return jobOrderService.removeAssignee(id, userId);
  }

  /**
   * Enforces the self-or-logistician rule for assignee mutations. Throws {@link
   * AccessDeniedException} when a non-LOGISTICIAN caller tries to modify someone else's assignment.
   *
   * @param targetUserId user id being added/removed
   * @param jwt caller's JWT
   */
  private void verifyAssigneeAccess(Jwt jwt, UUID targetUserId) {
    UUID currentUserId = userService.getUserIdFromJwt(jwt);
    if (!currentUserId.equals(targetUserId) && !authHelperService.isLogisticianOrAbove()) {
      throw new AccessDeniedException("Not allowed to modify other users' assignments");
    }
  }

  /**
   * Sets or replaces the note on an assignee entry. Same self-or-logistician rule as {@link
   * #addAssignee}; optimistic-locked on the assignee edge's own version.
   *
   * @param id job-order id
   * @param userId the assignee whose note is changed
   * @param body the new note + the edge version last seen by the client
   * @param jwt caller's JWT
   * @return the persisted DTO with the refreshed assignee list
   */
  @PutMapping("/{id}/assignees/{userId}/note")
  @Operation(
      summary = "Set an assignee note",
      description = "Creates or replaces the note on a user's assignee entry.")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)")
  public JobOrderDto setAssigneeNote(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @RequestBody @Valid AssigneeNoteRequest body,
      @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, userId);
    return jobOrderService.updateAssigneeNote(id, userId, body.note(), body.version());
  }

  /**
   * Clears the note on an assignee entry. Same self-or-logistician rule and optimistic-locking
   * semantics as {@link #setAssigneeNote}.
   *
   * @param id job-order id
   * @param userId the assignee whose note is cleared
   * @param jwt caller's JWT
   * @param version the assignee edge version last seen by the client, or {@code null} to skip the
   *     check
   * @return the persisted DTO with the refreshed assignee list
   */
  @DeleteMapping("/{id}/assignees/{userId}/note")
  @Operation(
      summary = "Delete an assignee note",
      description = "Clears the note on a user's assignee entry.")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)")
  public JobOrderDto deleteAssigneeNote(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @RequestParam(required = false) Long version,
      @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, userId);
    return jobOrderService.deleteAssigneeNote(id, userId, version);
  }

  /**
   * Request body for the assignee-note PUT endpoint.
   *
   * @param note the new note text (at most {@value
   *     de.greluc.krt.profit.basetool.backend.model.JobOrderAssignee#NOTE_MAX_LENGTH} characters;
   *     blank/{@code null} clears the note)
   * @param version the assignee edge version the client last saw, or {@code null} to skip the
   *     optimistic-lock check
   */
  public record AssigneeNoteRequest(
      @Size(max = JobOrderAssignee.NOTE_MAX_LENGTH) String note, Long version) {}
}
