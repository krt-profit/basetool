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
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
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
 * AJAX-only relay of the member's own Art. 17 erasure request (REQ-SEC-061). No user id is
 * accepted; the backend derives the subject from the token.
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
   * Re-renders the profile page's deletion card as a fragment for the in-place swap after a write
   * (REQ-FE-001).
   *
   * @param model the view model the fragment reads {@code deletionRequest} and {@code
   *     deletionRequestUnavailable} from
   * @return the fragment view name
   */
  @NotNull
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
    model.addAttribute("deletionRequestUnavailable", unavailable);
    return "fragments/profile-deletion-card :: card";
  }

  /**
   * Raises the caller's erasure request.
   *
   * @param request the client payload; only {@code eraseHistory} is read, a missing or malformed
   *     value counting as {@code false}
   * @return {@code 200} with the created request, or the relayed backend status
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> request(@NotNull @RequestBody Map<String, Object> request) {
    Object eraseHistory = request.get("eraseHistory");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("eraseHistory", Boolean.TRUE.equals(eraseHistory));
    return relay(
        log,
        "raising an account-deletion request (ajax)",
        () -> {
          Object created =
              backendApiClient.post("/api/v1/users/me/deletion-request", body, Object.class);
          return ResponseEntity.ok(created);
        });
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
    return relay(
        log,
        "withdrawing an account-deletion request (ajax)",
        () -> {
          backendApiClient.delete("/api/v1/users/me/deletion-request", Void.class);
          return ResponseEntity.noContent().build();
        });
  }
}
