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

import de.greluc.krt.profit.basetool.frontend.model.dto.BereichCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitNodeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitParentUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrganisationsleitungCreateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin page for defining the org hierarchy (epic #692, REQ-ORG-014): creating Bereiche and the
 * Organisationsleitung and wiring the parent edges that put a Staffel/SK under a Bereich and a
 * Bereich under the OL. It is the UI front for the backend {@code /api/v1/org-hierarchy} admin API
 * — leader/member seating stays on the org-chart page; this surface is purely the structure (create
 * + parent).
 *
 * <p>The page is gated to {@code hasRole('ADMIN')} (class-level), mirroring the backend. The write
 * actions are in-place AJAX twins ({@code X-Requested-With} header, JSON body) that relay to the
 * backend and let the page reload on success — an admin, low-traffic surface where a reload is the
 * simplest correct refresh (the CLAUDE.md no-reload fallback). Backend conflicts (a stale parent
 * version, a duplicate name, the OL singleton) are relayed verbatim as {@code problem+json} so the
 * shared {@code krtFetch} client shows the right toast.
 */
@Controller
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
   * The six Kartell departments (Bereichsfarben), mirroring the frozen backend {@code Department}
   * enum (epic #692, REQ-ORG-018). Used to populate the create-Bereich department picker; the
   * display labels come from the {@code department.*} message keys.
   */
  private static final List<String> DEPARTMENTS =
      List.of(
          "PROFIT", "SUB_RADAR", "RAUMUEBERLEGENHEIT", "FORSCHUNG", "MARINEKORPS", "SEARCH_RESCUE");

  private final BackendApiClient backendApiClient;

  /**
   * Renders the org-structure management page: the create-OL / create-Bereich forms and the table
   * of every org unit with its current parent. Splits the single backend read into the per-kind
   * option pools the parent pickers need (OL for a Bereich's parent, Bereiche for a Staffel/SK's
   * parent).
   *
   * @param model the view model.
   * @return the view name.
   */
  @GetMapping
  public String page(Model model) {
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
    return "admin/org-structure";
  }

  /**
   * Orders the flat node list for stable display: top-down by tier ({@link #KIND_DISPLAY_ORDER} —
   * OL, then Bereiche, then Staffeln/SKs) and case-insensitively by name within a tier. The backend
   * read returns rows in arbitrary order; sorting here also gives the per-kind parent-option pools
   * (derived by filtering this list) a stable alphabetical order.
   *
   * @param nodes the unsorted nodes; never {@code null}.
   * @return a new list ordered by tier then name.
   */
  private static List<OrgUnitNodeDto> sortForDisplay(List<OrgUnitNodeDto> nodes) {
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
    try {
      Object created = backendApiClient.post(BACKEND_BEREICHE, request, Object.class);
      // A new Bereich appears in the cached /org-units/active-all-kinds picker, so evict the shared
      // catalogue cache or that picker stays stale up to the TTL (REQ-DATA-007 eviction gate).
      backendApiClient.clearStaticDataCache();
      return ResponseEntity.ok(created);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Create Bereich (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
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
    try {
      Object created = backendApiClient.post(BACKEND_OL, request, Object.class);
      // The Organisationsleitung appears in the cached /org-units/active-all-kinds picker → evict.
      backendApiClient.clearStaticDataCache();
      return ResponseEntity.ok(created);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Create Organisationsleitung (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
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
    try {
      Object updated =
          backendApiClient.patch(
              "/api/v1/org-hierarchy/org-units/" + id + "/parent", request, Object.class);
      // Re-parenting changes the org-unit tree the cached /org-units/active-all-kinds picker
      // renders
      // (a unit can move under a different Bereich), so evict the shared catalogue cache.
      backendApiClient.clearStaticDataCache();
      return ResponseEntity.ok(updated);
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Set parent for org unit {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
