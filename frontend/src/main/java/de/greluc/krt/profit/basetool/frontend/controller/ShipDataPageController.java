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
import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.form.ManufacturerForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ShipTypeForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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

  private final BackendApiClient backendApiClient;

  /** Response type for the manufacturer catalog page fetch (includes hidden entries). */
  private static final ParameterizedTypeReference<PageResponse<ManufacturerDto>>
      MANUFACTURER_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the ship-type catalog page fetch (includes hidden entries). */
  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /**
   * Loads the complete manufacturer and ship-type catalogs including hidden entries
   * (REQ-ADMIN-001), sorted case-insensitively by name, and seeds empty forms.
   *
   * <p>A backend failure sets an error key; a page walk that hits its safety cap sets {@code
   * catalogTruncated} so the template warns (REQ-ADMIN-002).
   *
   * @param model Thymeleaf model populated with both forms, both lists and the optional error key
   * @return the {@code ship-data} view name
   */
  @NotNull
  @GetMapping
  @SuppressWarnings("unchecked")
  public String listData(Model model) {
    if (!model.containsAttribute("manufacturerForm")) {
      model.addAttribute("manufacturerForm", new ManufacturerForm("", "", "", "", ""));
    }
    if (!model.containsAttribute("shipTypeForm")) {
      model.addAttribute("shipTypeForm", new ShipTypeForm("", null, ""));
    }

    try {
      CompleteCatalog<ManufacturerDto> manufacturersCatalog =
          CatalogPages.fetchAll(
              page ->
                  backendApiClient.get(
                      "/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true&page="
                          + page,
                      MANUFACTURER_PAGE_TYPE));
      List<ManufacturerDto> manufacturers = new ArrayList<>(manufacturersCatalog.items());
      manufacturers.sort(
          Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));
      model.addAttribute("manufacturers", manufacturers);

      CompleteCatalog<ShipTypeDto> shipTypesCatalog =
          CatalogPages.fetchAll(
              page ->
                  backendApiClient.get(
                      "/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true&page=" + page,
                      SHIP_TYPE_PAGE_TYPE));
      List<ShipTypeDto> shipTypes = new ArrayList<>(shipTypesCatalog.items());
      shipTypes.sort(Comparator.comparing(ShipTypeDto::name, String.CASE_INSENSITIVE_ORDER));
      model.addAttribute("shipTypes", shipTypes);

      model.addAttribute(
          "catalogTruncated", manufacturersCatalog.truncated() || shipTypesCatalog.truncated());

    } catch (Exception e) {
      log.error("Error loading ship data", e);
      model.addAttribute("error", "error.shipdata.load");
    }

    return "ship-data";
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
      backendApiClient.put(
          "/api/v1/manufacturers/" + id + "/visibility?hidden=" + hidden, null, Void.class);
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
      backendApiClient.put(
          "/api/v1/ship-types/" + id + "/visibility?hidden=" + hidden, null, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update ShipType visibility failed", e);
      return "redirect:/ship-data?error=UpdateShipTypeVisibilityFailed";
    }
    return "redirect:/ship-data";
  }

  /**
   * Resets the {@code fitted} flag on every ship of the squadron; irreversible, restricted to
   * ADMIN/OFFICER.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /ship-data}
   */
  @NotNull
  @PostMapping("/reset-fitted")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String resetAllFitted(RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.post("/api/v1/hangar/ships/reset-fitted", null, Void.class);
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
          backendApiClient.post("/api/v1/hangar/ships/reset-fitted", null, Void.class);
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
          backendApiClient.put(
              "/api/v1/ship-types/" + id + "/visibility?hidden=" + hidden, null, Void.class);
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
          backendApiClient.put(
              "/api/v1/manufacturers/" + id + "/visibility?hidden=" + hidden, null, Void.class);
          return ResponseEntity.noContent().build();
        });
  }
}
