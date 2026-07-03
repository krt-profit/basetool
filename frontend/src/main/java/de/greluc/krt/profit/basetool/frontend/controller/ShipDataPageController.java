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

import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.form.ManufacturerForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ShipTypeForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
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
 * Spring MVC controller for the ship-data admin page ({@code /ship-data}).
 *
 * <p>Renders the manufacturer + ship-type catalogs side by side (both include hidden entries so
 * admins can un-hide), with PUT actions to toggle visibility on individual entries and a global
 * reset that clears the {@code fitted} flag on every ship in the squadron. The reset is a
 * destructive bulk op gated to ADMIN/OFFICER.
 */
@Controller
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
   * Loads the manufacturer and ship-type catalogs (size=1000, including hidden) and seeds empty
   * forms when not already in the model. Both lists are sorted case-insensitively by name. A
   * backend failure leaves an error key in the model rather than blanking the page.
   *
   * @param model Thymeleaf model populated with both forms, both lists and the optional error key
   * @return the {@code ship-data} view name
   */
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
      PageResponse<ManufacturerDto> manufacturersPage =
          backendApiClient.get(
              "/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true",
              MANUFACTURER_PAGE_TYPE);

      List<ManufacturerDto> manufacturers = new ArrayList<>();
      if (manufacturersPage != null && manufacturersPage.content() != null) {
        manufacturers = new ArrayList<>(manufacturersPage.content());
        manufacturers.sort(
            Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));
      }
      model.addAttribute("manufacturers", manufacturers);

      PageResponse<ShipTypeDto> shipTypesPage =
          backendApiClient.get(
              "/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true", SHIP_TYPE_PAGE_TYPE);

      List<ShipTypeDto> shipTypes = new ArrayList<>();
      if (shipTypesPage != null && shipTypesPage.content() != null) {
        shipTypes = new ArrayList<>(shipTypesPage.content());
        shipTypes.sort(Comparator.comparing(ShipTypeDto::name, String.CASE_INSENSITIVE_ORDER));
      }
      model.addAttribute("shipTypes", shipTypes);

    } catch (Exception e) {
      log.error("Error loading ship data", e);
      model.addAttribute("error", "error.shipdata.load");
    }

    return "ship-data";
  }

  /**
   * Toggles a manufacturer's hidden flag via the backend visibility endpoint. Failure redirects
   * with an error query param so the page renders an explicit "update failed" banner instead of
   * silently keeping the old state.
   *
   * @param id manufacturer id
   * @param hidden desired new flag value
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /ship-data} (optionally with {@code ?error=...})
   */
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
   * Squadron-wide bulk reset of the {@code fitted} flag on every ship.
   *
   * <p>The flag tracks whether a ship is currently outfitted for a mission; admins reset it after a
   * major event (e.g. patch wipe). Restricted to ADMIN/OFFICER because the operation cannot be
   * undone — every fleet member would otherwise have to manually re-mark their ships.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /ship-data}
   */
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

  // ----------------------------------------------------- AJAX twins (epic #571 / REQ-FE-001)

  /**
   * Header-gated AJAX twin of {@link #resetAllFitted}: clears the {@code fitted} flag on every ship
   * and returns {@code 204} so {@code ship-data.html} surfaces a toast and closes the confirm modal
   * without the classic POST→redirect reload (the reset has no per-row effect on this page). The
   * classic handler stays the no-JS fallback.
   *
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/reset-fitted", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @ResponseBody
  public ResponseEntity<Object> resetAllFittedAjax() {
    try {
      backendApiClient.post("/api/v1/hangar/ships/reset-fitted", null, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.error("Reset all fitted failed (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Reset all fitted failed (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #toggleShipTypeVisibility}: flips a ship type's hidden flag
   * and returns {@code 204} so the page toggles just that row (button label, secondary style,
   * dimmed opacity) in place instead of reloading. The classic handler stays the no-JS fallback.
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
    try {
      backendApiClient.put(
          "/api/v1/ship-types/" + id + "/visibility?hidden=" + hidden, null, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.error("Update ShipType visibility failed (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update ShipType visibility failed (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #toggleManufacturerVisibility}: flips a manufacturer's hidden
   * flag and returns {@code 204} so the page toggles just that row in place instead of reloading.
   * The classic handler stays the no-JS fallback.
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
    try {
      backendApiClient.put(
          "/api/v1/manufacturers/" + id + "/visibility?hidden=" + hidden, null, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.error("Update Manufacturer visibility failed (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update Manufacturer visibility failed (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
