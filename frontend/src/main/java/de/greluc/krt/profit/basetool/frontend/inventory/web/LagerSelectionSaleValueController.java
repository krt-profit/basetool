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

import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySelectionSaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.MySelectionSaleValueRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Renders the "Mein Lager" sum row of the entries marked on the page (REQ-INV-072, REQ-FE-001): the
 * page's script posts the marked ids and swaps the returned row in for the view's total.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class LagerSelectionSaleValueController {

  /** Values the marked entries on the backend. */
  private final InventoryBackendClient inventoryClient;

  /**
   * Renders the sum row of the marked entries; a failed backend read renders the row with an
   * unavailable note instead of a total.
   *
   * @param request the marked entry ids
   * @param model receives {@code saleValueSummary}, or {@code null} when the value is unavailable
   * @return the {@code lager-sale-value-total :: saleValueSelection} fragment view name
   */
  @NotNull
  @PostMapping(
      value = "/my/sale-value",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public String selectionSaleValue(
      @RequestBody @Valid @NotNull MySelectionSaleValueRequest request, @NotNull Model model) {
    InventorySelectionSaleValueDto value =
        SectionFallback.load(
            log,
            "lager-selection-sale-value",
            () -> inventoryClient.selectionSaleValue(request),
            null);
    model.addAttribute(
        "saleValueSummary", value == null ? null : LagerSaleValueSummary.ofSelection(value));
    return "inventory/fragments/lager-sale-value-total :: saleValueSelection";
  }
}
