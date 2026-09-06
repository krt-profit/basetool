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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The public Terms-of-Use page ({@code /terms}).
 *
 * <p>No longer a static template: the wording moved to the backend so that this page, the consent
 * gate and the Android app all render one source (ADR-0138). The read is <strong>anonymous</strong>
 * — the page is reachable without a session, which is the whole reason the backend endpoint permits
 * anonymous callers.
 *
 * <p>A backend outage therefore takes this page with it, where before it rendered from a local
 * bundle. That is the accepted cost of removing the second copy, and it is the failure mode every
 * other page already has. Keeping a fallback copy in this module would reintroduce exactly the
 * drift this change removes — and a fallback that silently serves <em>older</em> terms than the
 * ones being accepted is worse than a page that is briefly unavailable.
 */
@Controller
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
   * Renders the wording in force.
   *
   * @param model receives the document under {@code terms}
   * @return the {@code terms} view name
   */
  @GetMapping("/terms")
  public String showTerms(Model model) {
    // The only bearer-less backend call the frontend makes (REQ-SEC-052). Named rather than
    // expressed as a flag: a boolean parameter meaning "send this without an identity" was what
    // forty other call sites used to pass, and each of them was a decision nobody made on purpose.
    model.addAttribute("terms", backendApiClient.getTermsDocumentAnonymously());
    return "terms";
  }
}
