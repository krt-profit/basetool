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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportChangeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportDueDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportRunDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewResolveRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the P4K run list, the change reports and the review list
 * (REQ-P4K-014…016) over {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class P4kReportBackendClient {

  /** The P4K import base path. */
  private static final String BASE = "/api/v1/catalog/admin/import/p4k";

  /** The paging query every list carries. */
  private static final String PAGING = "page={page}&size={size}";

  /** The filter and paging query of a change report. */
  private static final String REPORT_QUERY = "/changes?kind={kind}&type={type}&" + PAGING;

  private static final ParameterizedTypeReference<PageResponse<P4kImportRunDto>> RUN_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<P4kImportChangeDto>> CHANGE_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<P4kReviewCaseDto>> CASE_PAGE =
      new ParameterizedTypeReference<>() {};

  /** The materials a resource-join case can assign. */
  private static final ParameterizedTypeReference<List<MaterialReferenceDto>> MATERIAL_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialCategoryDto>> GROUP_LIST =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads whether a P4K import is due (REQ-P4K-047).
   *
   * @return the due signal, or {@code null} when the backend sent no body
   */
  @Nullable
  public P4kImportDueDto importDue() {
    return backendApiClient.get(BASE + "/due", P4kImportDueDto.class);
  }

  /**
   * Reads one page of the applied runs, newest first.
   *
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<P4kImportRunDto> runs(int page, int size) {
    return backendApiClient.get(BASE + "/runs?" + PAGING, RUN_PAGE, page, size);
  }

  /**
   * Reads one page of an applied run's report.
   *
   * @param runId the run
   * @param kind the kind to show, or {@code null} for all
   * @param type the change type to show, or {@code null} for all
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<P4kImportChangeDto> runChanges(
      @NotNull UUID runId, @Nullable String kind, @Nullable String type, int page, int size) {
    return backendApiClient.get(
        BASE + "/runs/{id}" + REPORT_QUERY,
        CHANGE_PAGE,
        runId,
        orEmpty(kind),
        orEmpty(type),
        page,
        size);
  }

  /**
   * Reads one page of a preview job's report.
   *
   * @param jobId the preview job
   * @param kind the kind to show, or {@code null} for all
   * @param type the change type to show, or {@code null} for all
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<P4kImportChangeDto> previewChanges(
      @NotNull UUID jobId, @Nullable String kind, @Nullable String type, int page, int size) {
    return backendApiClient.get(
        BASE + "/jobs/{id}" + REPORT_QUERY,
        CHANGE_PAGE,
        jobId,
        orEmpty(kind),
        orEmpty(type),
        page,
        size);
  }

  /**
   * Reads one page of the open review cases.
   *
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<P4kReviewCaseDto> openReviewCases(int page, int size) {
    return backendApiClient.get(BASE + "/review-cases?" + PAGING, CASE_PAGE, page, size);
  }

  /**
   * Resolves a review case.
   *
   * @param caseId the case
   * @param request the resolution and the version the admin saw
   * @return the resolved case, or {@code null} when the backend sent no body
   */
  @Nullable
  public P4kReviewCaseDto resolveReviewCase(
      @NotNull UUID caseId, @NotNull P4kReviewResolveRequest request) {
    return backendApiClient.post(
        BASE + "/review-cases/{id}/resolve", request, P4kReviewCaseDto.class, caseId);
  }

  /**
   * Lists the resource groups a missing-group review case can assign (REQ-P4K-025).
   *
   * @return the live resource groups, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialCategoryDto> resourceGroups() {
    return backendApiClient.get(BASE + "/resource-groups", GROUP_LIST);
  }

  /**
   * Lists the materials a resource-join review case can assign (REQ-P4K-024).
   *
   * @return the live materials, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialReferenceDto> materials() {
    return backendApiClient.get(BASE + "/materials", MATERIAL_LIST);
  }

  /**
   * Turns an absent filter into the empty query value the backend reads as "all".
   *
   * @param value the filter, or {@code null}
   * @return the filter, or an empty string
   */
  @NotNull
  private static String orEmpty(@Nullable String value) {
    return Objects.requireNonNullElse(value, "");
  }
}
