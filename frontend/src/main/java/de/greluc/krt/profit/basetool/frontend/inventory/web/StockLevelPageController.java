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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.client.StockLevelBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The storage tab „Mindestbestände" ({@code /inventory/stock-levels}): the minimum stock levels of
 * every unit in the caller's Lager scope, one list per unit, with the create, edit and delete
 * dialogs for the unit's Logisticians (REQ-INV-068).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory/stock-levels")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class StockLevelPageController {

  /** The fragment value that renders only the level lists. */
  private static final String RESULTS_FRAGMENT = "results";

  private final StockLevelBackendClient stockLevelClient;
  private final InventoryBackendClient inventoryClient;
  private final QualityTierCatalog qualityTierCatalog;

  /**
   * The levels of one unit, in the order the backend lists them.
   *
   * @param unit the unit
   * @param levels the unit's levels
   */
  public record UnitLevels(
      @NotNull OrgUnitReferenceDto unit, @NotNull List<StockLevelDto> levels) {}

  /**
   * Renders the tab or, for {@code fragment=results}, only its level lists.
   *
   * @param fragment {@code results} renders only the lists
   * @param model the model
   * @return the {@code inventory/stock-levels} view or its {@code stockLevelResults} fragment
   */
  @NotNull
  @GetMapping
  public String view(
      @RequestParam(required = false) @Nullable String fragment, @NotNull Model model) {
    List<StockLevelDto> levels =
        SectionFallback.load(log, "stock-levels", stockLevelClient::levels, null);
    model.addAttribute("stockLevelsFailed", levels == null);
    model.addAttribute("unitLevels", byUnit(levels == null ? List.of() : levels));
    if (RESULTS_FRAGMENT.equalsIgnoreCase(fragment)) {
      return "inventory/stock-levels :: stockLevelResults";
    }
    List<OrgUnitReferenceDto> units =
        SectionFallback.load(
            log, "stock-level-units", stockLevelClient::maintainableUnits, List.of());
    model.addAttribute("maintainableUnits", units == null ? List.of() : units);
    if (units != null && !units.isEmpty()) {
      List<MaterialReferenceDto> materials = inventoryClient.materials();
      model.addAttribute("materials", materials == null ? List.of() : materials);
      model.addAttribute("tiers", activeTiers());
    }
    return "inventory/stock-levels";
  }

  private @NotNull List<QualityTierDto> activeTiers() {
    return qualityTierCatalog.all().stream().filter(QualityTierDto::active).toList();
  }

  private static @NotNull List<UnitLevels> byUnit(@NotNull List<StockLevelDto> levels) {
    Map<UUID, UnitLevels> groups = new LinkedHashMap<>();
    for (StockLevelDto level : levels) {
      groups
          .computeIfAbsent(
              level.orgUnit().id(), _ -> new UnitLevels(level.orgUnit(), new ArrayList<>()))
          .levels()
          .add(level);
    }
    return List.copyOf(groups.values());
  }
}
