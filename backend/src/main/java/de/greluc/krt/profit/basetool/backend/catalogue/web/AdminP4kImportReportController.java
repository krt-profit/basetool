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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialCategoryService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kChangeType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportChangeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportDueDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportDueService;
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
import java.util.List;
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
 *   <li>{@code POST /review-cases/{id}/resolve} — resolve a case as keep, hide or an assignment.
 *   <li>{@code GET /resource-groups}, {@code GET /materials} — what an assignment may pick.
 *   <li>{@code GET /due} — whether a P4K import is due (REQ-P4K-047).
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
  private final P4kImportDueService dueService;
  private final MaterialCategoryService categoryService;

  /**
   * Tells whether a P4K import is due: the UEX or the SC Wiki game version is newer than the patch
   * version of the last APPLY of known version, or no such APPLY exists (REQ-P4K-047).
   *
   * @return the decision and the versions it compared
   */
  @GetMapping("/due")
  @Operation(summary = "Tell whether a P4K import is due.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The due signal."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public P4kImportDueDto due() {
    return dueService.evaluate();
  }

  /**
   * An admin's resolution of a review case.
   *
   * @param resolution keep, hide, or assign for a {@code NO_RESOURCE_GROUP} or a resource-join case
   * @param categoryId the resource group an {@code ASSIGN} of a missing group gives the material,
   *     else {@code null}
   * @param materialId the material an {@code ASSIGN} of a resource-join case gives the resource,
   *     else {@code null}
   * @param version the case version the admin saw
   */
  public record P4kReviewResolveRequest(
      @NotNull P4kReviewResolution resolution,
      @Nullable UUID categoryId,
      @Nullable UUID materialId,
      @NotNull Long version) {}

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
  @Operation(summary = "Resolve a P4K review case as keep, hide or an assignment.")
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
    return reviewService.resolve(
        id,
        request.resolution(),
        request.categoryId(),
        request.materialId(),
        request.version(),
        adminUserId);
  }

  /**
   * Lists the resource groups an admin may assign to a material without one (REQ-P4K-025).
   *
   * @return the live P4K resource groups, sorted by name
   */
  @GetMapping("/resource-groups")
  @Operation(summary = "List the resource groups a missing-group review case can assign.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The live resource groups."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public List<MaterialCategoryDto> resourceGroups() {
    return categoryService.findResourceGroups();
  }

  /**
   * Lists the materials an admin may assign to a resource that joins no commodity or several
   * (REQ-P4K-024).
   *
   * @return the live materials, sorted by name
   */
  @GetMapping("/materials")
  @Operation(summary = "List the materials a resource-join review case can assign.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The live materials."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public List<MaterialReferenceDto> materials() {
    return reviewService.liveMaterials();
  }
}
