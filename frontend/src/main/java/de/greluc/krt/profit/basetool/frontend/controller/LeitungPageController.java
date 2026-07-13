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

import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungViewDto;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring MVC controller for the delegated Leitung page ({@code /organisation/leitung}, epic #800
 * REQ-ROLE-004). The page lists the org units the caller may appoint into (resolved server-side by
 * the backend, which returns only manageable units), and lets a leader appoint / change / remove
 * the ranks their tier delegates to them: a Bereichsleiter manages their Bereich's Koordinatoren /
 * Operatoren and their child Staffeln's / SKs' leads, a Staffelleiter manages their Staffel's
 * Kommandoleiter / Stellvertreter / Ensigns and Kommandogruppen, the OL appoints Bereichsleiter,
 * and admin does everything.
 *
 * <p>The page and its write proxies are gated to {@code ADMIN} / {@code OFFICER} only ({@link
 * Roles#ADMIN_OR_OFFICER}): every functional leader carries the operative {@code OFFICER} grant
 * (see {@code ROLES_AND_PERMISSIONS.md}), so no delegated leader is locked out, while a
 * capability-only Logistician / Mission-Manager (no appointment reach, empty page) is kept out.
 * Per-unit authorisation is still enforced by the backend appointment endpoints, which the write
 * methods below proxy verbatim, relaying any RFC-7807 failure as its original status + a slim
 * {@code {code, detail}} body so the page JS can toast the backend's localised message (and
 * recognise {@code OPTIMISTIC_LOCK} to prompt a reload). On success the page re-swaps the {@code
 * leitungSections} fragment rather than patching individual rows, so derived state (rank chips,
 * group tiles, capability buttons) never desyncs.
 */
@Controller
@RequestMapping("/organisation/leitung")
@RequiredArgsConstructor
@Slf4j
public class LeitungPageController {

  private final BackendApiClient backendApiClient;

  /**
   * Renders the Leitung page (or just its {@code leitungSections} fragment for an in-place
   * re-swap). Loads the caller's manageable view; the appointment pickers are server-side
   * searchable comboboxes (remote-users, #1193) that fetch matches from {@code /users/search} on
   * demand, so the roster is no longer preloaded.
   *
   * @param fragment when {@code "leitungSections"}, only the sections fragment is rendered for an
   *     AJAX swap; otherwise the full page.
   * @param model the Thymeleaf model, populated with {@code leitung}.
   * @return the view name, or its {@code leitungSections} selector for the fragment path.
   */
  @GetMapping
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public String leitung(@RequestParam(required = false) String fragment, Model model) {
    try {
      model.addAttribute(
          "leitung", backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class));
    } catch (BackendServiceException e) {
      log.debug("Failed to load Leitung view", e);
      model.addAttribute("error", "leitung.error.load");
    } catch (Exception e) {
      log.error("Failed to load Leitung view", e);
      model.addAttribute("error", "leitung.error.load");
    }
    if ("leitungSections".equals(fragment)) {
      return "organisation/leitung :: leitungSections";
    }
    return "organisation/leitung";
  }

  /**
   * Assigns (or changes) a member's squadron leadership rank.
   *
   * @param squadronId the Staffel.
   * @param userId the member.
   * @param body the {@code {role, kommandoGroupId?, version}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PutMapping("/squadrons/{squadronId}/ranks/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> assignSquadronRank(
      @PathVariable @NotNull UUID squadronId,
      @PathVariable @NotNull UUID userId,
      @RequestBody Map<String, Object> body) {
    return proxy(
        "Assign squadron rank failed",
        () ->
            backendApiClient.put(
                "/api/v1/squadrons/" + squadronId + "/ranks/" + userId, body, Object.class));
  }

  /**
   * Clears a member's squadron leadership rank back to a plain member.
   *
   * @param squadronId the Staffel.
   * @param userId the member.
   * @param version the optimistic-lock version the client last read.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/squadrons/{squadronId}/ranks/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeSquadronRank(
      @PathVariable @NotNull UUID squadronId,
      @PathVariable @NotNull UUID userId,
      @RequestParam("version") long version) {
    return proxy(
        "Remove squadron rank failed",
        () ->
            backendApiClient.delete(
                "/api/v1/squadrons/" + squadronId + "/ranks/" + userId + "?version=" + version,
                Object.class));
  }

  /**
   * Creates a Kommandogruppe in the Staffel.
   *
   * @param squadronId the Staffel.
   * @param body the {@code {name}} payload.
   * @return 200 with the created group, or the backend error status + body.
   */
  @PostMapping("/squadrons/{squadronId}/kommando-groups/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> createKommandoGroup(
      @PathVariable @NotNull UUID squadronId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Create Kommandogruppe failed",
        () ->
            backendApiClient.post(
                "/api/v1/squadrons/" + squadronId + "/kommando-groups", body, Object.class));
  }

  /**
   * Renames / reorders a Kommandogruppe.
   *
   * @param groupId the group.
   * @param body the {@code {name, sortIndex, version}} payload.
   * @return 200 with the updated group, or the backend error status + body.
   */
  @PutMapping("/kommando-groups/{groupId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> updateKommandoGroup(
      @PathVariable @NotNull UUID groupId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Update Kommandogruppe failed",
        () -> backendApiClient.put("/api/v1/kommando-groups/" + groupId, body, Object.class));
  }

  /**
   * Deletes a Kommandogruppe.
   *
   * @param groupId the group.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/kommando-groups/{groupId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> deleteKommandoGroup(@PathVariable @NotNull UUID groupId) {
    return proxy(
        "Delete Kommandogruppe failed",
        () -> backendApiClient.delete("/api/v1/kommando-groups/" + groupId, Object.class));
  }

  /**
   * Adds a Bereich leadership member (Bereichsleiter / Koordinator / Operator).
   *
   * @param bereichId the Bereich.
   * @param body the {@code {userId, role}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PostMapping("/bereiche/{bereichId}/members/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> addBereichLeader(
      @PathVariable @NotNull UUID bereichId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Add Bereich leader failed",
        () ->
            backendApiClient.post(
                "/api/v1/org-hierarchy/bereiche/" + bereichId + "/members", body, Object.class));
  }

  /**
   * Removes a Bereich leadership member.
   *
   * @param bereichId the Bereich.
   * @param userId the member.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/bereiche/{bereichId}/members/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeBereichLeader(
      @PathVariable @NotNull UUID bereichId, @PathVariable @NotNull UUID userId) {
    return proxy(
        "Remove Bereich leader failed",
        () ->
            backendApiClient.delete(
                "/api/v1/org-hierarchy/bereiche/" + bereichId + "/members/" + userId,
                Object.class));
  }

  /**
   * Adds an Organisationsleitung member (admin-only at the backend).
   *
   * @param olId the OL.
   * @param body the {@code {userId}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PostMapping("/organisationsleitung/{olId}/members/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> addOlMember(
      @PathVariable @NotNull UUID olId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Add OL member failed",
        () ->
            backendApiClient.post(
                "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/members",
                body,
                Object.class));
  }

  /**
   * Removes an Organisationsleitung member.
   *
   * @param olId the OL.
   * @param userId the member.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/organisationsleitung/{olId}/members/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeOlMember(
      @PathVariable @NotNull UUID olId, @PathVariable @NotNull UUID userId) {
    return proxy(
        "Remove OL member failed",
        () ->
            backendApiClient.delete(
                "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/members/" + userId,
                Object.class));
  }

  /**
   * Toggles the SK-Leiter flag on a Spezialkommando member.
   *
   * @param skId the Spezialkommando.
   * @param userId the member.
   * @param body the {@code {isLead, version}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PatchMapping("/special-commands/{skId}/members/{userId}/lead/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> toggleSkLead(
      @PathVariable @NotNull UUID skId,
      @PathVariable @NotNull UUID userId,
      @RequestBody Map<String, Object> body) {
    return proxy(
        "Toggle SK lead failed",
        () ->
            backendApiClient.patch(
                "/api/v1/special-commands/" + skId + "/members/" + userId + "/lead",
                body,
                Object.class));
  }

  /**
   * Runs a backend write call, returning its result as 200 and relaying any backend RFC-7807
   * failure as its original status + {@code {code, detail}} body (or a 500 for an unexpected
   * error).
   *
   * @param logMessage the log prefix for a failure.
   * @param call the backend call to run.
   * @return the proxied response.
   */
  private ResponseEntity<Object> proxy(String logMessage, BackendCall call) {
    try {
      Object result = call.run();
      return ResponseEntity.ok(result == null ? Map.of() : result);
    } catch (BackendServiceException e) {
      log.warn("{}: status={}, code={}", logMessage, e.getStatusCode(), e.getProblemCode());
      Map<String, Object> payload = new HashMap<>();
      payload.put("code", e.getProblemCode());
      payload.put("detail", e.getProblemDetail());
      int status = e.getStatusCode() > 0 ? e.getStatusCode() : 500;
      return ResponseEntity.status(status).body(payload);
    } catch (Exception e) {
      log.error(logMessage, e);
      Map<String, Object> payload = new HashMap<>();
      payload.put("code", "INTERNAL_ERROR");
      return ResponseEntity.status(500).body(payload);
    }
  }

  /** A backend write call that may throw a {@link BackendServiceException}. */
  @FunctionalInterface
  private interface BackendCall {
    /**
     * Runs the backend call.
     *
     * @return the backend result, possibly {@code null}.
     */
    Object run();
  }
}
