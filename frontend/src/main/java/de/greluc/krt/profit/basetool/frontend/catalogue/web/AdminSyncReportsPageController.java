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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.SyncReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Admin-only, read-only controller for the {@code /admin/sync-reports} pages: a combined view and
 * per-source views for SC Wiki and UEX, all rendered by {@code admin/sync-reports}.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSyncReportsPageController {

  /** Reads and purges the sync reports. */
  private final SyncReportBackendClient syncReportClient;

  private final SyncReportPageReader pageReader;

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
    try {
      SyncReportPurgeResultDto result = syncReportClient.purgeSyncReports(canonicalSource, days);
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
   * @return {@code 200 {"deleted": <n>}} on success, {@code 400} when {@code days < 1}; a backend
   *     failure is answered by the global handler with the backend's status
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
    String canonicalSource = canonicalSource(source);
    SyncReportPurgeResultDto result = syncReportClient.purgeSyncReports(canonicalSource, days);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("deleted", result == null ? 0 : result.deleted());
    return ResponseEntity.ok(body);
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
    SyncReportPageReader.Data data = pageReader.read(safePage, source);
    if (!data.eventsAvailable()) {
      model.addAttribute("error", "error.admin.syncReports.load");
    }
    PageResponse<SyncReportDto> events = data.events();
    model.addAttribute("events", events.content());
    model.addAttribute("currentPage", events.page());
    model.addAttribute("totalPages", events.totalPages());
    model.addAttribute("totalElements", events.totalElements());
    model.addAttribute("eventsPage", events);
    model.addAttribute("runSummaries", data.summaries());
    model.addAttribute("attemptsAvailable", data.attemptsAvailable());
    model.addAttribute("activeTab", activeTab);
    model.addAttribute("basePath", basePath);
    if ("SCWIKI".equals(source)) {
      SyncReportPageReader.WikiDuplicates duplicates = pageReader.readWikiDuplicates();
      model.addAttribute("wikiDuplicates", duplicates.manufacturers());
      model.addAttribute("wikiDuplicatesAvailable", duplicates.available());
    }
    return "results".equals(fragment)
        ? "catalogue/admin/sync-reports :: results"
        : "catalogue/admin/sync-reports";
  }
}
