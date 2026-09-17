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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Relays the member's own Art. 17 erasure request to the backend (REQ-SEC-061).
 *
 * <p>AJAX-only, because both actions are offered from the profile page's "Access &amp; Security"
 * card and both update it in place (REQ-FE-001): there is no page to redirect to that would say
 * anything the card does not already show. There is deliberately no non-AJAX twin — unlike the
 * profile's description and payout forms, this is not a form whose value a script-disabled browser
 * still needs to be able to save; it is a request behind a confirmation dialog, and a dialog needs
 * script anyway.
 *
 * <p>No user id is relayed and none is accepted. The backend derives the subject from the token, so
 * this proxy cannot be talked into acting for somebody else — the property that makes the surface
 * safe for every member rather than needing a scope check of its own.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/profile/deletion-request")
@RequiredArgsConstructor
@Slf4j
public class DeletionRequestProxyController {

  private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Re-renders the profile page's deletion card as a standalone fragment.
   *
   * <p>This is the other half of the live update: the two write endpoints below return the API's
   * answer, and the page then asks for this fragment and swaps it in place (REQ-FE-001). Rendering
   * the card server-side rather than rebuilding it in JavaScript keeps the three states — no
   * request, pending, declined-with-reason — decided in exactly one place, so the swapped card can
   * never disagree with the freshly loaded page.
   *
   * @param model the view model the fragment reads {@code deletionRequest} and {@code
   *     deletionRequestUnavailable} from
   * @return the fragment view name
   */
  @GetMapping(params = "fragment=card")
  @PreAuthorize("isAuthenticated()")
  public String card(Model model) {
    Map<String, Object> deletionRequest = null;
    boolean unavailable = false;
    try {
      deletionRequest =
          backendApiClient.get("/api/v1/users/me/deletion-request", STRING_OBJECT_MAP_TYPE);
    } catch (Exception e) {
      log.debug("Could not load the member's deletion request for the card fragment", e);
      unavailable = true;
    }
    model.addAttribute("deletionRequest", deletionRequest);
    // Reported rather than swallowed: the swap happens after a write that already succeeded, and
    // rendering the no-request state here would tell the member the opposite of what just
    // happened. See the same flag in ProfileController.
    model.addAttribute("deletionRequestUnavailable", unavailable);
    return "fragments/profile-deletion-card :: card";
  }

  /**
   * Raises the caller's erasure request.
   *
   * @param request the client payload; only {@code eraseHistory} is read, and it is coerced rather
   *     than validated, because a missing or malformed flag means "did not ask for the extra
   *     erasure" and that is the safe reading
   * @return {@code 200} with the created request, or the relayed backend status
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> request(@RequestBody Map<String, Object> request) {
    Object eraseHistory = request.get("eraseHistory");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("eraseHistory", Boolean.TRUE.equals(eraseHistory));
    try {
      Object created =
          backendApiClient.post("/api/v1/users/me/deletion-request", body, Object.class);
      return ResponseEntity.ok(created);
    } catch (BackendServiceException e) {
      log.debug("Raising an account-deletion request (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      // No id and no handle in the log line: the fact that THIS member asked to be erased is
      // itself personal data, and an error log is not where it belongs.
      log.error("Raising an account-deletion request (ajax) failed unexpectedly", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Withdraws the caller's pending erasure request. Idempotent.
   *
   * @return {@code 204}, or the relayed backend status
   */
  @ResponseBody
  @DeleteMapping(headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> withdraw() {
    try {
      backendApiClient.delete("/api/v1/users/me/deletion-request", Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.debug("Withdrawing an account-deletion request (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Withdrawing an account-deletion request (ajax) failed unexpectedly", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
