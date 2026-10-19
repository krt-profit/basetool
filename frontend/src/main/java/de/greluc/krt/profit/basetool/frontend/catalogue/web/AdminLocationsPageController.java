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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueListBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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
 * <p>Renders one page of the location list, sorted by name and searched server-side (including
 * hidden entries — admins need to see what's hidden to un-hide it), and toggles individual
 * locations' visibility.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/locations")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminLocationsPageController {

  /** The page path, also the base of the pagination links. */
  private static final String PATH = "/admin/locations";

  /** Reads and writes the locations. */
  private final CatalogueBackendClient catalogueClient;

  /** Evicts the location catalogue after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /** Reads the displayed page of the paginated catalogue list. */
  private final CatalogueListBackendClient listClient;

  /**
   * Renders one page of the locations, hidden ones included, narrowed by a server-side name search,
   * plus every location name for the search suggestions (REQ-ADMIN-001). A backend failure sets an
   * error key; hitting the suggestions' page-walk cap sets {@code catalogTruncated} for a warning
   * banner (REQ-ADMIN-002).
   *
   * @param query name fragment to search for, may be {@code null}
   * @param page zero-based page index, may be {@code null}
   * @param size page size out of 25, 50 and 100, may be {@code null}
   * @param fragment {@code results} to render only the swapped list fragment
   * @param model Thymeleaf model populated with the location page and the suggestion names
   * @return the {@code admin/locations} view, or its results fragment
   */
  @NotNull
  @GetMapping
  public String listData(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    CatalogueListPaging paging = CatalogueListPaging.of(page, size, query);
    paging.addTo(model, "locations", paging.orEmpty(null), PATH);
    model.addAttribute("locations", List.of());
    if (!SectionFallback.run(log, "admin-locations", () -> addLocations(model, paging))) {
      model.addAttribute("error", "error.admin.locations.load");
    }
    return CatalogueListPaging.wantsResults(fragment)
        ? "catalogue/admin/locations :: locationsResults"
        : "catalogue/admin/locations";
  }

  /**
   * Adds the displayed location page, every location name sorted for the suggestions and whether
   * the suggestions' page walk hit its cap.
   *
   * @param model the admin locations model
   * @param paging the requested page, size and search
   */
  private void addLocations(@NotNull Model model, @NotNull CatalogueListPaging paging) {
    PageResponse<LocationDto> locationsPage =
        paging.orEmpty(listClient.locations(paging.query(), paging.page(), paging.size()));
    paging.addTo(model, "locations", locationsPage, PATH);
    model.addAttribute("locations", locationsPage.content());

    CompleteCatalog<LocationDto> locationsCatalog =
        CatalogPages.fetchAll(catalogueClient::locationPage);
    model.addAttribute(
        "locationNames",
        locationsCatalog.items().stream()
            .map(LocationDto::name)
            .filter(Objects::nonNull)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());
    model.addAttribute("catalogTruncated", locationsCatalog.truncated());
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
      LocationDto currentLocation = catalogueClient.location(id);
      LocationDto body =
          new LocationDto(
              id,
              currentLocation.name(),
              currentLocation.description(),
              hidden,
              currentLocation.homeLocation(),
              currentLocation.version());
      catalogueClient.updateLocation(id, body);
      cacheEviction.evict(CacheDomain.LOCATION);
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
      LocationDto currentLocation = catalogueClient.location(id);
      LocationDto body =
          new LocationDto(
              id,
              currentLocation.name(),
              currentLocation.description(),
              currentLocation.hidden(),
              homeLocation,
              currentLocation.version());
      catalogueClient.updateLocation(id, body);
      cacheEviction.evict(CacheDomain.LOCATION);
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
          LocationDto current = catalogueClient.location(id);
          LocationDto body =
              new LocationDto(
                  id,
                  current.name(),
                  current.description(),
                  !current.hidden(),
                  current.homeLocation(),
                  current.version());
          catalogueClient.updateLocation(id, body);
          cacheEviction.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(catalogueClient.location(id));
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
          LocationDto current = catalogueClient.location(id);
          LocationDto body =
              new LocationDto(
                  id,
                  current.name(),
                  current.description(),
                  current.hidden(),
                  !current.homeLocation(),
                  current.version());
          catalogueClient.updateLocation(id, body);
          cacheEviction.evict(CacheDomain.LOCATION);
          return ResponseEntity.ok(catalogueClient.location(id));
        });
  }
}
