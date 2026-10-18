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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ItemDerivationView;
import de.greluc.krt.profit.basetool.frontend.kernel.model.ItemPickerOption;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ItemPickerLabels;
import de.greluc.krt.profit.basetool.frontend.kernel.web.PickerSearch;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON relays of the job-order item picker: the live search by item name and the derivation
 * preview, both with labelled items (REQ-INV-064).
 */
@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class JobOrderItemPickerController {

  /** Searches the orderable items and derives the previews in the backend. */
  private final JobOrderBackendClient jobOrderClient;

  /** Labels the matches with size, grade and class. */
  private final ItemPickerLabels itemPickerLabels;

  /** Labels the preview's sub-assemblies. */
  private final JobOrderItemLines jobOrderItemLines;

  /**
   * Searches the orderable items by name.
   *
   * @param q the case-insensitive item-name search term ({@code null} / blank = first page)
   * @return up to {@link PickerSearch#PAGE_SIZE} labelled matches, empty on failure
   */
  @NotNull
  @GetMapping("/item-search")
  public List<ItemPickerOption> itemSearch(@RequestParam(required = false) @Nullable String q) {
    return itemPickerLabels.options(
        SectionFallback.load(
            log,
            "job-order-item-search",
            () -> jobOrderClient.searchOrderableItems(q == null ? "" : q, PickerSearch.PAGE_SIZE),
            null));
  }

  /**
   * Returns the material-derivation preview of a blueprint at the given amount: resolved materials,
   * labelled sub-assembly suggestions and unresolved-ingredient names.
   *
   * @param blueprintId the chosen blueprint
   * @param amount the whole-unit amount to scale by (defaults to 1)
   * @return the derivation preview, or {@code null} on failure
   */
  @Nullable
  @GetMapping("/item-derivation/{blueprintId}")
  public ItemDerivationView itemDerivation(
      @PathVariable UUID blueprintId,
      @RequestParam(required = false, defaultValue = "1") int amount) {
    return jobOrderItemLines.derivation(
        SectionFallback.load(
            log,
            "job-order-item-derivation",
            () -> jobOrderClient.itemDerivation(blueprintId, amount),
            null));
  }
}
