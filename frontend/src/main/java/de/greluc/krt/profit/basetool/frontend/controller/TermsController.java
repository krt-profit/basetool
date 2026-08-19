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

import de.greluc.krt.profit.basetool.frontend.model.dto.TermsDocumentDto;
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

  /** Backend endpoint serving the wording in force; anonymous by design (ADR-0138). */
  private static final String TERMS_DOCUMENT_URI = "/api/v1/terms/document";

  private final BackendApiClient backendApiClient;

  /**
   * Renders the wording in force.
   *
   * @param model receives the document under {@code terms}
   * @return the {@code terms} view name
   */
  @GetMapping("/terms")
  public String showTerms(Model model) {
    model.addAttribute(
        "terms", backendApiClient.get(TERMS_DOCUMENT_URI, TermsDocumentDto.class, true));
    return "terms";
  }
}
