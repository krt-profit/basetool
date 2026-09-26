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
import org.jetbrains.annotations.NotNull;
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
 * Admin-only, read-only controller for the {@code /admin/sync-reports} pages: a combined view and
 * per-source views for SC Wiki and UEX, all rendered by {@code admin/sync-reports}.
 */
@Controller
@UsesLayoutModel
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
  @NotNull
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
  @NotNull
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
  @NotNull
  @GetMapping("/admin/sync-reports/uex")
  public String uex(
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      Model model) {
    return render("UEX", "UEX", "/admin/sync-reports/uex", page, fragment, model);
  }

  /**
   * Deletes sync-report events older than {@code days} days, for one source or both, and redirects
   * back to the tab with the deleted count or an error as flash attribute.
   *
   * @param source active source tab ({@code "SCWIKI"} / {@code "UEX"}), or blank for both
   * @param days minimum age in days a report must exceed to be deleted
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to the matching sync-reports tab
   */
  @NotNull
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
   * AJAX twin of {@link #deleteOld} that returns the deleted count as {@code {"deleted": <n>}}.
   *
   * @param source active source tab ({@code "SCWIKI"} / {@code "UEX"}), or blank for both
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
   * Canonicalizes the caller-supplied source tab, so only one of two literals is ever relayed to
   * the backend.
   *
   * @param source source tab in any case and with surrounding whitespace, or {@code null}
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
  @NotNull
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
   * Fetches one page of sync-report events, optionally filtered by source, and populates the model;
   * a backend failure yields an error banner and an empty list.
   *
   * @param source backend source filter ({@code "SCWIKI"} / {@code "UEX"}), or {@code null} for
   *     both
   * @param activeTab active tab marker ({@code "ALL"} / {@code "SCWIKI"} / {@code "UEX"})
   * @param basePath the page's own path, for pager links
   * @param page zero-based page index
   * @param fragment {@code "results"} to render only the table and pager (REQ-FE-002)
   * @param model Thymeleaf model
   * @return the {@code admin/sync-reports} view name, or its {@code results} fragment
   */
  @NotNull
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
  private void populateEmpty(@NotNull Model model) {
    model.addAttribute("events", List.of());
    model.addAttribute("currentPage", 0);
    model.addAttribute("totalPages", 0);
    model.addAttribute("totalElements", 0L);
  }
}
