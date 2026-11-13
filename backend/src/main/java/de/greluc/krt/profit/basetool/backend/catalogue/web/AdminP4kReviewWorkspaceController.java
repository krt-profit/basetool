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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewApplyRequest;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewBatchRequest;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewGroupDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewReadiness;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewReason;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewWorkspaceDetailDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewWorkspaceService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin workspace for reviewing P4K source conflicts and catalogue proposals. */
@RestController
@RequestMapping("/api/v1/catalog/admin/import/p4k/review-workspace")
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(name = "Admin - P4K Import")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class AdminP4kReviewWorkspaceController {

  private final P4kReviewWorkspaceService workspace;

  /**
   * Lists open review groups.
   *
   * @param kind the kind filter
   * @param reason the reason filter
   * @param readiness the next-action filter
   * @param page the zero-based page
   * @param size the page size
   * @return the matching groups
   */
  @GetMapping
  @Operation(summary = "List the open P4K review groups.")
  public PageResponse<P4kReviewGroupDto> groups(
      @RequestParam(required = false) @Nullable P4kKind kind,
      @RequestParam(required = false) @Nullable P4kReviewReason reason,
      @RequestParam(required = false) @Nullable P4kReviewReadiness readiness,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        workspace.groups(
            kind, reason, readiness, PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Lists the current open cases of one group.
   *
   * @param id the group
   * @param page the zero-based page
   * @param size the page size
   * @return the open cases
   */
  @GetMapping("/groups/{id}")
  @Operation(summary = "List open cases in a P4K review group.")
  public PageResponse<P4kReviewCaseDto> groupMembers(
      @PathVariable("id") UUID id,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size) {
    return PageResponse.of(
        workspace.groupMembers(id, PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Displays a case with its current source evidence and candidate actions.
   *
   * @param caseId the open case
   * @return the case and its options
   */
  @GetMapping("/{caseId}")
  @Operation(summary = "Show evidence and candidate actions for a P4K review case.")
  public P4kReviewWorkspaceDetailDto detail(@PathVariable("caseId") UUID caseId) {
    return workspace.detail(caseId);
  }

  /**
   * Confirms one proposed catalogue action.
   *
   * @param caseId the case
   * @param request the action and versions from the proposal
   * @param adminUserId the administrator confirming the proposal
   * @return the completed case
   */
  @PostMapping("/{caseId}/apply")
  @Operation(summary = "Apply a reviewed P4K catalogue proposal.")
  public P4kReviewCaseDto apply(
      @PathVariable("caseId") UUID caseId,
      @Valid @RequestBody P4kReviewApplyRequest request,
      @CurrentUserId UUID adminUserId) {
    return workspace.apply(caseId, request, adminUserId);
  }

  /**
   * Applies the selected decision to the cases inspected in the group.
   *
   * @param id the group
   * @param request the decision and selected case versions
   * @param adminUserId the administrator confirming the decision
   */
  @PostMapping("/groups/{id}/resolve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Resolve selected cases of an open P4K review group.")
  public void resolveGroup(
      @PathVariable("id") UUID id,
      @Valid @RequestBody P4kReviewBatchRequest request,
      @CurrentUserId UUID adminUserId) {
    workspace.resolveBatch(id, request, adminUserId);
  }
}
