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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.mapper.LocationMapper;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.LocationService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the location reference table. Public endpoint set; mutations are OFFICER/ADMIN.
 * Provides a lightweight {@code /lookup} projection for typeaheads and a dedicated {@code
 * /refineries} list used by the refinery-order create form.
 */
@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
@Transactional
public class LocationController {

  private final LocationService locationService;
  private final LocationMapper locationMapper;

  /**
   * Paged list with {@code includeHidden} for the admin view.
   *
   * @return paged location DTOs
   */
  @GetMapping
  public PageResponse<LocationDto> getAllLocations(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean includeHidden) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<Location> p = locationService.getAllLocations(pageable, includeHidden);
    return PageResponse.of(p.map(locationMapper::toDto));
  }

  /**
   * Lightweight projection for typeaheads — only id and name. Deliberately complete: a silent bound
   * would make locations beyond it unreachable for consumers of the full list. Pickers that must
   * stay payload-bounded use {@link #searchLocations(String, Integer, Integer, String)}.
   *
   * @return all non-hidden locations as reference DTOs
   */
  @GetMapping("/lookup")
  public List<de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto>
      lookupLocations() {
    return locationService.findAllReference();
  }

  /**
   * Live search for the location pickers (REQ-FE-016): pages non-hidden locations whose name
   * contains {@code search}, case-insensitively. Backs the searchable location comboboxes so every
   * location stays reachable by typing regardless of catalogue size, while each response stays
   * payload-bounded — the deliberate alternative to preloading (or silently capping) the full list.
   *
   * @param search optional name fragment; {@code null}/blank returns the unfiltered first page
   * @param page zero-based page index (defaulted by {@link PaginationUtil})
   * @param size page size (clamped by {@link PaginationUtil})
   * @param sort whitelisted sort ({@code name} or {@code id}), default name ascending
   * @return one page of matching non-hidden locations as reference DTOs
   */
  @GetMapping("/search")
  public PageResponse<de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto>
      searchLocations(
          @RequestParam(required = false) String search,
          @RequestParam(required = false) Integer page,
          @RequestParam(required = false) Integer size,
          @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    return PageResponse.of(locationService.searchReference(search, pageable));
  }

  /**
   * Returns the location DTO.
   *
   * @param id location id
   * @return the location DTO
   */
  @GetMapping("/{id}")
  public LocationDto getLocation(@PathVariable @NotNull UUID id) {
    return locationMapper.toDto(locationService.getLocation(id));
  }

  /**
   * Only the locations that host a refinery — used by the refinery-order create form.
   *
   * @return refinery-hosting locations
   */
  @GetMapping("/refineries")
  @PreAuthorize("isAuthenticated()")
  public List<LocationDto> getRefineryLocations() {
    return locationService.getRefineryLocations().stream().map(locationMapper::toDto).toList();
  }

  /**
   * Lists the admin-curated home locations (non-hidden), ordered by name descending — backs the
   * hangar bulk "set home location" picker.
   *
   * @return curated home locations as DTOs, name descending
   */
  @Operation(
      summary = "Curated home locations",
      description =
          "Lists the locations flagged as home locations and not hidden (name descending) as"
              + " the source for the hangar bulk button.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "List of home locations"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  @GetMapping("/home-locations")
  @PreAuthorize("isAuthenticated()")
  public List<LocationDto> getHomeLocations() {
    return locationService.getHomeLocations().stream().map(locationMapper::toDto).toList();
  }

  /**
   * Creates a new location. {@link
   * de.greluc.krt.profit.basetool.backend.mapper.LocationMapper#stripServerManaged} removes
   * client-supplied {@code id}/{@code version} so the client cannot mass-assign onto an existing
   * row.
   *
   * @param location create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public LocationDto createLocation(@RequestBody @Valid @NotNull LocationDto location) {
    // stripServerManaged drops client-supplied id / version so JPA performs an INSERT
    // and the client cannot mass-assign onto an existing row through this endpoint.
    Location entity = LocationMapper.stripServerManaged(locationMapper.toEntity(location));
    return locationMapper.toDto(locationService.createLocation(entity));
  }

  /**
   * Updates an existing location.
   *
   * @param id location id
   * @param location update payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public LocationDto updateLocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull LocationDto location) {
    return locationMapper.toDto(locationService.updateLocation(id, location));
  }

  /**
   * Deletes a location. Rejected with {@link
   * de.greluc.krt.profit.basetool.backend.exception.EntityInUseException} when any ship or refinery
   * order still references it.
   *
   * @param id location id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteLocation(@PathVariable @NotNull UUID id) {
    locationService.deleteLocation(id);
  }
}
