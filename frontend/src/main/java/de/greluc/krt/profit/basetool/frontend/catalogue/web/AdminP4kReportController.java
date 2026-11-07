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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.P4kReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportChangeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportDueDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportRunDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportRunsView;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewResolveRequest;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * JSON proxy of the {@code /admin/p4k-import} page for the run list, the change reports and the
 * review list (REQ-P4K-014…016); the page swaps each list in place through {@code krtFetch}.
 */
@RestController
@RequestMapping("/admin/p4k-import")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminP4kReportController {

  /** The page size of every list the page shows. */
  private static final int PAGE_SIZE = 25;

  /** Relays the runs, reports and review cases. */
  private final P4kReportBackendClient reportClient;

  /** Resolves the admins who applied the runs to display names. */
  private final IdentityBackendClient identityClient;

  /**
   * Tells the page's "P4K import due" banner whether to show and which versions it compares
   * (REQ-P4K-047).
   *
   * @return the due signal, or {@code null} when the backend sent no body
   */
  @GetMapping("/due")
  @Nullable
  public P4kImportDueDto due() {
    return withBackendStatus(reportClient::importDue);
  }

  /**
   * Lists one page of the applied runs, newest first, with the names of their admins.
   *
   * @param page zero-based page index
   * @return the runs and the admin names
   */
  @GetMapping("/runs")
  @NotNull
  public P4kImportRunsView runs(@RequestParam(defaultValue = "0") int page) {
    PageResponse<P4kImportRunDto> runs =
        withBackendStatus(() -> reportClient.runs(page, PAGE_SIZE));
    Map<String, String> names = new HashMap<>();
    for (P4kImportRunDto run : PageResponse.contentOf(runs)) {
      names.computeIfAbsent(run.createdBy(), this::adminName);
    }
    return new P4kImportRunsView(
        runs == null ? new PageResponse<>(List.of(), page, PAGE_SIZE, 0, 0, List.of()) : runs,
        Map.copyOf(names));
  }

  /**
   * Lists one page of an applied run's report.
   *
   * @param id the run
   * @param kind the kind to show, or all
   * @param type the change type to show, or all
   * @param page zero-based page index
   * @return the page of report rows
   */
  @GetMapping("/runs/{id}/changes")
  @Nullable
  public PageResponse<P4kImportChangeDto> runChanges(
      @PathVariable("id") @NotNull UUID id,
      @RequestParam(required = false) @Nullable String kind,
      @RequestParam(required = false) @Nullable String type,
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() -> reportClient.runChanges(id, kind, type, page, PAGE_SIZE));
  }

  /**
   * Lists one page of the report a preview job computed.
   *
   * @param id the preview job
   * @param kind the kind to show, or all
   * @param type the change type to show, or all
   * @param page zero-based page index
   * @return the page of report rows
   */
  @GetMapping("/jobs/{id}/changes")
  @Nullable
  public PageResponse<P4kImportChangeDto> previewChanges(
      @PathVariable("id") @NotNull UUID id,
      @RequestParam(required = false) @Nullable String kind,
      @RequestParam(required = false) @Nullable String type,
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() -> reportClient.previewChanges(id, kind, type, page, PAGE_SIZE));
  }

  /**
   * Lists one page of the open review cases.
   *
   * @param page zero-based page index
   * @return the page of open cases
   */
  @GetMapping("/review-cases")
  @Nullable
  public PageResponse<P4kReviewCaseDto> openReviewCases(
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() -> reportClient.openReviewCases(page, PAGE_SIZE));
  }

  /**
   * Lists the resource groups a missing-group review case can assign.
   *
   * @return the live resource groups
   */
  @GetMapping("/resource-groups")
  @Nullable
  public List<MaterialCategoryDto> resourceGroups() {
    return withBackendStatus(reportClient::resourceGroups);
  }

  /**
   * Lists the materials a resource-join review case can assign.
   *
   * @return the live materials
   */
  @GetMapping("/materials")
  @Nullable
  public List<MaterialReferenceDto> materials() {
    return withBackendStatus(reportClient::materials);
  }

  /**
   * Resolves a review case as keep, hide or by assigning a resource group; a stale version is
   * relayed as the backend's {@code 409} problem.
   *
   * @param id the case
   * @param request the resolution and the case version the admin saw
   * @return the resolved case, or the backend's refusal
   */
  @PostMapping("/review-cases/{id}/resolve")
  @NotNull
  public ResponseEntity<Object> resolve(
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull P4kReviewResolveRequest request) {
    return relay(
        log,
        "P4K review case resolution",
        () -> ResponseEntity.ok(reportClient.resolveReviewCase(id, request)));
  }

  /**
   * Reads an admin's display name.
   *
   * @param userId the admin's user id
   * @return the display name, else the user name, or {@code null} when the user is gone
   */
  @Nullable
  private String adminName(@NotNull String userId) {
    try {
      UserDto user = identityClient.user(UUID.fromString(userId));
      if (user == null) {
        return null;
      }
      return user.displayName() != null ? user.displayName() : user.username();
    } catch (BackendServiceException e) {
      log.debug("P4K run admin {} has no readable user record: {}", userId, e.getStatusCode());
      return null;
    }
  }
}
