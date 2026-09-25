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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the admin location-management page ({@code /admin/locations}).
 *
 * <p>Renders the location list (including hidden entries — admins need to see what's hidden to
 * un-hide it) and toggles individual locations' visibility. Sort is alphabetical case-insensitive
 * because backend sort treats locale-specific casing inconsistently across PostgreSQL collations.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/locations")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminLocationsPageController {

  /** Captured generic type for decoding the paged locations catalog. */
  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Fetches all locations, including hidden ones, sorts them by name and renders the table
   * (REQ-ADMIN-001). A backend failure sets an error key; hitting the page-walk cap sets {@code
   * catalogTruncated} for a warning banner (REQ-ADMIN-002).
   *
   * @param model Thymeleaf model populated with the sorted location list
   * @return the {@code admin/locations} view name
   */
  @NotNull
  @GetMapping
  public String listData(Model model) {
    try {
      CompleteCatalog<LocationDto> locationsCatalog =
          CatalogPages.fetchAll(
              page ->
                  backendApiClient.get(
                      "/api/v1/locations?size=1000&sort=name,asc&includeHidden=true&page=" + page,
                      LOCATION_PAGE));

      List<LocationDto> locations = new ArrayList<>(locationsCatalog.items());
      locations.sort(
          Comparator.comparing(
              l -> l.name() == null ? "" : l.name(), String.CASE_INSENSITIVE_ORDER));
      model.addAttribute("locations", locations);
      model.addAttribute("catalogTruncated", locationsCatalog.truncated());

    } catch (BackendServiceException e) {
      log.debug("Error loading locations data", e);
      model.addAttribute("error", "error.admin.locations.load");
    } catch (Exception e) {
      log.error("Error loading locations data", e);
      model.addAttribute("error", "error.admin.locations.load");
    }
    return "admin/locations";
  }

  /**
   * Toggles a location's hidden flag, re-reading the record to send a full {@link LocationDto} in
   * the PUT. A {@code concurrency-conflict} 409 shows an optimistic-lock toast.
   *
   * @param id location id
   * @param hidden desired new hidden flag
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/locations}
   */
  @NotNull
  @PostMapping("/{id}/toggle-visibility")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String toggleLocationVisibility(
      @PathVariable @NotNull UUID id,
      @RequestParam boolean hidden,
      RedirectAttributes redirectAttributes) {
    try {
      LocationDto currentLocation =
          backendApiClient.get("/api/v1/locations/" + id, LocationDto.class);
      LocationDto body =
          new LocationDto(
              id,
              currentLocation.name(),
              currentLocation.description(),
              hidden,
              currentLocation.homeLocation(),
              currentLocation.version());
      backendApiClient.put("/api/v1/locations/" + id, body, Void.class);
      backendApiClient.evict(CacheDomain.LOCATION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Toggle location visibility failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.admin.locations.load");
      }
      return "redirect:/admin/locations";
    } catch (Exception e) {
      log.error("Toggle location visibility failed", e);
      return "redirect:/admin/locations?error=ToggleVisibilityFailed";
    }
    return "redirect:/admin/locations";
  }

  /**
   * Toggles a location's home-location flag (the allowlist behind the hangar "set home location"
   * picker), re-reading the record to send a full {@link LocationDto}. A {@code
   * concurrency-conflict} 409 shows an optimistic-lock toast.
   *
   * @param id location id
   * @param homeLocation desired new home-location flag
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/locations}
   */
  @NotNull
  @PostMapping("/{id}/toggle-home-location")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String toggleHomeLocation(
      @PathVariable @NotNull UUID id,
      @RequestParam boolean homeLocation,
      RedirectAttributes redirectAttributes) {
    try {
      LocationDto currentLocation =
          backendApiClient.get("/api/v1/locations/" + id, LocationDto.class);
      LocationDto body =
          new LocationDto(
              id,
              currentLocation.name(),
              currentLocation.description(),
              currentLocation.hidden(),
              homeLocation,
              currentLocation.version());
      backendApiClient.put("/api/v1/locations/" + id, body, Void.class);
      backendApiClient.evict(CacheDomain.LOCATION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Toggle home-location failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.admin.locations.load");
      }
      return "redirect:/admin/locations";
    } catch (Exception e) {
      log.error("Toggle home-location failed", e);
      return "redirect:/admin/locations?error=ToggleHomeLocationFailed";
    }
    return "redirect:/admin/locations";
  }

  /**
   * In-place twin of {@link #toggleLocationVisibility}: flips the hidden flag on a freshly read
   * record and returns the persisted {@link LocationDto}; conflicts are relayed as {@code
   * application/problem+json}.
   *
   * @param id location id
   * @return the updated {@link LocationDto}, the relayed backend status on conflict or failure, or
   *     {@code 500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/{id}/toggle-visibility", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> toggleLocationVisibilityAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "toggle location visibility (ajax)",
        () -> {
          LocationDto current = backendApiClient.get("/api/v1/locations/" + id, LocationDto.class);
          LocationDto body =
              new LocationDto(
                  id,
                  current.name(),
                  current.description(),
                  !current.hidden(),
                  current.homeLocation(),
                  current.version());
          backendApiClient.put("/api/v1/locations/" + id, body, Void.class);
          backendApiClient.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(
              backendApiClient.get("/api/v1/locations/" + id, LocationDto.class));
        });
  }

  /**
   * In-place twin of {@link #toggleHomeLocation}: flips the home-location flag on a freshly read
   * record and returns the persisted {@link LocationDto}; conflicts are relayed as {@code
   * application/problem+json}.
   *
   * @param id location id
   * @return the updated {@link LocationDto}, the relayed backend status on conflict or failure, or
   *     {@code 500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/{id}/toggle-home-location", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> toggleHomeLocationAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "toggle home-location (ajax)",
        () -> {
          LocationDto current = backendApiClient.get("/api/v1/locations/" + id, LocationDto.class);
          LocationDto body =
              new LocationDto(
                  id,
                  current.name(),
                  current.description(),
                  current.hidden(),
                  !current.homeLocation(),
                  current.version());
          backendApiClient.put("/api/v1/locations/" + id, body, Void.class);
          backendApiClient.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(
              backendApiClient.get("/api/v1/locations/" + id, LocationDto.class));
        });
  }
}
