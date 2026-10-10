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

package de.greluc.krt.profit.basetool.frontend.refinery.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.RefiningMethodDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Typed backend client of the refinery domain: the order list, detail and mutations, the extract
 * import, the location yields, and the catalogues, missions, memberships and settings the refinery
 * forms offer, over {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class RefineryBackendClient {

  private static final ParameterizedTypeReference<PageResponse<RefineryOrderListDto>>
      REFINERY_ORDER_LIST_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<Map<String, Integer>> STRING_INTEGER_MAP =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> MATERIAL_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<RefiningMethodDto>>
      REFINING_METHOD_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<LocationDto>> LOCATION_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_LIST_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<JobOrderReferenceDto>>
      JOB_ORDER_REFERENCE_LIST = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of refinery orders for a status filter (REQ-REFINERY-019).
   *
   * @param mine whether the caller's own orders are listed instead of every order in scope
   * @param statuses the statuses to include
   * @param ready whether only orders whose end has passed or is unknown are listed
   * @param query the search text sent as {@code q}, or {@code null}
   * @param sort the backend {@code sort} value
   * @param page the zero-based page index
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<RefineryOrderListDto> orderPage(
      boolean mine,
      @NotNull List<String> statuses,
      boolean ready,
      @Nullable String query,
      @NotNull String sort,
      int page,
      int size) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromPath(
                mine ? "/api/v1/refinery-orders/my-orders" : "/api/v1/refinery-orders/all")
            .queryParam("page", "{page}")
            .queryParam("size", "{size}")
            .queryParam("sort", sort)
            .queryParam("status", String.join(",", statuses));
    if (ready) {
      builder.queryParam("ready", true);
    }
    if (query != null) {
      builder.queryParam("q", "{q}");
    }
    String uri = builder.encode().build().toUriString();
    return query == null
        ? backendApiClient.get(uri, REFINERY_ORDER_LIST_PAGE, page, size)
        : backendApiClient.get(uri, REFINERY_ORDER_LIST_PAGE, page, size, query);
  }

  /**
   * Reads one refinery order with its goods.
   *
   * @param id the order
   * @return the order, or {@code null} when the backend sent no body
   */
  @Nullable
  public RefineryOrderDto order(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/refinery-orders/{id}", RefineryOrderDto.class, id);
  }

  /**
   * Matches an uploaded {@code RefineryExtract} into an unsaved order draft; persists nothing.
   *
   * @param extract the parsed extract, a JSON object
   * @return the draft, or {@code null} when the backend sent no body
   */
  @Nullable
  public RefineryImportDraftDto importExtract(@NotNull JsonNode extract) {
    return backendApiClient.post(
        "/api/v1/refinery-orders/import-extract", extract, RefineryImportDraftDto.class);
  }

  /**
   * Stores a new refinery order.
   *
   * @param order the order
   * @return the stored order, or {@code null} when the backend sent no body
   */
  @Nullable
  public RefineryOrderDto create(@NotNull RefineryOrderDto order) {
    return backendApiClient.post("/api/v1/refinery-orders", order, RefineryOrderDto.class);
  }

  /**
   * Saves an edit of a refinery order, carrying its optimistic-lock version.
   *
   * @param id the order
   * @param order the edited order
   * @return the stored order, or {@code null} when the backend sent no body
   */
  @Nullable
  public RefineryOrderDto update(@NotNull UUID id, @NotNull RefineryOrderDto order) {
    return backendApiClient.put("/api/v1/refinery-orders/{id}", order, RefineryOrderDto.class, id);
  }

  /**
   * Cancels a refinery order.
   *
   * @param id the order
   */
  public void cancel(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/refinery-orders/{id}", Void.class, id);
  }

  /**
   * Completes a refinery order by storing its output in the Lager.
   *
   * @param id the order
   * @param store the rows to book in
   */
  public void store(@NotNull UUID id, @NotNull RefineryOrderStoreDto store) {
    backendApiClient.post("/api/v1/refinery-orders/{id}/store", store, Void.class, id);
  }

  /**
   * Reads the UEX yield bonus per material of a refinery location.
   *
   * @param locationId the refinery location
   * @return percent by material id, or {@code null} when the backend sent no body
   */
  @Nullable
  public Map<String, Integer> yields(@NotNull UUID locationId) {
    return backendApiClient.get(
        "/api/v1/refinery-orders/locations/{id}/yields", STRING_INTEGER_MAP, locationId);
  }

  /**
   * Reads the cached, complete material catalogue.
   *
   * @return the materials, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialDto> materials() {
    return backendApiClient.getCached(CachedCatalog.MATERIALS, MATERIAL_PAGE);
  }

  /**
   * Reads the cached, complete refining-method catalogue.
   *
   * @return the methods, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<RefiningMethodDto> refiningMethods() {
    return backendApiClient.getCached(CachedCatalog.REFINING_METHODS, REFINING_METHOD_PAGE);
  }

  /**
   * Reads the cached, complete location catalogue.
   *
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<LocationDto> allLocations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS, LOCATION_PAGE);
  }

  /**
   * Reads the cached list of selectable refinery locations.
   *
   * @return the refinery locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LocationDto> refineryLocations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS_REFINERIES, LOCATION_LIST);
  }

  /**
   * Reads up to 1000 missions, newest planned start first, for the mission dropdowns.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MissionListDto> missions() {
    return backendApiClient.get(
        "/api/v1/missions?size=1000&sort=plannedStartTime,desc", MISSION_LIST_PAGE);
  }

  /**
   * Reads the org units the caller may pick as an order's owner.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> pickableOrgUnits() {
    return backendApiClient.get(
        "/api/v1/users/me/pickable-org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
  }

  /**
   * Reads a member's org-unit memberships as picker options.
   *
   * @param userId the member
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> memberships(@NotNull UUID userId) {
    return backendApiClient.get(
        "/api/v1/users/{id}/memberships", ORG_UNIT_MEMBERSHIP_OPTION_LIST, userId);
  }

  /**
   * Reads one member's profile.
   *
   * @param id the member
   * @return the profile, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto user(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/users/{id}", UserDto.class, id);
  }

  /**
   * Reads the caller's own profile.
   *
   * @return the profile, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto currentUser() {
    return backendApiClient.get("/api/v1/users/me", UserDto.class);
  }

  /**
   * Reads the active job orders the caller may see, for the store dialog (REQ-ORDERS-018).
   *
   * @return the orders, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<JobOrderReferenceDto> activeJobOrders() {
    return backendApiClient.get("/api/v1/orders/lookup", JOB_ORDER_REFERENCE_LIST);
  }

  /**
   * Reads the refinery rounding-mode setting.
   *
   * @return the setting, or {@code null} when the backend sent no body
   */
  @Nullable
  public SystemSettingDto roundingMode() {
    return backendApiClient.get("/api/v1/settings/refinery.rounding.mode", SystemSettingDto.class);
  }
}
