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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient.UexEntity;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.CityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OutpostDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PoiDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpaceStationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.service.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
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
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
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
 * Admin page for every UEX-mirrored entity ({@code /admin/uex-data}): cities, stations, outposts
 * and POIs grouped by star system, with terminals nested under their city or station.
 *
 * <p>Override actions: {@code uex} clears the admin pin, {@code yes}/{@code no} set it.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/uex-data")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUexPageController {

  /** URL segments accepted as the {@code kind} path variable, each with the entity it names. */
  private static final Map<String, UexEntity> ALLOWED_LOADING_DOCK_KINDS =
      Map.of(
          "cities", UexEntity.CITIES,
          "space-stations", UexEntity.SPACE_STATIONS,
          "outposts", UexEntity.OUTPOSTS,
          "pois", UexEntity.POIS,
          "terminals", UexEntity.TERMINALS);

  /** Reads the UEX-mirrored universe and writes its overrides. */
  private final CatalogueBackendClient catalogueClient;

  /** Evicts the terminal catalogue after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Loads every page of the cities, stations, outposts, POIs and terminals (REQ-ADMIN-001) and
   * groups them by star system; unmatched terminals go to a per-system orphans list. Totals come
   * from the backend's {@code totalElements}, and a truncated walk raises the warning banner
   * (REQ-ADMIN-002).
   *
   * @param model Thymeleaf model populated with the star-system tree, totals and the last UEX sweep
   *     time
   * @return the {@code admin/uex} view name
   */
  @NotNull
  @GetMapping
  public String listData(Model model) {
    try {
      CompleteCatalog<CityDto> citiesCatalog = CatalogPages.fetchAll(catalogueClient::cityPage);
      CompleteCatalog<SpaceStationDto> stationsCatalog =
          CatalogPages.fetchAll(catalogueClient::spaceStationPage);
      CompleteCatalog<OutpostDto> outpostsCatalog =
          CatalogPages.fetchAll(catalogueClient::outpostPage);
      CompleteCatalog<PoiDto> poisCatalog = CatalogPages.fetchAll(catalogueClient::poiPage);
      CompleteCatalog<TerminalDto> terminalsCatalog =
          CatalogPages.fetchAll(catalogueClient::terminalPage);

      List<CityDto> cities = sortedByName(citiesCatalog.items(), CityDto::name);
      List<SpaceStationDto> stations = sortedByName(stationsCatalog.items(), SpaceStationDto::name);
      List<OutpostDto> outposts = sortedByName(outpostsCatalog.items(), OutpostDto::name);
      List<PoiDto> pois = sortedByName(poisCatalog.items(), PoiDto::name);
      List<TerminalDto> terminals = sortedByName(terminalsCatalog.items(), TerminalDto::name);

      List<StarSystemGroup> systems = buildHierarchy(cities, stations, outposts, pois, terminals);

      Instant latestSync =
          terminals.stream()
              .map(TerminalDto::uexSyncedAt)
              .filter(Objects::nonNull)
              .max(Comparator.naturalOrder())
              .orElse(null);

      model.addAttribute("starSystems", systems);
      model.addAttribute("latestUexSync", latestSync);
      model.addAttribute("totalCities", citiesCatalog.totalElements());
      model.addAttribute("totalStations", stationsCatalog.totalElements());
      model.addAttribute("totalOutposts", outpostsCatalog.totalElements());
      model.addAttribute("totalPois", poisCatalog.totalElements());
      model.addAttribute("totalTerminals", terminalsCatalog.totalElements());
      model.addAttribute(
          "catalogTruncated",
          citiesCatalog.truncated()
              || stationsCatalog.truncated()
              || outpostsCatalog.truncated()
              || poisCatalog.truncated()
              || terminalsCatalog.truncated());
    } catch (BackendServiceException e) {
      log.debug("Error loading UEX admin data", e);
      model.addAttribute("error", "error.admin.uex.load");
    } catch (Exception e) {
      log.error("Error loading UEX admin data", e);
      model.addAttribute("error", "error.admin.uex.load");
    }
    return "admin/uex";
  }

  /**
   * Sets or clears the loading-dock override on a city, station, outpost, POI or terminal.
   *
   * @param kind one of {@code cities}, {@code space-stations}, {@code outposts}, {@code pois},
   *     {@code terminals}
   * @param id entity id
   * @param action one of {@code uex} (clear pin), {@code yes} or {@code no} (set pin)
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to {@code /admin/uex}
   */
  @NotNull
  @PostMapping("/{kind}/{id}/loading-dock")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String updateLoadingDockOverride(
      @PathVariable @NotNull String kind,
      @PathVariable @NotNull UUID id,
      @RequestParam String action,
      RedirectAttributes redirectAttributes) {
    UexEntity entity = ALLOWED_LOADING_DOCK_KINDS.get(kind);
    if (entity == null) {
      redirectAttributes.addFlashAttribute("errorToast", "error.admin.uex.flag.update");
      return "redirect:/admin/uex-data";
    }
    return dispatchOverride(
        action,
        () -> catalogueClient.clearLoadingDockPin(entity, id),
        value -> catalogueClient.pinLoadingDock(entity, id, value),
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
  @NotNull
  @PostMapping("/terminals/{id}/auto-load")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String updateTerminalAutoLoadOverride(
      @PathVariable @NotNull UUID id,
      @RequestParam String action,
      RedirectAttributes redirectAttributes) {
    return dispatchOverride(
        action,
        () -> catalogueClient.clearTerminalAutoLoadPin(id),
        value -> catalogueClient.pinTerminalAutoLoad(id, value),
        redirectAttributes);
  }

  /**
   * Toggles a terminal's hidden flag, sending the freshly read record as the full {@link
   * TerminalDto} body.
   *
   * @param id terminal id
   * @param hidden desired hidden flag
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/uex}, optionally with {@code ?error=...}
   */
  @NotNull
  @PostMapping("/terminals/{id}/toggle-visibility")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String toggleTerminalVisibility(
      @PathVariable @NotNull UUID id,
      @RequestParam boolean hidden,
      RedirectAttributes redirectAttributes) {
    try {
      TerminalDto current = catalogueClient.terminal(id);
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
      catalogueClient.updateTerminal(id, body);
      cacheEviction.evict(CacheDomain.TERMINAL);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Toggle terminal visibility failed", e);
      return "redirect:/admin/uex-data?error=ToggleVisibilityFailed";
    }
    return "redirect:/admin/uex-data";
  }

  /**
   * AJAX twin of {@link #updateLoadingDockOverride}.
   *
   * @param kind one of {@code cities}, {@code space-stations}, {@code outposts}, {@code pois},
   *     {@code terminals}
   * @param id entity id
   * @param action one of {@code uex} (clear pin), {@code yes} or {@code no} (set pin)
   * @return {@code 200} on success, {@code 400} for an unknown kind or action, the relayed backend
   *     status on failure
   */
  @ResponseBody
  @PostMapping(value = "/{kind}/{id}/loading-dock", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> updateLoadingDockOverrideAjax(
      @PathVariable @NotNull String kind,
      @PathVariable @NotNull UUID id,
      @RequestParam String action) {
    UexEntity entity = ALLOWED_LOADING_DOCK_KINDS.get(kind);
    if (entity == null) {
      return ResponseEntity.badRequest().build();
    }
    return dispatchOverrideAjax(
        action,
        () -> catalogueClient.clearLoadingDockPin(entity, id),
        value -> catalogueClient.pinLoadingDock(entity, id, value));
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
        action,
        () -> catalogueClient.clearTerminalAutoLoadPin(id),
        value -> catalogueClient.pinTerminalAutoLoad(id, value));
  }

  /**
   * AJAX twin of {@link #toggleTerminalVisibility}.
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
    return relay(
        log,
        "toggle terminal visibility (ajax)",
        () -> {
          TerminalDto current = catalogueClient.terminal(id);
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
          catalogueClient.updateTerminal(id, body);
          cacheEviction.evict(CacheDomain.TERMINAL);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * AJAX counterpart of {@link #dispatchOverride} that returns an HTTP status instead of a
   * redirect.
   *
   * @param action button action ({@code uex}, {@code yes}, or {@code no})
   * @param clear drops the pin
   * @param pin pins the flag to the given value
   * @return {@code 200} on success, {@code 400} for an unknown action, the relayed backend status
   *     on failure
   */
  private ResponseEntity<Object> dispatchOverrideAjax(
      String action, Runnable clear, Consumer<Boolean> pin) {
    return relay(
        log,
        "override update (ajax)",
        () -> {
          switch (action) {
            case "uex" -> clear.run();
            case "yes" -> pin.accept(true);
            case "no" -> pin.accept(false);
            default -> {
              return ResponseEntity.badRequest().build();
            }
          }
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Common backend dispatch for the three-state override buttons. Maps {@code uex} to clearing the
   * pin, {@code yes} and {@code no} to pinning the flag to {@code true} and {@code false}; anything
   * else is rejected without a backend call.
   *
   * @param action button action ({@code uex}, {@code yes}, or {@code no})
   * @param clear drops the pin
   * @param pin pins the flag to the given value
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/uex}
   */
  @NotNull
  private String dispatchOverride(
      String action, Runnable clear, Consumer<Boolean> pin, RedirectAttributes redirectAttributes) {
    try {
      switch (action) {
        case "uex" -> clear.run();
        case "yes" -> pin.accept(true);
        case "no" -> pin.accept(false);
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
   * Groups the five entity lists into a per-star-system tree, nesting terminals under their city or
   * station by system and name; unmatched terminals go to the system's orphans list.
   *
   * @param cities sorted city list
   * @param stations sorted space station list
   * @param outposts sorted outpost list
   * @param pois sorted POI list
   * @param terminals sorted terminal list
   * @return groups sorted case-insensitively by system name, the unknown system last
   */
  @NotNull
  @Unmodifiable
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

  /**
   * Copies a catalogue and sorts it case-insensitively by name, a missing name first.
   *
   * @param rows the catalogue rows
   * @param nameAccessor reads a row's name
   * @param <T> the row type
   * @return the sorted, mutable copy
   */
  @NotNull
  private static <T> List<T> sortedByName(
      @NotNull List<T> rows, @NotNull Function<T, String> nameAccessor) {
    List<T> list = new ArrayList<>(rows);
    list.sort(
        Comparator.comparing(
            t -> {
              String n = nameAccessor.apply(t);
              return n == null ? "" : n;
            },
            String.CASE_INSENSITIVE_ORDER));
    return list;
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
    SystemAccumulator(String unusedSystemName) {}
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
   * All UEX-mirrored entities of one star system.
   *
   * @param name star system name, or {@code null} for entities without a system
   * @param cities cities in this system, with their terminals nested
   * @param spaceStations space stations in this system, with their terminals nested
   * @param outposts outposts in this system
   * @param pois POIs in this system
   * @param orphanTerminals terminals matched to no city or station
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
