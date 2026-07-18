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

import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.form.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the inventory read pages ({@code /inventory}, {@code /inventory/my},
 * {@code /inventory/all}, and the {@code /inventory/input} create form).
 *
 * <p>Read views: aggregated (sum per catalog entry across the squadron), per-material and
 * per-game-item drilldowns, personal ({@code /my}), and admin-all ({@code /all}). The aggregated,
 * personal and admin views carry a Material ↔ Items switch (REQ-INV-030, {@code view=items}): the
 * material variant filters by material ids, min quality, job order and mission, the item variant by
 * gameItem ids and job order (item rows have no quality or mission dimension). All list endpoints
 * support a {@code fragment=true} flag that returns just the table fragment so AJAX filter changes
 * do not reload the page.
 *
 * <p>Since the #924 read/write controller split (L5) this class owns only the read (GET) half of
 * the area; every mutating {@code /inventory} endpoint (create, book-out consume/transfer/sell,
 * inline transfer, bulk checkout, association / note / delivered updates) lives in {@link
 * InventoryWriteController}, which delegates back here for inline validation-failure re-renders.
 */
@Controller
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
  private final ParallelPageLoader parallelPageLoader;

  /**
   * Renders the squadron-wide aggregated inventory view ({@code /inventory}). The {@code view}
   * query parameter picks the catalog (REQ-INV-030): the default Material view keeps its fixed sort
   * — material name asc, quality desc, amount desc, the order operators actually want — while
   * {@code view=items} relays {@code catalog=ITEM} to the backend (whose default sort is game-item
   * name asc, amount desc; there are no quality columns to sort by).
   *
   * @param view {@code "items"} for the game-item catalog, anything else (or absent) for material
   * @param page zero-based page index
   * @param size page size
   * @param fragment when {@code "results"}, only the results+pagination fragment is rendered for an
   *     in-place AJAX swap (epic #571 / REQ-FE-005); otherwise the full page
   * @param model Thymeleaf model populated with the page, aggregated items and material catalog
   * @return the {@code inventory-index} view name, or its {@code inventoryResults} fragment
   *     selector
   */
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
        // catalog=ITEM has its own backend sort whitelist (gameItem.name / amount) and default;
        // the material sort spec would be rejected there, so it is simply not sent.
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
   * Resolves the Lager view-switch query parameter (REQ-INV-030): {@code view=items} selects the
   * game-item catalog, every other value — including absence — falls back to the material view, so
   * existing bookmarks and the write controller's parameterless inline re-renders keep rendering
   * the historical material tree.
   *
   * @param view the raw {@code view} query parameter, may be {@code null}
   * @return {@code true} when the items view was requested
   */
  private static boolean isItemsView(String view) {
    return "items".equalsIgnoreCase(view);
  }

  /**
   * Renders the per-material drilldown ({@code /inventory/material/{materialId}}) listing the
   * individual inventory rows for the given material, paginated server-side (REQ-INV-033) so a
   * material with many rows stays fully reachable page by page instead of being silently capped
   * (ADR-0104 — the page previously fetched a single {@code size=1000} slice and hid everything
   * beyond it). The material catalog behind the "Anderes Material" switcher renders outside the
   * results fragment, so it is fetched on the full render only; a pager click or a live-sync peer
   * refresh re-fetches just the items page. The drilldown offers no inline job-order re-assignment
   * (that moved onto the Lager stack-entry allocation chips, REQ-INV-027), so it loads no job-order
   * catalog.
   *
   * @param materialId material id to drill into
   * @param page zero-based page index; {@code null} or negative falls back to the first page
   * @param size requested page size; values outside {@link #DRILLDOWN_PAGE_SIZES} snap back to
   *     {@link #DRILLDOWN_DEFAULT_PAGE_SIZE}
   * @param fragment when {@code "results"}, only the results+pagination fragment is rendered for an
   *     in-place AJAX pager swap (REQ-FE-005) or a live-sync peer refresh (REQ-FE-010, #1309)
   * @param model Thymeleaf model populated with the items page, the pagination attributes and (on
   *     the full render) the material catalog
   * @return the {@code inventory-material} view name, or its {@code inventoryMaterialResults}
   *     fragment selector
   */
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
    // REQ-FE-005/REQ-FE-010 (#1309): the pager and the live-sync receiver re-fetch just the results
    // fragment, which needs no catalog — the material switcher is full-render-only (the
    // REQ-DATA-012
    // fragment-gating rule), so the fragment path skips its cached material lookup.
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory-material :: inventoryMaterialResults";
    }
    model.addAttribute("materials", fetchMaterials());
    return "inventory-material";
  }

  /**
   * Renders the per-game-item drilldown ({@code /inventory/game-item/{gameItemId}}, REQ-INV-030) —
   * the item sibling of {@link #viewMaterialInventory}: the individual non-personal stock rows of
   * the given game item, with no quality column. Paginated server-side (REQ-INV-033) exactly like
   * the material drilldown, so an item with many rows stays fully reachable page by page instead of
   * being silently capped (ADR-0104 — the page previously fetched a single {@code size=1000}
   * slice). Unlike the material page it deliberately loads no navigate catalog (the item catalog is
   * thousands of entries; a preload is off the table and the remote search picker ships with the
   * Einbuchen pass, design §6.6). The item's display name is taken from the first row of the
   * current page's {@code gameItem} reference.
   *
   * @param gameItemId game item to drill into
   * @param page zero-based page index; {@code null} or negative falls back to the first page
   * @param size requested page size; values outside {@link #DRILLDOWN_PAGE_SIZES} snap back to
   *     {@link #DRILLDOWN_DEFAULT_PAGE_SIZE}
   * @param fragment when {@code "results"}, only the results+pagination fragment is rendered for an
   *     in-place AJAX pager swap (REQ-FE-005) or the live-sync in-place swap (REQ-FE-015)
   * @param model Thymeleaf model populated with the rows, the pagination attributes and the
   *     resolved item display name
   * @return the {@code inventory-game-item} view name, or its {@code inventoryGameItemResults}
   *     fragment selector
   */
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
   * Fetches one page of a drilldown's rows, re-fetching the last page once if the requested page
   * overran the end (REQ-INV-033). A stale deep-link / bookmark or a peer's stock reduction (the
   * page index rides in the URL and the live-sync receiver re-fetches it verbatim) can leave the
   * URL pointing past the last page; the backend then returns an empty out-of-range page whose
   * {@code totalPages == 1} hides the whole pager, stranding the viewer on an empty table even
   * though rows still exist on page 0. Clamping to the last page hands back real rows and a usable
   * pager. The extra round-trip is paid only in that rare overrun case; an in-range or
   * genuinely-empty ({@code totalElements == 0}) result returns after the single fetch.
   *
   * @param baseUri the drilldown backend URI up to (but excluding) the {@code ?page/size} query —
   *     e.g. {@code /api/v1/inventory/material/{id}}
   * @param requestedPage the caller's already-non-negative page index
   * @param size the resolved (whitelisted) page size
   * @return the resolved page — the last page when the request overran a non-empty result — or
   *     {@code null} when the backend yields no page
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
   * JSON proxy for the {@code remote-game-items} combobox source (design §6.6, REQ-FE-016): looks
   * up bookable game items — the output of at least one active blueprint — by a free-text term and
   * unwraps the backend page into a flat list. Relays with the <em>token-carrying</em> {@link
   * BackendApiClient#get} (never {@code getPublic}): the backend {@code
   * /api/v1/inventory/item-catalog} sits under the role-gated {@code /api/v1/inventory/**}
   * umbrella, unlike the deliberately anonymous {@code /orders/item-search} sibling (design §5.3).
   * The page size is capped at 50 — the combobox renders at most its {@code maxResults} default of
   * 50 rows — sorted by {@code name} (the backend's sole whitelisted sort field). An empty list on
   * backend failure keeps the picker on "no matches" instead of surfacing the error.
   *
   * @param q the case-insensitive item-name search term; {@code null}/blank matches all (the
   *     combobox's browse-mode empty fetch)
   * @return up to 50 matching bookable game-item references, never {@code null}
   */
  @GetMapping("/item-search")
  @org.springframework.web.bind.annotation.ResponseBody
  public List<de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto>
      itemSearch(@RequestParam(required = false) String q) {
    // Build the URI with only the fixed (safe) paging params via UriComponentsBuilder so a crafted
    // `&` in the term cannot inject extra query parameters (the L-1 hardening), and pass the
    // free-text `q` as a WebClient URI-template variable ({q}) so it is percent-encoded exactly
    // once across the frontend->backend hop. Baking the pre-encoded toUriString() value into
    // get(String) would let the WebClient encode it a second time (space -> %2520), so a multi-word
    // item search reached the backend mangled and matched nothing (the #371 re-encoding trap). A
    // null/blank term is normalised to the empty match-all filter.
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/inventory/item-catalog")
            .queryParam("size", 50)
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
   * Renders the personal inventory list ({@code /inventory/my}). Filters are URL-driven so a user
   * can share a filtered link. {@code fragment=true} returns just the table fragment for AJAX
   * filter changes. The {@code view} parameter switches between the Material tree (default) and the
   * game-item tree (REQ-INV-030, {@code view=items}): the items view relays {@code catalog=ITEM}
   * plus the {@code gameItemIds} / {@code jobOrderIds} / personal-flag filters (there is no quality
   * or mission dimension on item rows) and populates its gameItem filter only from items that
   * currently have stock in the caller's scope — never the full catalog. In both views the Umbuchen
   * modal's target-location picker searches locations server-side (remote-locations combobox), so
   * no locations catalog is added to the model.
   *
   * @param view {@code "items"} for the game-item view, anything else (or absent) for material
   * @param materialIds optional material id filter (multi; material view only)
   * @param minQuality optional minimum-quality filter (material view only)
   * @param jobOrderIds optional job-order id filter (multi; both views)
   * @param missionIds optional mission id filter (multi; material view only)
   * @param gameItemIds optional game-item id filter (multi; items view only)
   * @param personalOnly when true, show only the caller's personal entries ({@code personal =
   *     true})
   * @param nonPersonalOnly when true, show only the caller's non-personal (shared) entries ({@code
   *     personal = false}); mutually exclusive with {@code personalOnly}
   * @param fragment when true, return the {@code inventoryTableFragment} fragment
   * @param model Thymeleaf model populated with grouped items, filter source catalogs and the
   *     auth-derived UX flags
   * @return either the full {@code inventory-my} view or its table fragment
   */
  @GetMapping("/my")
  public String viewMyInventory(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
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
      model.addAttribute("jobOrders", fetchActiveJobOrders());
      model.addAttribute("users", fetchUsers());
      model.addAttribute("selectedGameItemIds", gameItemIds);
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
      org.springframework.web.util.UriComponentsBuilder uriBuilder =
          org.springframework.web.util.UriComponentsBuilder.fromPath(
              "/api/v1/inventory/my-inventory/grouped");
      if (materialIds != null && !materialIds.isEmpty()) {
        for (UUID id : materialIds) {
          uriBuilder.queryParam("materialIds", id.toString());
        }
      }
      if (minQuality != null) {
        uriBuilder.queryParam("minQuality", minQuality);
      }
      if (jobOrderIds != null && !jobOrderIds.isEmpty()) {
        for (UUID id : jobOrderIds) {
          uriBuilder.queryParam("jobOrderIds", id.toString());
        }
      }
      if (missionIds != null && !missionIds.isEmpty()) {
        for (UUID id : missionIds) {
          uriBuilder.queryParam("missionIds", id.toString());
        }
      }
      if (personalOnly) {
        uriBuilder.queryParam("personalOnly", true);
      }
      if (nonPersonalOnly) {
        uriBuilder.queryParam("nonPersonalOnly", true);
      }
      String url = uriBuilder.build().toUriString();
      List<GroupedInventoryDto> res = backendApiClient.get(url, GROUPED_INVENTORY_LIST);
      if (res != null) {
        groupedItems = res;
      }
    } catch (Exception e) {
      log.error("Failed to fetch my grouped inventory", e);
      model.addAttribute("error", "error.inventory.personal.load");
    }

    model.addAttribute("groupedItems", groupedItems);
    // keeping empty items list to not break any existing template iteration if any
    model.addAttribute("items", new ArrayList<>());
    model.addAttribute("materials", fetchMaterials());
    // The Umbuchen target-location picker (inventory-my.html) searches locations on demand
    // (remote-locations combobox -> /catalog/location-search), so no locations catalog is
    // preloaded here; the modal-opening JS seeds the row's current location itself.
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    model.addAttribute("missions", fetchMissions());
    model.addAttribute("users", fetchUsers());
    model.addAttribute("selectedMaterialIds", materialIds);
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
   * Ids of every one of the caller's own inventory entries matching the current {@code
   * /inventory/my} filter — the JSON companion of {@link #viewMyInventory} that backs the "Alle
   * markieren" (select-all) button (REQ-INV-034). The grouped tree lazy-loads and paginates each
   * stack, so a client-side "check every visible box" would silently miss collapsed stacks and
   * later pages; this proxy relays the same filter + view to the backend's {@code
   * /api/v1/inventory/my-inventory/entry-ids} so the browser can select the complete filtered view
   * in one call and drive a bulk check-out over it. Owner-scoped by the backend from the JWT.
   *
   * <p>The {@code view} switch mirrors {@link #viewMyInventory}: the items view relays {@code
   * catalog=ITEM} with the {@code gameItemIds} / {@code jobOrderIds} / personal-flag filters (no
   * quality or mission dimension), the material view relays the material / min-quality / job-order
   * / mission / personal filters. The free-text-free, id-only params are appended via {@link
   * org.springframework.web.util.UriComponentsBuilder} so no re-encoding trap applies.
   *
   * @param view {@code "items"} for the game-item view, anything else (or absent) for material
   * @param materialIds optional material id filter (multi; material view only)
   * @param minQuality optional minimum-quality filter (material view only)
   * @param jobOrderIds optional job-order id filter (multi; both views)
   * @param missionIds optional mission id filter (multi; material view only)
   * @param gameItemIds optional game-item id filter (multi; items view only)
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
      @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
      @RequestParam(required = false, defaultValue = "false") boolean nonPersonalOnly) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
            "/api/v1/inventory/my-inventory/entry-ids");
    if (isItemsView(view)) {
      uriBuilder.queryParam("catalog", "ITEM");
      if (gameItemIds != null && !gameItemIds.isEmpty()) {
        for (UUID id : gameItemIds) {
          uriBuilder.queryParam("gameItemIds", id.toString());
        }
      }
      if (jobOrderIds != null && !jobOrderIds.isEmpty()) {
        for (UUID id : jobOrderIds) {
          uriBuilder.queryParam("jobOrderIds", id.toString());
        }
      }
    } else {
      if (materialIds != null && !materialIds.isEmpty()) {
        for (UUID id : materialIds) {
          uriBuilder.queryParam("materialIds", id.toString());
        }
      }
      if (minQuality != null) {
        uriBuilder.queryParam("minQuality", minQuality);
      }
      if (jobOrderIds != null && !jobOrderIds.isEmpty()) {
        for (UUID id : jobOrderIds) {
          uriBuilder.queryParam("jobOrderIds", id.toString());
        }
      }
      if (missionIds != null && !missionIds.isEmpty()) {
        for (UUID id : missionIds) {
          uriBuilder.queryParam("missionIds", id.toString());
        }
      }
    }
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
   * Fetches one grouped item-inventory result ({@code catalog=ITEM}, REQ-INV-030) from the given
   * backend grouped endpoint, relaying the item view's filter dimensions (gameItems, job orders and
   * — on {@code /my} — the personal flags). Quality and mission filters do not exist for item rows
   * and are never sent.
   *
   * @param basePath the backend grouped path ({@code …/my-inventory/grouped} or {@code
   *     …/all/grouped})
   * @param gameItemIds optional game-item filter
   * @param jobOrderIds optional job-order filter
   * @param personalOnly relay {@code personalOnly=true} (only meaningful on {@code /my})
   * @param nonPersonalOnly relay {@code nonPersonalOnly=true} (only meaningful on {@code /my})
   * @return the grouped result as returned by the backend, may be {@code null}
   */
  private List<GroupedInventoryDto> fetchGroupedItemInventory(
      @NotNull String basePath,
      List<UUID> gameItemIds,
      List<UUID> jobOrderIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    org.springframework.web.util.UriComponentsBuilder uriBuilder =
        org.springframework.web.util.UriComponentsBuilder.fromPath(basePath)
            .queryParam("catalog", "ITEM");
    if (gameItemIds != null && !gameItemIds.isEmpty()) {
      for (UUID id : gameItemIds) {
        uriBuilder.queryParam("gameItemIds", id.toString());
      }
    }
    if (jobOrderIds != null && !jobOrderIds.isEmpty()) {
      for (UUID id : jobOrderIds) {
        uriBuilder.queryParam("jobOrderIds", id.toString());
      }
    }
    if (personalOnly) {
      uriBuilder.queryParam("personalOnly", true);
    }
    if (nonPersonalOnly) {
      uriBuilder.queryParam("nonPersonalOnly", true);
    }
    return backendApiClient.get(uriBuilder.build().toUriString(), GROUPED_INVENTORY_LIST);
  }

  /**
   * Resolves the item view's gameItem filter options — only gameItems that currently have stock
   * rows in the viewer's scope, never the full catalog (REQ-INV-030, design §6.1: the grouped
   * query's key set). For an unfiltered render the already-fetched grouped result IS that key set;
   * when the full page is (deep-link) rendered with active filters, the displayed groups are a
   * narrowed subset, so one extra unfiltered grouped call restores the complete option list.
   * Fragment renders never need options (the filter form lives outside the swapped container), and
   * a failed lookup degrades to the displayed groups' keys.
   *
   * @param basePath the backend grouped path the page's table was fetched from
   * @param groupedItems the (possibly filtered) grouped result already fetched for the table
   * @param fragment whether this render is the table-fragment swap (options unused there)
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
            fetchGroupedItemInventory(basePath, null, null, false, false);
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
   * Renders the squadron-wide inventory list ({@code /inventory/all}). Same shape as {@link
   * #viewMyInventory} but the backend endpoint scopes to all users (gated by role at the backend).
   * {@code view=items} renders the game-item tree (REQ-INV-030) with the {@code gameItemIds} /
   * {@code jobOrderIds} filters; the personal flags stay a {@code /my}-only dimension (the global
   * Lager is non-personal by definition). Like {@code /my}, the Umbuchen target-location picker
   * searches server-side, so no locations catalog is added to the model.
   *
   * @param view {@code "items"} for the game-item view, anything else (or absent) for material
   * @param materialIds optional material id filter (multi; material view only)
   * @param minQuality optional minimum-quality filter (material view only)
   * @param jobOrderIds optional job-order id filter (multi; both views)
   * @param missionIds optional mission id filter (multi; material view only)
   * @param gameItemIds optional game-item id filter (multi; items view only)
   * @param fragment when true, return the table fragment
   * @return either the full {@code inventory-admin} view or its fragment
   */
  @GetMapping("/all")
  public String viewAllInventory(
      @RequestParam(required = false) String view,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
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
                "/api/v1/inventory/all/grouped", gameItemIds, jobOrderIds, false, false);
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
      model.addAttribute("jobOrders", fetchActiveJobOrders());
      model.addAttribute("selectedGameItemIds", gameItemIds);
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
      org.springframework.web.util.UriComponentsBuilder uriBuilder =
          org.springframework.web.util.UriComponentsBuilder.fromPath(
              "/api/v1/inventory/all/grouped");
      if (materialIds != null && !materialIds.isEmpty()) {
        for (UUID id : materialIds) {
          uriBuilder.queryParam("materialIds", id.toString());
        }
      }
      if (minQuality != null) {
        uriBuilder.queryParam("minQuality", minQuality);
      }
      if (jobOrderIds != null && !jobOrderIds.isEmpty()) {
        for (UUID id : jobOrderIds) {
          uriBuilder.queryParam("jobOrderIds", id.toString());
        }
      }
      if (missionIds != null && !missionIds.isEmpty()) {
        for (UUID id : missionIds) {
          uriBuilder.queryParam("missionIds", id.toString());
        }
      }
      String url = uriBuilder.build().toUriString();
      List<GroupedInventoryDto> res = backendApiClient.get(url, GROUPED_INVENTORY_LIST);
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
    model.addAttribute("selectedMaterialIds", materialIds);
    model.addAttribute("selectedMinQuality", minQuality);
    model.addAttribute("selectedJobOrderIds", jobOrderIds);
    model.addAttribute("selectedMissionIds", missionIds);
    // The Umbuchen target-location picker (inventory-admin.html) searches locations on demand
    // (remote-locations combobox -> /catalog/location-search), so no locations catalog is
    // preloaded here; the modal-opening JS seeds the row's current location itself.
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    model.addAttribute("missions", fetchMissions());
    // #1193: the /all book-out/transfer target-user picker (inventory-admin.html) now searches
    // users
    // on demand (remote-users combobox -> /users/search), so the preloaded users list is no longer
    // populated here. fetchUsers() is still used by the /my view's picker below.
    model.addAttribute("authUserId", currentAuthName());
    model.addAttribute("canEditForeignNotes", hasLogisticianOrAbove());

    if (fragment) {
      return "inventory-admin :: inventoryTableFragment";
    }
    return "inventory-admin";
  }

  /**
   * Lazily renders one page of a personal-Lager stack's individual entries — the AJAX drill-down
   * behind a collapsed stack on {@code /inventory/my}. The append-only Lager keeps every
   * contribution as its own row, so the grouped view never inlines them; the browser expands a
   * stack and this endpoint fetches that stack's entries oldest-first, paginated, from the
   * backend's {@code /api/v1/inventory/my-inventory/stack/entries}. The stack is addressed by the
   * stock-identity query params the grouped {@link InventoryStackDto} already exposes (a {@code
   * null} owning-org-unit selects the rows where that pool is itself absent). Since Variante C
   * (REQ-INV-027) the job-order / mission link is no longer part of the stock identity — it lives
   * per leaf entry as allocation chips — so it is not a stack-key param. Returns the {@code
   * stackEntries} HTML fragment that replaces the stack's entries container.
   *
   * @param materialId the stack's material (from the enclosing group)
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack holds the caller's private stock
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model Thymeleaf model populated with the entries page and association catalogs
   * @return the {@code inventory-my :: stackEntries} fragment view name
   */
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
   * Item-view sibling of {@link #viewMyStackEntries} (REQ-INV-030): lazily renders one page of a
   * personal game-item stack's entries. The stack is addressed by {@code gameItemId} instead of
   * {@code materialId}+{@code quality} — item stacks carry no quality dimension (REQ-INV-029) — and
   * the proxy relays {@code catalog=ITEM} to the backend's {@code
   * /api/v1/inventory/my-inventory/stack/entries}. Renders the same {@code stackEntriesMy}
   * fragment, whose rows branch per row kind (an item entry renders without quality or mission
   * split and with whole amounts). No mission catalog is loaded (item rows cannot carry mission
   * allocations, REQ-INV-031), but the {@code releasedItemIds} lookup runs exactly like the
   * material sibling so the item leaf's "Für Börse freigeben" toggle renders its checked / "Auf
   * Börse" state (stock-backed item offers, REQ-MARKET-002/014).
   *
   * @param gameItemId the stack's game item (from the enclosing group)
   * @param locationId the stack's storage location
   * @param personal whether the stack holds the caller's private stock
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model Thymeleaf model populated with the entries page and the job-order catalog
   * @return the {@code fragments/inventory-stack-entries :: stackEntriesMy} fragment view name
   */
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
   * Adds the {@code releasedItemIds} model attribute — the subset of the just-loaded Mein-Lager
   * leaf rows that currently carry an active Materialbörse offer — so the leaf template renders the
   * "Für Börse" checkbox as checked / "Auf Börse". Best-effort: a Materialbörse backend failure
   * leaves the set empty (the checkboxes simply render unchecked) rather than breaking the Lager.
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
   * Squadron-wide variant of {@link #viewMyStackEntries} — the AJAX drill-down behind a collapsed
   * stack on {@code /inventory/all}. A global stack is per-owner, so the stack key carries the
   * owning {@code userId} in addition to the other stock-identity dimensions; the backend
   * re-applies the same org-unit scope predicate as the grouped view, so the drill-down can never
   * widen visibility beyond the caller's slice. The global Lager is non-personal by definition, so
   * there is no {@code personal} param. Returns the {@code stackEntries} HTML fragment for the
   * admin page.
   *
   * @param materialId the stack's material (from the enclosing group)
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model Thymeleaf model populated with the entries page and association catalogs
   * @return the {@code inventory-admin :: stackEntries} fragment view name
   */
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
   * Item-view sibling of {@link #viewAllStackEntries} (REQ-INV-030): lazily renders one page of a
   * squadron-wide game-item stack's entries. A global stack is per-owner, so the key carries the
   * owning {@code userId}; the stack itself is addressed by {@code gameItemId} (no quality
   * dimension, REQ-INV-029) and the proxy relays {@code catalog=ITEM} to the backend's {@code
   * /api/v1/inventory/all/stack/entries}. Renders the same per-row-kind-branching {@code
   * stackEntriesAdmin} fragment; no mission catalog is loaded (REQ-INV-031).
   *
   * @param gameItemId the stack's game item (from the enclosing group)
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param owningOrgUnitId the stack's owning org-unit pool, or {@code null}
   * @param page zero-based page index, or {@code null} for the first page
   * @param size page size, or {@code null} for the backend default
   * @param model Thymeleaf model populated with the entries page and the job-order catalog
   * @return the {@code fragments/inventory-stack-entries :: stackEntriesAdmin} fragment view name
   */
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
   * Shared backend call + model population for the stack-entries drill-down endpoints. Fetches the
   * paginated entries page from the given backend URI and the job-order / mission catalogs the
   * per-entry association dropdowns render. A backend failure degrades to an empty entries list
   * plus an {@code error} flag so the fragment still renders a (empty) container instead of
   * throwing.
   *
   * @param uri the fully-built backend stack-entries URI (path + query)
   * @param model the Thymeleaf model to populate with {@code entries}, {@code entriesPage}, {@code
   *     jobOrders} and {@code missions}
   * @param includeMissions whether to load the mission catalog — {@code true} on the material
   *     endpoints (mission allocation chips), {@code false} on the item endpoints, where mission
   *     allocations are rejected (REQ-INV-031) and the model gets an empty list instead
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
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    model.addAttribute(
        "missions",
        includeMissions
            ? fetchMissions()
            : List.<de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto>of());
  }

  /**
   * Renders the inventory create form ({@code /inventory/input}). The {@code source=admin} mode
   * seeds {@code isGlobal=true} so the admin can pick a target user from the user dropdown;
   * otherwise the form creates a personal entry owned by the caller.
   *
   * @param source optional origin marker ({@code admin}, {@code my}, {@code aggregated}) used to
   *     pick the post-save redirect target
   * @param model Thymeleaf model populated with the form and dropdown catalogs
   * @return the {@code inventory-input} view name
   */
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

    // The input form's catalog lookups are independent; fetch them concurrently (missions, job
    // orders and the owner picker are uncached round-trips) and apply on the request thread. Each
    // helper swallows its own failure and returns an empty list, so join() never throws and the
    // page
    // degrades exactly as the serial version did.
    final InventoryForm boundForm = form;
    // #1193: the admin "assign to user" picker (inventory-input.html, shown when isGlobal) now
    // searches users on demand (remote-users combobox -> /users/search), so the preloaded roster is
    // no longer fetched here. Only the currently-chosen target user is seeded (edit-mode label), so
    // a re-render after a validation/backend error still shows — and keeps — the picked user.
    var materialsFuture = parallelPageLoader.loadAsync(this::fetchMaterials);
    var locationsFuture = parallelPageLoader.loadAsync(this::fetchLocations);
    var missionsFuture = parallelPageLoader.loadAsync(this::fetchMissions);
    var jobOrdersFuture = parallelPageLoader.loadAsync(this::fetchActiveJobOrders);
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
    model.addAttribute("jobOrders", jobOrdersFuture.join());
    model.addAttribute("ownerOptions", ownerFuture.join());
    model.addAttribute("selectedUser", selectedUserFuture.join());
    return "inventory-input";
  }

  /**
   * Resolves the admin-chosen target user for the inventory-input "assign to user" picker's
   * edit-mode seed (#1193): the picker now searches server-side rather than preloading the roster,
   * so only the currently-selected user's option is rendered and needs a display name. Returns
   * {@code null} when the form is not a global entry, has no chosen user, or the lookup fails
   * (leaving the picker on its "own entry" placeholder).
   *
   * @param form the inbound inventory form; may be {@code null} before binding.
   * @return the selected user DTO for the seed option, or {@code null}.
   */
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
      // REQ-OBS-004: log the id only, never the resolved name.
      log.warn(
          "Failed to resolve selected user {} for inventory-input picker seed",
          form.getUserId(),
          e);
      return null;
    }
  }

  /**
   * Resolves the {@link OrgUnitMembershipOptionDto} list that drives the R5.d owner-picker fragment
   * on the inventory-input form. The target user is:
   *
   * <ul>
   *   <li>The form's {@code userId} when the admin is creating a global entry for another user (the
   *       picker reflects the chosen user's memberships).
   *   <li>The calling user otherwise (a self-entry — picker reflects the caller's own memberships).
   * </ul>
   *
   * <p>Falling back to an empty list when the lookup fails keeps the page renderable: the fragment
   * collapses to a hidden state when its option list is empty, so a transient backend hiccup does
   * not break the rest of the form.
   *
   * @param form the inbound inventory form (may be {@code null} on first GET before binding).
   * @return picker options or empty list; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchOwnerPickerOptions(InventoryForm form) {
    if (form != null && Boolean.TRUE.equals(form.getIsGlobal()) && form.getUserId() != null) {
      // Admin creating a global entry for ANOTHER user → that user's DIRECT memberships (their own
      // stock). The create-on-behalf cascade is the caller's reach, not a third-party owner's.
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
    // Self-entry → the caller's pickable org units: direct memberships plus their cascading
    // leadership reach (own Bereich/OL + overseen subordinate Staffeln/SKs), epic #692 Phase 5.
    // Unchanged for an ordinary member. Resolved server-side for the caller.
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

  private List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto>
      fetchActiveJobOrders() {
    List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> orders =
        new ArrayList<>();
    try {
      List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto> content =
          backendApiClient.get("/api/v1/orders/lookup", JOB_ORDER_REFERENCE_LIST);
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

  private static String currentAuthName() {
    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    return auth != null ? auth.getName() : null;
  }

  private static boolean hasLogisticianOrAbove() {
    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (auth == null || auth.getAuthorities() == null) {
      return false;
    }
    for (org.springframework.security.core.GrantedAuthority a : auth.getAuthorities()) {
      String r = a.getAuthority();
      if (Roles.authority(Roles.LOGISTICIAN).equals(r)
          || Roles.authority(Roles.OFFICER).equals(r)
          || Roles.authority(Roles.ADMIN).equals(r)) {
        return true;
      }
    }
    return false;
  }
}
