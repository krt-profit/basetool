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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.StarmapBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapObjectAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapPlaceLinkDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapStoragePlaceRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapVisibilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.StringNormalization;
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

/**
 * JSON proxy of the {@code /admin/starmap} page (REQ-P4K-034…038): the page reads both lists and
 * swaps every changed row in place through {@code krtFetch}. A backend refusal of a write, a stale
 * version or link included, is relayed as {@code application/problem+json} with its status.
 *
 * <p>Every successful write evicts {@link CacheDomain#LOCATION}, whose rows carry the P4K names and
 * storage places the write can change.
 */
@RestController
@RequestMapping("/admin/starmap")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminStarmapController {

  /** The page size of both lists and of the link picker. */
  private static final int PAGE_SIZE = 25;

  /** Relays the starmap reads and writes. */
  private final StarmapBackendClient starmapClient;

  /** Evicts the location catalogue after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Lists one page of the starmap objects; a blank filter means "all".
   *
   * @param starSystem the star system folder, or blank
   * @param type the P4K type, or blank
   * @param query a fragment of the class name or either name, or blank
   * @param page zero-based page index
   * @return the page of objects
   */
  @GetMapping("/objects")
  @Nullable
  public PageResponse<StarmapObjectAdminDto> objects(
      @RequestParam(required = false) @Nullable String starSystem,
      @RequestParam(required = false) @Nullable String type,
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() ->
        starmapClient.objects(
            StringNormalization.trimToNull(starSystem),
            StringNormalization.trimToNull(type),
            StringNormalization.trimToNull(query),
            page,
            PAGE_SIZE));
  }

  /**
   * Sets an object visible or hidden, or back to the rule; a stale version is relayed as the
   * backend's {@code 409}.
   *
   * @param id the object
   * @param request the override and the version the admin saw
   * @return the object after the change, or the backend's refusal
   */
  @PostMapping("/objects/{id}/visibility")
  @NotNull
  public ResponseEntity<Object> overrideVisibility(
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull StarmapVisibilityRequest request) {
    return relay(
        log,
        "starmap visibility override",
        () -> {
          StarmapObjectAdminDto updated = starmapClient.overrideVisibility(id, request);
          cacheEviction.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(updated);
        });
  }

  /**
   * Creates a storage place on a visible object (REQ-P4K-036); a hidden or unknown object is
   * relayed as the backend's {@code 400}, an object that already has a place as its {@code 409}.
   *
   * @param id the object
   * @param request the dialog's name, description and flags
   * @return the created location, or the backend's refusal
   */
  @PostMapping("/objects/{id}/storage-place")
  @NotNull
  public ResponseEntity<Object> createStoragePlace(
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull StarmapStoragePlaceRequest request) {
    LocationDto location =
        new LocationDto(
            null,
            StringNormalization.trimToNull(request.name()),
            StringNormalization.trimToNull(request.description()),
            request.hidden(),
            request.homeLocation(),
            null,
            id,
            null,
            null);
    return relay(
        log,
        "starmap storage place create",
        () -> {
          LocationDto created = starmapClient.createStoragePlace(location);
          cacheEviction.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(created);
        });
  }

  /**
   * Lists one page of a UEX location table's rows with their links.
   *
   * @param kind the table, e.g. {@code CITY}
   * @param query a fragment of the row name, or blank
   * @param unlinkedOnly whether only unlinked rows are listed
   * @param page zero-based page index
   * @return the page of rows
   */
  @GetMapping("/places/{kind}")
  @Nullable
  public PageResponse<StarmapPlaceLinkDto> places(
      @PathVariable("kind") @NotNull String kind,
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(defaultValue = "false") boolean unlinkedOnly,
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() ->
        starmapClient.places(
            kind, StringNormalization.trimToNull(query), unlinkedOnly, page, PAGE_SIZE));
  }

  /**
   * Sets, clears or releases a row's link; a link that changed since the admin saw it is relayed as
   * the backend's {@code 409}, a set without an object as its {@code 400}.
   *
   * @param kind the table
   * @param id the row
   * @param request the change and the link the admin saw
   * @return the row after the change, or the backend's refusal
   */
  @PostMapping("/places/{kind}/{id}/link")
  @NotNull
  public ResponseEntity<Object> changeLink(
      @PathVariable("kind") @NotNull String kind,
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull StarmapLinkRequest request) {
    return relay(
        log,
        "starmap link change",
        () -> {
          StarmapPlaceLinkDto updated = starmapClient.changeLink(kind, id, request);
          cacheEviction.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(updated);
        });
  }
}
