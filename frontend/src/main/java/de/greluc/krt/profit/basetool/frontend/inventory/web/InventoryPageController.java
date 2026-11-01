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
import de.greluc.krt.profit.basetool.frontend.inventory.model.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.ItemPickerOption;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ItemPickerLabels;
import de.greluc.krt.profit.basetool.frontend.kernel.web.PickerSearch;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller for the aggregated Lager overview ({@code /inventory}), its per-material and
 * per-game-item drilldowns and the item picker's search.
 *
 * <p>The overview switches between Material and Items via {@code view=items} (REQ-INV-030) and can
 * return only its table fragment for AJAX updates.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryPageController {

  /**
   * Selectable page sizes for the per-material and per-game-item drilldowns (REQ-INV-033). An
   * out-of-list {@code size} snaps back to {@link #DRILLDOWN_DEFAULT_PAGE_SIZE}, so a crafted URL
   * cannot request an unbounded page from the backend.
   */
  private static final List<Integer> DRILLDOWN_PAGE_SIZES = List.of(50, 100, 200);

  /**
   * Default drilldown page size, used when the caller supplies no {@code size} or one outside
   * {@link #DRILLDOWN_PAGE_SIZES}.
   */
  private static final int DRILLDOWN_DEFAULT_PAGE_SIZE = 50;

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  private final InventoryCatalogs catalogs;

  /** Labels the item picker's search results with size, grade and class (REQ-INV-064). */
  private final ItemPickerLabels itemPickerLabels;

  /**
   * Renders the squadron-wide aggregated inventory view ({@code /inventory}); {@code view=items}
   * selects the game-item catalog (REQ-INV-030).
   *
   * @param view {@code "items"} for the game-item catalog, anything else (or absent) for material
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code "results"} renders only the results + pagination fragment (REQ-FE-005)
   * @param model model populated with the page, aggregated items and material catalog
   * @return the {@code inventory-index} view name, or its {@code inventoryResults} fragment
   *     selector
   */
  @NotNull
  @GetMapping
  public String viewAggregatedInventory(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean itemsView = isItemsView(view);
    List<AggregatedInventoryDto> aggregated =
        loadPageSection(
            model,
            "aggregated-inventory",
            "inventoryPage",
            "error.inventory.aggregate.load",
            () -> inventoryClient.aggregated(page, size, itemsView));

    List<MaterialReferenceDto> materials = itemsView ? List.of() : catalogs.materials();

    model.addAttribute("view", itemsView ? "items" : "material");
    model.addAttribute("aggregated", aggregated);
    model.addAttribute("materials", materials);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory/inventory-index :: inventoryResults";
    }
    return "inventory/inventory-index";
  }

  /**
   * Resolves the Lager view switch (REQ-INV-030): {@code view=items} selects the game-item view,
   * any other value or absence the material view.
   *
   * @param view the raw {@code view} query parameter, may be {@code null}
   * @return {@code true} when the items view was requested
   */
  private static boolean isItemsView(String view) {
    return "items".equalsIgnoreCase(view);
  }

  /**
   * Renders the per-material drilldown, server-side paginated (REQ-INV-033). The material switcher
   * catalog is loaded on full renders only.
   *
   * @param materialId material id to drill into
   * @param page zero-based page index; {@code null} or negative falls back to the first page
   * @param size requested page size; values outside {@link #DRILLDOWN_PAGE_SIZES} snap back to
   *     {@link #DRILLDOWN_DEFAULT_PAGE_SIZE}
   * @param fragment {@code "results"} renders only the results + pagination fragment (REQ-FE-005,
   *     REQ-FE-010)
   * @param model model populated with the items page, the pagination attributes and, on full
   *     renders, the material catalog
   * @return the {@code inventory-material} view name, or its {@code inventoryMaterialResults}
   *     fragment selector
   */
  @NotNull
  @GetMapping("/material/{materialId}")
  public String viewMaterialInventory(
      @PathVariable @NotNull UUID materialId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize =
        size != null && DRILLDOWN_PAGE_SIZES.contains(size) ? size : DRILLDOWN_DEFAULT_PAGE_SIZE;
    List<InventoryItemDto> items =
        loadPageSection(
            model,
            "material-drilldown",
            "inventoryMaterialPage",
            "error.inventory.material.load",
            () -> fetchDrilldownPage(false, materialId, effectivePage, effectiveSize));

    model.addAttribute("items", items);
    model.addAttribute("pageSizes", DRILLDOWN_PAGE_SIZES);
    model.addAttribute("selectedMaterialId", materialId);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory/inventory-material :: inventoryMaterialResults";
    }
    List<MaterialReferenceDto> materials = catalogs.materials();
    model.addAttribute("materials", materials);
    model.addAttribute("materialName", drilldownMaterialName(materialId, items, materials));
    return "inventory/inventory-material";
  }

  /**
   * Names the drilled-into material for the page head: the first row's material, else the catalog
   * entry with the id.
   *
   * @param materialId the drilled-into material
   * @param items the rows of the current page
   * @param materials the material catalog
   * @return the material's name, or {@code null} when neither source knows it
   */
  @Nullable
  private static String drilldownMaterialName(
      @NotNull UUID materialId,
      @NotNull List<InventoryItemDto> items,
      @NotNull List<MaterialReferenceDto> materials) {
    return items.stream()
        .map(InventoryItemDto::material)
        .filter(Objects::nonNull)
        .map(MaterialReferenceDto::name)
        .filter(Objects::nonNull)
        .findFirst()
        .or(() ->
            materials.stream()
                .filter(m -> materialId.equals(m.id()))
                .map(MaterialReferenceDto::name)
                .findFirst())
        .orElse(null);
  }

  /**
   * Renders the per-game-item drilldown (REQ-INV-030), server-side paginated (REQ-INV-033), without
   * a quality column or navigation catalog. The display name comes from the first row's {@code
   * gameItem}.
   *
   * @param gameItemId game item to drill into
   * @param page zero-based page index; {@code null} or negative falls back to the first page
   * @param size requested page size; values outside {@link #DRILLDOWN_PAGE_SIZES} snap back to
   *     {@link #DRILLDOWN_DEFAULT_PAGE_SIZE}
   * @param fragment {@code "results"} renders only the results + pagination fragment (REQ-FE-005,
   *     REQ-FE-015)
   * @param model model populated with the rows, the pagination attributes and the item display name
   * @return the {@code inventory-game-item} view name, or its {@code inventoryGameItemResults}
   *     fragment selector
   */
  @NotNull
  @GetMapping("/game-item/{gameItemId}")
  public String viewGameItemInventory(
      @PathVariable @NotNull UUID gameItemId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize =
        size != null && DRILLDOWN_PAGE_SIZES.contains(size) ? size : DRILLDOWN_DEFAULT_PAGE_SIZE;
    List<InventoryItemDto> items =
        loadPageSection(
            model,
            "game-item-drilldown",
            "inventoryGameItemPage",
            "error.inventory.gameItem.load",
            () -> fetchDrilldownPage(true, gameItemId, effectivePage, effectiveSize));

    model.addAttribute("items", items);
    model.addAttribute("pageSizes", DRILLDOWN_PAGE_SIZES);
    model.addAttribute("selectedGameItemId", gameItemId);
    model.addAttribute(
        "gameItemName",
        items.stream()
            .map(InventoryItemDto::gameItem)
            .filter(Objects::nonNull)
            .map(InventoryGameItemReferenceDto::name)
            .findFirst()
            .orElse(null));
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory/inventory-game-item :: inventoryGameItemResults";
    }
    return "inventory/inventory-game-item";
  }

  /**
   * Fetches one page of a drilldown's rows, re-fetching the last page once when the requested page
   * lies past the end of a non-empty result (REQ-INV-033).
   *
   * @param gameItem {@code true} for the game-item drilldown, {@code false} for the material one
   * @param id the drilled-into material or game item
   * @param requestedPage the caller's non-negative page index
   * @param size the whitelisted page size
   * @return the resolved page, or {@code null} when the backend yields no page
   */
  private PageResponse<InventoryItemDto> fetchDrilldownPage(
      boolean gameItem, @NotNull UUID id, int requestedPage, int size) {
    PageResponse<InventoryItemDto> p =
        inventoryClient.drilldownPage(gameItem, id, requestedPage, size);
    if (p != null
        && p.totalElements() > 0
        && requestedPage > 0
        && requestedPage >= p.totalPages()) {
      int lastPage = p.totalPages() - 1;
      p = inventoryClient.drilldownPage(gameItem, id, lastPage, size);
    }
    return p;
  }

  /**
   * Loads a paged table section: the page goes into the model under {@code pageAttribute}, its rows
   * are returned; a failed read sets {@code error} to {@code errorKey} and returns no rows.
   *
   * @param model the page model
   * @param section the section name for the failure log
   * @param pageAttribute the model attribute that carries the page
   * @param errorKey the {@code error} message key on failure
   * @param read reads the page from the backend
   * @param <T> the row type
   * @return the page's rows, empty when the backend sent none or the read failed
   */
  @NotNull
  private <T> List<T> loadPageSection(
      @NotNull Model model,
      @NotNull String section,
      @NotNull String pageAttribute,
      @NotNull String errorKey,
      @NotNull Supplier<PageResponse<T>> read) {
    List<T> rows = new ArrayList<>();
    if (!SectionFallback.run(
        log,
        section,
        () -> {
          PageResponse<T> p = read.get();
          if (p != null) {
            if (p.content() != null) {
              rows.addAll(p.content());
            }
            model.addAttribute(pageAttribute, p);
          }
        })) {
      model.addAttribute("error", errorKey);
    }
    return rows;
  }

  /**
   * JSON proxy for the {@code remote-game-items} combobox (REQ-FE-016, REQ-INV-064): searches
   * bookable game items by name via the authenticated client and returns a flat list of labelled
   * options, empty on backend failure.
   *
   * @param q the case-insensitive item-name search term; {@code null}/blank matches all
   * @return up to {@link PickerSearch#PAGE_SIZE} matching bookable game-item options, never {@code
   *     null}
   */
  @GetMapping("/item-search")
  @ResponseBody
  public List<ItemPickerOption> itemSearch(@RequestParam(required = false) String q) {
    return SectionFallback.load(
        log,
        "item-search",
        () ->
            itemPickerLabels.options(
                inventoryClient.itemCatalog(PickerSearch.PAGE_SIZE, q == null ? "" : q)),
        List.of());
  }
}
