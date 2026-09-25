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
import de.greluc.krt.profit.basetool.frontend.model.dto.BereichCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitNodeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitParentUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrganisationsleitungCreateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin page for defining the org hierarchy (REQ-ORG-014): creating Bereiche and the
 * Organisationsleitung and setting parent edges, via the backend {@code /api/v1/org-hierarchy} API.
 *
 * <p>Writes are in-place AJAX relays that re-render the affected section through {@link #page} and
 * broadcast on the {@code org-structure} live-sync room (REQ-FE-010); backend conflicts are relayed
 * as {@code problem+json}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/org-structure")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminOrgStructurePageController {

  /** Backend org-unit kind discriminator: the top-of-hierarchy Organisationsleitung. */
  private static final String KIND_OL = "ORGANISATIONSLEITUNG";

  /** Backend org-unit kind discriminator: the area (Bereich) tier. */
  private static final String KIND_BEREICH = "BEREICH";

  /**
   * Top-down display order of the four kinds for the management table (OL → Bereich → Staffel/SK).
   * A kind not in this list sorts first ({@code List.indexOf} returns {@code -1}).
   */
  private static final List<String> KIND_DISPLAY_ORDER =
      List.of(KIND_OL, KIND_BEREICH, "SQUADRON", "SPECIAL_COMMAND");

  /** Backend admin list of every active org unit with its parent edge + version. */
  private static final String BACKEND_ORG_UNITS = "/api/v1/org-hierarchy/org-units";

  /** Backend create endpoint for a Bereich. */
  private static final String BACKEND_BEREICHE = "/api/v1/org-hierarchy/bereiche";

  /** Backend create endpoint for the Organisationsleitung. */
  private static final String BACKEND_OL = "/api/v1/org-hierarchy/organisationsleitung";

  /** Response type for the flat org-unit node list. */
  private static final ParameterizedTypeReference<List<OrgUnitNodeDto>> NODE_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /**
   * The six Kartell departments, mirroring the backend {@code Department} enum (REQ-ORG-026), for
   * the create-Bereich picker; labels come from the {@code department.*} message keys.
   */
  private static final List<String> DEPARTMENTS =
      List.of(
          "PROFIT", "SUB_RADAR", "RAUMUEBERLEGENHEIT", "FORSCHUNG", "MARINEKORPS", "SEARCH_RESCUE");

  private final BackendApiClient backendApiClient;

  /**
   * Renders the org-structure page: the create-OL and create-Bereich forms and the table of every
   * org unit with its parent, plus the per-kind parent option pools.
   *
   * @param fragment {@code "units"} or {@code "forms"} to render only that fragment for an in-place
   *     swap; otherwise the full page
   * @param model the view model
   * @return the view name, or the requested fragment selector
   */
  @NotNull
  @GetMapping
  public String page(@RequestParam(required = false) String fragment, Model model) {
    List<OrgUnitNodeDto> nodes = List.of();
    try {
      List<OrgUnitNodeDto> fetched = backendApiClient.get(BACKEND_ORG_UNITS, NODE_LIST_TYPE);
      if (fetched != null) {
        nodes = fetched;
      }
    } catch (Exception e) {
      log.debug("Failed to load org units for the structure page", e);
      model.addAttribute("error", "admin.orgStructure.error.load");
    }
    nodes = sortForDisplay(nodes);
    model.addAttribute("nodes", nodes);
    model.addAttribute(
        "organisationsleitungen", nodes.stream().filter(n -> KIND_OL.equals(n.kind())).toList());
    model.addAttribute(
        "bereiche", nodes.stream().filter(n -> KIND_BEREICH.equals(n.kind())).toList());
    model.addAttribute("hasOl", nodes.stream().anyMatch(n -> KIND_OL.equals(n.kind())));
    model.addAttribute("departments", DEPARTMENTS);
    if ("units".equalsIgnoreCase(fragment)) {
      return "admin/org-structure :: orgStructureUnits";
    }
    if ("forms".equalsIgnoreCase(fragment)) {
      return "admin/org-structure :: orgStructureForms";
    }
    return "admin/org-structure";
  }

  /**
   * Orders nodes by tier ({@link #KIND_DISPLAY_ORDER}: OL, Bereiche, then Staffeln/SKs) and
   * case-insensitively by name within a tier.
   *
   * @param nodes the unsorted nodes; never {@code null}
   * @return a new list ordered by tier then name
   */
  private static List<OrgUnitNodeDto> sortForDisplay(@NotNull List<OrgUnitNodeDto> nodes) {
    return nodes.stream()
        .sorted(
            Comparator.comparingInt((OrgUnitNodeDto n) -> KIND_DISPLAY_ORDER.indexOf(n.kind()))
                .thenComparing(
                    n -> n.name() == null ? "" : n.name(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Creates a Bereich (AJAX relay), optionally already wired under the Organisationsleitung.
   *
   * @param request the create payload.
   * @return the created Bereich, or the relayed backend error.
   */
  @ResponseBody
  @PostMapping(value = "/bereiche", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createBereich(@RequestBody BereichCreateRequest request) {
    return relay(
        log,
        "create Bereich (ajax)",
        () -> {
          Object created = backendApiClient.post(BACKEND_BEREICHE, request, Object.class);
          backendApiClient.evict(CacheDomain.ORG_UNIT);
          return ResponseEntity.ok(created);
        });
  }

  /**
   * Creates the Organisationsleitung (AJAX relay). The backend rejects a second one with 409.
   *
   * @param request the create payload.
   * @return the created OL, or the relayed backend error.
   */
  @ResponseBody
  @PostMapping(value = "/organisationsleitung", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createOrganisationsleitung(
      @RequestBody OrganisationsleitungCreateRequest request) {
    return relay(
        log,
        "create Organisationsleitung (ajax)",
        () -> {
          Object created = backendApiClient.post(BACKEND_OL, request, Object.class);
          backendApiClient.evict(CacheDomain.ORG_UNIT);
          return ResponseEntity.ok(created);
        });
  }

  /**
   * Sets (or clears) an org unit's parent (AJAX relay), forwarding the optimistic-lock version. The
   * backend validates the kind pairing and the version.
   *
   * @param id the child org unit id.
   * @param request the new parent id (or {@code null} to detach) plus the child's version.
   * @return the child's bumped identity/version, or the relayed backend error.
   */
  @ResponseBody
  @PatchMapping(value = "/org-units/{id}/parent", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> setParent(
      @PathVariable @NotNull UUID id, @RequestBody OrgUnitParentUpdateRequest request) {
    return relay(
        log,
        "set parent for org unit " + id + " (ajax)",
        () -> {
          Object updated =
              backendApiClient.patch(
                  "/api/v1/org-hierarchy/org-units/" + id + "/parent", request, Object.class);
          backendApiClient.evict(CacheDomain.ORG_UNIT);
          return ResponseEntity.ok(updated);
        });
  }
}
