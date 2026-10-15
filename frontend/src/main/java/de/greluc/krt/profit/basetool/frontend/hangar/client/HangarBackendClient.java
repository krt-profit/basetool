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

package de.greluc.krt.profit.basetool.frontend.hangar.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.SetHomeLocationRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOrgUnitResultDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the hangar domain: the own ships and the org-unit overview
 * (REQ-HANGAR-001/002), the ship writes, the home-location bulk action, the ship-export import and
 * the delete-all, over {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class HangarBackendClient {

  /** The backend's ship collection, which also clears the caller's hangar. */
  private static final String SHIPS = "/api/v1/hangar/ships";

  /** The ship-export import path, whose format the backend detects. */
  private static final String IMPORT_SHIPS = "/api/v1/hangar/import/ships";

  /** A member's ship collection on the admin path, keyed by the member's id. */
  private static final String MEMBER_SHIPS = "/api/v1/hangar/admin/users/{userId}/ships";

  /** One ship of a member on the admin path, keyed by the member's and the ship's id. */
  private static final String MEMBER_SHIP = "/api/v1/hangar/admin/users/{userId}/ships/{shipId}";

  private static final ParameterizedTypeReference<PageResponse<ShipDto>> SHIP_PAGE =
      new ParameterizedTypeReference<PageResponse<ShipDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<SquadronShipOverviewDto>>
      OVERVIEW_PAGE = new ParameterizedTypeReference<PageResponse<SquadronShipOverviewDto>>() {};

  private static final ParameterizedTypeReference<List<LocationDto>> LOCATION_LIST =
      new ParameterizedTypeReference<List<LocationDto>>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      MEMBERSHIP_OPTION_LIST =
          new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of the caller's ships, filtered by fitted state and search term
   * (REQ-HANGAR-002).
   *
   * @param page zero-based page index
   * @param size page size
   * @param fitted the fitted filter, or {@code null} for every ship
   * @param search the ship-type/manufacturer search term, or {@code null}/blank for none
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShipDto> myShips(
      int page, int size, @Nullable Boolean fitted, @Nullable String search) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/hangar/my-ships")
            .queryParam("page", page)
            .queryParam("size", size);
    if (fitted != null) {
      uri.queryParam("fitted", fitted);
    }
    return withSearch(uri, search, SHIP_PAGE);
  }

  /**
   * Reads one page of the ship counts per type in the caller's org-unit scope (REQ-HANGAR-001).
   *
   * @param page zero-based page index
   * @param size page size
   * @param search the ship-type/manufacturer search term, or {@code null}/blank for none
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SquadronShipOverviewDto> squadronOverview(
      int page, int size, @Nullable String search) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/hangar/squadron-overview")
            .queryParam("page", page)
            .queryParam("size", size);
    return withSearch(uri, search, OVERVIEW_PAGE);
  }

  /**
   * Reads the curated home locations, ordered by the backend, from the catalogue cache.
   *
   * @return the home locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LocationDto> homeLocations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS_HOME, LOCATION_LIST);
  }

  /**
   * Lists the org units the caller may pick as a ship's owner.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> pickableOrgUnits() {
    return backendApiClient.get("/api/v1/org-units/me/pickable", MEMBERSHIP_OPTION_LIST);
  }

  /**
   * Lists the org units the caller is a direct member of, all four kinds: the targets of a ship's
   * org-unit change (REQ-HANGAR-009).
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> directOrgUnits() {
    return backendApiClient.get(
        "/api/v1/org-units/me/memberships?allKinds=true", MEMBERSHIP_OPTION_LIST);
  }

  /**
   * Changes the owning org unit of one of the caller's ships (REQ-HANGAR-009).
   *
   * @param id the ship
   * @param request the new org unit and the last-seen unit counter
   * @return the changed ship, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShipDto changeShipOrgUnit(@NotNull UUID id, @NotNull ShipOrgUnitRequestDto request) {
    return backendApiClient.put("/api/v1/hangar/ships/{id}/org-unit", request, ShipDto.class, id);
  }

  /**
   * Changes the owning org unit of a selection of the caller's ships (REQ-HANGAR-010).
   *
   * @param request the selected ships and the new org unit
   * @return the changed and skipped counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShipBulkOrgUnitResultDto bulkChangeShipOrgUnit(
      @NotNull ShipBulkOrgUnitRequestDto request) {
    return backendApiClient.post(
        "/api/v1/hangar/ships/bulk-org-unit", request, ShipBulkOrgUnitResultDto.class);
  }

  /**
   * Adds a ship to the caller's hangar.
   *
   * @param request the new ship
   * @return the created ship, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShipDto createShip(@NotNull ShipRequestDto request) {
    return backendApiClient.post(SHIPS, request, ShipDto.class);
  }

  /**
   * Updates a ship, carrying the optimistic-lock version in the request.
   *
   * @param id the ship
   * @param request the edited ship
   * @return the updated ship, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShipDto updateShip(@NotNull UUID id, @NotNull ShipRequestDto request) {
    return backendApiClient.put("/api/v1/hangar/ships/{id}", request, ShipDto.class, id);
  }

  /**
   * Deletes a ship from the caller's hangar.
   *
   * @param id the ship
   */
  public void deleteShip(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/hangar/ships/{id}", Void.class, id);
  }

  /**
   * Sets a curated home location on every ship the caller owns.
   *
   * @param request the chosen home location
   */
  public void setHomeLocation(@NotNull SetHomeLocationRequestDto request) {
    backendApiClient.post("/api/v1/hangar/ships/home-location", request, Void.class);
  }

  /**
   * Reads one page of a member's ships for the admin member hangar (REQ-HANGAR-011).
   *
   * @param memberId the hangar's owner
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShipDto> memberShips(@NotNull UUID memberId, int page, int size) {
    String uri =
        UriComponentsBuilder.fromPath(MEMBER_SHIPS)
            .queryParam("page", page)
            .queryParam("size", size)
            .build()
            .toUriString();
    return backendApiClient.get(uri, SHIP_PAGE, memberId);
  }

  /**
   * Reads the member whose hangar the admin member hangar shows.
   *
   * @param memberId the member
   * @return the member, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto member(@NotNull UUID memberId) {
    return backendApiClient.get("/api/v1/users/{id}", UserDto.class, memberId);
  }

  /**
   * Lists every direct org-unit membership of a member as owner-picker options for a ship an admin
   * adds to that member's hangar.
   *
   * @param memberId the member
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> memberOrgUnits(@NotNull UUID memberId) {
    return backendApiClient.get(
        "/api/v1/org-units/members/{id}/memberships?allKinds=true",
        MEMBERSHIP_OPTION_LIST,
        memberId);
  }

  /**
   * Adds a ship to a member's hangar on an admin's behalf (REQ-HANGAR-008).
   *
   * @param memberId the hangar's owner
   * @param request the new ship
   * @return the created ship, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShipDto createMemberShip(@NotNull UUID memberId, @NotNull ShipRequestDto request) {
    return backendApiClient.post(MEMBER_SHIPS, request, ShipDto.class, memberId);
  }

  /**
   * Updates a ship of a member's hangar on an admin's behalf, carrying the optimistic-lock version.
   *
   * @param memberId the hangar's owner
   * @param shipId the ship
   * @param request the edited ship
   * @return the updated ship, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShipDto updateMemberShip(
      @NotNull UUID memberId, @NotNull UUID shipId, @NotNull ShipRequestDto request) {
    return backendApiClient.put(MEMBER_SHIP, request, ShipDto.class, memberId, shipId);
  }

  /**
   * Deletes a ship of a member's hangar on an admin's behalf.
   *
   * @param memberId the hangar's owner
   * @param shipId the ship
   */
  public void deleteMemberShip(@NotNull UUID memberId, @NotNull UUID shipId) {
    backendApiClient.delete(MEMBER_SHIP, Void.class, memberId, shipId);
  }

  /**
   * Imports a third-party ship export, whose format the backend detects.
   *
   * @param upload the uploaded file, streamed as the {@code file} part
   * @return the import result, or {@code null} when the backend sent no body
   */
  @Nullable
  public FleetviewImportResponseDto importShips(@NotNull Resource upload) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder.part("file", upload).contentType(MediaType.APPLICATION_OCTET_STREAM);
    return backendApiClient.execute(
        HttpMethod.POST,
        IMPORT_SHIPS,
        webClient ->
            webClient
                .post()
                .uri(IMPORT_SHIPS)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())),
        spec -> spec.bodyToMono(FleetviewImportResponseDto.class));
  }

  /** Deletes every ship of the caller's hangar. */
  public void deleteAllShips() {
    backendApiClient.execute(
        HttpMethod.DELETE,
        SHIPS,
        webClient -> webClient.delete().uri(SHIPS),
        WebClient.ResponseSpec::toBodilessEntity);
  }

  /**
   * Sends a paged read, adding the search term as a template variable so it is encoded exactly
   * once; a {@code null} or blank term is left out.
   *
   * @param uri the paging URI, carrying only safe values
   * @param search the search term, or {@code null}/blank for none
   * @param responseType the decoded page type
   * @param <T> the page type
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  private <T> T withSearch(
      @NotNull UriComponentsBuilder uri,
      @Nullable String search,
      @NotNull ParameterizedTypeReference<T> responseType) {
    String base = uri.toUriString();
    if (search == null || search.isBlank()) {
      return backendApiClient.get(base, responseType);
    }
    String separator = base.indexOf('?') >= 0 ? "&" : "?";
    return backendApiClient.get(base + separator + "search={search}", responseType, search);
  }
}
