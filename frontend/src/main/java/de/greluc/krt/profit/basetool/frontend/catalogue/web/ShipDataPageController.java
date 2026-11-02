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
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerForm;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Spring MVC controller for the ship-data admin page ({@code /ship-data}): the manufacturer and
 * ship-type catalogs including hidden entries, visibility toggles, and a squadron-wide reset of
 * every ship's {@code fitted} flag.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/ship-data")
@RequiredArgsConstructor
@Slf4j
public class ShipDataPageController {

  /** The page path, also the base of the pagination links. */
  private static final String PATH = "/ship-data";

  /** Reads and writes the manufacturer and ship-type catalogues. */
  private final CatalogueBackendClient catalogueClient;

  /** Reads the displayed page of the paginated catalogue list. */
  private final CatalogueListBackendClient listClient;

  /**
   * Renders one page of the ship types, narrowed by a server-side name search, and the complete
   * manufacturer catalogue, both including hidden entries (REQ-ADMIN-001), and seeds empty forms.
   *
   * <p>A backend failure sets an error key; a page walk that hits its safety cap sets {@code
   * catalogTruncated} so the template warns (REQ-ADMIN-002). The results fragment loads the ship
   * type page only.
   *
   * @param query ship-type name fragment to search for, may be {@code null}
   * @param page zero-based ship-type page index, may be {@code null}
   * @param size ship-type page size out of 25, 50 and 100, may be {@code null}
   * @param fragment {@code results} to render only the swapped ship-type list fragment
   * @param model Thymeleaf model populated with both forms, both lists and the optional error key
   * @return the {@code ship-data} view, or its ship-type results fragment
   */
  @NotNull
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @SuppressWarnings("unchecked")
  public String listData(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    CatalogueListPaging paging = CatalogueListPaging.of(page, size, query);
    paging.addTo(model, "shipTypes", paging.orEmpty(null), PATH);
    model.addAttribute("shipTypes", List.of());
    boolean resultsOnly = CatalogueListPaging.wantsResults(fragment);
    if (resultsOnly) {
      if (!SectionFallback.run(log, "ship-data", () -> addShipTypePage(model, paging))) {
        model.addAttribute("error", "error.shipdata.load");
      }
      return "catalogue/ship-data :: shipTypesResults";
    }

    if (!model.containsAttribute("manufacturerForm")) {
      model.addAttribute("manufacturerForm", new ManufacturerForm("", "", "", "", ""));
    }
    if (!model.containsAttribute("shipTypeForm")) {
      model.addAttribute("shipTypeForm", new ShipTypeForm("", null, ""));
    }

    if (!SectionFallback.run(log, "ship-data", () -> addCatalogs(model, paging))) {
      model.addAttribute("error", "error.shipdata.load");
    }

    return "catalogue/ship-data";
  }

  /**
   * Adds the displayed ship-type page.
   *
   * @param model the ship-data model
   * @param paging the requested page, size and search
   */
  private void addShipTypePage(@NotNull Model model, @NotNull CatalogueListPaging paging) {
    PageResponse<ShipTypeDto> shipTypesPage =
        paging.orEmpty(listClient.shipTypes(paging.query(), paging.page(), paging.size()));
    paging.addTo(model, "shipTypes", shipTypesPage, PATH);
    model.addAttribute("shipTypes", shipTypesPage.content());
  }

  /**
   * Adds the displayed ship-type page, every ship-type name for the search suggestions, the
   * name-sorted manufacturers and whether either page walk hit its cap.
   *
   * @param model the ship-data model
   * @param paging the requested page, size and search
   */
  private void addCatalogs(@NotNull Model model, @NotNull CatalogueListPaging paging) {
    addShipTypePage(model, paging);

    CompleteCatalog<ManufacturerDto> manufacturersCatalog =
        CatalogPages.fetchAll(catalogueClient::manufacturerPage);
    List<ManufacturerDto> manufacturers = new ArrayList<>(manufacturersCatalog.items());
    manufacturers.sort(Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));
    model.addAttribute("manufacturers", manufacturers);

