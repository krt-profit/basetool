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
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.view.ItemCollectionGroup;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Frontend controller for the Itemsammelübersicht of an ITEM job order (REQ-ORDERS-031): lists the
 * game-item stock earmarked to the order so users can reassign it and mark slices delivered.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class ItemCollectionPageController {

  /** Loads the earmarked item stock and the location lookup from the backend. */
  private final JobOrderBackendClient jobOrderClient;

  /**
   * Renders the item-collection page of an ITEM job order ({@code
   * /orders/{jobOrderId}/item-collection}).
   *
   * <p>The item stock and the location lookup each degrade to an empty list on a backend failure.
   * {@code ?fragment=results} returns only the {@code collectionResults} fragment for live sync
   * (REQ-FE-010).
   *
   * @param jobOrderId job order id passed through to the template
   * @param fragment when {@code results}, render only the {@code collectionResults} fragment
   * @param model Thymeleaf model populated with {@code jobOrderId}, {@code itemStock}, the group
   *     rows {@code itemGroups}, the overall {@code collectionProgress} and {@code locations}
   * @return the {@code item-collection} view name, or its {@code collectionResults} fragment
   */
  @NotNull
  @GetMapping("/{jobOrderId}/item-collection")
  @PreAuthorize("isAuthenticated()")
  public String viewItemCollection(
      @PathVariable UUID jobOrderId,
      @RequestParam(name = "fragment", required = false) String fragment,
      Model model) {
    List<JobOrderItemStockGroupDto> itemStock = Collections.emptyList();
    List<LocationReferenceDto> locations = Collections.emptyList();

    try {
      itemStock = jobOrderClient.itemStock(jobOrderId);
    } catch (BackendServiceException e) {
      log.warn("Could not load item collection for job order {}: {}", jobOrderId, e.getMessage());
    }

    try {
      locations = jobOrderClient.locations();
    } catch (BackendServiceException e) {
      log.warn("Could not load locations: {}", e.getMessage());
    }

    model.addAttribute("jobOrderId", jobOrderId);
    List<ItemCollectionGroup> itemGroups =
        ItemCollectionGroup.of(itemStock == null ? List.of() : itemStock);
    model.addAttribute("itemStock", itemStock);
    model.addAttribute("itemGroups", itemGroups);
    model.addAttribute("collectionProgress", ItemCollectionGroup.total(itemGroups));
    model.addAttribute("locations", locations);
    if ("results".equals(fragment)) {
      return "item-collection :: collectionResults";
    }
    return "item-collection";
  }
}
