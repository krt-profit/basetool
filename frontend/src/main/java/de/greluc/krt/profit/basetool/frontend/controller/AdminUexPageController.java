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

import de.greluc.krt.profit.basetool.frontend.model.dto.CityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OutpostDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PoiDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpaceStationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
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
 * Consolidated admin page for every UEX-mirrored entity ({@code /admin/uex-data}).
 *
 * <p>Replaces the previously separate {@code /admin/terminals} and {@code /admin/uex-locations}
 * pages with one hierarchical view: cities, space stations, outposts and POIs are grouped by their
 * star system, and the terminals belonging to a city or station are rendered directly below their
 * parent. The page therefore answers "where can my freighter actually dock?" in a single screen
 * instead of forcing the admin to alt-tab between two flat tables.
 *
 * <p>The backend endpoints used here are the same ones the previous two controllers called; no
 * backend surface change ships with the consolidation. The dispatcher methods carry the same {@code
 * uex}/{@code yes}/{@code no} action semantics as before: {@code uex} clears the admin pin so the
 * next UEX sweep restores the value, {@code yes}/{@code no} pin the flag.
 */
@Controller
@RequestMapping("/admin/uex-data")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUexPageController {

  /** URL segments accepted as the {@code kind} path variable for the loading-dock dispatcher. */
  private static final Set<String> ALLOWED_LOADING_DOCK_KINDS =
      Set.of("cities", "space-stations", "outposts", "pois", "terminals");

  /**
   * Response type for the generic UEX-entity page pulls (cities, stations, outposts, POIs,
   * terminals), each read as an untyped {@code Map} page before being parsed into its DTO.
   */
  private static final ParameterizedTypeReference<PageResponse<Map<String, Object>>> MAP_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Loads cities, space stations, outposts, POIs and terminals (one paginated call each, capped at
   * 10 000 rows like the previous flat pages) and groups them by star system. Terminals are
   * bucketed onto their parent city or station by name within the same star system; any terminal
   * that lacks a city/station match — typically free-floating orbital terminals — is collected into
   * a per-system "orphans" list so it stays visible. Backend failures land as an error attribute
   * rather than blanking the page.
   *
   * @param model Thymeleaf model populated with the sorted star-system tree, total counts and the
   *     most recent UEX sweep timestamp
   * @return the {@code admin/uex} view name
   */
  @GetMapping
  public String listData(Model model) {
    try {
      List<CityDto> cities = parseCities(loadPage("/api/v1/cities?size=10000&sort=name,asc"));
      List<SpaceStationDto> stations =
          parseStations(loadPage("/api/v1/space-stations?size=10000&sort=name,asc"));
      List<OutpostDto> outposts =
          parseOutposts(loadPage("/api/v1/outposts?size=10000&sort=name,asc"));
      List<PoiDto> pois = parsePois(loadPage("/api/v1/pois?size=10000&sort=name,asc"));
      List<TerminalDto> terminals =
          parseTerminals(loadPage("/api/v1/terminals?size=10000&sort=name,asc"));

      List<StarSystemGroup> systems = buildHierarchy(cities, stations, outposts, pois, terminals);

      Instant latestSync =
          terminals.stream()
              .map(TerminalDto::uexSyncedAt)
              .filter(Objects::nonNull)
              .max(Comparator.naturalOrder())
              .orElse(null);

      model.addAttribute("starSystems", systems);
      model.addAttribute("latestUexSync", latestSync);
      model.addAttribute("totalCities", cities.size());
      model.addAttribute("totalStations", stations.size());
      model.addAttribute("totalOutposts", outposts.size());
      model.addAttribute("totalPois", pois.size());
      model.addAttribute("totalTerminals", terminals.size());
    } catch (Exception e) {
      log.error("Error loading UEX admin data", e);
      model.addAttribute("error", "error.admin.uex.load");
    }
    return "admin/uex";
  }

  /**
   * Sets or clears the loading-dock override on a city, space station, outpost, POI or terminal.
   * The {@code kind} path variable maps 1:1 to the backend URL segment so the same dispatcher
   * serves all five entity types.
   *
   * @param kind one of {@code cities}, {@code space-stations}, {@code outposts}, {@code pois},
   *     {@code terminals}
   * @param id entity id
   * @param action one of {@code uex} (clear pin), {@code yes} or {@code no} (set pin)
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to {@code /admin/uex}
   */
  @PostMapping("/{kind}/{id}/loading-dock")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String updateLoadingDockOverride(
      @PathVariable @NotNull String kind,
      @PathVariable @NotNull UUID id,
      @RequestParam String action,
      RedirectAttributes redirectAttributes) {
    if (!ALLOWED_LOADING_DOCK_KINDS.contains(kind)) {
      redirectAttributes.addFlashAttribute("errorToast", "error.admin.uex.flag.update");
      return "redirect:/admin/uex-data";
    }
    return dispatchOverride(
        "/api/v1/" + kind + "/" + id,
        action,
        "loading-dock",
        "loading-dock-override",
        redirectAttributes);
  }

  /**
   * Sets or clears the auto-load override on a terminal. Only terminals carry this flag, so unlike
   * the loading-dock dispatcher there is no {@code kind} path variable.
   *
   * @param id terminal id
   * @param action one of {@code uex} (clear pin), {@code yes} or {@code no} (set pin)
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to {@code /admin/uex}
   */
  @PostMapping("/terminals/{id}/auto-load")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String updateTerminalAutoLoadOverride(
      @PathVariable @NotNull UUID id,
      @RequestParam String action,
      RedirectAttributes redirectAttributes) {
    return dispatchOverride(
        "/api/v1/terminals/" + id, action, "auto-load", "auto-load-override", redirectAttributes);
  }

  /**
   * Toggles a single terminal's hidden flag. Pulls the current record so the PUT body contains the
   * UEX-imported display fields verbatim — the backend's PUT endpoint only updates the hidden flag,
   * but the body still has to be a full {@link TerminalDto}.
   *
   * @param id terminal id
   * @param hidden desired new hidden flag
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/uex} (optionally with {@code ?error=...})
   */
  @PostMapping("/terminals/{id}/toggle-visibility")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String toggleTerminalVisibility(
      @PathVariable @NotNull UUID id,
      @RequestParam boolean hidden,
      RedirectAttributes redirectAttributes) {
    try {
      TerminalDto current = backendApiClient.get("/api/v1/terminals/" + id, TerminalDto.class);
      TerminalDto body =
          new TerminalDto(
              id,
              current.name(),
              current.nickname(),
              current.starSystemName(),
              current.planetName(),
              current.cityName(),
              current.spaceStationName(),
              current.hasLoadingDock(),
              current.isAutoLoad(),
              current.hasLoadingDockOverridden(),
              current.isAutoLoadOverridden(),
              current.uexHasLoadingDock(),
              current.uexIsAutoLoad(),
              current.uexSyncedAt(),
              hidden);
      backendApiClient.put("/api/v1/terminals/" + id, body, Void.class);
      // Flipping a terminal's hidden flag changes the cached terminal catalogue (the
      // profit-calculator
      // star-system dropdown reads it), so evict the TERMINAL domain (REQ-DATA-007).
      backendApiClient.evict(CacheDomain.TERMINAL);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Toggle terminal visibility failed", e);
      return "redirect:/admin/uex-data?error=ToggleVisibilityFailed";
    }
    return "redirect:/admin/uex-data";
  }

  /**
   * In-place (AJAX) twin of {@link #updateLoadingDockOverride} — routed here ahead of the classic
   * handler by the {@code X-Requested-With} header so the no-JS form keeps its redirect fallback.
   * The button active-state is patched deterministically client-side from the action, so this just
   * relays success/failure.
   *
   * @param kind one of {@code cities}, {@code space-stations}, {@code outposts}, {@code pois},
   *     {@code terminals}
   * @param id entity id
   * @param action one of {@code uex} (clear pin), {@code yes} or {@code no} (set pin)
   * @return {@code 200} on success, {@code 400} for an unknown kind/action, the relayed backend
   *     status on a backend failure
   */
  @ResponseBody
  @PostMapping(value = "/{kind}/{id}/loading-dock", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> updateLoadingDockOverrideAjax(
      @PathVariable @NotNull String kind,
      @PathVariable @NotNull UUID id,
      @RequestParam String action) {
    if (!ALLOWED_LOADING_DOCK_KINDS.contains(kind)) {
      return ResponseEntity.badRequest().build();
    }
    return dispatchOverrideAjax(
        "/api/v1/" + kind + "/" + id, action, "loading-dock", "loading-dock-override");
  }

  /**
   * In-place (AJAX) twin of {@link #updateTerminalAutoLoadOverride}.
   *
   * @param id terminal id
   * @param action one of {@code uex}, {@code yes}, {@code no}
   * @return {@code 200} on success, {@code 400} for an unknown action, the relayed backend status
   *     on failure
   */
  @ResponseBody
  @PostMapping(value = "/terminals/{id}/auto-load", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> updateTerminalAutoLoadOverrideAjax(
      @PathVariable @NotNull UUID id, @RequestParam String action) {
    return dispatchOverrideAjax(
        "/api/v1/terminals/" + id, action, "auto-load", "auto-load-override");
  }

  /**
   * In-place (AJAX) twin of {@link #toggleTerminalVisibility}. Flips the hidden flag server-side
   * off a freshly-read record (so the body still carries the UEX-imported display fields verbatim)
   * and relays success/failure; the button is re-rendered client-side.
   *
   * @param id terminal id
   * @return {@code 200} on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(
      value = "/terminals/{id}/toggle-visibility",
      headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> toggleTerminalVisibilityAjax(@PathVariable @NotNull UUID id) {
    try {
      TerminalDto current = backendApiClient.get("/api/v1/terminals/" + id, TerminalDto.class);
      TerminalDto body =
          new TerminalDto(
              id,
              current.name(),
              current.nickname(),
              current.starSystemName(),
              current.planetName(),
              current.cityName(),
              current.spaceStationName(),
              current.hasLoadingDock(),
              current.isAutoLoad(),
              current.hasLoadingDockOverridden(),
              current.isAutoLoadOverridden(),
              current.uexHasLoadingDock(),
              current.uexIsAutoLoad(),
              current.uexSyncedAt(),
              !current.hidden());
      backendApiClient.put("/api/v1/terminals/" + id, body, Void.class);
      backendApiClient.evict(CacheDomain.TERMINAL);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("Toggle terminal visibility (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Toggle terminal visibility (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * AJAX counterpart of {@link #dispatchOverride}: applies the same {@code uex}/{@code yes}/{@code
   * no} mapping but returns an HTTP status instead of a redirect, relaying any backend problem so
   * {@code krtFetch} can branch on it.
   *
   * @param baseUri backend URI up to and including the entity id, with no trailing slash
   * @param action button action ({@code uex}, {@code yes}, or {@code no})
   * @param setPath URL segment that pins the flag on a PATCH
   * @param clearPath URL segment that drops the pin on a DELETE
   * @return {@code 200} on success, {@code 400} for an unknown action, the relayed backend status
   *     on failure
   */
  private ResponseEntity<Object> dispatchOverrideAjax(
      String baseUri, String action, String setPath, String clearPath) {
    try {
      switch (action) {
        case "uex" -> backendApiClient.delete(baseUri + "/" + clearPath, Void.class);
        case "yes" ->
            backendApiClient.patch(baseUri + "/" + setPath + "?value=true", null, Void.class);
        case "no" ->
            backendApiClient.patch(baseUri + "/" + setPath + "?value=false", null, Void.class);
        default -> {
          return ResponseEntity.badRequest().build();
        }
      }
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("Override update (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Override update (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Common backend dispatch for the three-state override buttons. Maps {@code uex} to {@code DELETE
   * clearPath}, {@code yes} to {@code PATCH setPath?value=true}, {@code no} to {@code PATCH
   * setPath?value=false}; anything else is rejected without a backend call.
   *
   * @param baseUri backend URI up to and including the entity id, with no trailing slash
   * @param action button action ({@code uex}, {@code yes}, or {@code no})
   * @param setPath URL segment that pins the flag on a PATCH
   * @param clearPath URL segment that drops the pin on a DELETE
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/uex}
   */
  private String dispatchOverride(
      String baseUri,
      String action,
      String setPath,
      String clearPath,
      RedirectAttributes redirectAttributes) {
    try {
      switch (action) {
        case "uex" -> backendApiClient.delete(baseUri + "/" + clearPath, Void.class);
        case "yes" ->
            backendApiClient.patch(baseUri + "/" + setPath + "?value=true", null, Void.class);
        case "no" ->
            backendApiClient.patch(baseUri + "/" + setPath + "?value=false", null, Void.class);
        default -> {
          redirectAttributes.addFlashAttribute("errorToast", "error.admin.uex.flag.update");
          return "redirect:/admin/uex-data";
        }
      }
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Override update failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.admin.uex.flag.update");
    } catch (Exception e) {
      log.error("Override update failed", e);
      return "redirect:/admin/uex-data?error=OverrideUpdateFailed";
    }
    return "redirect:/admin/uex-data";
  }

  /**
   * Groups the five flat entity lists into a per-star-system tree. Terminals are matched to their
   * parent city or station by the {@code starSystemName + cityName} / {@code starSystemName +
   * spaceStationName} tuple; any terminal that lacks a usable match goes onto the system's
   * "orphans" list so it is still surfaced.
   *
   * <p>Visible for tests so the bucketing logic — the hard part of this controller — can be
   * exercised without spinning up a Thymeleaf rendering pass.
   *
   * @param cities sorted city list
   * @param stations sorted space station list
   * @param outposts sorted outpost list
   * @param pois sorted POI list
   * @param terminals sorted terminal list
   * @return per-star-system groups, sorted case-insensitively by system name (unknown system goes
   *     last under an empty-string label)
   */
  List<StarSystemGroup> buildHierarchy(
      List<CityDto> cities,
      List<SpaceStationDto> stations,
      List<OutpostDto> outposts,
      List<PoiDto> pois,
      List<TerminalDto> terminals) {
    Map<String, SystemAccumulator> bySystem =
        new TreeMap<>(Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    for (CityDto city : cities) {
      bySystem.computeIfAbsent(city.starSystemName(), SystemAccumulator::new).cities.add(city);
    }
    for (SpaceStationDto station : stations) {
      bySystem
          .computeIfAbsent(station.starSystemName(), SystemAccumulator::new)
          .stations
          .add(station);
    }
    for (OutpostDto outpost : outposts) {
      bySystem
          .computeIfAbsent(outpost.starSystemName(), SystemAccumulator::new)
          .outposts
          .add(outpost);
    }
    for (PoiDto poi : pois) {
      bySystem.computeIfAbsent(poi.starSystemName(), SystemAccumulator::new).pois.add(poi);
    }

    // Bucket terminals into (system, city) / (system, station); leftovers go on the
    // system's orphan list. Names are matched case-insensitively to be resilient to
    // small whitespace/casing drift between the UEX dump and the parent record.
    Map<String, Map<String, List<TerminalDto>>> cityTerminals = new LinkedHashMap<>();
    Map<String, Map<String, List<TerminalDto>>> stationTerminals = new LinkedHashMap<>();
    for (TerminalDto term : terminals) {
      String system = nullSafe(term.starSystemName());
      SystemAccumulator acc =
          bySystem.computeIfAbsent(term.starSystemName(), SystemAccumulator::new);
      if (term.cityName() != null && !term.cityName().isBlank()) {
        cityTerminals
            .computeIfAbsent(system, s -> new LinkedHashMap<>())
            .computeIfAbsent(term.cityName().toLowerCase(), k -> new ArrayList<>())
            .add(term);
      } else if (term.spaceStationName() != null && !term.spaceStationName().isBlank()) {
        stationTerminals
            .computeIfAbsent(system, s -> new LinkedHashMap<>())
            .computeIfAbsent(term.spaceStationName().toLowerCase(), k -> new ArrayList<>())
            .add(term);
      } else {
        acc.orphanTerminals.add(term);
      }
    }

    List<StarSystemGroup> result = new ArrayList<>();
    for (Map.Entry<String, SystemAccumulator> entry : bySystem.entrySet()) {
      String systemKey = nullSafe(entry.getKey());
      SystemAccumulator acc = entry.getValue();
      Map<String, List<TerminalDto>> sysCityBuckets =
          cityTerminals.getOrDefault(systemKey, Map.of());
      Map<String, List<TerminalDto>> sysStationBuckets =
          stationTerminals.getOrDefault(systemKey, Map.of());
      Set<String> usedCityKeys = new HashSet<>();
      Set<String> usedStationKeys = new HashSet<>();

      List<CityNode> cityNodes = new ArrayList<>();
      for (CityDto city : acc.cities) {
        String key = nullSafe(city.name()).toLowerCase();
        usedCityKeys.add(key);
        List<TerminalDto> matched = sysCityBuckets.getOrDefault(key, List.of());
        cityNodes.add(new CityNode(city, List.copyOf(matched)));
      }

      List<SpaceStationNode> stationNodes = new ArrayList<>();
      for (SpaceStationDto station : acc.stations) {
        String key = nullSafe(station.name()).toLowerCase();
        usedStationKeys.add(key);
        List<TerminalDto> matched = sysStationBuckets.getOrDefault(key, List.of());
        stationNodes.add(new SpaceStationNode(station, List.copyOf(matched)));
      }

      // Terminals whose claimed cityName/spaceStationName does not match any
      // record we just fetched would otherwise vanish from the page. Realistic
      // cause: a UEX sweep populated terminals before the parent city/station
      // sweep on a fresh install, or a parent record was retired but the
      // terminal still references it. Either way the admin still needs the
      // override buttons, so we surface these on the per-system orphan list.
      List<TerminalDto> systemOrphans = new ArrayList<>(acc.orphanTerminals);
      sysCityBuckets.forEach(
          (k, v) -> {
            if (!usedCityKeys.contains(k)) {
              systemOrphans.addAll(v);
            }
          });
      sysStationBuckets.forEach(
          (k, v) -> {
            if (!usedStationKeys.contains(k)) {
              systemOrphans.addAll(v);
            }
          });

      result.add(
          new StarSystemGroup(
              entry.getKey(),
              List.copyOf(cityNodes),
              List.copyOf(stationNodes),
              List.copyOf(acc.outposts),
              List.copyOf(acc.pois),
              List.copyOf(systemOrphans)));
    }
    return List.copyOf(result);
  }

  private PageResponse<Map<String, Object>> loadPage(String uri) {
    return backendApiClient.get(uri, MAP_PAGE_TYPE);
  }

  private List<CityDto> parseCities(PageResponse<Map<String, Object>> page) {
    return parseAndSort(
        page,
        m ->
            new CityDto(
                parseUuid(m.get("id")),
                parseString(m.get("name")),
                parseString(m.get("starSystemName")),
                parseString(m.get("planetName")),
                parseNullableBoolean(m.get("hasLoadingDock")),
                Boolean.TRUE.equals(m.get("hasLoadingDockOverridden"))),
        CityDto::name);
  }

  private List<SpaceStationDto> parseStations(PageResponse<Map<String, Object>> page) {
    return parseAndSort(
        page,
        m ->
            new SpaceStationDto(
                parseUuid(m.get("id")),
                parseString(m.get("name")),
                parseString(m.get("starSystemName")),
                parseString(m.get("planetName")),
                parseNullableBoolean(m.get("hasLoadingDock")),
                Boolean.TRUE.equals(m.get("hasLoadingDockOverridden"))),
        SpaceStationDto::name);
  }

  private List<OutpostDto> parseOutposts(PageResponse<Map<String, Object>> page) {
    return parseAndSort(
        page,
        m ->
            new OutpostDto(
                parseUuid(m.get("id")),
                parseString(m.get("name")),
                parseString(m.get("starSystemName")),
                parseString(m.get("planetName")),
                parseNullableBoolean(m.get("hasLoadingDock")),
                Boolean.TRUE.equals(m.get("hasLoadingDockOverridden"))),
        OutpostDto::name);
  }

  private List<PoiDto> parsePois(PageResponse<Map<String, Object>> page) {
    return parseAndSort(
        page,
        m ->
            new PoiDto(
                parseUuid(m.get("id")),
                parseString(m.get("name")),
                parseString(m.get("starSystemName")),
                parseString(m.get("planetName")),
                parseNullableBoolean(m.get("hasLoadingDock")),
                Boolean.TRUE.equals(m.get("hasLoadingDockOverridden"))),
        PoiDto::name);
  }

  private List<TerminalDto> parseTerminals(PageResponse<Map<String, Object>> page) {
    return parseAndSort(
        page,
        m ->
            new TerminalDto(
                parseUuid(m.get("id")),
                parseString(m.get("name")),
                parseString(m.get("nickname")),
                parseString(m.get("starSystemName")),
                parseString(m.get("planetName")),
                parseString(m.get("cityName")),
                parseString(m.get("spaceStationName")),
                parseNullableBoolean(m.get("hasLoadingDock")),
                parseNullableBoolean(m.get("isAutoLoad")),
                Boolean.TRUE.equals(m.get("hasLoadingDockOverridden")),
                Boolean.TRUE.equals(m.get("isAutoLoadOverridden")),
                parseNullableBoolean(m.get("uexHasLoadingDock")),
                parseNullableBoolean(m.get("uexIsAutoLoad")),
                parseInstant(m.get("uexSyncedAt")),
                Boolean.TRUE.equals(m.get("hidden"))),
        TerminalDto::name);
  }

  private <T> List<T> parseAndSort(
      PageResponse<Map<String, Object>> page,
      java.util.function.Function<Map<String, Object>, T> mapper,
      java.util.function.Function<T, String> nameAccessor) {
    if (page == null || page.content() == null) {
      return new ArrayList<>();
    }
    List<T> list =
        page.content().stream().map(mapper).collect(Collectors.toCollection(ArrayList::new));
    list.sort(
        Comparator.comparing(
            t -> {
              String n = nameAccessor.apply(t);
              return n == null ? "" : n;
            },
            String.CASE_INSENSITIVE_ORDER));
    return list;
  }

  private String parseString(Object o) {
    return o == null ? null : o.toString();
  }

  private UUID parseUuid(Object o) {
    if (o == null) {
      return null;
    }
    try {
      return UUID.fromString(o.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private Boolean parseNullableBoolean(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(o.toString());
  }

  private Instant parseInstant(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Instant i) {
      return i;
    }
    try {
      return Instant.parse(o.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }

  /** Mutable bucket used only while {@link #buildHierarchy} is grouping entities by star system. */
  private static final class SystemAccumulator {
    final List<CityDto> cities = new ArrayList<>();
    final List<SpaceStationDto> stations = new ArrayList<>();
    final List<OutpostDto> outposts = new ArrayList<>();
    final List<PoiDto> pois = new ArrayList<>();
    final List<TerminalDto> orphanTerminals = new ArrayList<>();

    @SuppressWarnings("unused")
    SystemAccumulator(String unusedSystemName) {
      // computeIfAbsent supplies the system name; we don't need to store it because
      // the surrounding TreeMap already keys by it.
    }
  }

  /**
   * One city plus the terminals that UEX maps to it.
   *
   * @param city the city itself
   * @param terminals terminals whose {@code cityName} matches the city's name in the same star
   *     system (may be empty)
   */
  public record CityNode(CityDto city, List<TerminalDto> terminals) {}

  /**
   * One space station plus the terminals that UEX maps to it.
   *
   * @param station the space station itself
   * @param terminals terminals whose {@code spaceStationName} matches the station's name in the
   *     same star system (may be empty)
   */
  public record SpaceStationNode(SpaceStationDto station, List<TerminalDto> terminals) {}

  /**
   * All UEX-mirrored entities that share one star system.
   *
   * @param name star system name (may be {@code null} for orphaned entities with no system label)
   * @param cities cities in this system, with their terminals nested
   * @param spaceStations space stations in this system, with their terminals nested
   * @param outposts outposts in this system (no terminals attach to outposts)
   * @param pois POIs in this system (no terminals attach to POIs)
   * @param orphanTerminals terminals in this system that could not be matched to any city or
   *     station — typically free-floating orbital terminals that have neither {@code cityName} nor
   *     {@code spaceStationName} set
   */
  public record StarSystemGroup(
      String name,
      List<CityNode> cities,
      List<SpaceStationNode> spaceStations,
      List<OutpostDto> outposts,
      List<PoiDto> pois,
      List<TerminalDto> orphanTerminals) {

    /**
     * Convenience accessor used by the Thymeleaf header chips so the template does not need to
     * recompute the sum on every render.
     *
     * @return total locations of every type contained in this system
     */
    public int locationCount() {
      return cities.size() + spaceStations.size() + outposts.size() + pois.size();
    }

    /**
     * Counts terminals contained anywhere in the system, including the orphan bucket.
     *
     * @return total terminals contained in this system
     */
    public int terminalCount() {
      int cityTerminals = cities.stream().mapToInt(c -> c.terminals().size()).sum();
      int stationTerminals = spaceStations.stream().mapToInt(s -> s.terminals().size()).sum();
      return cityTerminals + stationTerminals + orphanTerminals.size();
    }
  }
}
