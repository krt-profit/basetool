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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kChangeType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportChangeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportReportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportRunDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewResolution;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only reads of the P4K runs and change reports, and the review list (REQ-P4K-014…016).
 *
 * <ul>
 *   <li>{@code GET /runs} — the applied runs, newest first.
 *   <li>{@code GET /runs/{id}/changes} — an applied run's report, filtered and paged.
 *   <li>{@code GET /jobs/{id}/changes} — a preview's report, filtered and paged.
 *   <li>{@code GET /review-cases} — the open review cases.
 *   <li>{@code POST /review-cases/{id}/resolve} — resolve a case as keep or hide.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/catalog/admin/import/p4k")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – P4K Import",
    description =
        "Administrator endpoints for importing the p4kx catalogue (schemaVersion 2, LIVE) as"
            + " asynchronous background jobs.")
@SecurityRequirement(name = "bearer-jwt")
public class AdminP4kImportReportController {

  private final P4kImportReportService reportService;
  private final P4kReviewService reviewService;

  /**
   * An admin's resolution of a review case.
   *
   * @param resolution keep or hide
   * @param version the case version the admin saw
   */
  public record P4kReviewResolveRequest(
      @NotNull P4kReviewResolution resolution, @NotNull Long version) {}

  /**
   * Pages the applied runs, newest first.
   *
   * @param page zero-based page index
   * @param size page size
   * @return the page of runs with their counts per kind
   */
  @GetMapping("/runs")
  @Operation(summary = "List the applied P4K import runs, newest first.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The runs."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<P4kImportRunDto> runs(
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        reportService.runs(PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Pages an applied run's change report.
   *
   * @param id the run
   * @param kind the kind to show, or all
   * @param type the change type to show, or all
   * @param page zero-based page index
   * @param size page size
   * @return the page of report rows in the order the run found them
   */
  @GetMapping("/runs/{id}/changes")
  @Operation(summary = "Page the change report of an applied P4K run.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The report rows."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No run with the given id.")
  })
  public PageResponse<P4kImportChangeDto> runChanges(
      @PathVariable("id") UUID id,
      @RequestParam(required = false) @Nullable P4kKind kind,
      @RequestParam(required = false) @Nullable P4kChangeType type,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        reportService.runChanges(
            id, kind, type, PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Pages the change report a preview job computed.
   *
   * @param id the preview job
   * @param kind the kind to show, or all
   * @param type the change type to show, or all
   * @param page zero-based page index
   * @param size page size
   * @return the page of report rows in the order the preview found them
   */
  @GetMapping("/jobs/{id}/changes")
  @Operation(summary = "Page the change report a P4K preview job computed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The report rows."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(
        responseCode = "404",
        description = "No job with the given id, or the job holds no preview report.")
  })
  public PageResponse<P4kImportChangeDto> previewChanges(
      @PathVariable("id") UUID id,
      @RequestParam(required = false) @Nullable P4kKind kind,
      @RequestParam(required = false) @Nullable P4kChangeType type,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        reportService.previewChanges(
            id, kind, type, PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Pages the open review cases, oldest first.
   *
   * @param page zero-based page index
   * @param size page size
   * @return the page of open cases
   */
  @GetMapping("/review-cases")
  @Operation(summary = "List the open P4K review cases.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The open cases."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<P4kReviewCaseDto> openReviewCases(
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        reviewService.openCases(PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Resolves an open review case as keep or hide.
   *
   * @param id the case
   * @param request the resolution and the case version the admin saw
   * @param adminUserId the resolving administrator
   * @return the resolved case
   */
  @PostMapping("/review-cases/{id}/resolve")
  @Operation(summary = "Resolve a P4K review case as keep or hide.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The resolved case."),
    @ApiResponse(responseCode = "400", description = "The case is already resolved."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No case with the given id."),
    @ApiResponse(responseCode = "409", description = "The case changed since it was read.")
  })
  public P4kReviewCaseDto resolve(
      @PathVariable("id") UUID id,
      @RequestBody @Valid P4kReviewResolveRequest request,
      @CurrentUserId UUID adminUserId) {
    return reviewService.resolve(id, request.resolution(), request.version(), adminUserId);
  }
}
