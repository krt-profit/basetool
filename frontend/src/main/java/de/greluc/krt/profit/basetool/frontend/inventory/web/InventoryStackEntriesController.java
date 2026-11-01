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
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderGameItemNeedDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialNeedDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MissionReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
 * Controller for the stack drill-down fragments of the Lager lists: one page of a stack's entries,
 * personal or global, for materials and for game items.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryStackEntriesController {

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  private final InventoryCatalogs catalogs;

  /**
   * Renders one page of a personal Lager stack's entries, oldest first, as the AJAX drill-down on
   * {@code /inventory/my}. The stack is addressed by its stock-identity params; a {@code null}
   * owning org unit selects rows without one.
   *
   * @param materialId the stack's material
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack holds the caller's private stock
   * @param stolen whether the stack holds stock marked „gestohlen" (REQ-INV-053)
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model model populated with the entries page and association catalogs
   * @return the {@code inventory-my :: stackEntries} fragment view name
   */
  @NotNull
  @GetMapping("/my/stack/entries")
  public String viewMyStackEntries(
      @RequestParam @NotNull UUID materialId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false, defaultValue = "false") boolean personal,
      @RequestParam(required = false, defaultValue = "false") boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    fetchStackEntriesIntoModel(
        () ->
            inventoryClient.myStackEntries(
                materialId, locationId, quality, personal, stolen, owningOrgUnitId, page, size),
        model,
        true);
    addReleasedItemIds(model);
    return "inventory/fragments/inventory-stack-entries :: stackEntriesMy";
  }

  /**
   * Item-view sibling of {@link #viewMyStackEntries} (REQ-INV-030): renders one page of a personal
   * game-item stack's entries, addressed by {@code gameItemId}, without a mission catalog.
   *
   * @param gameItemId the stack's game item
   * @param locationId the stack's storage location
   * @param personal whether the stack holds the caller's private stock
   * @param stolen whether the stack holds stock marked „gestohlen" (REQ-INV-053)
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model model populated with the entries page and the job-order catalog
   * @return the {@code fragments/inventory-stack-entries :: stackEntriesMy} fragment view name
   */
  @NotNull
  @GetMapping("/my/game-item-stack/entries")
  public String viewMyGameItemStackEntries(
      @RequestParam @NotNull UUID gameItemId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false, defaultValue = "false") boolean personal,
      @RequestParam(required = false, defaultValue = "false") boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    fetchStackEntriesIntoModel(
        () ->
            inventoryClient.myGameItemStackEntries(
                gameItemId, locationId, personal, stolen, owningOrgUnitId, page, size),
        model,
        false);
    addReleasedItemIds(model);
    return "inventory/fragments/inventory-stack-entries :: stackEntriesMy";
  }

  /**
   * Adds {@code releasedItemIds}, the loaded leaf rows with an active Materialbörse offer; left
   * empty on backend failure.
   *
   * @param model the model already populated with the {@code entries} leaf list.
   */
  private void addReleasedItemIds(Model model) {
    Set<UUID> released = new HashSet<>();
    if (model.getAttribute("entries") instanceof List<?> entries && !entries.isEmpty()) {
      List<UUID> ids = new ArrayList<>();
      for (Object entry : entries) {
        if (entry instanceof InventoryItemDto item && item.id() != null) {
          ids.add(item.id());
        }
      }
      if (!ids.isEmpty()) {
        List<UUID> result =
            SectionFallback.load(
                log, "released-item-ids", () -> inventoryClient.releasedItemIds(ids), null);
        if (result != null) {
          released.addAll(result);
        }
      }
    }
    model.addAttribute("releasedItemIds", released);
  }

  /**
   * Squadron-wide variant of {@link #viewMyStackEntries} for {@code /inventory/all}; the stack key
   * includes the owning {@code userId}, and the backend applies the same scope as the grouped view.
   *
   * @param materialId the stack's material
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param stolen whether the stack holds stock marked „gestohlen" (REQ-INV-053)
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model model populated with the entries page and association catalogs
   * @return the {@code inventory-admin :: stackEntries} fragment view name
   */
  @NotNull
  @GetMapping("/all/stack/entries")
  public String viewAllStackEntries(
      @RequestParam @NotNull UUID materialId,
      @RequestParam @NotNull UUID userId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false, defaultValue = "false") boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    fetchStackEntriesIntoModel(
        () ->
            inventoryClient.allStackEntries(
                materialId, userId, locationId, quality, stolen, owningOrgUnitId, page, size),
        model,
        true);
    return "inventory/fragments/inventory-stack-entries :: stackEntriesAdmin";
  }

  /**
   * Item-view sibling of {@link #viewAllStackEntries} (REQ-INV-030): renders one page of a
   * squadron-wide game-item stack's entries, without a mission catalog.
   *
   * @param gameItemId the stack's game item
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param stolen whether the stack holds stock marked „gestohlen" (REQ-INV-053)
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model model populated with the entries page and the job-order catalog
   * @return the {@code fragments/inventory-stack-entries :: stackEntriesAdmin} fragment view name
   */
  @NotNull
  @GetMapping("/all/game-item-stack/entries")
  public String viewAllGameItemStackEntries(
      @RequestParam @NotNull UUID gameItemId,
      @RequestParam @NotNull UUID userId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false, defaultValue = "false") boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    fetchStackEntriesIntoModel(
        () ->
            inventoryClient.allGameItemStackEntries(
                gameItemId, userId, locationId, stolen, owningOrgUnitId, page, size),
        model,
        false);
    return "inventory/fragments/inventory-stack-entries :: stackEntriesAdmin";
  }

  /**
   * Fetches a stack-entries page and the job-order / mission catalogs into the model; a backend
   * failure yields an empty list plus an {@code error} flag.
   *
   * @param read reads the stack-entries page from the backend
   * @param model the model to populate with {@code entries}, {@code entriesPage}, {@code jobOrders}
   *     and {@code missions}
   * @param includeMissions whether to load the mission catalog; {@code false} yields an empty list
   *     (REQ-INV-031)
   */
  private void fetchStackEntriesIntoModel(
      @NotNull Supplier<PageResponse<InventoryItemDto>> read,
      Model model,
      boolean includeMissions) {
    if (!SectionFallback.run(log, "stack-entries", () -> addStackEntries(model, read.get()))) {
      model.addAttribute("error", "inventory.stack.entries.error");
      addStackEntries(model, null);
    }
    List<JobOrderReferenceDto> stackJobOrders = catalogs.activeJobOrders(true);
    model.addAttribute("jobOrders", stackJobOrders);
    model.addAttribute("orderNeedAmounts", orderNeedAmounts(stackJobOrders));
    model.addAttribute("orderGameItemNeedAmounts", orderGameItemNeedAmounts(stackJobOrders));
    model.addAttribute(
        "missions", includeMissions ? catalogs.missions() : List.<MissionReferenceDto>of());
  }

  /**
   * Adds a stack-entries page and its rows to the model as {@code entriesPage} and {@code entries}.
   *
   * @param model the fragment model
   * @param page the loaded page, or {@code null} when none was loaded
   */
  private static void addStackEntries(
      @NotNull Model model, @Nullable PageResponse<InventoryItemDto> page) {
    List<InventoryItemDto> entries =
        (page != null && page.content() != null)
            ? new ArrayList<>(page.content())
            : new ArrayList<>();
    model.addAttribute("entries", entries);
    model.addAttribute("entriesPage", page);
  }

  /**
   * Flattens the order needs into an outstanding-amount map keyed {@code "<orderId>|<materialId>"}
   * for the allocation popover (REQ-INV-039). Quality buckets of one material are summed.
   *
   * @param orders the loaded order catalog, already carrying its needs.
   * @return outstanding amount by {@code orderId|materialId}; only entries still needed.
   */
  @NotNull
  private static Map<String, Double> orderNeedAmounts(List<JobOrderReferenceDto> orders) {
    Map<String, Double> amounts = new LinkedHashMap<>();
    for (JobOrderReferenceDto order : orders) {
      if (order.id() == null || order.materialNeeds() == null) {
        continue;
      }
      for (JobOrderMaterialNeedDto need : order.materialNeeds()) {
        if (need.materialId() == null
            || need.outstandingAmount() == null
            || need.outstandingAmount() <= 0.0) {
          continue;
        }
        amounts.merge(order.id() + "|" + need.materialId(), need.outstandingAmount(), Double::sum);
      }
    }
    return amounts;
  }

  /**
   * Item sibling of {@link #orderNeedAmounts}: outstanding whole units keyed {@code
   * "<orderId>|<gameItemId>"} (REQ-INV-039).
   *
   * @param orders the loaded order catalog, already carrying its needs.
   * @return outstanding whole units by {@code orderId|gameItemId}; only entries still needed.
   */
  @NotNull
  private static Map<String, Integer> orderGameItemNeedAmounts(List<JobOrderReferenceDto> orders) {
    Map<String, Integer> amounts = new LinkedHashMap<>();
    for (JobOrderReferenceDto order : orders) {
      if (order.id() == null || order.gameItemNeeds() == null) {
        continue;
      }
      for (JobOrderGameItemNeedDto need : order.gameItemNeeds()) {
        if (need.gameItemId() == null
            || need.outstandingAmount() == null
            || need.outstandingAmount() <= 0) {
          continue;
        }
        amounts.merge(order.id() + "|" + need.gameItemId(), need.outstandingAmount(), Integer::sum);
      }
    }
    return amounts;
  }
}
