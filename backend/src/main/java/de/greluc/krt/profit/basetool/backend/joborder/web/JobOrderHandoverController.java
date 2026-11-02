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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverReportService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemHandoverReportService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemHandoverService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemProductionService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.PdfResponses;
import de.greluc.krt.profit.basetool.backend.platform.api.UserZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over the handovers of a job order: material and item handovers, the production
 * booking of an ordered item line and the handover report PDFs.
 *
 * <p>Every endpoint needs LOGISTICIAN or ADMIN and edit access to the order.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderHandoverController {
  private final JobOrderItemHandoverService jobOrderItemHandoverService;
  private final JobOrderItemProductionService jobOrderItemProductionService;
  private final JobOrderItemHandoverReportService jobOrderItemHandoverReportService;
  private final JobOrderHandoverService jobOrderHandoverService;
  private final JobOrderHandoverReportService jobOrderHandoverReportService;

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
          + Roles.ADMIN
          + "')) and @jobOrderAccessPolicy.canEditJobOrder(#id)")
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
          + Roles.ADMIN
          + "')) and @jobOrderAccessPolicy.canEditJobOrder(#id)")
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
          + Roles.ADMIN
          + "')) and @jobOrderAccessPolicy.canEditJobOrder(#id)")
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
          + Roles.ADMIN
          + "') and @jobOrderAccessPolicy.canEditJobOrder(#jobOrderId)")
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
          + Roles.ADMIN
          + "') and @jobOrderAccessPolicy.canEditJobOrder(#jobOrderId)")
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
          + Roles.ADMIN
          + "') and @jobOrderAccessPolicy.canEditJobOrder(#jobOrderId)")
  public ResponseEntity<byte[]> previewHandoverReport(
      @PathVariable UUID jobOrderId, @RequestBody @Valid HandoverReportPreviewRequestDto dto) {
    byte[] pdf = jobOrderHandoverReportService.generateHandoverReportPreview(dto);
    String filename = "uebergabeprotokoll-vorschau.pdf";
    return PdfResponses.pdfAttachment(pdf, filename);
  }
}
