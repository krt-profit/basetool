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

import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewApplyRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewBatchRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewCaseDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewGroupDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewWorkspaceDetailDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Relays the P4K review workspace's grouped reads and confirmed changes. */
@Service
@RequiredArgsConstructor
public class P4kReviewWorkspaceBackendClient {

  private static final String BASE = "/api/v1/catalog/admin/import/p4k/review-workspace";
  private static final String PAGING = "page={page}&size={size}";

  private static final ParameterizedTypeReference<PageResponse<P4kReviewGroupDto>> GROUP_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<P4kReviewCaseDto>> CASE_PAGE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Lists groups of open cases matching the selected filters.
   *
   * @param kind the selected catalogue kind, or all
   * @param reason the selected review reason, or all
   * @param readiness the selected readiness, or all
   * @param page zero-based page index
   * @param size the number of groups per page
   * @return the group page, or {@code null} when the backend sends no body
   */
  @Nullable
  public PageResponse<P4kReviewGroupDto> groups(
      @Nullable String kind,
      @Nullable String reason,
      @Nullable String readiness,
      int page,
      int size) {
    return backendApiClient.get(
        BASE + "?kind={kind}&reason={reason}&readiness={readiness}&" + PAGING,
        GROUP_PAGE,
        Objects.requireNonNullElse(kind, ""),
        Objects.requireNonNullElse(reason, ""),
        Objects.requireNonNullElse(readiness, ""),
        page,
        size);
  }

  /**
   * Lists the open cases in a review group.
   *
   * @param id the opaque group identifier
   * @param page zero-based page index
   * @param size the number of cases per page
   * @return the case page, or {@code null} when the backend sends no body
   */
  @Nullable
  public PageResponse<P4kReviewCaseDto> groupMembers(@NotNull UUID id, int page, int size) {
    return backendApiClient.get(BASE + "/groups/{id}?" + PAGING, CASE_PAGE, id, page, size);
  }

  /**
   * Loads the evaluated actions and evidence for a case.
   *
   * @param id the case identifier
   * @return the detail, or {@code null} when the backend sends no body
   */
  @Nullable
  public P4kReviewWorkspaceDetailDto detail(@NotNull UUID id) {
    return backendApiClient.get(BASE + "/{caseId}", P4kReviewWorkspaceDetailDto.class, id);
  }

  /**
   * Applies a confirmed review action through the catalogue's guarded write path.
   *
   * @param id the case identifier
   * @param request the selected action and previewed versions
   * @return the resolved case, or {@code null} when the backend sends no body
   */
  @Nullable
  public P4kReviewCaseDto apply(@NotNull UUID id, @NotNull P4kReviewApplyRequest request) {
    return backendApiClient.post(BASE + "/{caseId}/apply", request, P4kReviewCaseDto.class, id);
  }

  /**
   * Resolves the selected members of an open review group.
   *
   * @param id the group identifier
   * @param request the selected members and the bulk resolution
   */
  public void resolveGroup(@NotNull UUID id, @NotNull P4kReviewBatchRequest request) {
    backendApiClient.post(BASE + "/groups/{id}/resolve", request, Void.class, id);
  }
}