    CompleteCatalog<ShipTypeDto> shipTypesCatalog =
        CatalogPages.fetchAll(catalogueClient::shipTypePage);
    model.addAttribute(
        "shipTypeNames",
        shipTypesCatalog.items().stream()
            .map(ShipTypeDto::name)
            .filter(Objects::nonNull)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());

    model.addAttribute(
        "catalogTruncated", manufacturersCatalog.truncated() || shipTypesCatalog.truncated());
  }

  /**
   * Toggles a manufacturer's hidden flag; a failure redirects with an error query parameter.
   *
   * @param id manufacturer id
   * @param hidden desired new flag value
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /ship-data} (optionally with {@code ?error=...})
   */
  @NotNull
  @PostMapping("/manufacturers/{id}/visibility")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String toggleManufacturerVisibility(
      @PathVariable @NotNull UUID id,
      @RequestParam boolean hidden,
      RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.setManufacturerHidden(id, hidden);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update Manufacturer visibility failed", e);
      return "redirect:/ship-data?error=UpdateManufacturerVisibilityFailed";
    }
    return "redirect:/ship-data";
  }

  /**
   * Toggles a ship type's hidden flag. Same failure-redirect pattern as {@link
   * #toggleManufacturerVisibility}.
   *
   * @param id ship type id
   * @param hidden desired new flag value
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /ship-data} (optionally with {@code ?error=...})
   */
  @NotNull
  @PostMapping("/ship-types/{id}/visibility")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String toggleShipTypeVisibility(
      @PathVariable @NotNull UUID id,
      @RequestParam boolean hidden,
      RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.setShipTypeHidden(id, hidden);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update ShipType visibility failed", e);
      return "redirect:/ship-data?error=UpdateShipTypeVisibilityFailed";
    }
    return "redirect:/ship-data";
  }

  /**
   * Resets the {@code fitted} flag on every ship of the squadron; irreversible, restricted to
   * ADMIN.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /ship-data}
   */
  @NotNull
  @PostMapping("/reset-fitted")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String resetAllFitted(RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.resetAllFitted();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.ship_unfitted");
    } catch (Exception e) {
      log.error("Reset all fitted failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.shipdata.unfit.failed");
    }
    return "redirect:/ship-data";
  }

  /**
   * AJAX twin of {@link #resetAllFitted}: clears the {@code fitted} flag on every ship.
   *
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/reset-fitted", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @ResponseBody
  public ResponseEntity<Object> resetAllFittedAjax() {
    return relay(
        log,
        "reset all fitted (ajax)",
        () -> {
          catalogueClient.resetAllFitted();
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * AJAX twin of {@link #toggleShipTypeVisibility}: flips a ship type's hidden flag.
   *
   * @param id ship type id
   * @param hidden the desired new flag value
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/ship-types/{id}/visibility", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @ResponseBody
  public ResponseEntity<Object> toggleShipTypeVisibilityAjax(
      @PathVariable @NotNull UUID id, @RequestParam boolean hidden) {
    return relay(
        log,
        "update ShipType visibility (ajax)",
        () -> {
          catalogueClient.setShipTypeHidden(id, hidden);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * AJAX twin of {@link #toggleManufacturerVisibility}: flips a manufacturer's hidden flag.
   *
   * @param id manufacturer id
   * @param hidden the desired new flag value
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(
      value = "/manufacturers/{id}/visibility",
      headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @ResponseBody
  public ResponseEntity<Object> toggleManufacturerVisibilityAjax(
      @PathVariable @NotNull UUID id, @RequestParam boolean hidden) {
    return relay(
        log,
        "update Manufacturer visibility (ajax)",
        () -> {
          catalogueClient.setManufacturerHidden(id, hidden);
          return ResponseEntity.noContent().build();
        });
  }
}
