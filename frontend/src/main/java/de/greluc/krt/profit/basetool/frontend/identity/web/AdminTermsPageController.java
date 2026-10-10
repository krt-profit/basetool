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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.PendingCountDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Read-only admin overview of who has and has not accepted the Terms of Use (REQ-SEC-028).
 *
 * <p>Filtering and paging swap the results in place; the page has no live peer sync.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminTermsPageController {

  /** Filters the page offers, mirroring what the backend accepts. */
  private static final Set<String> ALLOWED_FILTERS = Set.of("ALL", "ACCEPTED", "PENDING");

  /** Rows per page. */
  private static final int PAGE_SIZE = 25;

  /** Reads the consent overview from the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Renders the consent overview, or its results fragment for an in-place swap.
   *
   * @param filter {@code ALL}, {@code ACCEPTED} or {@code PENDING}; anything else, and the default,
   *     is {@code PENDING}
   * @param page zero-based page index, clamped at zero
   * @param fragment {@code results} to render only the results section
   * @param model receives the rows, the pending count and the filter
   * @return the {@code admin/terms} view, or its {@code adminTermsResults} fragment
   */
  @GetMapping("/admin/terms")
  public @NotNull String showOverview(
      @RequestParam(required = false, defaultValue = "PENDING") String filter,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    String effectiveFilter = normalizeFilter(filter);
    int effectivePage = Math.max(page, 0);

    PageResponse<TermsAcceptanceStatusDto> rows = null;
    Long pending = null;
    try {
      rows = identityClient.termsAcceptances(effectiveFilter, effectivePage, PAGE_SIZE);
      PendingCountDto count = identityClient.termsPendingCount();
      pending = count == null ? null : count.pending();
    } catch (BackendServiceException e) {
      log.debug("Terms consent overview could not be read from the backend.", e);
    }

    model.addAttribute("termsFilter", effectiveFilter);
    model.addAttribute("termsRows", rows == null ? List.of() : rows.content());
    model.addAttribute("termsPage", rows);
    model.addAttribute("termsPendingCount", pending);
    model.addAttribute("termsLoadFailed", rows == null);
    return "results".equals(fragment) ? "admin/terms :: adminTermsResults" : "admin/terms";
  }

  /**
   * Maps a caller-supplied filter onto one the backend accepts.
   *
   * @param filter the raw query-string value, possibly absent or misspelled
   * @return the upper-cased filter when recognised, otherwise {@code PENDING}
   */
  private static @NotNull String normalizeFilter(String filter) {
    if (filter == null) {
      return "PENDING";
    }
    String normalized = filter.toUpperCase(java.util.Locale.ROOT);
    return ALLOWED_FILTERS.contains(normalized) ? normalized : "PENDING";
  }
}
