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
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The public Terms-of-Use page ({@code /terms}), rendered anonymously from the wording the backend
 * serves (ADR-0138).
 */
@Slf4j
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
public class TermsController {

  /**
   * Reads the wording in force through the one bearer-less call the frontend makes: {@code GET
   * /api/v1/terms/document} is one of the four backend paths REQ-SEC-052 serves without a token,
   * because a document everyone must be able to read before agreeing to anything cannot require
   * having agreed (ADR-0138). Every other call this module makes carries the caller's bearer.
   */
  private final BackendApiClient backendApiClient;

  /**
   * Renders the wording in force, or the "temporarily unavailable" notice when the backend read
   * fails; the failure is logged at DEBUG only.
   *
   * @param model receives the document under {@code terms}, or nothing when it could not be read
   * @return the {@code terms} view name
   */
  @NotNull
  @GetMapping("/terms")
  public String showTerms(Model model) {
    try {
      model.addAttribute("terms", backendApiClient.getTermsDocumentAnonymously());
    } catch (BackendServiceException e) {
      log.debug("Terms document unavailable; rendering the notice instead", e);
      model.addAttribute("terms", null);
    }
    return "terms";
  }
}
