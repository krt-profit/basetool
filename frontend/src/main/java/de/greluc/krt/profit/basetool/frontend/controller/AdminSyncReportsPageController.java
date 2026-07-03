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
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    String redirect = redirectPathFor(source);
    if (days < 1) {
      redirectAttributes.addFlashAttribute("error", "error.admin.syncReports.delete");
      return "redirect:" + redirect;
    }
    String uri = "/api/v1/sync-reports?olderThanDays=" + days;
    if (source != null && !source.isBlank()) {
      uri += "&source=" + source;
    }
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
    String uri = "/api/v1/sync-reports?olderThanDays=" + days;
    if (source != null && !source.isBlank()) {
      uri += "&source=" + source;
    }
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
   * Maps the active source tab to the page path the delete action should redirect back to, so the
   * user lands on the same tab they triggered the purge from.
   *
   * @param source active source tab ({@code "SCWIKI"} / {@code "UEX"}), or blank / {@code null}
   * @return the matching sync-reports page path
   */
  private static String redirectPathFor(String source) {
    if (source == null || source.isBlank()) {
      return "/admin/sync-reports";
    }
    return switch (source.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "SCWIKI" -> "/admin/sync-reports/scwiki";
      case "UEX" -> "/admin/sync-reports/uex";
      default -> "/admin/sync-reports";
    };
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
    String uri = "/api/v1/sync-reports?page=" + safePage + "&size=" + PAGE_SIZE;
    if (source != null) {
      uri += "&source=" + source;
    }
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
