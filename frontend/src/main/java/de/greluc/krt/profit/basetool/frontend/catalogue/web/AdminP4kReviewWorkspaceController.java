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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;
import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.P4kReviewWorkspaceBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewApplyRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewBatchRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewGroupDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewWorkspaceDetailDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin admin endpoints for the P4K review workspace. */
@RestController
@RequestMapping("/admin/p4k-import/review-workspace")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminP4kReviewWorkspaceController {

  private final P4kReviewWorkspaceBackendClient client;

  /**
   * Lists the open review groups using the current queue filters.
   *
   * @param kind the catalogue kind, or all
   * @param reason the reason, or all
   * @param readiness the readiness, or all
   * @param page zero-based page index
   * @param size the number of groups per page
   * @return the group page, or {@code null} when the backend sends no body
   */
  @GetMapping
  @Nullable
  public PageResponse<P4kReviewGroupDto> groups(
      @RequestParam(required = false) @Nullable String kind,
      @RequestParam(required = false) @Nullable String reason,
      @RequestParam(required = false) @Nullable String readiness,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return withBackendStatus(() -> client.groups(kind, reason, readiness, page, size));
  }

  /**
   * Lists the individual open cases in a review group.
   *
   * @param id the group identifier
   * @param page zero-based page index
   * @param size the number of cases per page
   * @return the case page, or {@code null} when the backend sends no body
   */
  @GetMapping("/groups/{id}")
  @Nullable
  public PageResponse<P4kReviewCaseDto> groupMembers(
      @PathVariable("id") @NotNull UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return withBackendStatus(() -> client.groupMembers(id, page, size));
  }

  /**
   * Reads the candidate comparison for an open review case.
   *
   * @param caseId the review case
   * @return the review detail, or {@code null} when the backend sends no body
   */
  @GetMapping("/{caseId}")
  @Nullable
  public P4kReviewWorkspaceDetailDto detail(@PathVariable("caseId") @NotNull UUID caseId) {
    return withBackendStatus(() -> client.detail(caseId));
  }

  /**
   * Applies a review action using the versions from its comparison.
   *
   * @param caseId the review case
   * @param request the selected action, target and versions
   * @return the resolved case or the backend's refusal
   */
  @PostMapping("/{caseId}/apply")
  @NotNull
  public ResponseEntity<Object> apply(
      @PathVariable("caseId") @NotNull UUID caseId,
      @RequestBody @Valid @NotNull P4kReviewApplyRequest request) {
    return relay(
        log, "P4K review workspace action", () -> ResponseEntity.ok(client.apply(caseId, request)));
  }

  /**
   * Resolves the selected members of a review group using their displayed versions.
   *
   * @param id the group identifier
   * @param request the resolution and selected cases
   * @return no content after success, or the backend's refusal
   */
  @PostMapping("/groups/{id}/resolve")
  @NotNull
  public ResponseEntity<Object> resolveGroup(
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull P4kReviewBatchRequest request) {
    return relay(
        log,
        "P4K review group resolution",
        () -> {
          client.resolveGroup(id, request);
          return ResponseEntity.noContent().build();
        });
  }
}
