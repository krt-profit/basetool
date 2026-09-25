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
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonSearchResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The admin Personensuche page ({@code /admin/person-search}, REQ-SEC-060), which finds every place
 * a name appears so a rectification or erasure request can cover all of them.
 *
 * <p>ADMIN only. The search term is a person's name and is never logged (REQ-OBS-004).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/person-search")
@RequiredArgsConstructor
@Slf4j
public class AdminPersonSearchPageController {

  /**
   * The result type, as a {@link ParameterizedTypeReference} rather than a {@code Class}, because
   * only that overload of {@code BackendApiClient#get} takes URI-template variables — and binding
   * the term as a variable is the whole point here.
   */
  private static final ParameterizedTypeReference<PersonSearchResultDto> RESULT_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the page, and the results when a term was given.
   *
   * @param q the search term, or {@code null} for the empty initial state
   * @param fragment when {@code results}, only the results block is rendered, for the in-place swap
   * @param model the view model
   * @return the view or fragment name
   */
  @NotNull
  @GetMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String page(
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "fragment", required = false) String fragment,
      Model model) {
    String term = q == null ? "" : q.trim();
    model.addAttribute("term", term);
    model.addAttribute("hits", List.of());
    model.addAttribute("truncated", false);
    model.addAttribute("cappedColumns", List.of());
    model.addAttribute("searched", false);

    if (term.length() >= 3) {
      try {
        PersonSearchResultDto result =
            backendApiClient.get("/api/v1/admin/person-search?q={q}", RESULT_TYPE, term);
        model.addAttribute("hits", result == null ? List.of() : result.hits());
        model.addAttribute("truncated", result != null && result.truncated());
        model.addAttribute(
            "cappedColumns",
            result == null || result.cappedColumns() == null ? List.of() : result.cappedColumns());
        model.addAttribute("searched", true);
      } catch (BackendServiceException e) {
        log.debug("Person search failed with status {}", e.getStatusCode());
        model.addAttribute("error", "admin.personSearch.error.load");
      } catch (Exception e) {
        log.error("Person search failed unexpectedly", e);
        model.addAttribute("error", "admin.personSearch.error.load");
      }
    } else if (!term.isEmpty()) {
      model.addAttribute("error", "admin.personSearch.tooShort");
    }

    return "results".equals(fragment) ? "admin/person-search :: results" : "admin/person-search";
  }
}
