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
import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetHomeLocationRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.form.ShipForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalogListLoader;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for the personal hangar ({@code /hangar}) and the squadron hangar overview ({@code
 * /hangar/squadron}). Sorting and text filtering happen in the backend (REQ-HANGAR-002).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/hangar")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class HangarPageController {

  private final BackendApiClient backendApiClient;
  private final ParallelPageLoader parallelPageLoader;
  private final CachedCatalogListLoader catalogListLoader;

  /**
   * Publishes the ship-import upload cap to every hangar view for the client-side size check.
   *
   * @return {@link HangarImportProxyController#MAX_IMPORT_BYTES}, the inclusive byte limit
   */
  @ModelAttribute("hangarImportMaxBytes")
  public long hangarImportMaxBytes() {
    return HangarImportProxyController.MAX_IMPORT_BYTES;
  }

  /**
   * Runs a backend GET with the pre-set paging params plus an optional {@code search} term passed
   * as a URI-template variable, so the term is encoded exactly once. A {@code null}/blank term is
   * omitted.
   *
   * @param uri the pre-built URI carrying only the safe paging params
   * @param search the free-text ship-name filter, or {@code null}/blank for no filter
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded backend response
   */
  private <T> T backendSearch(
      @NotNull org.springframework.web.util.UriComponentsBuilder uri,
      String search,
      ParameterizedTypeReference<T> responseType) {
    String base = uri.toUriString();
    if (search == null || search.isBlank()) {
      return backendApiClient.get(base, responseType);
    }
    String separator = base.indexOf('?') >= 0 ? "&" : "?";
    return backendApiClient.get(base + separator + "search={search}", responseType, search);
  }

  /**
   * Renders the personal hangar page, server-side paginated and filtered (REQ-HANGAR-002). The
   * uncached ship page and the cached reference catalogs load in parallel; each catalog degrades to
   * an empty list on failure.
   *
   * @param page zero-based page index; negatives are clamped to 0
   * @param size page size, validated against {@link #HANGAR_PAGE_SIZES}
   * @param search optional ship-type/manufacturer filter, applied by the backend
   * @param fragment {@code "results"} renders only the ship-table fragment (REQ-FE-005)
   * @param model model populated with the ship form, ship page, reference catalogs and pagination
   *     state
   * @return the {@code hangar} view name, or its {@code hangarResults} fragment selector
   */
  @NotNull
  @GetMapping
  public String viewHangar(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("shipForm")) {
      model.addAttribute("shipForm", new ShipForm());
    }

    int effectiveSize =
        size != null && HANGAR_PAGE_SIZES.contains(size) ? size : HANGAR_DEFAULT_PAGE_SIZE;
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveSearch = search == null || search.isBlank() ? null : search.trim();

    org.springframework.web.util.UriComponentsBuilder myShipsUri =
        org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/hangar/my-ships")
            .queryParam("page", effectivePage)
            .queryParam("size", effectiveSize);

    CompletableFuture<PageResponse<ShipDto>> shipsFuture =
        parallelPageLoader
            .<PageResponse<ShipDto>>loadAsync(
                () -> {
                  PageResponse<ShipDto> p =
                      backendSearch(myShipsUri, effectiveSearch, MY_SHIPS_PAGE_TYPE);
                  return p != null
                      ? p
                      : new PageResponse<>(
                          List.of(), effectivePage, effectiveSize, 0L, 0, List.of());
                })
            .exceptionally(
                e -> {
                  log.error("Failed to fetch my ships", e);
                  model.addAttribute("error", "error.hangar.ships.load");
                  return new PageResponse<>(
                      List.of(), effectivePage, effectiveSize, 0L, 0, List.of());
                });

    CompletableFuture<List<ShipTypeDto>> shipTypesFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.SHIP_TYPES, SHIP_TYPE_PAGE_TYPE, "ship types"));

    CompletableFuture<List<LocationDto>> locationsFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.LOCATIONS, LOCATION_PAGE_TYPE, "locations"));

    CompletableFuture<List<ManufacturerDto>> manufacturersFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.MANUFACTURERS, MANUFACTURER_PAGE_TYPE, "manufacturers"));

    CompletableFuture<List<LocationDto>> homeLocationsFuture =
        parallelPageLoader
            .<List<LocationDto>>loadAsync(
                () -> {
                  List<LocationDto> hl =
                      backendApiClient.getCached(
                          CachedCatalog.LOCATIONS_HOME, HOME_LOCATION_LIST_TYPE);
                  return hl != null ? new ArrayList<>(hl) : new ArrayList<>();
                })
            .exceptionally(
                e -> {
                  log.error("Failed to fetch home locations", e);
                  return new ArrayList<>();
                });

    CompletableFuture.allOf(
            shipsFuture, shipTypesFuture, locationsFuture, manufacturersFuture, homeLocationsFuture)
        .join();

    List<ShipTypeDto> shipTypes = shipTypesFuture.join();
    shipTypes.sort(Comparator.comparing(ShipTypeDto::name, String.CASE_INSENSITIVE_ORDER));

    List<LocationDto> locations = locationsFuture.join();
    locations.sort(Comparator.comparing(LocationDto::name, String.CASE_INSENSITIVE_ORDER));

    List<ManufacturerDto> manufacturers = manufacturersFuture.join();
    manufacturers.sort(Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));

    List<LocationDto> homeLocations = homeLocationsFuture.join();

    String paginationBaseUrl =
        effectiveSearch == null
            ? "/hangar"
            : org.springframework.web.util.UriComponentsBuilder.fromPath("/hangar")
                .queryParam("search", effectiveSearch)
                .toUriString();

    PageResponse<ShipDto> myShipsPage = shipsFuture.join();
    model.addAttribute(
        "myShips", myShipsPage.content() != null ? myShipsPage.content() : List.of());
    model.addAttribute("myShipsPage", myShipsPage);
    model.addAttribute("totalShipCount", myShipsPage.totalElements());
    model.addAttribute("pageSizes", HANGAR_PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("search", effectiveSearch);
    model.addAttribute("paginationBaseUrl", paginationBaseUrl);
    model.addAttribute("shipTypes", shipTypes);
    model.addAttribute("locations", locations);
    model.addAttribute("manufacturers", manufacturers);
    model.addAttribute("homeLocations", homeLocations);
    model.addAttribute("ownerOptions", fetchCallerMembershipOptions());

    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "hangar :: hangarResults";
    }
    return "hangar";
  }

  /**
   * Fetches the caller's org-unit memberships for the add-ship owner picker.
   *
   * @return picker options, or an empty list on backend failure; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchCallerMembershipOptions() {
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get("/api/v1/users/me/pickable-org-units", PICKABLE_ORG_UNIT_LIST_TYPE);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch pickable org units for hangar add-ship owner-picker", e);
      return List.of();
    }
  }

  /**
   * Page sizes both hangar pages offer in their pickers — the personal hangar (REQ-HANGAR-002) and
   * the squadron overview (REQ-HANGAR-001), the shared REQ-INV-013 trio (10/50/100). Any other
   * client-supplied {@code size} is snapped back to {@link #HANGAR_DEFAULT_PAGE_SIZE} so a crafted
   * URL cannot request an unbounded page from the backend.
   */
  private static final List<Integer> HANGAR_PAGE_SIZES = List.of(10, 50, 100);

  /** Page size applied when the request carries none (or a non-whitelisted one). */
  private static final int HANGAR_DEFAULT_PAGE_SIZE = 50;

  /**
   * Response type for the uncached {@code /my-ships} call — one server-side-paginated page of the
   * caller's ships (REQ-HANGAR-002).
   */
  private static final ParameterizedTypeReference<PageResponse<ShipDto>> MY_SHIPS_PAGE_TYPE =
      new ParameterizedTypeReference<PageResponse<ShipDto>>() {};

  /**
   * Response type for the cached ship-type catalog page ({@code /ship-types}), unwrapped into the
   * hangar's ship-type dropdown.
   */
  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE_TYPE =
      new ParameterizedTypeReference<PageResponse<ShipTypeDto>>() {};

  /**
   * Response type for the cached location catalog page ({@code /locations}), unwrapped into the
   * hangar's location dropdown.
   */
  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE_TYPE =
      new ParameterizedTypeReference<PageResponse<LocationDto>>() {};

  /**
   * Response type for the cached manufacturer catalog page ({@code /manufacturers}), unwrapped into
   * the hangar's manufacturer dropdown.
   */
  private static final ParameterizedTypeReference<PageResponse<ManufacturerDto>>
      MANUFACTURER_PAGE_TYPE = new ParameterizedTypeReference<PageResponse<ManufacturerDto>>() {};

  /**
   * Response type for the cached curated home-locations call ({@code /locations/home-locations}),
   * which returns a bare list (already ordered Z-&gt;A) rather than a paginated envelope.
   */
  private static final ParameterizedTypeReference<List<LocationDto>> HOME_LOCATION_LIST_TYPE =
      new ParameterizedTypeReference<List<LocationDto>>() {};

  /**
   * Response type for the caller's pickable org units ({@code /users/me/pickable-org-units}) that
   * populate the add-ship owner-picker; a bare list resolved server-side for the caller.
   */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      PICKABLE_ORG_UNIT_LIST_TYPE =
          new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {};

  /**
   * Response type for the squadron-wide hangar overview page ({@code /hangar/squadron-overview}),
   * one server-side-paginated page of per-ship-type counts (REQ-HANGAR-001).
   */
  private static final ParameterizedTypeReference<PageResponse<SquadronShipOverviewDto>>
      SQUADRON_OVERVIEW_PAGE_TYPE =
          new ParameterizedTypeReference<PageResponse<SquadronShipOverviewDto>>() {};

  /**
   * Renders the squadron hangar overview, ship counts per type in the caller's scope, server-side
   * paginated (REQ-HANGAR-001).
   *
   * @param page zero-based page index; negatives are clamped to 0
   * @param size page size, validated against {@link #HANGAR_PAGE_SIZES}
   * @param search optional ship-type/manufacturer filter, applied by the backend
   * @param fragment {@code "results"} renders only the results + pagination fragment (REQ-FE-005)
   * @param model model populated with the overview page, the picker options and the pagination base
   *     URL
   * @return the {@code hangar-squadron} view name, or its {@code squadronResults} fragment selector
   */
  @NotNull
  @GetMapping("/squadron")
  public String viewSquadron(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String fragment,
      Model model) {
    int effectiveSize =
        size != null && HANGAR_PAGE_SIZES.contains(size) ? size : HANGAR_DEFAULT_PAGE_SIZE;
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveSearch = search == null || search.isBlank() ? null : search.trim();

    List<SquadronShipOverviewDto> overview = new ArrayList<>();
    PageResponse<SquadronShipOverviewDto> res = null;
    try {
      org.springframework.web.util.UriComponentsBuilder uriBuilder =
          org.springframework.web.util.UriComponentsBuilder.fromPath(
                  "/api/v1/hangar/squadron-overview")
              .queryParam("page", effectivePage)
              .queryParam("size", effectiveSize);
      res = backendSearch(uriBuilder, effectiveSearch, SQUADRON_OVERVIEW_PAGE_TYPE);
      if (res != null && res.content() != null) {
        overview = new ArrayList<>(res.content());
      }
    } catch (BackendServiceException e) {
      log.debug("Failed to fetch squadron overview", e);
      model.addAttribute("error", "error.hangar.squadron.load");
    } catch (Exception e) {
      log.error("Failed to fetch squadron overview", e);
      model.addAttribute("error", "error.hangar.squadron.load");
    }

    String paginationBaseUrl =
        effectiveSearch == null
            ? "/hangar/squadron"
            : org.springframework.web.util.UriComponentsBuilder.fromPath("/hangar/squadron")
                .queryParam("search", effectiveSearch)
                .toUriString();

    model.addAttribute("overview", overview);
    model.addAttribute("overviewPage", res);
    model.addAttribute("search", effectiveSearch);
    model.addAttribute("pageSizes", HANGAR_PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("paginationBaseUrl", paginationBaseUrl);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "hangar-squadron :: squadronResults";
    }
    return "hangar-squadron";
  }

  /**
   * Adds a new ship to the current user's hangar. Validation errors re-render the hangar view
   * inline instead of redirecting.
   *
   * @param form ship form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return inline {@code hangar} view on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/add")
  public String addShip(
      @Valid @ModelAttribute("shipForm") ShipForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("showShipModal", true);
      model.addAttribute("modalAction", "/hangar/add");
      return viewHangar(null, null, null, null, model);
    }

    try {
      ShipRequestDto request =
          new ShipRequestDto(
              form.getName(),
              form.getShipTypeId(),
              form.getInsurance(),
              form.getLocationId(),
              form.isFitted(),
              null,
              form.getOwningOrgUnitId());
      backendApiClient.post("/api/v1/hangar/ships", request, ShipDto.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.ship_add");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "POST /api/v1/hangar/ships", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.ship.add");
      redirectAttributes.addFlashAttribute("shipForm", form);
    } catch (Exception e) {
      log.error("Failed to add ship", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.ship.add");
      redirectAttributes.addFlashAttribute("shipForm", form);
    }
    return "redirect:/hangar";
  }

  /**
   * Updates an existing ship. Optimistic-locking version travels through the form so the backend
   * can reject concurrent edits.
   *
   * @param id ship id
   * @param form ship form (carries the version field)
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return inline {@code hangar} view on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/{id}/update")
  public String updateShip(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("shipForm") ShipForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      log.warn("Validation failed for ship update {}", id);
      model.addAttribute("errorToast", "error.validation.failed");
      model.addAttribute("showShipModal", true);
      model.addAttribute("modalAction", "/hangar/" + id + "/update");
      return viewHangar(null, null, null, null, model);
    }

    try {
      ShipRequestDto request =
          new ShipRequestDto(
              form.getName(),
              form.getShipTypeId(),
              form.getInsurance(),
              form.getLocationId(),
              form.isFitted(),
              form.getVersion(),
              null);
      backendApiClient.put("/api/v1/hangar/ships/" + id, request, ShipDto.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.ship_update");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "PUT /api/v1/hangar/ships", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.ship.update");
    } catch (Exception e) {
      log.error("Failed to update ship", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.ship.update");
    }
    return "redirect:/hangar";
  }

  /**
   * Deletes a ship from the user's hangar.
   *
   * @param id ship id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /hangar}
   */
  @NotNull
  @PostMapping("/{id}/delete")
  public String deleteShip(@PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/hangar/ships/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.ship_delete");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "DELETE /api/v1/hangar/ships", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.ship.delete");
    } catch (Exception e) {
      log.error("Failed to delete ship", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.ship.delete");
    }
    return "redirect:/hangar";
  }

  /**
   * Sets the chosen home location on every ship the caller owns, then redirects to the hangar.
   *
   * @param locationId the curated home location chosen in the modal
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /hangar}
   */
  @NotNull
  @PostMapping("/home-location")
  public String setHomeLocation(
      @RequestParam("locationId") @NotNull UUID locationId, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.post(
          "/api/v1/hangar/ships/home-location",
          new SetHomeLocationRequestDto(locationId),
          Void.class);
      redirectAttributes.addFlashAttribute(
          "successToast", "notification.success.home_location_set");
    } catch (Exception e) {
      log.error("Failed to set home location for ships", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.hangar.home_location.set");
    }
    return "redirect:/hangar";
  }

  /**
   * AJAX twin of {@link #addShip}, selected by {@code X-Requested-With=XMLHttpRequest}: adds a ship
   * and returns {@code 204} for the in-place table swap.
   *
   * @param request the ship payload as JSON ({@code version} is ignored)
   * @return {@code 204} on success, {@code 422} on a missing required field, or the relayed backend
   *     {@code problem+json}
   */
  @PostMapping(value = "/add", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> addShipAjax(@RequestBody ShipRequestDto request) {
    if (request == null || request.shipTypeId() == null || isBlank(request.insurance())) {
      return validationProblem();
    }
    return relay(
        log,
        "add ship (ajax)",
        () -> {
          backendApiClient.post(
              "/api/v1/hangar/ships",
              new ShipRequestDto(
                  request.name(),
                  request.shipTypeId(),
                  request.insurance(),
                  request.locationId(),
                  request.fitted(),
                  null,
                  request.owningOrgUnitId()),
              ShipDto.class);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * AJAX twin of {@link #updateShip}: updates a ship and returns {@code 204} for the in-place table
   * swap. {@code owningOrgUnitId} is not changed.
   *
   * @param id ship id
   * @param request the ship payload as JSON, carrying the last-seen {@code version}
   * @return {@code 204} on success, {@code 422} on a missing required field, or the relayed backend
   *     {@code problem+json}
   */
  @PostMapping(value = "/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> updateShipAjax(
      @PathVariable @NotNull UUID id, @RequestBody ShipRequestDto request) {
    if (request == null || request.shipTypeId() == null || isBlank(request.insurance())) {
      return validationProblem();
    }
    return relay(
        log,
        "update ship (ajax)",
        () -> {
          backendApiClient.put(
              "/api/v1/hangar/ships/" + id,
              new ShipRequestDto(
                  request.name(),
                  request.shipTypeId(),
                  request.insurance(),
                  request.locationId(),
                  request.fitted(),
                  request.version(),
                  null),
              ShipDto.class);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * AJAX twin of {@link #deleteShip}: deletes a ship and returns {@code 204}.
   *
   * @param id ship id
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteShipAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "delete ship (ajax)",
        () -> {
          backendApiClient.delete("/api/v1/hangar/ships/" + id, Void.class);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * AJAX twin of {@link #setHomeLocation}: sets the home location on every ship the caller owns and
   * returns {@code 204}.
   *
   * @param request the chosen curated home location id as JSON
   * @return {@code 204} on success, {@code 422} on a missing location, or the relayed backend
   *     {@code problem+json}
   */
  @PostMapping(value = "/home-location", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> setHomeLocationAjax(
      @RequestBody SetHomeLocationRequestDto request) {
    if (request == null || request.locationId() == null) {
      return validationProblem();
    }
    return relay(
        log,
        "set home location (ajax)",
        () -> {
          backendApiClient.post("/api/v1/hangar/ships/home-location", request, Void.class);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Null/blank guard kept local so the AJAX twins can validate a required string field without
   * dragging in a utility dependency.
   *
   * @param value the value to test
   * @return {@code true} when {@code value} is {@code null} or blank
   */
  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Builds the {@code 422} {@code problem+json} carrying the stable {@code VALIDATION} code that
   * {@code hangar.html} maps to an inline toast when a required ship field is missing on an AJAX
   * write, so the create/edit modal surfaces the error without a navigation.
   *
   * @return a {@code 422} {@code problem+json} response
   */
  private static ResponseEntity<Object> validationProblem() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", 422);
    body.put("code", "VALIDATION");
    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  @Contract("null -> null")
  @Nullable
  private Long parseLong(Object o) {
    if (o == null) {
      return null;
    }
    try {
      if (o instanceof Number number) {
        return number.longValue();
      }
      return Long.parseLong(o.toString());
    } catch (Exception e) {
      return null;
    }
  }
}
