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

import de.greluc.krt.profit.basetool.frontend.config.GrafanaLinkProperties;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.exchange.client.ExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientUsageDto;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin page for the exchange client registry and the global exchange switch (REQ-XCH-003); its
 * writes go through {@link AdminExchangeClientsRelayController}, after which the registry section
 * is re-fetched in place (REQ-FE-001).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/exchange-clients")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminExchangeClientsPageController {

  /** The {@code fragment} value that renders only the registry section, for the in-place swap. */
  static final String REGISTRY_FRAGMENT = "registry";

  /** The {@code fragment} value that renders only the bulk undo runs, for the in-place swap. */
  static final String UNDO_RUNS_FRAGMENT = "undoRuns";

  /**
   * Every capability the registry can grant, in the backend's declaration order, labelled via
   * {@code exchange.capability.<scope>}.
   */
  static final List<String> CAPABILITIES =
      List.of(
          "exchange.connect",
          "exchange.blueprints.read",
          "exchange.blueprints.write",
          "exchange.stock.read",
          "exchange.stock.write",
          "exchange.hangar.read",
          "exchange.hangar.write",
          "exchange.demand.read",
          "exchange.drafts.blueprints",
          "exchange.drafts.refinery");

  /** Talks to the backend. */
  private final ExchangeBackendClient exchangeClient;

  /** Where the per-client error rate lives. */
  private final GrafanaLinkProperties grafanaLinks;

  /**
   * Renders the registry page, or with {@code fragment=registry} only its registry section and with
   * {@code fragment=undoRuns} only the bulk undo runs. A failed load renders an empty list with
   * {@code error} set and no switch.
   *
   * @param fragment {@code registry} or {@code undoRuns} for a section fragment; anything else
   *     renders the page
   * @param model receives {@code clients}, {@code settings}, {@code usage} by client id, {@code
   *     capabilities}, {@code grafanaUrl}, {@code undoRuns}, {@code undoRunning} and, on a failed
   *     load, {@code error}
   * @return {@code admin/exchange-clients}, or one of its fragments
   */
  @NotNull
  @GetMapping
  public String page(@Nullable @RequestParam(required = false) String fragment, Model model) {
    try {
      List<ExchangeClientDto> clients = exchangeClient.clients();
      model.addAttribute("clients", clients == null ? List.of() : clients);
      model.addAttribute("settings", exchangeClient.settings());
    } catch (Exception e) {
      log.debug("Failed to load the exchange registry", e);
      model.addAttribute("clients", List.of());
      model.addAttribute("settings", null);
      model.addAttribute("error", "admin.exchangeClients.error.load");
    }
    model.addAttribute("usage", usage());
    model.addAttribute("capabilities", CAPABILITIES);
    model.addAttribute("grafanaUrl", grafanaLinks.operationsDashboardUrl());
    List<ExchangeBulkUndoRunDto> runs = undoRuns();
    model.addAttribute("undoRuns", runs);
    model.addAttribute("undoRunning", runs.stream().anyMatch(ExchangeBulkUndoRunDto::running));
    if (REGISTRY_FRAGMENT.equals(fragment)) {
      return "admin/exchange-clients :: " + REGISTRY_FRAGMENT;
    }
    return UNDO_RUNS_FRAGMENT.equals(fragment)
        ? "admin/exchange-clients :: " + UNDO_RUNS_FRAGMENT
        : "admin/exchange-clients";
  }

  /**
   * Loads the recent bulk undo runs; a failure leaves the list empty rather than failing the page.
   *
   * @return the runs, newest first
   */
  private @NotNull List<ExchangeBulkUndoRunDto> undoRuns() {
    try {
      List<ExchangeBulkUndoRunDto> runs = exchangeClient.undoRuns();
      return runs == null ? List.of() : runs;
    } catch (Exception e) {
      log.debug("Failed to load the bulk undo runs", e);
      return List.of();
    }
  }

  /**
   * Loads how widely each client is in use; a failure leaves the usage columns empty rather than
   * failing the page.
   *
   * @return the usage by registry id, empty when it cannot be read
   */
  private @NotNull Map<UUID, ExchangeClientUsageDto> usage() {
    try {
      List<ExchangeClientUsageDto> rows = exchangeClient.usage();
      Map<UUID, ExchangeClientUsageDto> byId = new HashMap<>();
      if (rows != null) {
        rows.forEach(row -> byId.put(row.id(), row));
      }
      return byId;
    } catch (Exception e) {
      log.debug("Failed to load the exchange client usage", e);
      return Map.of();
    }
  }
}
