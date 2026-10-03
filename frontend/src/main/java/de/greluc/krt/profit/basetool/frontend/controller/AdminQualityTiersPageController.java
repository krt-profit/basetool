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
import de.greluc.krt.profit.basetool.frontend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin-only page controller for {@code /admin/quality-tiers}: renders the quality-tier catalogue
 * from {@code /api/v1/admin/quality-tiers} (REQ-ORDERS-036). The writes are relayed by {@link
 * AdminQualityTiersRelayController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/quality-tiers")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminQualityTiersPageController {

  private static final String BACKEND_BASE = "/api/v1/admin/quality-tiers";

  private static final String VIEW = "admin/quality-tiers";

  /** Response type of the backend's tier list. */
  private static final ParameterizedTypeReference<List<QualityTierDto>> TIER_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the tier table, inactive tiers included; a backend failure shows an error banner over
   * an empty table.
   *
   * @param fragment {@code "results"} to render only the table fragment (REQ-FE-002)
   * @param model Thymeleaf model populated with {@code tiers} and, on failure, {@code error}
   * @return the {@code admin/quality-tiers} view, or its {@code results} fragment
   */
  @NotNull
  @GetMapping
  public String list(@RequestParam(required = false) @Nullable String fragment, Model model) {
    try {
      List<QualityTierDto> tiers = backendApiClient.get(BACKEND_BASE, TIER_LIST_TYPE);
      model.addAttribute("tiers", tiers == null ? List.of() : tiers);
    } catch (Exception e) {
      log.error("Failed to load quality-tier admin page", e);
      model.addAttribute("error", "admin.qualityTiers.error.load");
      model.addAttribute("tiers", List.of());
    }
    return "results".equals(fragment) ? VIEW + " :: results" : VIEW;
  }
}
