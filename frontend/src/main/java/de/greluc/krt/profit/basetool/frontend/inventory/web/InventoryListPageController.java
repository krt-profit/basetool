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
import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryBookOutForm;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryStackDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.LagerQuery;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.security.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller for the grouped Lager lists on the Lager tab: the personal list ({@code
 * /inventory?storage=my}) and the global list ({@code /inventory?storage=all}), each in the
 * material and the item view (REQ-INV-030).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryListPageController {

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  private final InventoryCatalogs catalogs;

  /**
   * Exposes the caller's {@code app_user.id} to the Lager pages as {@code currentMemberId}, the id
   * their membership lookups and the rebooking's default target need.
   *
   * @param principal the OIDC principal, or {@code null} when anonymous
   * @return the caller's user id as text, or {@code null}
   */
  @Nullable
  @ModelAttribute("currentMemberId")
  public String currentMemberId(@Nullable @AuthenticationPrincipal OidcUser principal) {
    return CurrentUser.userIdText(principal);
  }

  /**
   * Renders the personal inventory list ({@code /inventory?storage=my}) with URL-driven filters.
   * {@code view=items} shows the game-item tree (REQ-INV-030); filter options list only items and
   * locations in stock in the caller's scope (REQ-INV-040).
   *
   * @param query the Lager filters bound from the query string
   * @param fragment when true, return the {@code inventoryTableFragment} fragment
   * @param model model populated with grouped items, filter catalogs and auth-derived UX flags
   * @return either the full {@code inventory-my} view or its table fragment
   */
  @NotNull
  @GetMapping(params = LagerStorage.MY_CONDITION)
  public String viewMyInventory(
      @ModelAttribute LagerQuery query,
      @RequestParam(required = false, defaultValue = "false") boolean fragment,
      Model model) {
    if (!model.containsAttribute("inventoryForm")) {
      model.addAttribute("inventoryForm", new InventoryForm());
    }
    if (!model.containsAttribute("inventoryBookOutForm")) {
      model.addAttribute("inventoryBookOutForm", new InventoryBookOutForm());
    }
    boolean itemsView = query.itemsView();
    model.addAttribute("view", itemsView ? "items" : "material");
    addLagerParameterNames(model);

    if (itemsView) {
      addItemViewModel(
          model,
          InventoryBackendClient.Listing.MY,
          "my-item-inventory",
          "error.inventory.personal.load",
          query,
          fragment);
      model.addAttribute("users", catalogs.users());
      model.addAttribute("selectedPersonalOnly", query.personalOnly());
      model.addAttribute("selectedNonPersonalOnly", query.nonPersonalOnly());
      model.addAttribute("authUserId", currentAuthName());
      if (fragment) {
        return "inventory/inventory-my :: inventoryTableFragment";
      }
      return "inventory/inventory-my";
    }

    List<GroupedInventoryDto> groupedItems =
        loadGroupedSection(
            model,
            "my-material-inventory",
            "error.inventory.personal.load",
            () -> inventoryClient.grouped(InventoryBackendClient.Listing.MY, query));

    model.addAttribute("groupedItems", groupedItems);
    model.addAttribute("items", new ArrayList<>());
    model.addAttribute("materials", catalogs.materials());
    model.addAttribute(
        "locations",
        resolveLocationFilterOptions(
            groupedItems,
            fragment,
            query.anyActive(),
            () -> inventoryClient.grouped(InventoryBackendClient.Listing.MY, LagerQuery.empty())));
    model.addAttribute("jobOrders", catalogs.activeJobOrders(false));
    model.addAttribute("missions", catalogs.missions());
    model.addAttribute("users", catalogs.users());
    addSelectedMaterialFilters(model, query);
    model.addAttribute("selectedPersonalOnly", query.personalOnly());
    model.addAttribute("selectedNonPersonalOnly", query.nonPersonalOnly());
    model.addAttribute("authUserId", currentAuthName());

    if (fragment) {
      return "inventory/inventory-my :: inventoryTableFragment";
    }
    return "inventory/inventory-my";
  }

  /**
   * Returns the ids of every own inventory entry matching the current personal-list filter and
   * view, backing "Alle markieren" (REQ-INV-034).
   *
   * @param query the Lager filters bound from the query string
   * @return the ids of every matching entry, in creation order; never {@code null}
   */
  @GetMapping("/my/entry-ids")
  @ResponseBody
  public List<UUID> myEntryIds(@ModelAttribute LagerQuery query) {
    List<UUID> ids = inventoryClient.myEntryIds(query);
    return ids != null ? ids : List.of();
  }

  /**
   * Publishes the filter parameter names and the attribute-filter families, so the page script
   * reads the names from the page instead of repeating them (REQ-INV-030).
   *
   * @param model the model to fill
   */
  private static void addLagerParameterNames(@NotNull Model model) {
    model.addAttribute("lagerFilterParams", String.join(",", LagerQuery.parameterSlots()));
    model.addAttribute("lagerAttributeFilters", String.join(",", LagerQuery.attributeFilters()));
  }

  /**
   * Publishes the active material-view selection for the filter widgets.
   *
   * @param model the model to fill
   * @param query the Lager filters of the request
   */
  private static void addSelectedMaterialFilters(@NotNull Model model, @NotNull LagerQuery query) {
    model.addAttribute("selectedMaterialIds", query.materialIds());
    model.addAttribute("selectedLocationIds", query.locationIds());
    model.addAttribute("selectedMinQuality", query.minQuality());
    model.addAttribute("selectedJobOrderIds", query.jobOrderIds());
    model.addAttribute("selectedMissionIds", query.missionIds());
    model.addAttribute("selectedStolenOnly", query.stolenOnly());
    model.addAttribute("selectedNonStolenOnly", query.nonStolenOnly());
  }

  /**
   * Fills the item view's model (REQ-INV-030, REQ-INV-040, REQ-INV-063): the grouped stock, the
   * filter options drawn from in-scope stock and the active selection.
   *
   * <p>The options never narrow by the active filter: with one active, a full-page render reads the
   * listing once more unfiltered, and a failed read falls back to the displayed groups.
   *
   * @param model the model to fill
   * @param listing the grouped listing, {@code /my} or {@code /all}
   * @param section the section name the grouped read logs under
   * @param errorKey the message key shown when the grouped read fails
   * @param filter the item view's filters
   * @param fragment whether this render is the table-fragment swap
   */
  private void addItemViewModel(
      @NotNull Model model,
      @NotNull InventoryBackendClient.Listing listing,
      @NotNull String section,
      @NotNull String errorKey,
      @NotNull LagerQuery filter,
      boolean fragment) {
    List<GroupedInventoryDto> groupedItems =
        loadGroupedSection(
            model, section, errorKey, () -> inventoryClient.grouped(listing, filter));
    List<GroupedInventoryDto> optionSource = groupedItems;
    if (!fragment && filter.anyActive()) {
      List<GroupedInventoryDto> unfiltered =
          SectionFallback.load(
              log,
              "item-filter-options",
              () -> inventoryClient.grouped(listing, LagerQuery.emptyItems()),
              null);
      if (unfiltered != null) {
        optionSource = unfiltered;
      }
    }
    model.addAttribute("groupedItems", groupedItems);
    model.addAttribute("items", new ArrayList<>());
    model.addAttribute(
        "gameItems",
        optionSource.stream()
            .map(GroupedInventoryDto::gameItem)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
    model.addAttribute("locations", locationOptions(optionSource));
    model.addAttribute("itemAttributeOptions", ItemAttributeOptions.of(optionSource));
    model.addAttribute("jobOrders", catalogs.activeJobOrders(false));
    model.addAttribute("selectedGameItemIds", filter.gameItemIds());
    model.addAttribute("selectedLocationIds", filter.locationIds());
    model.addAttribute("selectedJobOrderIds", filter.jobOrderIds());
    model.addAttribute("selectedStolenOnly", filter.stolenOnly());
    model.addAttribute("selectedNonStolenOnly", filter.nonStolenOnly());
    model.addAttribute("selectedItemAttributes", filter.attributes());
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
  private List<LocationReferenceDto> resolveLocationFilterOptions(
      @NotNull List<GroupedInventoryDto> groupedItems,
      boolean fragment,
      boolean anyFilterActive,
      @NotNull Supplier<List<GroupedInventoryDto>> unfilteredFetch) {
    List<GroupedInventoryDto> source = groupedItems;
    if (!fragment && anyFilterActive) {
      List<GroupedInventoryDto> unfiltered =
          SectionFallback.load(log, "location-filter-options", unfilteredFetch, null);
      if (unfiltered != null) {
        source = unfiltered;
      }
    }
    return locationOptions(source);
  }

  /**
   * The distinct storage locations of the given groups' stacks, ordered by name.
   *
   * @param source the grouped stock the locations are drawn from
   * @return the locations to offer in the location filter
   */
  private static List<LocationReferenceDto> locationOptions(
      @NotNull List<GroupedInventoryDto> source) {
    return source.stream()
        .filter(group -> group.stacks() != null)
        .flatMap(group -> group.stacks().stream())
        .map(InventoryStackDto::location)
        .filter(Objects::nonNull)
        .distinct()
        .sorted(
            Comparator.comparing(
                LocationReferenceDto::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
        .toList();
  }

  /**
   * Renders the squadron-wide inventory list ({@code /inventory?storage=all}), like {@link
   * #viewMyInventory} but over all users in scope and without the personal flags.
   *
   * @param requested the Lager filters bound from the query string; the personal flags are ignored
   * @param fragment when true, return the table fragment
   * @param model model populated with grouped items, filter catalogs and auth-derived UX flags
   * @return either the full {@code inventory-admin} view or its fragment
   */
  @NotNull
  @GetMapping(params = LagerStorage.ALL_CONDITION)
  public String viewAllInventory(
      @ModelAttribute LagerQuery requested,
      @RequestParam(required = false, defaultValue = "false") boolean fragment,
      Model model) {
    if (!model.containsAttribute("inventoryForm")) {
      model.addAttribute("inventoryForm", new InventoryForm());
    }
    if (!model.containsAttribute("inventoryBookOutForm")) {
      model.addAttribute("inventoryBookOutForm", new InventoryBookOutForm());
    }
    LagerQuery query = requested.withoutPersonalScope();
    boolean itemsView = query.itemsView();
    model.addAttribute("view", itemsView ? "items" : "material");
    addLagerParameterNames(model);

    if (itemsView) {
      addItemViewModel(
          model,
          InventoryBackendClient.Listing.ALL,
          "all-item-inventory",
          "error.inventory.global.load",
          query,
          fragment);
      model.addAttribute("authUserId", currentAuthName());
      if (fragment) {
        return "inventory/inventory-admin :: inventoryTableFragment";
      }
      return "inventory/inventory-admin";
    }

    List<GroupedInventoryDto> groupedItems =
        loadGroupedSection(
            model,
            "all-material-inventory",
            "error.inventory.global.load",
            () -> inventoryClient.grouped(InventoryBackendClient.Listing.ALL, query));

    model.addAttribute("groupedItems", groupedItems);
    model.addAttribute("items", new ArrayList<>());
    model.addAttribute("materials", catalogs.materials());
    model.addAttribute(
        "locations",
        resolveLocationFilterOptions(
            groupedItems,
            fragment,
            query.anyActive(),
            () -> inventoryClient.grouped(InventoryBackendClient.Listing.ALL, LagerQuery.empty())));
    addSelectedMaterialFilters(model, query);
    model.addAttribute("jobOrders", catalogs.activeJobOrders(false));
    model.addAttribute("missions", catalogs.missions());
    model.addAttribute("authUserId", currentAuthName());

    if (fragment) {
      return "inventory/inventory-admin :: inventoryTableFragment";
    }
    return "inventory/inventory-admin";
  }

  /**
   * Loads a grouped Lager table section; a failed read sets {@code error} to {@code errorKey} and
   * returns no groups.
   *
   * @param model the page model
   * @param section the section name for the failure log
   * @param errorKey the {@code error} message key on failure
   * @param read reads the grouped result from the backend
   * @return the groups, empty when the backend sent none or the read failed
   */
  @NotNull
  private List<GroupedInventoryDto> loadGroupedSection(
      @NotNull Model model,
      @NotNull String section,
      @NotNull String errorKey,
      @NotNull Supplier<List<GroupedInventoryDto>> read) {
    List<GroupedInventoryDto> groups = new ArrayList<>();
    if (!SectionFallback.run(
        log,
        section,
        () -> {
          List<GroupedInventoryDto> res = read.get();
          if (res != null) {
            groups.addAll(res);
          }
        })) {
      model.addAttribute("error", errorKey);
    }
    return groups;
  }

  @Nullable
  private static String currentAuthName() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null ? auth.getName() : null;
  }
}
