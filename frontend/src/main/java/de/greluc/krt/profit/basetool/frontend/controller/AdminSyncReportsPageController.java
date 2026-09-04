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
import de.greluc.krt.profit.basetool.frontend.model.dto.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Spring MVC controller backing the {@code /admin/sync-reports} pages (SC_WIKI_SYNC_PLAN.md §8.8):
 * a combined view plus per-source views for SC Wiki and UEX. All three render the same {@code
 * admin/sync-reports} template, differing only in the {@code source} filter relayed to the backend
 * and the active-tab marker.
 *
 * <p>Admin-only — class-level {@code @PreAuthorize("hasRole('ADMIN')")} matches the backend gate.
 * Read-only: the page never mutates the audit log.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSyncReportsPageController {

  private static final int PAGE_SIZE = 100;

  /** Response type for the paged {@code /sync-reports} listing. */
  private static final ParameterizedTypeReference<PageResponse<SyncReportDto>>
      SYNC_REPORT_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Combined view across both catalogues.
   *
   * @param page zero-based page index
   * @param model Thymeleaf model
   * @return the {@code admin/sync-reports} view name
   */
  @GetMapping("/admin/sync-reports")
  public String combined(
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      Model model) {
    return render(null, "ALL", "/admin/sync-reports", page, fragment, model);
  }

  /**
   * SC Wiki-only view.
   *
   * @param page zero-based page index
   * @param model Thymeleaf model
   * @return the {@code admin/sync-reports} view name
   */
  @GetMapping("/admin/sync-reports/scwiki")
  public String scwiki(
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      Model model) {
    return render("SCWIKI", "SCWIKI", "/admin/sync-reports/scwiki", page, fragment, model);
  }

  /**
   * UEX-only view.
   *
   * @param page zero-based page index
   * @param model Thymeleaf model
   * @return the {@code admin/sync-reports} view name
   */
  @GetMapping("/admin/sync-reports/uex")
  public String uex(
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      Model model) {
    return render("UEX", "UEX", "/admin/sync-reports/uex", page, fragment, model);
  }

  /**
   * Deletes sync-report events older than {@code days} days, optionally scoped to the active source
   * tab, then redirects back to that tab with a flash result. A blank {@code source} purges the
   * combined view (both catalogues); {@code "SCWIKI"} / {@code "UEX"} confine the purge to one
   * source. The deleted-row count is relayed via the {@code deletedCount} flash attribute so the
   * page can show a success banner; a backend failure or invalid input lands as an {@code error}
   * flash attribute instead.
   *
   * @param source active source tab ({@code "SCWIKI"} / {@code "UEX"}), or blank for the combined
   *     view
   * @param days minimum age in days a report must exceed to be deleted
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to the matching sync-reports tab
   */
  @PostMapping("/admin/sync-reports/delete-old")
  public String deleteOld(
      @RequestParam(required = false) String source,
      @RequestParam int days,
      RedirectAttributes redirectAttributes) {
    String canonicalSource = canonicalSource(source);
    String redirect = redirectPathFor(canonicalSource);
    if (days < 1) {
      redirectAttributes.addFlashAttribute("error", "error.admin.syncReports.delete");
      return "redirect:" + redirect;
    }
    String uri = purgeUri(canonicalSource, days);
    try {
      SyncReportPurgeResultDto result =
          backendApiClient.delete(uri, SyncReportPurgeResultDto.class);
      redirectAttributes.addFlashAttribute("deletedCount", result == null ? 0 : result.deleted());
    } catch (Exception e) {
      log.error("Failed to delete old sync reports (source={}, days={})", source, days, e);
      redirectAttributes.addFlashAttribute("error", "error.admin.syncReports.delete");
    }
    return "redirect:" + redirect;
  }

  /**
   * In-place (AJAX) twin of {@link #deleteOld} — routed here ahead of the classic handler by the
   * {@code X-Requested-With} header so the no-JS form keeps its redirect fallback. Performs the
   * same purge but returns the deleted-row count as {@code {"deleted": <n>}} so the page can show a
   * count toast and re-swap the results table in place instead of reloading.
   *
   * @param source active source tab ({@code "SCWIKI"} / {@code "UEX"}), or blank for the combined
   *     view
   * @param days minimum age in days a report must exceed to be deleted
   * @return {@code 200 {"deleted": <n>}} on success, {@code 400} when {@code days < 1}, {@code 500}
   *     on a backend failure
   */
  @ResponseBody
  @PostMapping(
      value = "/admin/sync-reports/delete-old",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteOldAjax(
      @RequestParam(required = false) String source, @RequestParam int days) {
    if (days < 1) {
      return ResponseEntity.badRequest().build();
    }
    String uri = purgeUri(canonicalSource(source), days);
    try {
      SyncReportPurgeResultDto result =
          backendApiClient.delete(uri, SyncReportPurgeResultDto.class);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("deleted", result == null ? 0 : result.deleted());
      return ResponseEntity.ok(body);
    } catch (Exception e) {
      log.error("Failed to delete old sync reports (ajax) (source={}, days={})", source, days, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Canonicalizes the caller-supplied source tab to one of the two catalogue names, or {@code null}
   * for the combined view.
   *
   * <p>Both callers of this used to differ on what "the source" was: the redirect trimmed and
   * upper-cased it while the relayed backend query took the raw value, so {@code ?source=scwiki}
   * landed the user on the SC Wiki tab but sent {@code scwiki} to the backend — which does not
   * recognise it and therefore purges *both* catalogues. Canonicalizing once fixes that, and it is
   * also what keeps the value out of the relayed URI: what is forwarded is one of two literals from
   * this switch, never the caller's string.
   *
   * @param source active source tab, in any case and with surrounding whitespace, or {@code null}
   * @return {@code "SCWIKI"}, {@code "UEX"}, or {@code null} for the combined view
   */
  private static @Nullable String canonicalSource(@Nullable String source) {
    if (source == null || source.isBlank()) {
      return null;
    }
    return switch (source.trim().toUpperCase(Locale.ROOT)) {
      case "SCWIKI" -> "SCWIKI";
      case "UEX" -> "UEX";
      default -> null;
    };
  }

  /**
   * Maps the active source tab to the page path the delete action should redirect back to, so the
   * user lands on the same tab they triggered the purge from.
   *
   * @param source the canonical source ({@code "SCWIKI"} / {@code "UEX"}), or {@code null}
   * @return the matching sync-reports page path
   */
  private static String redirectPathFor(@Nullable String source) {
    if (source == null) {
      return "/admin/sync-reports";
    }
    return switch (source) {
      case "SCWIKI" -> "/admin/sync-reports/scwiki";
      case "UEX" -> "/admin/sync-reports/uex";
      default -> "/admin/sync-reports";
    };
  }

  /**
   * Builds the backend purge URI for a canonical source, or for both catalogues when it is {@code
   * null}.
   *
   * @param source the canonical source ({@code "SCWIKI"} / {@code "UEX"}), or {@code null} for both
   * @param days minimum age in days a report must exceed to be deleted
   * @return the relative backend URI
   */
  private static String purgeUri(@Nullable String source, int days) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/sync-reports").queryParam("olderThanDays", days);
    if (source != null) {
      uri.queryParam("source", source);
    }
    return uri.toUriString();
  }

  /**
   * Shared render path: fetches one page of events from the backend (filtered to {@code source}
   * when non-null), populates the model, and returns the view name. A backend failure collapses to
   * an error banner with an empty list rather than a 500.
   *
   * @param source backend source filter ({@code "SCWIKI"} / {@code "UEX"}), or {@code null} for the
   *     combined view
   * @param activeTab marker for the active tab in the template ({@code "ALL"} / {@code "SCWIKI"} /
   *     {@code "UEX"})
   * @param basePath the page's own path, used to build pager links
   * @param page zero-based page index
   * @param fragment when {@code "results"} only the table + pager fragment is rendered (AJAX pager
   *     swap, REQ-FE-002); otherwise the full page is returned
   * @param model Thymeleaf model
   * @return the {@code admin/sync-reports} view name, or its {@code results} fragment for an AJAX
   *     swap
   */
  private String render(
      String source, String activeTab, String basePath, int page, String fragment, Model model) {
    int safePage = Math.max(page, 0);
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/sync-reports")
            .queryParam("page", safePage)
            .queryParam("size", PAGE_SIZE);
    if (source != null) {
      uriBuilder.queryParam("source", source);
    }
    String uri = uriBuilder.toUriString();
    try {
      PageResponse<SyncReportDto> events = backendApiClient.get(uri, SYNC_REPORT_PAGE_TYPE);
      if (events != null) {
        model.addAttribute("events", events.content() == null ? List.of() : events.content());
        model.addAttribute("currentPage", events.page());
        model.addAttribute("totalPages", events.totalPages());
        model.addAttribute("totalElements", events.totalElements());
      } else {
        populateEmpty(model);
      }
    } catch (Exception e) {
      log.error("Failed to load sync reports (source={})", source, e);
      model.addAttribute("error", "error.admin.syncReports.load");
      populateEmpty(model);
    }
    model.addAttribute("activeTab", activeTab);
    model.addAttribute("basePath", basePath);
    return "results".equals(fragment) ? "admin/sync-reports :: results" : "admin/sync-reports";
  }

  /**
   * Fills the paging model attributes with an empty result, used on a backend miss / failure so the
   * template never dereferences a missing attribute.
   *
   * @param model Thymeleaf model to fill
   */
  private void populateEmpty(Model model) {
    model.addAttribute("events", List.of());
    model.addAttribute("currentPage", 0);
    model.addAttribute("totalPages", 0);
    model.addAttribute("totalElements", 0L);
  }
}
