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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.DonationDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.MyDonationStatsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplySettingsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStatsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The supply pages „Bedarf", „Meine Spenden", „Eingehende Spenden" and „Statistik" and the admin
 * settings (REQ-SUP-001 … REQ-SUP-013). Filtering, paging and every rule live in the backend; the
 * pages answer 404 while the surface is switched off.
 */
@Controller
@UsesLayoutModel
@RequestMapping
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SupplyPageController {

  /** The page sizes the lists offer. */
  static final List<Integer> PAGE_SIZES = List.of(10, 50, 100);

  /** The default page size. */
  static final int DEFAULT_PAGE_SIZE = 10;

  /** The states a need filter and a donation filter accept. */
  private static final Set<String> NEED_STATUSES =
      Set.of("OPEN", "PAUSED", "FULFILLED", "WITHDRAWN");

  private static final Set<String> DONATION_STATUSES =
      Set.of("PLEDGED", "RECEIVED", "PARTIALLY_RECEIVED", "DECLINED", "WITHDRAWN");

  private static final String LIVE_SYNC_NEEDS = "supply-needs";

  private final SupplyBackendClient supplyClient;
  private final SupplyFeatureState featureState;
  private final QualityTierCatalog qualityTierCatalog;
  private final FrontendAuthHelperService authHelper;

  /**
   * Decides which of the optional tabs the supply pages show: the incoming donations for the
   * managers of a receiving unit, the statistics for them and for an admin.
   *
   * @return the tabs to show; none while the backend cannot be reached
   */
  @ModelAttribute("supplyTabs")
  public @NotNull SupplyTabs supplyTabs() {
    try {
      List<SupplyUnitDto> units = orEmpty(supplyClient.units());
      boolean incoming = units.stream().anyMatch(u -> u.canManage() && u.acceptsDonations());
      boolean managesAny = units.stream().anyMatch(SupplyUnitDto::canManage);
      return new SupplyTabs(incoming, incoming || managesAny || authHelper.isAdmin());
    } catch (RuntimeException _) {
      return new SupplyTabs(false, false);
    }
  }

  /**
   * The optional tabs of the supply pages.
   *
   * @param incoming whether the „Eingehende Spenden" tab is shown
   * @param stats whether the „Statistik" tab is shown
   */
  public record SupplyTabs(boolean incoming, boolean stats) {}

  /**
   * Renders the needs page: every open need, and for a unit's managers the unit's other states too
   * (REQ-SUP-003, REQ-SUP-006).
   *
   * @param orgUnitId only this raising unit
   * @param status only this state
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code results} renders only the list fragment
   * @param model the model
   * @return the {@code supply/needs} view or its {@code needsResults} fragment
   */
  @NotNull
  @GetMapping("/supply/needs")
  public String needs(
      @RequestParam(required = false) UUID orgUnitId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    requireEnabled();
    int effectiveSize = effectiveSize(size);
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveStatus = status != null && NEED_STATUSES.contains(status) ? status : null;
    PageResponse<NeedDto> needs =
        orEmpty(
            supplyClient.needs(orgUnitId, effectiveStatus, effectivePage, effectiveSize),
            effectivePage,
            effectiveSize);
    List<SupplyUnitDto> units = orEmpty(supplyClient.units());
    model.addAttribute("needsPage", needs);
    model.addAttribute("needs", needs.content());
    model.addAttribute("units", units);
    model.addAttribute(
        "raisingUnits", units.stream().filter(u -> u.canManage() && u.mayRaiseNeeds()).toList());
    model.addAttribute(
        "receivingUnits", units.stream().filter(SupplyUnitDto::acceptsDonations).toList());
    model.addAttribute("selectedUnit", orgUnitId);
    model.addAttribute("selectedStatus", effectiveStatus);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("paginationBaseUrl", listUrl("/supply/needs", orgUnitId, effectiveStatus));
    model.addAttribute("supplyLiveSyncTopic", LIVE_SYNC_NEEDS);
    if (!"results".equalsIgnoreCase(fragment)) {
      addCatalogues(model);
    }
    return "results".equalsIgnoreCase(fragment) ? "supply/needs :: needsResults" : "supply/needs";
  }

  /**
   * Renders the caller's own donations with their totals (REQ-SUP-013).
   *
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code results} renders only the list fragment
   * @param model the model
   * @return the {@code supply/my-donations} view or its {@code myDonationsResults} fragment
   */
  @NotNull
  @GetMapping("/supply/donations/mine")
  public String myDonations(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    requireEnabled();
    int effectiveSize = effectiveSize(size);
    int effectivePage = page == null || page < 0 ? 0 : page;
    PageResponse<DonationDto> donations =
        orEmpty(
            supplyClient.myDonations(effectivePage, effectiveSize), effectivePage, effectiveSize);
    MyDonationStatsDto stats = supplyClient.myStats();
    model.addAttribute("donationsPage", donations);
    model.addAttribute("donations", donations.content());
    model.addAttribute("myStats", stats);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("paginationBaseUrl", "/supply/donations/mine");
    model.addAttribute("supplyLiveSyncTopic", LIVE_SYNC_NEEDS);
    if (!"results".equalsIgnoreCase(fragment)) {
      List<SupplyUnitDto> units = orEmpty(supplyClient.units());
      model.addAttribute(
          "receivingUnits", units.stream().filter(SupplyUnitDto::acceptsDonations).toList());
      addCatalogues(model);
    }
    return "results".equalsIgnoreCase(fragment)
        ? "supply/my-donations :: myDonationsResults"
        : "supply/my-donations";
  }

  /**
   * Renders the donations a unit received or was pledged, for its managers (REQ-SUP-009 …
   * REQ-SUP-011).
   *
   * @param orgUnitId the receiving unit; the first unit the caller manages when omitted
   * @param status only this state
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code results} renders only the list fragment
   * @param model the model
   * @return the {@code supply/incoming-donations} view or its {@code incomingResults} fragment
   */
  @NotNull
  @GetMapping("/supply/donations/incoming")
  public String incomingDonations(
      @RequestParam(required = false) UUID orgUnitId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    requireEnabled();
    List<SupplyUnitDto> managed =
        orEmpty(supplyClient.units()).stream()
            .filter(u -> u.canManage() && u.acceptsDonations())
            .toList();
    SupplyUnitDto unit =
        managed.stream()
            .filter(u -> u.orgUnitId().equals(orgUnitId))
            .findFirst()
            .orElse(managed.isEmpty() ? null : managed.getFirst());
    int effectiveSize = effectiveSize(size);
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveStatus = status != null && DONATION_STATUSES.contains(status) ? status : null;
    PageResponse<DonationDto> donations =
        unit == null
            ? emptyPage(effectivePage, effectiveSize)
            : orEmpty(
                supplyClient.donationsOfUnit(
                    unit.orgUnitId(), effectiveStatus, effectivePage, effectiveSize),
                effectivePage,
                effectiveSize);
    model.addAttribute("managedUnits", managed);
    model.addAttribute("unit", unit);
    model.addAttribute("donationsPage", donations);
    model.addAttribute("donations", donations.content());
    model.addAttribute("selectedStatus", effectiveStatus);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute(
        "paginationBaseUrl",
        listUrl(
            "/supply/donations/incoming", unit == null ? null : unit.orgUnitId(), effectiveStatus));
    model.addAttribute(
        "supplyLiveSyncTopic", unit == null ? null : "supply-donations:" + unit.orgUnitId());
    if (!"results".equalsIgnoreCase(fragment)) {
      model.addAttribute(
          "openNeeds",
          unit == null ? List.of() : orEmpty(supplyClient.openNeeds(unit.orgUnitId())));
      addCatalogues(model);
    }
    return "results".equalsIgnoreCase(fragment)
        ? "supply/incoming-donations :: incomingResults"
        : "supply/incoming-donations";
  }

  /**
   * Renders the donation statistics per receiving unit and month (REQ-SUP-013).
   *
   * @param months how many months back
   * @param model the model
   * @return the {@code supply/stats} view
   */
  @NotNull
  @GetMapping("/supply/stats")
  public String stats(@RequestParam(defaultValue = "6") int months, @NotNull Model model) {
    requireEnabled();
    SupplyStatsDto stats = supplyClient.stats(months);
    model.addAttribute("stats", stats);
    model.addAttribute("months", Math.max(1, Math.min(36, months)));
    return "supply/stats";
  }

  /**
   * Renders the admin page of the supply module: the switch, the governing Bereich and the units
   * that take part (REQ-SUP-001, REQ-SUP-002).
   *
   * @param model the model
   * @return the {@code supply/admin/supply-settings} view
   */
  @NotNull
  @GetMapping("/admin/supply")
  @PreAuthorize("hasRole('ADMIN')")
  public String adminSettings(@NotNull Model model) {
    SupplySettingsDto settings = supplyClient.settings();
    model.addAttribute("settings", settings);
    model.addAttribute("adminUnits", orEmpty(supplyClient.adminUnits()));
    featureState.evict();
    return "supply/admin/supply-settings";
  }

  private void requireEnabled() {
    if (!featureState.enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private void addCatalogues(@NotNull Model model) {
    List<MaterialReferenceDto> materials = orEmpty(supplyClient.materials());
    PageResponse<InventoryGameItemReferenceDto> items = supplyClient.items();
    List<LocationReferenceDto> locations = orEmpty(supplyClient.locations());
    List<QualityTierDto> tiers =
        qualityTierCatalog.all().stream().filter(QualityTierDto::active).toList();
    model.addAttribute("materials", materials);
    model.addAttribute("items", items == null ? List.of() : items.content());
    model.addAttribute("locations", locations);
    model.addAttribute("tiers", tiers);
  }

  private static int effectiveSize(@Nullable Integer size) {
    return size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
  }

  private static <T> @NotNull List<T> orEmpty(@Nullable List<T> list) {
    return list == null ? List.of() : list;
  }

  private static <T> @NotNull PageResponse<T> orEmpty(
      @Nullable PageResponse<T> page, int pageNumber, int size) {
    return page != null ? page : emptyPage(pageNumber, size);
  }

  private static <T> @NotNull PageResponse<T> emptyPage(int pageNumber, int size) {
    return new PageResponse<>(List.of(), pageNumber, size, 0L, 0, List.of());
  }

  private static @NotNull String listUrl(
      @NotNull String path, @Nullable UUID orgUnitId, @Nullable String status) {
    StringBuilder url = new StringBuilder(path);
    String separator = "?";
    if (orgUnitId != null) {
      url.append(separator).append("orgUnitId=").append(orgUnitId);
      separator = "&";
    }
    if (status != null) {
      url.append(separator).append("status=").append(status);
    }
    return url.toString();
  }
}
