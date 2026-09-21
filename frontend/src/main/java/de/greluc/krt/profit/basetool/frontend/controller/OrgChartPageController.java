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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring MVC controller for the Profit-Bereich org chart page ({@code /org-chart}). The page itself
 * is open to every authenticated user (read-only view); the inline editor's write operations are
 * proxied through the AJAX endpoints below, each hard-gated to ADMIN — the class is intentionally
 * NOT class-level {@code @PreAuthorize("hasRole('ADMIN')")} so members can still view the chart.
 *
 * <p>The AJAX proxies forward the JSON body verbatim to the backend and, on a backend RFC-7807
 * failure, relay the HTTP status plus a slim {@code {code, detail}} body so the page JS can show
 * the backend's localized message in a toast (and recognise the {@code OPTIMISTIC_LOCK} code to
 * prompt a reload).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/org-chart")
@RequiredArgsConstructor
@Slf4j
public class OrgChartPageController {

  private final BackendApiClient backendApiClient;

  /**
   * Renders the org-chart page (read-only for everyone; admins edit free-text holders inline).
   * Since epic #800 (REQ-ROLE-006) account-linked seats are a mirror of the functional ranks
   * appointed under Organisation → Leitung, so the chart editor no longer offers an account picker
   * and the page needs no user-lookup preload.
   *
   * <p>When {@code fragment=chartBody} the controller returns only the {@code chartBody} fragment
   * so the page re-renders the whole tree in place after an edit instead of a full reload (epic
   * #571 / REQ-FE-005). The chart is a flat, CSS-connected pre-order tree whose add affordances and
   * vacant/filled transitions are derived aggregate state, so a per-node DOM patch would desync the
   * "+" buttons and the ARIA roving-tabindex order — a full fragment swap re-stamps every {@code
   * data-version} and rebuilds the tree atomically.
   *
   * @param fragment when {@code "chartBody"}, only the chart-body fragment is rendered for an
   *     in-place AJAX swap; otherwise the full page.
   * @param model Thymeleaf model populated with {@code orgChart}.
   * @return the {@code org-chart} view name, or its {@code chartBody} selector for the fragment
   *     path.
   */
  @NotNull
  @GetMapping
  public String orgChart(@RequestParam(required = false) String fragment, Model model) {
    try {
      model.addAttribute("orgChart", backendApiClient.get("/api/v1/org-chart", OrgChartDto.class));
    } catch (Exception e) {
      log.error("Failed to load org chart", e);
      model.addAttribute("error", "error.orgChart.load");
    }
    if ("chartBody".equals(fragment)) {
      return "org-chart :: chartBody";
    }
    return "org-chart";
  }

  /**
   * Creates a new org-chart position. ADMIN-only. Relays backend validation failures (scope,
   * parent, cardinality, uniqueness) as their original status + {@code {code, detail}} body.
   *
   * @param body the create payload ({@code positionType}, {@code orgUnitId}, {@code userId}, {@code
   *     parentId}, {@code sortIndex}).
   * @return 200 with the created position on success, or the backend error status + body.
   */
  @PostMapping("/positions/ajax")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> createPosition(@RequestBody Map<String, Object> body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post("/api/v1/org-chart/positions", body, Object.class));
    } catch (BackendServiceException e) {
      return relayError("Create org-chart position failed", e);
    } catch (Exception e) {
      return unexpectedError("Create org-chart position failed", e);
    }
  }

  /**
   * Reassigns or reorders an existing position. ADMIN-only.
   *
   * @param id the position id.
   * @param body the edit payload ({@code userId}, {@code sortIndex}, {@code version}).
   * @return 200 with the updated position on success, or the backend error status + body.
   */
  @PutMapping("/positions/{id}/ajax")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> updatePosition(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.put("/api/v1/org-chart/positions/" + id, body, Object.class));
    } catch (BackendServiceException e) {
      return relayError("Update org-chart position failed", e);
    } catch (Exception e) {
      return unexpectedError("Update org-chart position failed", e);
    }
  }

  /**
   * Vacates a Kommando's Kommandoleiter — clears the holder while keeping the Kommando, its Stv.
   * and its Ensigns. Distinct from {@link #deletePosition}, which removes the whole Kommando. The
   * optimistic-lock version is relayed to the backend as a query parameter. ADMIN-only.
   *
   * @param id the Kommando ({@code COMMAND_LEAD}) position id.
   * @param version the optimistic-lock version the client last saw.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/positions/{id}/leader/ajax")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> vacateLeader(
      @PathVariable @NotNull UUID id, @RequestParam("version") long version) {
    try {
      backendApiClient.delete(
          "/api/v1/org-chart/positions/" + id + "/leader?version=" + version, Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      return relayError("Vacate org-chart Kommandoleiter failed", e);
    } catch (Exception e) {
      return unexpectedError("Vacate org-chart Kommandoleiter failed", e);
    }
  }

  /**
   * Removes a position (cascading a Kommandoleiter's Stv. + Ensigns). ADMIN-only.
   *
   * @param id the position id.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/positions/{id}/ajax")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> deletePosition(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete("/api/v1/org-chart/positions/" + id, Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      return relayError("Delete org-chart position failed", e);
    } catch (Exception e) {
      return unexpectedError("Delete org-chart position failed", e);
    }
  }

  private ResponseEntity<Object> relayError(String logMessage, @NotNull BackendServiceException e) {
    log.warn("{}: status={}, code={}", logMessage, e.getStatusCode(), e.getProblemCode());
    Map<String, Object> payload = new HashMap<>();
    payload.put("code", e.getProblemCode());
    payload.put("detail", e.getProblemDetail());
    int status = e.getStatusCode() > 0 ? e.getStatusCode() : 500;
    return ResponseEntity.status(status).body(payload);
  }

  private ResponseEntity<Object> unexpectedError(String logMessage, Exception e) {
    log.error(logMessage, e);
    Map<String, Object> payload = new HashMap<>();
    payload.put("code", "INTERNAL_ERROR");
    return ResponseEntity.status(500).body(payload);
  }
}
