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
import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.form.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controller for the inventory read pages ({@code /inventory}, {@code /inventory/my}, {@code
 * /inventory/all}, the drilldowns and the {@code /inventory/input} form); writes live in {@link
 * InventoryWriteController}.
 *
 * <p>The aggregated, personal and admin views switch between Material and Items via {@code
 * view=items} (REQ-INV-030) and can return only their table fragment for AJAX updates.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryPageController {

  /**
   * Response type for the squadron-wide aggregated inventory page ({@code
   * /api/v1/inventory/aggregated}), decoding the paginated per-material summary rows the {@code
   * /inventory} index renders.
   */
  private static final ParameterizedTypeReference<PageResponse<AggregatedInventoryDto>>
      AGGREGATED_INVENTORY_PAGE =
          new ParameterizedTypeReference<PageResponse<AggregatedInventoryDto>>() {};

  /**
   * Response type for paginated individual inventory rows — shared by the per-material drilldown
   * ({@code /api/v1/inventory/material/{id}}) and the two stack-entries drill-down endpoints.
   */
  private static final ParameterizedTypeReference<PageResponse<InventoryItemDto>>
      INVENTORY_ITEM_PAGE = new ParameterizedTypeReference<PageResponse<InventoryItemDto>>() {};

  /** Response type for the Materialbörse released-item-ids lookup (the "Auf Börse" flags). */
  private static final ParameterizedTypeReference<List<UUID>> UUID_LIST =
      new ParameterizedTypeReference<List<UUID>>() {};

  /**
   * Response type for the bookable-item catalog search ({@code GET /api/v1/inventory/item-catalog})
   * backing the {@code remote-game-items} combobox source's {@code /inventory/item-search} proxy.
   */
  private static final ParameterizedTypeReference<
          PageResponse<
              de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto>>
      GAME_ITEM_REFERENCE_PAGE =
          new ParameterizedTypeReference<
              PageResponse<
                  de.greluc.krt.profit.basetool.frontend.model.dto
                      .InventoryGameItemReferenceDto>>() {};

  /**
   * Response type for the grouped {@code /my} and {@code /all} list views ({@code .../grouped}),
   * decoding the Material-to-Stack grouping records the personal and admin Lager tables render.
   */
  private static final ParameterizedTypeReference<List<GroupedInventoryDto>>
      GROUPED_INVENTORY_LIST = new ParameterizedTypeReference<List<GroupedInventoryDto>>() {};

  /**
   * Response type for the owner-picker option lookups ({@code /api/v1/users/{id}/memberships} and
   * {@code /api/v1/users/me/pickable-org-units}) that populate the inventory-input R5.d owner
   * picker.
   */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST =
          new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {};

  /**
   * Response type for the user lookup ({@code /api/v1/users/lookup}) that fills the admin
   * target-user dropdown on the create form and the list-view user filter.
   */
  private static final ParameterizedTypeReference<
          List<de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto>>
      USER_REFERENCE_LIST =
          new ParameterizedTypeReference<
              List<de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto>>() {};

  /**
   * Response type for the cached material catalog lookup ({@code /api/v1/materials/lookup}) that
   * feeds every inventory view's material filter and the create form's material dropdown.
   */
  private static final ParameterizedTypeReference<
          List<de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto>>
      MATERIAL_REFERENCE_LIST =
          new ParameterizedTypeReference<
              List<de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto>>() {};

  /**
   * Response type for the cached location catalog lookup ({@code /api/v1/locations/lookup}) that
   * feeds the create form's location-picker seed (the list views' Umbuchen location picker searches
   * server-side instead).
   */
  private static final ParameterizedTypeReference<
          List<de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto>>
      LOCATION_REFERENCE_LIST =
          new ParameterizedTypeReference<
              List<de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto>>() {};

  /**
   * Response type for the active-job-order lookup ({@code /api/v1/orders/lookup}) that populates
   * the job-order filter and the inline item-to-order re-assignment dropdowns.
   */
  private static final ParameterizedTypeReference<
          List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto>>
      JOB_ORDER_REFERENCE_LIST =
          new ParameterizedTypeReference<
              List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto>>() {};

  /**
   * Response type for the mission lookup ({@code /api/v1/missions/lookup}) that populates the
   * mission filter and the per-entry mission-association dropdowns.
   */
  private static final ParameterizedTypeReference<
          List<de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto>>
      MISSION_REFERENCE_LIST =
          new ParameterizedTypeReference<
              List<de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto>>() {};

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

  private final BackendApiClient backendApiClient;

  /**
   * Writes the check-in picker's per-order need figures into the page's {@code data-order-needs}
   * attribute (REQ-INV-039). A {@code static} instance — writes are thread-safe and the
   * server-rendered frontend context registers no shared {@code ObjectMapper} bean (the same
   * rationale {@code RefineryImportProxyController} states for its own).
   */
  private static final ObjectMapper NEEDS_MAPPER = JsonMapper.builder().build();

  private final ParallelPageLoader parallelPageLoader;

  /** Resolves ADMIN/OFFICER onto LOGISTICIAN so the gate is asked, not enumerated. */
  private final org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy;

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
    List<AggregatedInventoryDto> aggregated = new ArrayList<>();
    try {
      StringBuilder uri = new StringBuilder("/api/v1/inventory/aggregated?");
      if (page != null) {
        uri.append("page=").append(page).append("&");
      }
      if (size != null) {
        uri.append("size=").append(size).append("&");
      }
      if (itemsView) {
        uri.append("catalog=ITEM");
      } else {
        uri.append("sort=material.name,asc;quality,desc;amount,desc");
      }

      PageResponse<AggregatedInventoryDto> p =
          backendApiClient.get(uri.toString(), AGGREGATED_INVENTORY_PAGE);
      if (p != null) {
        if (p.content() != null) {
          aggregated = new ArrayList<>(p.content());
        }
        model.addAttribute("inventoryPage", p);
      }
    } catch (Exception e) {
      log.error("Failed to fetch aggregated inventory", e);
      model.addAttribute("error", "error.inventory.aggregate.load");
    }

    List<de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto> materials =
        itemsView ? List.of() : fetchMaterials();

    model.addAttribute("view", itemsView ? "items" : "material");
    model.addAttribute("aggregated", aggregated);
    model.addAttribute("materials", materials);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory-index :: inventoryResults";
    }
    return "inventory-index";
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
    List<InventoryItemDto> items = new ArrayList<>();
    try {
      PageResponse<InventoryItemDto> p =
          fetchDrilldownPage(
              "/api/v1/inventory/material/" + materialId, effectivePage, effectiveSize);
      if (p != null) {
        if (p.content() != null) {
          items = new ArrayList<>(p.content());
        }
        model.addAttribute("inventoryMaterialPage", p);
      }
    } catch (Exception e) {
      log.error("Failed to fetch material inventory", e);
      model.addAttribute("error", "error.inventory.material.load");
    }

    model.addAttribute("items", items);
    model.addAttribute("pageSizes", DRILLDOWN_PAGE_SIZES);
    model.addAttribute("selectedMaterialId", materialId);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory-material :: inventoryMaterialResults";
    }
    model.addAttribute("materials", fetchMaterials());
    return "inventory-material";
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
    List<InventoryItemDto> items = new ArrayList<>();
    try {
      PageResponse<InventoryItemDto> p =
          fetchDrilldownPage(
              "/api/v1/inventory/game-item/" + gameItemId, effectivePage, effectiveSize);
      if (p != null) {
        if (p.content() != null) {
          items = new ArrayList<>(p.content());
        }
        model.addAttribute("inventoryGameItemPage", p);
      }
    } catch (Exception e) {
      log.error("Failed to fetch game-item inventory", e);
      model.addAttribute("error", "error.inventory.gameItem.load");
    }

    model.addAttribute("items", items);
    model.addAttribute("pageSizes", DRILLDOWN_PAGE_SIZES);
    model.addAttribute("selectedGameItemId", gameItemId);
    model.addAttribute(
        "gameItemName",
        items.stream()
            .map(InventoryItemDto::gameItem)
            .filter(java.util.Objects::nonNull)
            .map(
                de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto
                    ::name)
            .findFirst()
            .orElse(null));
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory-game-item :: inventoryGameItemResults";
    }
    return "inventory-game-item";
  }

  /**
   * Fetches one page of a drilldown's rows, re-fetching the last page once when the requested page
   * lies past the end of a non-empty result (REQ-INV-033).
   *
   * @param baseUri the drilldown backend URI without the {@code ?page/size} query
   * @param requestedPage the caller's non-negative page index
   * @param size the whitelisted page size
   * @return the resolved page, or {@code null} when the backend yields no page
   */
  private PageResponse<InventoryItemDto> fetchDrilldownPage(
      @NotNull String baseUri, int requestedPage, int size) {
    PageResponse<InventoryItemDto> p =
        backendApiClient.get(
            baseUri + "?page=" + requestedPage + "&size=" + size, INVENTORY_ITEM_PAGE);
    if (p != null
        && p.totalElements() > 0
        && requestedPage > 0
        && requestedPage >= p.totalPages()) {
      int lastPage = p.totalPages() - 1;
      p =
          backendApiClient.get(
              baseUri + "?page=" + lastPage + "&size=" + size, INVENTORY_ITEM_PAGE);
    }
    return p;
  }

  /**
   * JSON proxy for the {@code remote-game-items} combobox (REQ-FE-016): searches bookable game
   * items by name via the authenticated client and returns a flat list, empty on backend failure.
   *
   * @param q the case-insensitive item-name search term; {@code null}/blank matches all
   * @return up to {@link PickerSearch#PAGE_SIZE} matching bookable game-item references, never
   *     {@code null}
   */
  @GetMapping("/item-search")
  @org.springframework.web.bind.annotation.ResponseBody
  public List<de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto>
      itemSearch(@RequestParam(required = false) String q) {
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/inventory/item-catalog")
            .queryParam("size", PickerSearch.PAGE_SIZE)
            .queryParam("sort", "name,asc")
            .toUriString();
    try {
      PageResponse<de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto>
          page = backendApiClient.get(uri + "&q={q}", GAME_ITEM_REFERENCE_PAGE, q == null ? "" : q);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception e) {
      log.error("Failed to search bookable game items", e);
      return List.of();
    }
  }

  /**
   * Renders the personal inventory list ({@code /inventory/my}) with URL-driven filters. {@code
   * view=items} shows the game-item tree (REQ-INV-030); filter options list only items and
   * locations in stock in the caller's scope (REQ-INV-040).
   *
   * @param view {@code "items"} for the game-item view, anything else (or absent) for material
   * @param materialIds optional material id filter (multi; material view only)
   * @param minQuality optional minimum-quality filter (material view only)
   * @param jobOrderIds optional job-order id filter (multi; both views)
   * @param missionIds optional mission id filter (multi; material view only)
   * @param gameItemIds optional game-item id filter (multi; items view only)
   * @param locationIds optional storage-location id filter (multi; both views, REQ-INV-040)
   * @param personalOnly when true, show only the caller's personal entries
   * @param nonPersonalOnly when true, show only the caller's shared entries; mutually exclusive
   *     with {@code personalOnly}
   * @param fragment when true, return the {@code inventoryTableFragment} fragment
   * @param model model populated with grouped items, filter catalogs and auth-derived UX flags
   * @return either the full {@code inventory-my} view or its table fragment
   */
  @NotNull
  @GetMapping("/my")
  public String viewMyInventory(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
      @RequestParam(required = false, defaultValue = "false") boolean nonPersonalOnly,
      @RequestParam(required = false, defaultValue = "false") boolean fragment,
      Model model) {
    if (!model.containsAttribute("inventoryForm")) {
      model.addAttribute("inventoryForm", new InventoryForm());
    }
    if (!model.containsAttribute("inventoryBookOutForm")) {
      model.addAttribute(
          "inventoryBookOutForm",
          new de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm());
    }
    boolean itemsView = isItemsView(view);
    model.addAttribute("view", itemsView ? "items" : "material");

    if (itemsView) {
      List<GroupedInventoryDto> groupedItems = new ArrayList<>();
      try {
        List<GroupedInventoryDto> res =
            fetchGroupedItemInventory(
                "/api/v1/inventory/my-inventory/grouped",
                gameItemIds,
                locationIds,
                jobOrderIds,
                personalOnly,
                nonPersonalOnly);
        if (res != null) {
          groupedItems = res;
        }
      } catch (Exception e) {
        log.error("Failed to fetch my grouped item inventory", e);
        model.addAttribute("error", "error.inventory.personal.load");
      }
      model.addAttribute("groupedItems", groupedItems);
      model.addAttribute("items", new ArrayList<>());
      model.addAttribute(
          "gameItems",
          resolveGameItemFilterOptions(
              "/api/v1/inventory/my-inventory/grouped",
              groupedItems,
              fragment,
              gameItemIds,
              jobOrderIds,
              personalOnly || nonPersonalOnly));
      model.addAttribute(
          "locations",
          resolveLocationFilterOptions(
              groupedItems,
              fragment,
              anyItemFilterActive(
                  gameItemIds, locationIds, jobOrderIds, personalOnly || nonPersonalOnly),
              () ->
                  fetchGroupedItemInventory(
                      "/api/v1/inventory/my-inventory/grouped", null, null, null, false, false)));
      model.addAttribute("jobOrders", fetchActiveJobOrders());
      model.addAttribute("users", fetchUsers());
      model.addAttribute("selectedGameItemIds", gameItemIds);
      model.addAttribute("selectedLocationIds", locationIds);
      model.addAttribute("selectedJobOrderIds", jobOrderIds);
      model.addAttribute("selectedPersonalOnly", personalOnly);
      model.addAttribute("selectedNonPersonalOnly", nonPersonalOnly);
      model.addAttribute("authUserId", currentAuthName());
      model.addAttribute("canEditForeignNotes", hasLogisticianOrAbove());
      if (fragment) {
        return "inventory-my :: inventoryTableFragment";
      }
      return "inventory-my";
    }

    List<GroupedInventoryDto> groupedItems = new ArrayList<>();
    try {
      List<GroupedInventoryDto> res =
          fetchGroupedMaterialInventory(
              "/api/v1/inventory/my-inventory/grouped",
              materialIds,
              locationIds,
              minQuality,
              jobOrderIds,
              missionIds,
              personalOnly,
              nonPersonalOnly);
      if (res != null) {
        groupedItems = res;
      }
    } catch (Exception e) {
      log.error("Failed to fetch my grouped inventory", e);
      model.addAttribute("error", "error.inventory.personal.load");
    }

    model.addAttribute("groupedItems", groupedItems);
    model.addAttribute("items", new ArrayList<>());
    model.addAttribute("materials", fetchMaterials());
    model.addAttribute(
        "locations",
        resolveLocationFilterOptions(
            groupedItems,
            fragment,
            anyMaterialFilterActive(
                materialIds,
                locationIds,
                minQuality,
                jobOrderIds,
                missionIds,
                personalOnly || nonPersonalOnly),
            () ->
                fetchGroupedMaterialInventory(
                    "/api/v1/inventory/my-inventory/grouped",
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false)));
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    model.addAttribute("missions", fetchMissions());
    model.addAttribute("users", fetchUsers());
    model.addAttribute("selectedMaterialIds", materialIds);
    model.addAttribute("selectedLocationIds", locationIds);
    model.addAttribute("selectedMinQuality", minQuality);
    model.addAttribute("selectedJobOrderIds", jobOrderIds);
    model.addAttribute("selectedMissionIds", missionIds);
    model.addAttribute("selectedPersonalOnly", personalOnly);
    model.addAttribute("selectedNonPersonalOnly", nonPersonalOnly);
    model.addAttribute("authUserId", currentAuthName());
    model.addAttribute("canEditForeignNotes", hasLogisticianOrAbove());

    if (fragment) {
      return "inventory-my :: inventoryTableFragment";
    }
    return "inventory-my";
  }

  /**
   * Returns the ids of every own inventory entry matching the current {@code /inventory/my} filter
   * and view, backing "Alle markieren" (REQ-INV-034).
   *
   * @param view {@code "items"} for the game-item view, anything else (or absent) for material
   * @param materialIds optional material id filter (multi; material view only)
   * @param minQuality optional minimum-quality filter (material view only)
   * @param jobOrderIds optional job-order id filter (multi; both views)
   * @param missionIds optional mission id filter (multi; material view only)
   * @param gameItemIds optional game-item id filter (multi; items view only)
   * @param locationIds optional storage-location id filter (multi; both views, REQ-INV-040)
   * @param personalOnly when true, restrict to the caller's personal entries
   * @param nonPersonalOnly when true, restrict to the caller's shared entries (mutually exclusive
   *     with {@code personalOnly})
   * @return the ids of every matching entry, in creation order; never {@code null}
   */
  @GetMapping("/my/entry-ids")
  @org.springframework.web.bind.annotation.ResponseBody
  public List<UUID> myEntryIds(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
      @RequestParam(required = false, defaultValue = "false") boolean nonPersonalOnly) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
            "/api/v1/inventory/my-inventory/entry-ids");
    if (isItemsView(view)) {
      uriBuilder.queryParam("catalog", "ITEM");
      appendIdParams(uriBuilder, "gameItemIds", gameItemIds);
      appendIdParams(uriBuilder, "jobOrderIds", jobOrderIds);
    } else {
      appendIdParams(uriBuilder, "materialIds", materialIds);
      if (minQuality != null) {
        uriBuilder.queryParam("minQuality", minQuality);
      }
      appendIdParams(uriBuilder, "jobOrderIds", jobOrderIds);
      appendIdParams(uriBuilder, "missionIds", missionIds);
    }
    appendIdParams(uriBuilder, "locationIds", locationIds);
    if (personalOnly) {
      uriBuilder.queryParam("personalOnly", true);
    }
    if (nonPersonalOnly) {
      uriBuilder.queryParam("nonPersonalOnly", true);
    }
    List<UUID> ids = backendApiClient.get(uriBuilder.build().toUriString(), UUID_LIST);
    return ids != null ? ids : List.of();
  }

  /**
   * Fetches one grouped item-inventory result ({@code catalog=ITEM}, REQ-INV-030) with the item
   * view's filters; quality and mission filters are never sent.
   *
   * @param basePath the backend grouped path ({@code …/my-inventory/grouped} or {@code
   *     …/all/grouped})
   * @param gameItemIds optional game-item filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param jobOrderIds optional job-order filter
   * @param personalOnly relay {@code personalOnly=true} (only meaningful on {@code /my})
   * @param nonPersonalOnly relay {@code nonPersonalOnly=true} (only meaningful on {@code /my})
   * @return the grouped result as returned by the backend, may be {@code null}
   */
  private List<GroupedInventoryDto> fetchGroupedItemInventory(
      @NotNull String basePath,
      List<UUID> gameItemIds,
      List<UUID> locationIds,
      List<UUID> jobOrderIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(basePath)
            .queryParam("catalog", "ITEM");
    appendIdParams(uriBuilder, "gameItemIds", gameItemIds);
    appendIdParams(uriBuilder, "locationIds", locationIds);
    appendIdParams(uriBuilder, "jobOrderIds", jobOrderIds);
    if (personalOnly) {
      uriBuilder.queryParam("personalOnly", true);
    }
    if (nonPersonalOnly) {
      uriBuilder.queryParam("nonPersonalOnly", true);
    }
    return backendApiClient.get(uriBuilder.build().toUriString(), GROUPED_INVENTORY_LIST);
  }

  /**
   * Appends one repeated query parameter per id; a {@code null} or empty list appends nothing.
   *
   * @param uriBuilder the builder collecting the backend request URI
   * @param name the query-parameter name to repeat
   * @param ids the ids to append; {@code null} or empty appends nothing
   */
  private static void appendIdParams(
      @NotNull org.springframework.web.util.UriComponentsBuilder uriBuilder,
      @NotNull String name,
      List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    for (UUID id : ids) {
      uriBuilder.queryParam(name, id.toString());
    }
  }

  /**
   * Fetches one grouped material-inventory result with the material view's filters; used for both
   * the table read and the unfiltered read behind {@link #resolveLocationFilterOptions}.
   *
   * @param basePath the backend grouped path ({@code …/my-inventory/grouped} or {@code
   *     …/all/grouped})
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param minQuality optional quality floor
   * @param jobOrderIds optional job-order filter
   * @param missionIds optional mission filter
   * @param personalOnly relay {@code personalOnly=true} (only meaningful on {@code /my})
   * @param nonPersonalOnly relay {@code nonPersonalOnly=true} (only meaningful on {@code /my})
   * @return the grouped result as returned by the backend, may be {@code null}
   */
  private List<GroupedInventoryDto> fetchGroupedMaterialInventory(
      @NotNull String basePath,
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(basePath);
    appendIdParams(uriBuilder, "materialIds", materialIds);
    appendIdParams(uriBuilder, "locationIds", locationIds);
    if (minQuality != null) {
      uriBuilder.queryParam("minQuality", minQuality);
    }
    appendIdParams(uriBuilder, "jobOrderIds", jobOrderIds);
    appendIdParams(uriBuilder, "missionIds", missionIds);
    if (personalOnly) {
      uriBuilder.queryParam("personalOnly", true);
    }
    if (nonPersonalOnly) {
      uriBuilder.queryParam("nonPersonalOnly", true);
    }
    return backendApiClient.get(uriBuilder.build().toUriString(), GROUPED_INVENTORY_LIST);
  }

  /**
   * Whether any material-view filter dimension narrows the grouped result.
   *
   * @param materialIds the active material filter, if any
   * @param locationIds the active location filter, if any
   * @param minQuality the active quality floor, if any
   * @param jobOrderIds the active job-order filter, if any
   * @param missionIds the active mission filter, if any
   * @param personalFlagActive whether a personal/non-personal narrowing flag is set
   * @return {@code true} when at least one dimension narrows the result
   */
  private static boolean anyMaterialFilterActive(
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      boolean personalFlagActive) {
    return notEmpty(materialIds)
        || notEmpty(locationIds)
        || minQuality != null
        || notEmpty(jobOrderIds)
        || notEmpty(missionIds)
        || personalFlagActive;
  }

  /**
   * Item-view sibling of {@link #anyMaterialFilterActive}, without quality and mission dimensions.
   *
   * @param gameItemIds the active game-item filter, if any
   * @param locationIds the active location filter, if any
   * @param jobOrderIds the active job-order filter, if any
   * @param personalFlagActive whether a personal/non-personal narrowing flag is set
   * @return {@code true} when at least one dimension narrows the result
   */
  private static boolean anyItemFilterActive(
      List<UUID> gameItemIds,
      List<UUID> locationIds,
      List<UUID> jobOrderIds,
      boolean personalFlagActive) {
    return notEmpty(gameItemIds)
        || notEmpty(locationIds)
        || notEmpty(jobOrderIds)
        || personalFlagActive;
  }

  /**
   * Whether an id filter list actually narrows anything — a {@code null} or empty list does not.
   *
   * @param ids the filter list to test
   * @return {@code true} when the list holds at least one id
   */
  private static boolean notEmpty(List<UUID> ids) {
    return ids != null && !ids.isEmpty();
  }

  /**
   * Resolves the location filter options (REQ-INV-040): only locations with stock in the viewer's
   * scope. With an active filter, one extra unfiltered read supplies the full option list; fragment
   * renders need none, and a failed read falls back to the displayed groups.
   *
   * @param groupedItems the (possibly filtered) grouped result already fetched for the table
   * @param fragment whether this render is the table-fragment swap
   * @param anyFilterActive whether any filter dimension is narrowing the fetched result
   * @param unfilteredFetch supplies the unfiltered grouped result for this page and view
   * @return the distinct in-scope locations to offer, ordered by name
   */
  private List<de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto>
      resolveLocationFilterOptions(
          @NotNull List<GroupedInventoryDto> groupedItems,
          boolean fragment,
          boolean anyFilterActive,
          @NotNull java.util.function.Supplier<List<GroupedInventoryDto>> unfilteredFetch) {
    List<GroupedInventoryDto> source = groupedItems;
    if (!fragment && anyFilterActive) {
      try {
        List<GroupedInventoryDto> unfiltered = unfilteredFetch.get();
        if (unfiltered != null) {
          source = unfiltered;
        }
      } catch (Exception e) {
        log.warn("Failed to fetch unfiltered groups for the location filter options", e);
      }
    }
    return source.stream()
        .filter(group -> group.stacks() != null)
        .flatMap(group -> group.stacks().stream())
        .map(de.greluc.krt.profit.basetool.frontend.model.dto.InventoryStackDto::location)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted(
            java.util.Comparator.comparing(
                de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto::name,
                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
        .toList();
  }

  /**
   * Resolves the item view's gameItem filter options (REQ-INV-030): only items with stock in the
   * viewer's scope. With an active filter, one extra unfiltered read supplies the full option list;
   * fragment renders need none, and a failed read falls back to the displayed groups.
   *
   * @param basePath the backend grouped path the page's table was fetched from
   * @param groupedItems the (possibly filtered) grouped result already fetched for the table
   * @param fragment whether this render is the table-fragment swap
   * @param gameItemIds the active gameItem filter, if any
   * @param jobOrderIds the active job-order filter, if any
   * @param personalFlagActive whether a personal/non-personal narrowing flag is active
   * @return the distinct gameItem references to offer in the filter multi-select
   */
  private List<de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto>
      resolveGameItemFilterOptions(
          @NotNull String basePath,
          @NotNull List<GroupedInventoryDto> groupedItems,
          boolean fragment,
          List<UUID> gameItemIds,
          List<UUID> jobOrderIds,
          boolean personalFlagActive) {
    boolean anyFilterActive =
        (gameItemIds != null && !gameItemIds.isEmpty())
            || (jobOrderIds != null && !jobOrderIds.isEmpty())
            || personalFlagActive;
    List<GroupedInventoryDto> source = groupedItems;
    if (!fragment && anyFilterActive) {
      try {
        List<GroupedInventoryDto> unfiltered =
            fetchGroupedItemInventory(basePath, null, null, null, false, false);
        if (unfiltered != null) {
          source = unfiltered;
        }
      } catch (Exception e) {
        log.warn("Failed to fetch unfiltered item groups for the gameItem filter options", e);
      }
    }
    return source.stream()
        .map(GroupedInventoryDto::gameItem)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }

  /**
   * Renders the squadron-wide inventory list ({@code /inventory/all}), like {@link
   * #viewMyInventory} but over all users in scope and without the personal flags.
   *
   * @param view {@code "items"} for the game-item view, anything else (or absent) for material
   * @param materialIds optional material id filter (multi; material view only)
   * @param minQuality optional minimum-quality filter (material view only)
   * @param jobOrderIds optional job-order id filter (multi; both views)
   * @param missionIds optional mission id filter (multi; material view only)
   * @param gameItemIds optional game-item id filter (multi; items view only)
   * @param locationIds optional storage-location id filter (multi; both views, REQ-INV-040)
   * @param fragment when true, return the table fragment
   * @return either the full {@code inventory-admin} view or its fragment
   */
  @NotNull
  @GetMapping("/all")
  public String viewAllInventory(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false, defaultValue = "false") boolean fragment,
      Model model) {
    if (!model.containsAttribute("inventoryForm")) {
      model.addAttribute("inventoryForm", new InventoryForm());
    }
    if (!model.containsAttribute("inventoryBookOutForm")) {
      model.addAttribute(
          "inventoryBookOutForm",
          new de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm());
    }
    boolean itemsView = isItemsView(view);
    model.addAttribute("view", itemsView ? "items" : "material");

    if (itemsView) {
      List<GroupedInventoryDto> groupedItems = new ArrayList<>();
      try {
        List<GroupedInventoryDto> res =
            fetchGroupedItemInventory(
                "/api/v1/inventory/all/grouped",
                gameItemIds,
                locationIds,
                jobOrderIds,
                false,
                false);
        if (res != null) {
          groupedItems = res;
        }
      } catch (Exception e) {
        log.error("Failed to fetch all grouped item inventory", e);
        model.addAttribute("error", "error.inventory.global.load");
      }
      model.addAttribute("groupedItems", groupedItems);
      model.addAttribute("items", new ArrayList<>());
      model.addAttribute(
          "gameItems",
          resolveGameItemFilterOptions(
              "/api/v1/inventory/all/grouped",
              groupedItems,
              fragment,
              gameItemIds,
              jobOrderIds,
              false));
      model.addAttribute(
          "locations",
          resolveLocationFilterOptions(
              groupedItems,
              fragment,
              anyItemFilterActive(gameItemIds, locationIds, jobOrderIds, false),
              () ->
                  fetchGroupedItemInventory(
                      "/api/v1/inventory/all/grouped", null, null, null, false, false)));
      model.addAttribute("jobOrders", fetchActiveJobOrders());
      model.addAttribute("selectedGameItemIds", gameItemIds);
      model.addAttribute("selectedLocationIds", locationIds);
      model.addAttribute("selectedJobOrderIds", jobOrderIds);
      model.addAttribute("authUserId", currentAuthName());
      model.addAttribute("canEditForeignNotes", hasLogisticianOrAbove());
      if (fragment) {
        return "inventory-admin :: inventoryTableFragment";
      }
      return "inventory-admin";
    }

    List<GroupedInventoryDto> groupedItems = new ArrayList<>();
    try {
      List<GroupedInventoryDto> res =
          fetchGroupedMaterialInventory(
              "/api/v1/inventory/all/grouped",
              materialIds,
              locationIds,
              minQuality,
              jobOrderIds,
              missionIds,
              false,
              false);
      if (res != null) {
        groupedItems = res;
      }
    } catch (Exception e) {
      log.error("Failed to fetch all grouped inventory", e);
      model.addAttribute("error", "error.inventory.global.load");
    }

    model.addAttribute("groupedItems", groupedItems);
    model.addAttribute("items", new ArrayList<>());
    model.addAttribute("materials", fetchMaterials());
    model.addAttribute(
        "locations",
        resolveLocationFilterOptions(
            groupedItems,
            fragment,
            anyMaterialFilterActive(
                materialIds, locationIds, minQuality, jobOrderIds, missionIds, false),
            () ->
                fetchGroupedMaterialInventory(
                    "/api/v1/inventory/all/grouped", null, null, null, null, null, false, false)));
    model.addAttribute("selectedMaterialIds", materialIds);
    model.addAttribute("selectedLocationIds", locationIds);
    model.addAttribute("selectedMinQuality", minQuality);
    model.addAttribute("selectedJobOrderIds", jobOrderIds);
    model.addAttribute("selectedMissionIds", missionIds);
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    model.addAttribute("missions", fetchMissions());
    model.addAttribute("authUserId", currentAuthName());
    model.addAttribute("canEditForeignNotes", hasLogisticianOrAbove());

    if (fragment) {
      return "inventory-admin :: inventoryTableFragment";
    }
    return "inventory-admin";
  }

  /**
   * Renders one page of a personal Lager stack's entries, oldest first, as the AJAX drill-down on
   * {@code /inventory/my}. The stack is addressed by its stock-identity params; a {@code null}
   * owning org unit selects rows without one.
   *
   * @param materialId the stack's material
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack holds the caller's private stock
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
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/v1/inventory/my-inventory/stack/entries")
            .queryParam("materialId", materialId)
            .queryParam("locationId", locationId)
            .queryParam("personal", personal);
    if (quality != null) {
      uriBuilder.queryParam("quality", quality);
    }
    if (owningOrgUnitId != null) {
      uriBuilder.queryParam("owningOrgUnitId", owningOrgUnitId);
    }
    if (page != null) {
      uriBuilder.queryParam("page", page);
    }
    if (size != null) {
      uriBuilder.queryParam("size", size);
    }
    fetchStackEntriesIntoModel(uriBuilder.build().toUriString(), model, true);
    addReleasedItemIds(model);
    return "fragments/inventory-stack-entries :: stackEntriesMy";
  }

  /**
   * Item-view sibling of {@link #viewMyStackEntries} (REQ-INV-030): renders one page of a personal
   * game-item stack's entries, addressed by {@code gameItemId}, without a mission catalog.
   *
   * @param gameItemId the stack's game item
   * @param locationId the stack's storage location
   * @param personal whether the stack holds the caller's private stock
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
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/v1/inventory/my-inventory/stack/entries")
            .queryParam("catalog", "ITEM")
            .queryParam("gameItemId", gameItemId)
            .queryParam("locationId", locationId)
            .queryParam("personal", personal);
    if (owningOrgUnitId != null) {
      uriBuilder.queryParam("owningOrgUnitId", owningOrgUnitId);
    }
    if (page != null) {
      uriBuilder.queryParam("page", page);
    }
    if (size != null) {
      uriBuilder.queryParam("size", size);
    }
    fetchStackEntriesIntoModel(uriBuilder.build().toUriString(), model, false);
    addReleasedItemIds(model);
    return "fragments/inventory-stack-entries :: stackEntriesMy";
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
      org.springframework.web.util.UriComponentsBuilder uri =
          org.springframework.web.util.UriComponentsBuilder.fromPath(
              "/api/v1/material-exchange/released-item-ids");
      boolean any = false;
      for (Object entry : entries) {
        if (entry instanceof InventoryItemDto item && item.id() != null) {
          uri.queryParam("ids", item.id());
          any = true;
        }
      }
      if (any) {
        try {
          List<UUID> result = backendApiClient.get(uri.build().toUriString(), UUID_LIST);
          if (result != null) {
            released.addAll(result);
          }
        } catch (Exception e) {
          log.error("Failed to load Materialbörse released-item ids", e);
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
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/v1/inventory/all/stack/entries")
            .queryParam("materialId", materialId)
            .queryParam("userId", userId)
            .queryParam("locationId", locationId);
    if (quality != null) {
      uriBuilder.queryParam("quality", quality);
    }
    if (owningOrgUnitId != null) {
      uriBuilder.queryParam("owningOrgUnitId", owningOrgUnitId);
    }
    if (page != null) {
      uriBuilder.queryParam("page", page);
    }
    if (size != null) {
      uriBuilder.queryParam("size", size);
    }
    fetchStackEntriesIntoModel(uriBuilder.build().toUriString(), model, true);
    return "fragments/inventory-stack-entries :: stackEntriesAdmin";
  }

  /**
   * Item-view sibling of {@link #viewAllStackEntries} (REQ-INV-030): renders one page of a
   * squadron-wide game-item stack's entries, without a mission catalog.
   *
   * @param gameItemId the stack's game item
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
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
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      Model model) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/v1/inventory/all/stack/entries")
            .queryParam("catalog", "ITEM")
            .queryParam("gameItemId", gameItemId)
            .queryParam("userId", userId)
            .queryParam("locationId", locationId);
    if (owningOrgUnitId != null) {
      uriBuilder.queryParam("owningOrgUnitId", owningOrgUnitId);
    }
    if (page != null) {
      uriBuilder.queryParam("page", page);
    }
    if (size != null) {
      uriBuilder.queryParam("size", size);
    }
    fetchStackEntriesIntoModel(uriBuilder.build().toUriString(), model, false);
    return "fragments/inventory-stack-entries :: stackEntriesAdmin";
  }

  /**
   * Fetches a stack-entries page and the job-order / mission catalogs into the model; a backend
   * failure yields an empty list plus an {@code error} flag.
   *
   * @param uri the fully-built backend stack-entries URI (path + query)
   * @param model the model to populate with {@code entries}, {@code entriesPage}, {@code jobOrders}
   *     and {@code missions}
   * @param includeMissions whether to load the mission catalog; {@code false} yields an empty list
   *     (REQ-INV-031)
   */
  private void fetchStackEntriesIntoModel(
      @NotNull String uri, Model model, boolean includeMissions) {
    PageResponse<InventoryItemDto> p = null;
    try {
      p = backendApiClient.get(uri, INVENTORY_ITEM_PAGE);
    } catch (Exception e) {
      log.error("Failed to fetch stack entries", e);
      model.addAttribute("error", "inventory.stack.entries.error");
    }
    List<InventoryItemDto> entries =
        (p != null && p.content() != null) ? new ArrayList<>(p.content()) : new ArrayList<>();
    model.addAttribute("entries", entries);
    model.addAttribute("entriesPage", p);
    List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> stackJobOrders =
        fetchActiveJobOrders(true);
    model.addAttribute("jobOrders", stackJobOrders);
    model.addAttribute("orderNeedAmounts", orderNeedAmounts(stackJobOrders));
    model.addAttribute("orderGameItemNeedAmounts", orderGameItemNeedAmounts(stackJobOrders));
    model.addAttribute(
        "missions",
        includeMissions
            ? fetchMissions()
            : List.<de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto>of());
  }

  /**
   * Renders the inventory create form; {@code source=admin} seeds {@code isGlobal=true} so the
   * admin can pick a target user, otherwise the entry is the caller's own.
   *
   * @param source optional origin marker ({@code admin}, {@code my}, {@code aggregated}) used to
   *     pick the post-save redirect target
   * @param model model populated with the form and dropdown catalogs
   * @return the {@code inventory-input} view name
   */
  @NotNull
  @GetMapping("/input")
  public String viewInputPage(@RequestParam(required = false) String source, Model model) {
    InventoryForm form;
    if (!model.containsAttribute("inventoryForm")) {
      form = new InventoryForm();
      if ("admin".equals(source)) {
        form.setIsGlobal(true);
      } else {
        form.setIsGlobal(false);
      }
      form.setSource(source);
      model.addAttribute("inventoryForm", form);
    } else {
      form = (InventoryForm) model.getAttribute("inventoryForm");
    }

    final InventoryForm boundForm = form;
    var materialsFuture = parallelPageLoader.loadAsync(this::fetchMaterials);
    var locationsFuture = parallelPageLoader.loadAsync(this::fetchLocations);
    var missionsFuture = parallelPageLoader.loadAsync(this::fetchMissions);
    var jobOrdersFuture = parallelPageLoader.loadAsync(() -> fetchActiveJobOrders(true));
    var ownerFuture = parallelPageLoader.loadAsync(() -> fetchOwnerPickerOptions(boundForm));
    var selectedUserFuture = parallelPageLoader.loadAsync(() -> fetchSelectedInputUser(boundForm));
    CompletableFuture.allOf(
            materialsFuture,
            locationsFuture,
            missionsFuture,
            jobOrdersFuture,
            ownerFuture,
            selectedUserFuture)
        .join();
    model.addAttribute("materials", materialsFuture.join());
    model.addAttribute("locations", locationsFuture.join());
    model.addAttribute("missions", missionsFuture.join());
    List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> inputJobOrders =
        jobOrdersFuture.join();
    model.addAttribute("jobOrders", inputJobOrders);
    model.addAttribute("jobOrderNeedsJson", writeOrderNeedsJson(orderNeeds(inputJobOrders)));
    model.addAttribute("ownerOptions", ownerFuture.join());
    model.addAttribute("selectedUser", selectedUserFuture.join());
    return "inventory-input";
  }

  /**
   * Returns the active-order catalog with its per-material needs as JSON, for the Einbuchen form's
   * live-sync relabelling of its order options (REQ-INV-039, REQ-FE-010).
   *
   * @return the active orders with their needs; an empty list when the lookup fails
   */
  @NotNull
  @ResponseBody
  @GetMapping(value = "/order-needs", headers = "X-Requested-With=XMLHttpRequest")
  public Map<String, Object> orderNeedsAjax() {
    return orderNeeds(fetchActiveJobOrders(true));
  }

  /**
   * Flattens the order needs into an outstanding-amount map keyed {@code "<orderId>|<materialId>"}
   * for the allocation popover (REQ-INV-039). Quality buckets of one material are summed.
   *
   * @param orders the loaded order catalog, already carrying its needs.
   * @return outstanding amount by {@code orderId|materialId}; only entries still needed.
   */
  @NotNull
  private static Map<String, Double> orderNeedAmounts(
      List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> orders) {
    Map<String, Double> amounts = new LinkedHashMap<>();
    for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto order : orders) {
      if (order.id() == null || order.materialNeeds() == null) {
        continue;
      }
      for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialNeedDto need :
          order.materialNeeds()) {
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
   * Reduces the order catalog to one envelope of order-id-keyed material and game-item needs;
   * orders needing nothing in a dimension are omitted from that map.
   *
   * @param orders the loaded order catalog, already carrying its needs.
   * @return {@code {"materials": {orderId: [...]}, "gameItems": {orderId: [...]}}}, never {@code
   *     null}.
   */
  @NotNull
  private static Map<String, Object> orderNeeds(
      List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> orders) {
    Map<UUID, List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialNeedDto>>
        materials = new LinkedHashMap<>();
    Map<UUID, List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderGameItemNeedDto>>
        gameItems = new LinkedHashMap<>();
    for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto order : orders) {
      if (order.id() == null) {
        continue;
      }
      if (order.materialNeeds() != null && !order.materialNeeds().isEmpty()) {
        materials.put(order.id(), order.materialNeeds());
      }
      if (order.gameItemNeeds() != null && !order.gameItemNeeds().isEmpty()) {
        gameItems.put(order.id(), order.gameItemNeeds());
      }
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("materials", materials);
    envelope.put("gameItems", gameItems);
    return envelope;
  }

  /**
   * Item sibling of {@link #orderNeedAmounts}: outstanding whole units keyed {@code
   * "<orderId>|<gameItemId>"} (REQ-INV-039).
   *
   * @param orders the loaded order catalog, already carrying its needs.
   * @return outstanding whole units by {@code orderId|gameItemId}; only entries still needed.
   */
  @NotNull
  private static Map<String, Integer> orderGameItemNeedAmounts(
      List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> orders) {
    Map<String, Integer> amounts = new LinkedHashMap<>();
    for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto order : orders) {
      if (order.id() == null || order.gameItemNeeds() == null) {
        continue;
      }
      for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderGameItemNeedDto need :
          order.gameItemNeeds()) {
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

  /**
   * Serialises the need map for the page's {@code data-order-needs} attribute.
   *
   * @param needs the needs by order id.
   * @return the JSON object, or {@code "{}"} when it cannot be written.
   */
  private String writeOrderNeedsJson(Map<String, Object> needs) {
    try {
      return NEEDS_MAPPER.writeValueAsString(needs);
    } catch (Exception e) {
      log.warn("Failed to serialise job-order need figures for the check-in picker", e);
      return "{}";
    }
  }

  /**
   * Resolves the admin-chosen target user for the input form's user-picker seed option.
   *
   * @param form the inbound inventory form; may be {@code null} before binding.
   * @return the selected user DTO, or {@code null} when not a global entry, none chosen, or the
   *     lookup fails.
   */
  @Nullable
  private de.greluc.krt.profit.basetool.frontend.model.dto.UserDto fetchSelectedInputUser(
      InventoryForm form) {
    if (form == null || !Boolean.TRUE.equals(form.getIsGlobal()) || form.getUserId() == null) {
      return null;
    }
    try {
      return backendApiClient.get(
          "/api/v1/users/" + form.getUserId(),
          de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class);
    } catch (Exception e) {
      log.warn(
          "Failed to resolve selected user {} for inventory-input picker seed",
          form.getUserId(),
          e);
      return null;
    }
  }

  /**
   * Resolves the owner-picker options on the inventory-input form from the memberships of the
   * form's {@code userId} for an admin global entry, otherwise of the caller.
   *
   * @param form the inbound inventory form (may be {@code null} before binding).
   * @return picker options, or an empty list on failure; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchOwnerPickerOptions(InventoryForm form) {
    if (form != null && Boolean.TRUE.equals(form.getIsGlobal()) && form.getUserId() != null) {
      try {
        List<OrgUnitMembershipOptionDto> options =
            backendApiClient.get(
                "/api/v1/users/" + form.getUserId() + "/memberships",
                ORG_UNIT_MEMBERSHIP_OPTION_LIST);
        return options != null ? options : List.of();
      } catch (Exception e) {
        log.warn("Failed to fetch memberships for owner-picker", e);
        return List.of();
      }
    }
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get(
              "/api/v1/users/me/pickable-org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch pickable org units for owner-picker", e);
      return List.of();
    }
  }

  private List<de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto> fetchUsers() {
    try {
      List<de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto> content =
          backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST);
      if (content != null) {
        return content;
      }
    } catch (Exception e) {
      log.warn("Failed to fetch users (might not be an admin/officer)");
    }
    return new ArrayList<>();
  }

  @NotNull
  private List<de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto>
      fetchMaterials() {
    List<de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto> materials =
        new ArrayList<>();
    try {
      List<de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto> content =
          backendApiClient.getCached(CachedCatalog.MATERIALS_LOOKUP, MATERIAL_REFERENCE_LIST);
      if (content != null) {
        materials.addAll(content);
      }
    } catch (Exception e) {
      log.error("Failed to fetch materials", e);
    }
    return materials;
  }

  @NotNull
  private List<de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto>
      fetchLocations() {
    List<de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto> locations =
        new ArrayList<>();
    try {
      List<de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto> content =
          backendApiClient.getCached(CachedCatalog.LOCATIONS_LOOKUP, LOCATION_REFERENCE_LIST);
      if (content != null) {
        locations.addAll(content);
      }
    } catch (Exception e) {
      log.error("Failed to fetch locations", e);
    }
    return locations;
  }

  @NotNull
  private List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto>
      fetchActiveJobOrders() {
    return fetchActiveJobOrders(false);
  }

  /**
   * Loads the active-order catalog, optionally with each order's outstanding per-material need
   * (REQ-INV-039).
   *
   * @param withNeeds whether to request the per-material need figures
   * @return the active orders the caller may see; empty (never {@code null}) when the lookup fails
   */
  @NotNull
  private List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto>
      fetchActiveJobOrders(boolean withNeeds) {
    List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> orders =
        new ArrayList<>();
    try {
      List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> content =
          backendApiClient.get(
              withNeeds ? "/api/v1/orders/lookup?withNeeds=true" : "/api/v1/orders/lookup",
              JOB_ORDER_REFERENCE_LIST);
      if (content != null) {
        orders.addAll(content);
      }
    } catch (Exception e) {
      log.error("Failed to fetch active job orders", e);
    }
    return orders;
  }

  private List<de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto>
      fetchMissions() {
    try {
      List<de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto> content =
          backendApiClient.get("/api/v1/missions/lookup", MISSION_REFERENCE_LIST);
      if (content != null) {
        return content;
      }
    } catch (Exception e) {
      log.error("Failed to fetch missions", e);
    }
    return new ArrayList<>();
  }

  @Nullable
  private static String currentAuthName() {
    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    return auth != null ? auth.getName() : null;
  }

  /**
   * Whether the caller holds {@code LOGISTICIAN} or a role above it, resolved through the role
   * hierarchy (ADR-0151).
   *
   * @return {@code true} iff the caller holds Logistician or a role above it.
   */
  private boolean hasLogisticianOrAbove() {
    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (auth == null || auth.getAuthorities() == null) {
      return false;
    }
    return roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities()).stream()
        .anyMatch(a -> Roles.authority(Roles.LOGISTICIAN).equals(a.getAuthority()));
  }
}
