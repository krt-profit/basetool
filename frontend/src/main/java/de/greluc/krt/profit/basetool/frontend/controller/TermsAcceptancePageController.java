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

import de.greluc.krt.profit.basetool.frontend.config.TermsAcceptanceGateFilter;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Renders the Terms-of-Use consent gate and records the answer (REQ-SEC-028).
 *
 * <p>The backend decides which version is in force and stamps it on acceptance; this controller
 * only relays the caller's token.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class TermsAcceptancePageController {

  /** Backend endpoint reporting whether the caller has accepted the version in force. */
  private static final String TERMS_STATUS_URI = "/api/v1/terms/status";

  /** Backend endpoint recording the caller's consent. */
  private static final String TERMS_ACCEPTANCE_URI = "/api/v1/terms/acceptance";

  /** Backend endpoint serving the wording the member is being asked to accept (ADR-0138). */
  private static final String TERMS_DOCUMENT_URI = "/api/v1/terms/document";

  private final BackendApiClient backendApiClient;

  /**
   * Renders the consent page, or redirects a user who has already consented into the tool.
   *
   * @param model receives the wording under {@code terms}
   * @return the {@code terms-accept} view, or a redirect to the start page when consent is already
   *     on record
   */
  @NotNull
  @GetMapping("/terms/accept")
  public String showAcceptancePage(Model model) {
    try {
      TermsStatusDto status = backendApiClient.get(TERMS_STATUS_URI, TermsStatusDto.class);
      if (status != null && status.accepted()) {
        return "redirect:/";
      }
    } catch (BackendServiceException e) {
      log.debug("Terms status could not be read; rendering the consent page anyway.", e);
    }
    model.addAttribute("terms", backendApiClient.get(TERMS_DOCUMENT_URI, TermsDocumentDto.class));
    return "terms-accept";
  }

  /**
   * Records the caller's consent; the page navigates itself afterwards.
   *
   * @param request the current request, whose session caches the gate verdict to be cleared
   * @return {@code 204} once consent is recorded, {@code 502} if the backend could not record it
   */
  @PostMapping("/terms/accept")
  @ResponseBody
  public @NotNull ResponseEntity<Void> recordAcceptance(@NotNull HttpServletRequest request) {
    try {
      backendApiClient.post(TERMS_ACCEPTANCE_URI, null, Void.class);
      TermsAcceptanceGateFilter.clearCachedVerdict(request);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.warn("Terms acceptance could not be recorded in the backend.", e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
  }
}
