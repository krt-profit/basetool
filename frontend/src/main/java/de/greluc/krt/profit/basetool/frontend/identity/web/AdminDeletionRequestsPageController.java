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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.DecideDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

  /** Reads and decides the erasure requests on the backend. */
  private final IdentityBackendClient identityClient;

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
      model.addAttribute("requests", identityClient.deletionRequests());
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
    model.addAttribute("requests", identityClient.deletionRequests());
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
    DecideDeletionRequestRequest body =
        new DecideDeletionRequestRequest(false, text, version(request.get("version")));
    return relay(
        log,
        "declining deletion request " + id,
        () -> {
          identityClient.declineDeletionRequest(id, body);
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
    DecideDeletionRequestRequest body =
        new DecideDeletionRequestRequest(
            Boolean.TRUE.equals(request.get("grantHistoryErasure")),
            null,
            version(request.get("version")));
    return relay(
        log,
        "executing deletion request " + id,
        () -> {
          identityClient.executeDeletionRequest(id, body);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Reads the request row's optimistic-lock version from the browser payload.
   *
   * @param value the payload's {@code version}, a JSON number or {@code null}
   * @return the version, or {@code null} when the payload carried no number
   */
  @Nullable
  private static Long version(@Nullable Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }
}
