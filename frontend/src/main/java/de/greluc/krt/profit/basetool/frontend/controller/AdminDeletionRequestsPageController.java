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
import de.greluc.krt.profit.basetool.frontend.model.dto.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin queue for members' Art. 17 erasure requests ({@code /admin/deletion-requests},
 * REQ-SEC-061), ordered oldest first because each request has a one-month response deadline.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/deletion-requests")
@RequiredArgsConstructor
@Slf4j
public class AdminDeletionRequestsPageController {

  private static final ParameterizedTypeReference<List<AdminDeletionRequestDto>> REQUEST_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the queue; a backend failure renders an error banner with an empty list.
   *
   * @param model the view model
   * @return the view name
   */
  @NotNull
  @GetMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String page(Model model) {
    try {
      model.addAttribute(
          "requests", backendApiClient.get("/api/v1/admin/deletion-requests", REQUEST_LIST_TYPE));
    } catch (Exception e) {
      log.error("Loading the deletion-request queue failed", e);
      model.addAttribute("requests", List.of());
      model.addAttribute("error", "admin.deletionRequests.error.load");
    }
    return "admin/deletion-requests";
  }

  /**
   * Re-renders the queue table as a fragment for the in-place refresh (REQ-FE-001). A backend
   * failure is re-thrown so the client keeps the current table and shows an error toast.
   *
   * @param model the view model
   * @return the fragment view name
   */
  @NotNull
  @GetMapping(params = "fragment=rows")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String rows(@NotNull Model model) {
    model.addAttribute(
        "requests", backendApiClient.get("/api/v1/admin/deletion-requests", REQUEST_LIST_TYPE));
    return "admin/deletion-requests :: rows";
  }

  /**
   * Refuses a request. A non-blank reason is mandatory, as the requester must be told it.
   *
   * @param id the request to refuse
   * @param request the client payload; {@code note} must be non-blank
   * @return {@code 200} on success, {@code 400} without a reason, else the relayed backend status
   */
  @ResponseBody
  @PostMapping(value = "/{id}/decline", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> decline(
      @PathVariable UUID id, @NotNull @RequestBody Map<String, Object> request) {
    Object note = request.get("note");
    if (!(note instanceof String text) || text.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("grantHistoryErasure", false);
    body.put("note", text);
    body.put("version", request.get("version"));
    return relay(
        log,
        "declining deletion request " + id,
        () -> {
          backendApiClient.post(
              "/api/v1/admin/deletion-requests/" + id + "/decline", body, Object.class);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Carries a request out, irreversibly deleting the local user and the Keycloak account; with
   * {@code grantHistoryErasure} the member's handle snapshots are anonymised first. The flag comes
   * from the admin's payload, not from the request row.
   *
   * @param id the request to carry out
   * @param request the client payload; only {@code grantHistoryErasure} is read
   * @return {@code 200} on success, else the relayed backend status
   */
  @ResponseBody
  @PostMapping(value = "/{id}/execute", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> execute(
      @PathVariable UUID id, @RequestBody Map<String, Object> request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("grantHistoryErasure", Boolean.TRUE.equals(request.get("grantHistoryErasure")));
    body.put("version", request.get("version"));
    return relay(
        log,
        "executing deletion request " + id,
        () -> {
          backendApiClient.post(
              "/api/v1/admin/deletion-requests/" + id + "/execute", body, Object.class);
          return ResponseEntity.ok().build();
        });
  }
}
