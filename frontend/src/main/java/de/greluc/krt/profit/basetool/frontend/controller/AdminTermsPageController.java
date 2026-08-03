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

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Admin overview of who has and has not accepted the Terms of Use (REQ-SEC-028).
 *
 * <p>Read-only by design: this page reports consent, it never grants it. Accepting on someone
 * else's behalf would defeat the point of recording consent in the first place, so there is no
 * write here for an admin to reach for.
 *
 * <p>Filtering and paging swap the results fragment in place (REQ-FE-001). There is deliberately
 * <em>no</em> live peer sync: the rows change when ordinary users accept on their own gate page,
 * not when another admin edits something, so there is no peer edit to propagate — and standing up a
 * broadcast room for "somebody, somewhere, accepted" would put a message on the relay for every
 * consent in the fleet to refresh a page that is opened a handful of times per terms change.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminTermsPageController {

  /** Backend endpoint listing users with their consent state. */
  private static final String ADMIN_TERMS_URI = "/api/v1/admin/terms";

  /** Backend endpoint reporting how many users still owe consent. */
  private static final String PENDING_COUNT_URI = "/api/v1/admin/terms/pending-count";

  /** Filters the page offers, mirroring what the backend accepts. */
  private static final Set<String> ALLOWED_FILTERS = Set.of("ALL", "ACCEPTED", "PENDING");

  /** Rows per page. */
  private static final int PAGE_SIZE = 25;

  private static final ParameterizedTypeReference<PageResponse<TermsAcceptanceStatusDto>>
      STATUS_PAGE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the consent overview, or its results fragment when swapped in place.
   *
   * <p>Defaults to {@code PENDING} rather than {@code ALL}: the reason to open this page is almost
   * always "who is still missing", and after a terms change the {@code ALL} list is a wall of rows
   * in which the handful that matter are invisible.
   *
   * @param filter {@code ALL}, {@code ACCEPTED} or {@code PENDING}; anything else falls back to
   *     {@code PENDING} rather than erroring, because the value reaches us from a query string a
   *     user can edit and a broken filter should not be a broken page
   * @param page zero-based page index, clamped at zero
   * @param fragment {@code results} when {@code krtFetch.swap} is asking for the results section
   *     alone; anything else renders the whole page
   * @param model receives the rows, the pending count and the echoed filter
   * @return the {@code admin/terms} view, or its {@code adminTermsResults} fragment for a swap
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
      rows =
          backendApiClient.get(
              UriComponentsBuilder.fromPath(ADMIN_TERMS_URI)
                  .queryParam("filter", effectiveFilter)
                  .queryParam("page", effectivePage)
                  .queryParam("size", PAGE_SIZE)
                  .queryParam("sort", "username,asc")
                  .build()
                  .toUriString(),
              STATUS_PAGE);
      PendingCountView count = backendApiClient.get(PENDING_COUNT_URI, PendingCountView.class);
      pending = count == null ? null : count.pending();
    } catch (BackendServiceException e) {
      // Already logged at the BackendApiClient boundary (REQ-OBS-001). The page renders its
      // "could not be loaded" state rather than an error screen, so an admin checking the rollout
      // during a backend wobble sees which half of the page is missing.
      log.debug("Terms consent overview could not be read from the backend.", e);
    }

    model.addAttribute("termsFilter", effectiveFilter);
    model.addAttribute("termsRows", rows == null ? List.of() : rows.content());
    model.addAttribute("termsPage", rows);
    model.addAttribute("termsPendingCount", pending);
    model.addAttribute("termsLoadFailed", rows == null);
    // krtFetch.swap appends `fragment=results` and expects a section-sized response. Returning the
    // whole document here would nest header, nav, the heading and the filter form INSIDE
    // #admin-terms-results on every filter change and page click — swap() only bails on a redirect
    // or a non-2xx, and a full page is neither, so nothing would report the breakage.
    //
    // The fragment is named adminTermsResults rather than `results` on purpose: a fragment whose
    // name equals its container id re-nests itself on swap.
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

  /**
   * Frontend view of the backend's pending-count response.
   *
   * @param pending how many login-capable users still owe consent
   * @param termsVersion the wording that count refers to
   */
  public record PendingCountView(long pending, String termsVersion) {}
}
