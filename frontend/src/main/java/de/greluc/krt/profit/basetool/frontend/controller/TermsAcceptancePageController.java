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

import de.greluc.krt.profit.basetool.frontend.model.dto.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Renders the Terms-of-Use consent gate and records the answer (REQ-SEC-028).
 *
 * <p>A user who has not accepted the wording currently in force is redirected here by {@code
 * TermsAcceptanceGateFilter} from every other page. This controller and the logout endpoint are
 * exempt from that redirect — otherwise the gate would loop and there would be no way through it —
 * as are the imprint, privacy policy and the public {@code /terms} page, because a person cannot be
 * asked to agree to something they are prevented from reading.
 *
 * <p>The backend is the authority: this controller neither knows nor decides which version is in
 * force, it relays the caller's own token to {@code /api/v1/terms/acceptance} and lets the backend
 * stamp the version. A frontend that named the version could record consent to wording the user was
 * never shown.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class TermsAcceptancePageController {

  /** Backend endpoint reporting whether the caller has accepted the version in force. */
  private static final String TERMS_STATUS_URI = "/api/v1/terms/status";

  /** Backend endpoint recording the caller's consent. */
  private static final String TERMS_ACCEPTANCE_URI = "/api/v1/terms/acceptance";

  private final BackendApiClient backendApiClient;

  /**
   * Renders the consent page, or sends an already-consenting user into the tool.
   *
   * <p>The redirect matters for the person who accepts in one tab and then reloads a second tab
   * still showing the gate: without it they would be asked to agree to something they have already
   * agreed to, and the page would look broken.
   *
   * @return the {@code terms-accept} view, or a redirect to the start page when consent is already
   *     on record
   */
  @GetMapping("/terms/accept")
  public String showAcceptancePage() {
    try {
      TermsStatusDto status = backendApiClient.get(TERMS_STATUS_URI, TermsStatusDto.class);
      if (status != null && status.accepted()) {
        return "redirect:/";
      }
    } catch (BackendServiceException e) {
      // Show the page rather than a error screen: the gate's job is to obtain consent, and a
      // backend hiccup on the status read must not make consent impossible to give. A stale "not
      // accepted" costs the user one extra click; a hard failure costs them the tool.
      log.debug("Terms status could not be read; rendering the consent page anyway.", e);
    }
    return "terms-accept";
  }

  /**
   * Records the caller's consent and reports success to the page's {@code krtFetch} call, which
   * then navigates into the tool.
   *
   * <p>Answers {@code 204} rather than a redirect because the caller is an AJAX write: the page
   * decides where to go next, so the browser makes one navigation instead of following a redirect
   * inside an XHR and rendering the start page into a fragment.
   *
   * @return {@code 204} once consent is recorded, {@code 502} if the backend could not record it
   */
  @PostMapping("/terms/accept")
  @ResponseBody
  public @NotNull ResponseEntity<Void> recordAcceptance() {
    try {
      backendApiClient.post(TERMS_ACCEPTANCE_URI, null, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      // Logged at WARN, not DEBUG: unlike the status read this is the user actively trying to get
      // through the gate, and a failure here locks them out of the whole tool until it is fixed.
      log.warn("Terms acceptance could not be recorded in the backend.", e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
  }
}
