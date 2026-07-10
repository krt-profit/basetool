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
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryStackDto;
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
 * <p>Four read views: aggregated (sum per material across the squadron), per-material drilldown,
 * personal ({@code /my}), and admin-all ({@code /all}). All four list endpoints accept the same
 * filter dimensions (material ids, min quality, job order, mission) and support a {@code
 * fragment=true} flag that returns just the table fragment so AJAX filter changes do not reload the
 * page.
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
   * feeds the storage-location dropdowns on the list and create views.
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

  private final BackendApiClient backendApiClient;
  private final ParallelPageLoader parallelPageLoader;

  /**
   * Renders the squadron-wide aggregated inventory view ({@code /inventory}). Sort is fixed to
   * material name asc, quality desc, amount desc — operators look for the highest-quality stock
   * first.
   *
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
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    List<AggregatedInventoryDto> aggregated = new ArrayList<>();
    try {
      StringBuilder uri = new StringBuilder("/api/v1/inventory/aggregated?");
      if (page != null) {
        uri.append("page=").append(page).append("&");
      }
      if (size != null) {
        uri.append("size=").append(size).append("&");
      }
      uri.append("sort=material.name,asc;quality,desc;amount,desc");

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
        fetchMaterials();

    model.addAttribute("aggregated", aggregated);
    model.addAttribute("materials", materials);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "inventory-index :: inventoryResults";
    }
    return "inventory-index";
  }

  /**
   * Renders the per-material drilldown ({@code /inventory/material/{materialId}}) showing every
   * individual inventory row for the given material (up to 1000 in one page). Loads the
   * active-job-order list because the page offers inline re-assignment of items to job orders.
   *
   * @param materialId material id to drill into
   * @param model Thymeleaf model populated with items, the material catalog and active job orders
   * @return the {@code inventory-material} view name
   */
  @GetMapping("/material/{materialId}")
  public String viewMaterialInventory(@PathVariable @NotNull UUID materialId, Model model) {
    List<InventoryItemDto> items = new ArrayList<>();
    try {
      PageResponse<InventoryItemDto> p =
          backendApiClient.get(
              "/api/v1/inventory/material/" + materialId + "?size=1000", INVENTORY_ITEM_PAGE);
      if (p != null && p.content() != null) {
        items = new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch material inventory", e);
      model.addAttribute("error", "error.inventory.material.load");
    }

    model.addAttribute("items", items);
    model.addAttribute("materials", fetchMaterials());
    model.addAttribute("selectedMaterialId", materialId);
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    return "inventory-material";
  }

  /**
   * Per-material grouping wrapper for the {@code /my} and {@code /all} list views.
   *
   * <p>The backend's {@code /grouped} endpoint returns this shape directly so the page renders an
   * outer "material" row with summary stats (total amount, average + max quality) and an inner list
   * of {@link InventoryStackDto} stacks, each of which expands to the individual append-only
   * entries (Material → Stack → Entries).
   *
   * @param material the grouping material
   * @param totalAmount sum across all stacks of this material
   * @param averageQuality weighted average quality across all stacks
   * @param maxQuality the highest quality value seen in the group
   * @param stacks the per-stock-identity stacks this material breaks down into
   */
  public record GroupedInventoryDto(
      de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto material,
      Double totalAmount,
      Double averageQuality,
      Integer maxQuality,
      List<InventoryStackDto> stacks) {

    /**
     * Counts the distinct owning users across this material's stacks, for the grouped Lager row's
     * context line ("{n} Nutzer / {m} Stacks").
     *
     * @return the number of distinct users owning at least one stack of this material
     */
    public int userCount() {
      return (int)
          stacks.stream()
              .map(InventoryStackDto::user)
              .filter(java.util.Objects::nonNull)
              .map(u -> u.id())
              .distinct()
              .count();
    }
  }

  /**
   * Renders the personal inventory list ({@code /inventory/my}). Filters are URL-driven so a user
   * can share a filtered link. {@code fragment=true} returns just the table fragment for AJAX
   * filter changes.
   *
   * @param materialIds optional material id filter (multi)
   * @param minQuality optional minimum-quality filter
   * @param jobOrderIds optional job-order id filter (multi)
   * @param missionIds optional mission id filter (multi)
   * @param personalOnly when true, show only the caller's personal entries ({@code personal =
   *     true})
   * @param fragment when true, return the {@code inventoryTableFragment} fragment
   * @param model Thymeleaf model populated with grouped items, filter source catalogs and the
   *     auth-derived UX flags
   * @return either the full {@code inventory-my} view or its table fragment
   */
  @GetMapping("/my")
  public String viewMyInventory(
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
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
    model.addAttribute("locations", fetchLocations());
    model.addAttribute("jobOrders", fetchActiveJobOrders());
    model.addAttribute("missions", fetchMissions());
    model.addAttribute("users", fetchUsers());
    model.addAttribute("selectedMaterialIds", materialIds);
    model.addAttribute("selectedMinQuality", minQuality);
    model.addAttribute("selectedJobOrderIds", jobOrderIds);
    model.addAttribute("selectedMissionIds", missionIds);
    model.addAttribute("selectedPersonalOnly", personalOnly);
    model.addAttribute("authUserId", currentAuthName());
    model.addAttribute("canEditForeignNotes", hasLogisticianOrAbove());

    if (fragment) {
      return "inventory-my :: inventoryTableFragment";
    }
    return "inventory-my";
  }

  /**
   * Renders the squadron-wide inventory list ({@code /inventory/all}). Same shape as {@link
   * #viewMyInventory} but the backend endpoint scopes to all users (gated by role at the backend).
   *
   * @param materialIds optional material id filter (multi)
   * @param minQuality optional minimum-quality filter
   * @param jobOrderIds optional job-order id filter (multi)
   * @param missionIds optional mission id filter (multi)
   * @param fragment when true, return the table fragment
   * @return either the full {@code inventory-admin} view or its fragment
   */
  @GetMapping("/all")
  public String viewAllInventory(
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
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
    model.addAttribute("locations", fetchLocations());
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
   * null} job-order / mission / owning-org-unit selects the rows where that association is itself
   * absent). Returns the {@code stackEntries} HTML fragment that replaces the stack's entries
   * container.
   *
   * @param materialId the stack's material (from the enclosing group)
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param jobOrderId the stack's linked job-order id, or {@code null} for the unassigned slice
   * @param missionId the stack's linked mission id, or {@code null} for the unassigned slice
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
      @RequestParam(required = false) UUID jobOrderId,
      @RequestParam(required = false) UUID missionId,
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
    if (jobOrderId != null) {
      uriBuilder.queryParam("jobOrderId", jobOrderId);
    }
    if (missionId != null) {
      uriBuilder.queryParam("missionId", missionId);
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
    fetchStackEntriesIntoModel(uriBuilder.build().toUriString(), model);
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
   * @param jobOrderId the stack's linked job-order id, or {@code null} for the unassigned slice
   * @param missionId the stack's linked mission id, or {@code null} for the unassigned slice
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
      @RequestParam(required = false) UUID jobOrderId,
      @RequestParam(required = false) UUID missionId,
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
    if (jobOrderId != null) {
      uriBuilder.queryParam("jobOrderId", jobOrderId);
    }
    if (missionId != null) {
      uriBuilder.queryParam("missionId", missionId);
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
    fetchStackEntriesIntoModel(uriBuilder.build().toUriString(), model);
    return "fragments/inventory-stack-entries :: stackEntriesAdmin";
  }

  /**
   * Shared backend call + model population for the two stack-entries drill-down endpoints. Fetches
   * the paginated entries page from the given backend URI and the job-order / mission catalogs the
   * per-entry association dropdowns render. A backend failure degrades to an empty entries list
   * plus an {@code error} flag so the fragment still renders a (empty) container instead of
   * throwing.
   *
   * @param uri the fully-built backend stack-entries URI (path + query)
   * @param model the Thymeleaf model to populate with {@code entries}, {@code entriesPage}, {@code
   *     jobOrders} and {@code missions}
   */
  private void fetchStackEntriesIntoModel(@NotNull String uri, Model model) {
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
    model.addAttribute("missions", fetchMissions());
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
