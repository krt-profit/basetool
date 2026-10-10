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

package de.greluc.krt.profit.basetool.frontend.operation.client;

import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationForm;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationPayoutStatusUpdateDto;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationPayoutSummaryDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the operation domain: the operation list and detail with its missions,
 * finance roll-up and payouts, and the operation writes, over {@link BackendApiClient} (plan §5.9,
 * ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class OperationBackendClient {

  private static final ParameterizedTypeReference<PageResponse<OperationDto>> OPERATION_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      PICKABLE_ORG_UNIT_LIST_TYPE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * The filters of one operation-list page, each already narrowed by the caller (REQ-SEC-051).
   *
   * @param query free-text filter; {@code null} or blank sends none
   * @param start inclusive lower bound on the linked missions' span, or {@code null}
   * @param end inclusive upper bound on the linked missions' span, or {@code null}
   * @param page zero-based page index
   * @param size page size
   * @param period the effective period segment, {@code ALL}, {@code PAST} or {@code UPCOMING}
   */
  public record OperationSearch(
      @Nullable String query,
      @Nullable Instant start,
      @Nullable Instant end,
      @NotNull Integer page,
      @NotNull Integer size,
      @NotNull String period) {}

  /**
   * Reads one page of the operations list, newest first.
   *
   * @param search the page and its filters
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<OperationDto> searchOperations(@NotNull OperationSearch search) {
    StringBuilder uri = new StringBuilder("/api/v1/operations/search?");
    List<Object> uriVariables = new ArrayList<>();
    if (search.query() != null && !search.query().isBlank()) {
      uri.append("query={query}&");
      uriVariables.add(search.query());
    }
    if (search.start() != null) {
      uri.append("start={start}&");
      uriVariables.add(search.start());
    }
    if (search.end() != null) {
      uri.append("end={end}&");
      uriVariables.add(search.end());
    }
    uri.append("page={page}&");
    uriVariables.add(search.page());
    uri.append("size={size}&");
    uriVariables.add(search.size());
    uri.append("sort=createdAt,desc&");
    switch (search.period()) {
      case "ALL" -> uri.append("status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELED&");
      case "PAST" -> uri.append("status=COMPLETED&status=CANCELED&");
      default -> uri.append("status=PLANNED&status=ACTIVE&");
    }
    return backendApiClient.get(uri.toString(), OPERATION_PAGE_TYPE, uriVariables.toArray());
  }

  /**
   * Lists the org units the caller may pick as an operation's owner.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> pickableOrgUnits() {
    return backendApiClient.get("/api/v1/users/me/pickable-org-units", PICKABLE_ORG_UNIT_LIST_TYPE);
  }

  /**
   * Reads one operation.
   *
   * @param id the operation
   * @return the operation, or {@code null} when the backend sent no body
   */
  @Nullable
  public OperationDto operation(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/operations/{id}", OperationDto.class, id);
  }

  /**
   * Reads one page of the operation's missions, earliest planned start first.
   *
   * @param id the operation
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MissionListDto> missions(
      @NotNull UUID id, @Nullable Integer page, @Nullable Integer size) {
    return backendApiClient.get(
        "/api/v1/missions/search?operationId={id}&page={page}&size={size}"
            + "&sort=plannedStartTime,asc",
        MISSION_PAGE_TYPE,
        id,
        page,
        size);
  }

  /**
   * Reads the operation's finance roll-up.
   *
   * @param id the operation
   * @return the roll-up, or {@code null} when the backend sent no body
   */
  @Nullable
  public OperationFinanceSummaryDto financeSummary(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/operations/{id}/finance-summary", OperationFinanceSummaryDto.class, id);
  }

  /**
   * Reads the operation's payouts and donation total.
   *
   * @param id the operation
   * @return the payout summary, or {@code null} when the backend sent no body
   */
  @Nullable
  public OperationPayoutSummaryDto payouts(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/operations/{id}/payouts", OperationPayoutSummaryDto.class, id);
  }

  /**
   * Reads one mission's finance breakdown within the operation.
   *
   * @param id the operation
   * @param missionId the mission, which must belong to the operation
   * @return the breakdown, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionFinanceSummaryDto missionFinance(@NotNull UUID id, @NotNull UUID missionId) {
    return backendApiClient.get(
        "/api/v1/operations/{id}/finances/{missionId}",
        MissionFinanceSummaryDto.class,
        id,
        missionId);
  }

  /**
   * Creates an operation.
   *
   * @param form the new operation
   */
  public void createOperation(@Nullable OperationForm form) {
    backendApiClient.post("/api/v1/operations", form, Void.class);
  }

  /**
   * Updates an operation, carrying the optimistic-lock version in the form.
   *
   * @param id the operation
   * @param form the edit
   */
  public void updateOperation(@NotNull UUID id, @Nullable OperationForm form) {
    backendApiClient.put("/api/v1/operations/{id}", form, Void.class, id);
  }

  /**
   * Updates an operation and answers the stored operation.
   *
   * @param id the operation
   * @param form the edit, carrying the optimistic-lock version
   * @return the updated operation, or {@code null} when the backend sent no body
   */
  @Nullable
  public OperationDto updateOperationAndRead(@NotNull UUID id, @Nullable OperationForm form) {
    return backendApiClient.put("/api/v1/operations/{id}", form, OperationDto.class, id);
  }

  /**
   * Sets or clears one participant's paid-out flag.
   *
   * @param id the operation
   * @param request the participant key and the new flag
   * @return the refreshed paid-out status, or {@code null} when the backend sent no body
   */
  @Nullable
  public OperationPayoutStatusDto updatePayoutStatus(
      @NotNull UUID id, @Nullable OperationPayoutStatusUpdateDto request) {
    return backendApiClient.put(
        "/api/v1/operations/{id}/payouts/paid-out", request, OperationPayoutStatusDto.class, id);
  }

  /**
   * Deletes an operation.
   *
   * @param id the operation
   */
  public void deleteOperation(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/operations/{id}", Void.class, id);
  }
}
