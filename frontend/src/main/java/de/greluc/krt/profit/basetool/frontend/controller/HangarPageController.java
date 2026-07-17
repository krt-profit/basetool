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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

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
import org.jetbrains.annotations.NotNull;
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
 * Spring MVC controller for the personal hangar pages ({@code /hangar} and {@code
 * /hangar/squadron}).
 *
 * <p>The personal hangar lists the current user's ships, server-side paginated and ordered by the
 * backend's rich multi-key comparator: manufacturer name, ship type, insurance tier (LTI &lt;
 * numeric &lt; unset), insurance number desc, location and finally fitted-status + name. The order
 * is deliberate — fleet members compare insurance state across ships of the same type, so insurance
 * grouping has to win over location. Since #773 the ordering and the text filter live in the
 * backend ({@code /my-ships} with {@code page}/{@code size}/{@code search}, REQ-HANGAR-002) so they
 * span the user's whole fleet rather than one client-fetched page. The squadron overview aggregates
 * the entire org's hangar into a count-per-type table.
 */
@Controller
@RequestMapping("/hangar")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class HangarPageController {

  private final BackendApiClient backendApiClient;
  private final ParallelPageLoader parallelPageLoader;
  private final CachedCatalogListLoader catalogListLoader;

  /**
   * Runs a backend GET whose query carries the fixed paging params plus an optional free-text
   * {@code search} term, encoding {@code search} <em>exactly once</em> across the
   * frontend&rarr;backend hop. The caller pre-sets the safe paging params on {@code uri}; this
   * method appends a non-blank term as a WebClient URI-template variable ({@code search={search}})
   * so the WebClient encodes it once per RFC 3986, rather than baking the already-encoded {@link
   * org.springframework.web.util.UriComponentsBuilder#toUriString()} output into the {@code
   * get(String)} call — which the WebClient would encode a second time (a space becomes {@code
   * %2520} instead of {@code %20}, an umlaut {@code %25C3%25BC}), so a multi-word or umlaut ship
   * search reached the backend {@code @RequestParam} mangled and matched nothing (the #371
   * re-encoding trap). A {@code null}/blank term is omitted so the unfiltered page is fetched.
   *
   * @param uri the pre-built URI carrying only the safe paging params
   * @param search the free-text ship-name filter, or {@code null}/blank for no filter
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded backend response
   */
  private <T> T backendSearch(
      org.springframework.web.util.UriComponentsBuilder uri,
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
   * Renders the personal hangar page, server-side paginated (REQ-HANGAR-002). Fetches one page of
   * my ships and the three cached reference catalogs (ship types, locations, manufacturers) in
   * parallel via {@link ParallelPageLoader}; each catalog call independently degrades to an empty
   * list on backend failure so a single dead reference catalog never blanks the whole page. The
   * ship page is ordered and filtered entirely by the backend (no client-side {@code SHIP_SORT}),
   * so the order and the {@code search} term span the user's whole fleet, not one fetched page. The
   * {@code /my-ships} call is intentionally <em>uncached</em> (per-user data must never be served
   * from a shared cache); only the reference catalogs go through {@code getCached}.
   *
   * @param page zero-based page index, defaults to the first page; negatives are clamped to 0
   * @param size page size, validated against {@link #HANGAR_PAGE_SIZES} (else snapped to the
   *     default)
   * @param search optional ship-type/manufacturer filter, applied server-side by the backend
   * @param fragment when {@code "results"}, only the ship-table results fragment is rendered for an
   *     in-place AJAX swap after a ship write or a filter/page change (epic #571 / REQ-FE-005);
   *     otherwise the full page
   * @param model Thymeleaf model populated with the ship form, ship page, reference catalogs and
   *     the pagination state (page metadata, page sizes, search-preserving base URL, total ship
   *     count)
   * @return the {@code hangar} view name, or its {@code hangarResults} fragment selector
   */
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

    // The three sortable reference catalogs share one degrade-to-empty loader
    // (CachedCatalogListLoader); each fetch still runs on its own virtual thread and is sorted
    // below
    // after the join, so a single dead catalog never blanks the whole page.
    CompletableFuture<List<ShipTypeDto>> shipTypesFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.SHIP_TYPES, SHIP_TYPE_PAGE_TYPE, false, "ship types"));

    CompletableFuture<List<LocationDto>> locationsFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.LOCATIONS, LOCATION_PAGE_TYPE, false, "locations"));

    CompletableFuture<List<ManufacturerDto>> manufacturersFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.MANUFACTURERS, MANUFACTURER_PAGE_TYPE, false, "manufacturers"));

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

    // join() blocks until every parallel fetch finishes; each call ran on its own virtual thread
    // with the full request-scoped context (SecurityContext / RequestAttributes / squadron /
    // correlation id) restored, so OAuth2 bearer relay and squadron-header propagation behave
    // identically to the previous sequential implementation.
    CompletableFuture.allOf(
            shipsFuture, shipTypesFuture, locationsFuture, manufacturersFuture, homeLocationsFuture)
        .join();

    List<ShipTypeDto> shipTypes = shipTypesFuture.join();
    shipTypes.sort(Comparator.comparing(ShipTypeDto::name, String.CASE_INSENSITIVE_ORDER));

    List<LocationDto> locations = locationsFuture.join();
    locations.sort(Comparator.comparing(LocationDto::name, String.CASE_INSENSITIVE_ORDER));

    List<ManufacturerDto> manufacturers = manufacturersFuture.join();
    manufacturers.sort(Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));

    // Curated home locations for the bulk "set home location" picker. The backend already returns
    // them alphabetically descending (Z->A); preserve that order (do not re-sort).
    List<LocationDto> homeLocations = homeLocationsFuture.join();

    // Page links must keep the active filter, so the fragment's base URL carries the search term
    // percent-encoded (toUriString() encodes — a raw term could otherwise smuggle extra query
    // params into every pagination link); page/size are appended by the shared pagination fragment.
    String paginationBaseUrl =
        effectiveSearch == null
            ? "/hangar"
            : org.springframework.web.util.UriComponentsBuilder.fromPath("/hangar")
                .queryParam("search", effectiveSearch)
                .toUriString();

    // The page is already ordered + filtered by the backend (REQ-HANGAR-002); render its content
    // verbatim. No client-side SHIP_SORT — that would only reorder the rows of the current page.
    PageResponse<ShipDto> myShipsPage = shipsFuture.join();
    model.addAttribute(
        "myShips", myShipsPage.content() != null ? myShipsPage.content() : List.of());
    model.addAttribute("myShipsPage", myShipsPage);
    // Home-location / delete-all act on ALL the user's ships (not the current page), so their count
    // reflects the page envelope's total, not the size of the rendered page.
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
   * Fetches the caller's OrgUnit memberships for the R5.d.f owner-picker on the add-ship modal.
   * Ships are always added for the calling user (no admin-cross-user override on this page), so the
   * picker reflects the caller's own memberships. Falls back to an empty list on backend hiccup;
   * the fragment collapses to its hidden state in that case.
   *
   * @return picker options or empty list; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchCallerMembershipOptions() {
    try {
      // Epic #692 Phase 5: the owner picker offers the caller's pickable org units — their direct
      // memberships plus their cascading leadership reach (a Bereichsleitung/OL leader's own
      // Bereich/OL + the subordinate Staffeln/SKs they oversee). For an ordinary member this equals
      // their direct memberships, so the picker is unchanged. Resolved server-side for the caller.
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
   * Renders the squadron-wide hangar overview ({@code /hangar/squadron}), server-side paginated
   * across every ship type in the caller's scope (REQ-HANGAR-001). The backend aggregates counts
   * per ship type, sorted by ship-type name; page metadata, the page-size choice (10/50/100 — any
   * other value snaps to the default) and the optional search term travel as query parameters and
   * are echoed into the model so the pagination links and the filter form can reproduce the state.
   *
   * @param page zero-based page index, defaults to the first page; negatives are clamped to 0
   * @param size page size, validated against {@link #HANGAR_PAGE_SIZES}
   * @param search optional ship-type/manufacturer filter, applied server-side by the backend
   * @param fragment when {@code "results"}, only the results+pagination fragment is rendered for an
   *     in-place AJAX swap (epic #571 / REQ-FE-005); otherwise the full page
   * @param model Thymeleaf model populated with the overview page, the picker options and the
   *     pagination base URL (search-preserving)
   * @return the {@code hangar-squadron} view name, or its {@code squadronResults} fragment selector
   */
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

    // Page links must keep the active filter, so the fragment's base URL carries the search term
    // percent-encoded (toUriString() encodes — a raw term could otherwise smuggle extra query
    // params into every pagination link); page/size are appended by the fragment itself.
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
   * Adds a new ship to the current user's hangar. Validation errors render the hangar view inline
   * (no redirect) so the BindingResult stays request-scoped — pushing it through the Redis-backed
   * FlashMap would crash on the self-referencing cycle.
   *
   * @param form ship form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return inline {@code hangar} view on validation failure, otherwise redirect
   */
  @PostMapping("/add")
  public String addShip(
      @Valid @ModelAttribute("shipForm") ShipForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Render directly; the BindingResult stays request-scoped so it never goes
      // through a Redis-serialised FlashMap (see RedisSessionConfig).
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
              // Update path: owningOrgUnitId is not editable, the existing stamp survives.
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
   * Bulk-sets the chosen curated home location on every ship the calling user owns, then redirects
   * back to the hangar — a full reload that resyncs each row's displayed location and version. The
   * location id comes from the home-location modal's select; the backend derives the owner from the
   * JWT and validates that the id is a selectable home location.
   *
   * @param locationId the curated home location chosen in the modal
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /hangar}
   */
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

  // ----------------------------------------------------- AJAX twins (epic #571 / REQ-FE-005)

  /**
   * Header-gated AJAX twin of {@link #addShip}: adds a ship and returns {@code 204} so {@code
   * hangar.html} re-swaps the ship table in place via {@code GET /hangar?fragment=results} instead
   * of the classic POST→redirect reload. The twin is selected only for an {@code
   * X-Requested-With=XMLHttpRequest} request, so the classic handler stays the no-JS fallback. The
   * modal's HTML5 {@code required} dropdowns guard ship-type + insurance client-side; a missing one
   * still yields a {@code 422} so a crafted request cannot slip past, and a backend rejection is
   * relayed verbatim as {@code problem+json}.
   *
   * @param request the ship payload submitted as JSON ({@code version} is ignored — always a
   *     create)
   * @return {@code 204} on success, {@code 422} on a missing required field, or the relayed backend
   *     {@code problem+json}
   */
  @PostMapping(value = "/add", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> addShipAjax(@RequestBody ShipRequestDto request) {
    if (request == null || request.shipTypeId() == null || isBlank(request.insurance())) {
      return validationProblem();
    }
    try {
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
    } catch (BackendServiceException e) {
      log.debug("Failed to add ship (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to add ship (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #updateShip}: updates a ship and returns {@code 204} so the
   * page re-swaps the ship table in place. The optimistic-lock {@code version} travels in the JSON
   * payload; a concurrent edit surfaces as a {@code 409} {@code problem+json} carrying {@code
   * OPTIMISTIC_LOCK}, which the client turns into the sanctioned reload-confirm. {@code
   * owningOrgUnitId} is not editable on update (the existing stamp survives), mirroring the classic
   * handler.
   *
   * @param id ship id
   * @param request the ship payload submitted as JSON (carries the last-seen {@code version})
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
    try {
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
    } catch (BackendServiceException e) {
      log.debug("Failed to update ship (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to update ship (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #deleteShip}: deletes a ship and returns {@code 204} so the
   * page removes the row and re-swaps the table in place. A backend failure is relayed as {@code
   * problem+json}.
   *
   * @param id ship id
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteShipAjax(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete("/api/v1/hangar/ships/" + id, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.debug("Failed to delete ship (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to delete ship (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #setHomeLocation}: bulk-sets the chosen home location on every
   * ship the caller owns and returns {@code 204} so the page re-swaps the table (every row's
   * location cell resyncs from the fresh fragment). A backend failure is relayed as {@code
   * problem+json}.
   *
   * @param request the chosen curated home location id, submitted as JSON
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
    try {
      backendApiClient.post("/api/v1/hangar/ships/home-location", request, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.debug("Failed to set home location (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to set home location (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
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
