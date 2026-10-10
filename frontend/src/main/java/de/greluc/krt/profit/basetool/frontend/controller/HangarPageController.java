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
import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
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
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalogListLoader;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Controller for the hangar page and its two tabs, the own ships ({@code /hangar}) and the org-unit
 * overview ({@code /hangar/squadron}). Sorting and filtering happen in the backend
 * (REQ-HANGAR-001/002).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/hangar")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class HangarPageController {

  /** Reads the hangar pages and sends the ship writes. */
  private final HangarBackendClient hangarClient;

  /** Runs the page's independent reads in parallel. */
  private final ParallelPageLoader parallelPageLoader;

  /** Reads the cached reference catalogues behind the ship form. */
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
   * Names the caller's own hangar live-sync room, which the page subscribes to so a connected
   * application's change appears without a reload.
   *
   * @param principal the caller, or {@code null} when anonymous
   * @return {@code hangar:<user id>}, or {@code null} without a caller
   */
  @ModelAttribute("hangarLiveSyncTopic")
  public @Nullable String hangarLiveSyncTopic(
      @AuthenticationPrincipal @Nullable OidcUser principal) {
    String id = CurrentUser.userIdText(principal);
    return id == null ? null : "hangar:" + id;
  }

  /**
   * Renders the hangar page on its „Meine Schiffe" tab, server-side paginated and filtered by
   * search term and fitted flag (REQ-HANGAR-002). The uncached ship page, the ready/total counter
   * and the cached reference catalogs load in parallel; each catalog degrades to an empty list on
   * failure.
   *
   * @param page zero-based page index; negatives are clamped to 0
   * @param size page size, validated against {@link #HANGAR_PAGE_SIZES}
   * @param search optional ship-type/manufacturer filter, applied by the backend
   * @param fitted optional fitted filter, {@code true} or {@code false}; anything else means both
   * @param fragment {@code "results"} renders only the ship-table fragment (REQ-FE-005)
   * @param model model populated with the ship form, ship page, counters, reference catalogs and
   *     pagination state
   * @return the {@code hangar} view name, or its {@code hangarResults} fragment selector
   */
  @NotNull
  @GetMapping
  public String viewHangar(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String fitted,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("shipForm")) {
      model.addAttribute("shipForm", new ShipForm());
    }

    boolean fragmentOnly = "results".equalsIgnoreCase(fragment);
    int effectiveSize =
        size != null && HANGAR_PAGE_SIZES.contains(size) ? size : HANGAR_DEFAULT_PAGE_SIZE;
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveSearch = search == null || search.isBlank() ? null : search.trim();
    Boolean fittedFilter = parseFitted(fitted);

    AtomicBoolean shipsFailed = new AtomicBoolean(false);

    CompletableFuture<PageResponse<ShipDto>> shipsFuture =
        parallelPageLoader
            .<PageResponse<ShipDto>>loadAsync(
                () -> {
                  PageResponse<ShipDto> p =
                      hangarClient.myShips(
                          effectivePage, effectiveSize, fittedFilter, effectiveSearch);
                  return p != null
                      ? p
                      : new PageResponse<>(
                          List.of(), effectivePage, effectiveSize, 0L, 0, List.of());
                })
            .exceptionally(
                e -> {
                  log.error("Failed to fetch my ships", e);
                  shipsFailed.set(true);
                  model.addAttribute("error", "error.hangar.ships.load");
                  return new PageResponse<>(
                      List.of(), effectivePage, effectiveSize, 0L, 0, List.of());
                });

    Boolean counterpartFitted = fittedFilter == null ? Boolean.TRUE : null;
    CompletableFuture<Long> counterpartFuture =
        parallelPageLoader
            .<Long>loadAsync(
                () -> totalOf(hangarClient.myShips(0, 1, counterpartFitted, effectiveSearch)))
            .exceptionally(
                e -> {
                  log.warn("Failed to count my ships for the readiness counter", e);
                  return null;
                });

    CompletableFuture<Long> unitTypeCountFuture =
        fragmentOnly
            ? CompletableFuture.completedFuture(null)
            : parallelPageLoader
                .<Long>loadAsync(() -> totalOf(hangarClient.squadronOverview(0, 1, null)))
                .exceptionally(
                    e -> {
                      log.warn("Failed to count the org-unit overview for the hangar tab", e);
                      return null;
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
                  List<LocationDto> hl = hangarClient.homeLocations();
                  return hl != null ? new ArrayList<>(hl) : new ArrayList<>();
                })
            .exceptionally(
                e -> {
                  log.error("Failed to fetch home locations", e);
                  return new ArrayList<>();
                });

    CompletableFuture.allOf(
            shipsFuture,
            counterpartFuture,
            unitTypeCountFuture,
            shipTypesFuture,
            locationsFuture,
            manufacturersFuture,
            homeLocationsFuture)
        .join();

    List<ShipTypeDto> shipTypes = shipTypesFuture.join();
    shipTypes.sort(Comparator.comparing(ShipTypeDto::name, String.CASE_INSENSITIVE_ORDER));

    List<LocationDto> locations = locationsFuture.join();
    locations.sort(Comparator.comparing(LocationDto::name, String.CASE_INSENSITIVE_ORDER));

    List<ManufacturerDto> manufacturers = manufacturersFuture.join();
    manufacturers.sort(Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));

    List<LocationDto> homeLocations = homeLocationsFuture.join();

    PageResponse<ShipDto> myShipsPage = shipsFuture.join();
    long listed = myShipsPage.totalElements();
    Long counterpart = shipsFailed.get() ? null : counterpartFuture.join();
    Long totalShips = fittedFilter == null ? Long.valueOf(listed) : counterpart;
    Long fittedShips = fittedShipCount(fittedFilter, listed, counterpart);
    long totalShipCount = totalShips != null ? totalShips : listed;

    model.addAttribute("activeTab", TAB_MINE);
    model.addAttribute(
        "myShips", myShipsPage.content() != null ? myShipsPage.content() : List.of());
    model.addAttribute("myShipsPage", myShipsPage);
    model.addAttribute("totalShipCount", totalShipCount);
    model.addAttribute("fittedShipCount", shipsFailed.get() ? null : fittedShips);
    model.addAttribute("mineTabCount", shipsFailed.get() ? null : totalShipCount);
    model.addAttribute("unitTabCount", unitTypeCountFuture.join());
    model.addAttribute("pageSizes", HANGAR_PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("search", effectiveSearch);
    model.addAttribute("fitted", fittedFilter == null ? "" : fittedFilter.toString());
    model.addAttribute("paginationBaseUrl", listBaseUrl("/hangar", effectiveSearch, fittedFilter));
    model.addAttribute("shipTypes", shipTypes);
    model.addAttribute("locations", locations);
    model.addAttribute("manufacturers", manufacturers);
    model.addAttribute("homeLocations", homeLocations);
    model.addAttribute("ownerOptions", fetchCallerMembershipOptions());

    if (fragmentOnly) {
      return "hangar :: hangarResults";
    }
    return "hangar";
  }

  /**
   * Reads the {@code fitted} list filter leniently, so a crafted value lists every ship instead of
   * failing the page.
   *
   * @param fitted the raw query value
   * @return {@code TRUE} or {@code FALSE} for {@code "true"} / {@code "false"}, otherwise {@code
   *     null}
   */
  @Contract("null -> null")
  private static @Nullable Boolean parseFitted(@Nullable String fitted) {
    if ("true".equalsIgnoreCase(fitted)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(fitted)) {
      return Boolean.FALSE;
    }
    return null;
  }

  /**
   * Derives how many of the listed scope's ships are fitted from the listed page total and the
   * counterpart count, which is the fitted count when no fitted filter applies and the unfiltered
   * total otherwise.
   *
   * @param fittedFilter the active fitted filter, or {@code null}
   * @param listed the total of the listed page
   * @param counterpart the counterpart total, or {@code null} when it could not be read
   * @return the fitted ship count, or {@code null} when it cannot be derived
   */
  private static @Nullable Long fittedShipCount(
      @Nullable Boolean fittedFilter, long listed, @Nullable Long counterpart) {
    if (fittedFilter == null) {
      return counterpart;
    }
    if (fittedFilter) {
      return listed;
    }
    return counterpart == null ? null : Math.max(0L, counterpart - listed);
  }

  /**
   * Reads only the total of a backend page, for a tab or readiness counter.
   *
   * @param response the page, usually asking for a single entry, or {@code null}
   * @return the page's {@code totalElements}, or {@code null} when the backend returned no body
   */
  @Contract("null -> null")
  @Nullable
  private static Long totalOf(@Nullable PageResponse<?> response) {
    return response == null ? null : response.totalElements();
  }

  /**
   * Builds a tab's pagination base URL keeping the active search and fitted filter, so paging and
   * the size picker never drop them.
   *
   * @param path the tab's own path
   * @param search the active search term, or {@code null}
   * @param fitted the active fitted filter, or {@code null}
   * @return the base URL without paging params
   */
  private static @NotNull String listBaseUrl(
      @NotNull String path, @Nullable String search, @Nullable Boolean fitted) {
    if (search == null && fitted == null) {
      return path;
    }
    UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
    if (search != null) {
      uri.queryParam("search", search);
    }
    if (fitted != null) {
      uri.queryParam("fitted", fitted);
    }
    return uri.toUriString();
  }

  /**
   * Fetches the caller's org-unit memberships for the add-ship owner picker.
   *
   * @return picker options, or an empty list on backend failure; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchCallerMembershipOptions() {
    try {
      List<OrgUnitMembershipOptionDto> options = hangarClient.pickableOrgUnits();
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

  /** The {@code activeTab} value of the „Meine Schiffe" tab. */
  private static final String TAB_MINE = "mine";

  /** The {@code activeTab} value of the org-unit overview tab. */
  private static final String TAB_UNIT = "unit";

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
   * Renders the hangar page on its org-unit tab: ship counts per type in the caller's scope,
   * server-side paginated and filtered (REQ-HANGAR-001), with the own-ships tab count beside it.
   *
   * @param page zero-based page index; negatives are clamped to 0
   * @param size page size, validated against {@link #HANGAR_PAGE_SIZES}
   * @param search optional ship-type/manufacturer filter, applied by the backend
   * @param fragment {@code "results"} renders only the results + pagination fragment (REQ-FE-005)
   * @param model model populated with the overview page, the tab counts, the picker options and the
   *     pagination base URL
   * @return the {@code hangar} view name, or its {@code squadronResults} fragment selector
   */
  @NotNull
  @GetMapping("/squadron")
  public String viewSquadron(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean fragmentOnly = "results".equalsIgnoreCase(fragment);
    int effectiveSize =
        size != null && HANGAR_PAGE_SIZES.contains(size) ? size : HANGAR_DEFAULT_PAGE_SIZE;
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveSearch = search == null || search.isBlank() ? null : search.trim();

    final CompletableFuture<Long> mineCountFuture =
        fragmentOnly
            ? CompletableFuture.completedFuture(null)
            : parallelPageLoader
                .<Long>loadAsync(() -> totalOf(hangarClient.myShips(0, 1, null, null)))
                .exceptionally(
                    e -> {
                      log.warn("Failed to count my ships for the hangar tab", e);
                      return null;
                    });

    List<SquadronShipOverviewDto> overview = new ArrayList<>();
    PageResponse<SquadronShipOverviewDto> res = null;
    try {
      res = hangarClient.squadronOverview(effectivePage, effectiveSize, effectiveSearch);
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

    model.addAttribute("activeTab", TAB_UNIT);
    model.addAttribute("overview", overview);
    model.addAttribute("overviewPage", res);
    model.addAttribute("search", effectiveSearch);
    model.addAttribute("pageSizes", HANGAR_PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("paginationBaseUrl", listBaseUrl("/hangar/squadron", effectiveSearch, null));
    model.addAttribute("mineTabCount", mineCountFuture.join());
    model.addAttribute("unitTabCount", res != null ? Long.valueOf(res.totalElements()) : null);
    if (fragmentOnly) {
      return "hangar :: squadronResults";
    }
    return "hangar";
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
      return viewHangar(null, null, null, null, null, model);
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
      hangarClient.createShip(request);
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
      return viewHangar(null, null, null, null, null, model);
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
      hangarClient.updateShip(id, request);
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
      hangarClient.deleteShip(id);
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
      hangarClient.setHomeLocation(new SetHomeLocationRequestDto(locationId));
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
          hangarClient.createShip(
              new ShipRequestDto(
                  request.name(),
                  request.shipTypeId(),
                  request.insurance(),
                  request.locationId(),
                  request.fitted(),
                  null,
                  request.owningOrgUnitId()));
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
          hangarClient.updateShip(
              id,
              new ShipRequestDto(
                  request.name(),
                  request.shipTypeId(),
                  request.insurance(),
                  request.locationId(),
                  request.fitted(),
                  request.version(),
                  null));
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
          hangarClient.deleteShip(id);
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
          hangarClient.setHomeLocation(request);
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
    } catch (Exception _) {
      return null;
    }
  }
}
