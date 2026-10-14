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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PauseStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingReportDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingSettingsDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The purchasing pages „Ankauf", „Partner", „Handelsbeauftragte" and „Bericht" and the admin
 * settings (REQ-PUR-001 … REQ-PUR-023). Filtering, paging and every rule live in the backend; the
 * pages answer 404 while the surface is switched off.
 */
@Controller
@UsesLayoutModel
@RequestMapping
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PurchasingPageController {

  /** The page sizes the lists offer. */
  static final List<Integer> PAGE_SIZES = List.of(10, 50, 100);

  /** The default page size. */
  static final int DEFAULT_PAGE_SIZE = 10;

  private static final Set<String> DEAL_STATUSES =
      Set.of(
          "DRAFT",
          "SUBMITTED",
          "FUNDED",
          "IN_DELIVERY",
          "DELIVERED",
          "SETTLED",
          "CANCELLED",
          "FAILED");

  private static final Set<String> PARTNER_KINDS = Set.of("ORG", "INDIVIDUAL");

  private static final Set<String> PARTNER_STATUSES = Set.of("ACTIVE", "BLOCKED");

  private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

  private static final String LIVE_SYNC_DEALS = "purchasing-deals";

  private final PurchasingBackendClient purchasingClient;
  private final SupplyBackendClient supplyClient;
  private final PurchasingFeatureState featureState;
  private final QualityTierCatalog qualityTierCatalog;

  /**
   * Reads what the caller may do in the purchasing module; a failed read answers nothing.
   *
   * @return the caller's capabilities, all off while the backend cannot be reached
   */
  @ModelAttribute("purchasingState")
  public @NotNull PurchasingStateDto purchasingState() {
    try {
      PurchasingStateDto state = purchasingClient.state();
      return state == null ? NO_STATE : state;
    } catch (RuntimeException _) {
      return NO_STATE;
    }
  }

  /**
   * Decides which tabs the purchasing pages show.
   *
   * @param state the caller's capabilities
   * @return the tabs to show
   */
  @ModelAttribute("purchasingTabs")
  public @NotNull PurchasingTabs purchasingTabs(
      @ModelAttribute("purchasingState") @NotNull PurchasingStateDto state) {
    return new PurchasingTabs(
        state.delegate() || state.governing(), state.governing(), state.canReadReport());
  }

  /**
   * The optional tabs of the purchasing pages.
   *
   * @param partners whether the „Partner" tab is shown
   * @param delegates whether the „Handelsbeauftragte" tab is shown
   * @param report whether the „Bericht" tab is shown
   */
  public record PurchasingTabs(boolean partners, boolean delegates, boolean report) {}

  private static final PurchasingStateDto NO_STATE =
      new PurchasingStateDto(false, false, false, false, false, false, false);

  /**
   * Renders the deal list: the deals the caller may read, filtered by state (REQ-PUR-007,
   * REQ-PUR-021).
   *
   * @param status only this state
   * @param mine only the caller's own deals
   * @param overdue only the deals whose settlement is overdue
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code results} renders only the list fragment
   * @param model the model
   * @return the {@code purchasing/deals} view or its {@code dealsResults} fragment
   */
  @NotNull
  @GetMapping("/purchasing/deals")
  public String deals(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "false") boolean overdue,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    requireEnabled();
    int effectiveSize = effectiveSize(size);
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveStatus = status != null && DEAL_STATUSES.contains(status) ? status : null;
    PageResponse<DealDto> deals =
        orEmpty(
            purchasingClient.deals(effectiveStatus, mine, overdue, effectivePage, effectiveSize),
            effectivePage,
            effectiveSize);
    model.addAttribute("dealsPage", deals);
    model.addAttribute("deals", deals.content());
    model.addAttribute("selectedStatus", effectiveStatus);
    model.addAttribute("mine", mine);
    model.addAttribute("overdue", overdue);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("paginationBaseUrl", dealsUrl(effectiveStatus, mine, overdue));
    model.addAttribute("purchasingLiveSyncTopic", LIVE_SYNC_DEALS);
    boolean results = "results".equalsIgnoreCase(fragment);
    model.addAttribute("pauseState", pauseStateOrNone());
    if (!results) {
      PurchasingStateDto state = purchasingState();
      model.addAttribute("openNeeds", state.delegate() ? purchasableNeeds() : List.<NeedDto>of());
      model.addAttribute("partners", state.delegate() ? activePartners() : List.<PartnerDto>of());
      model.addAttribute("pausableUnits", pausableUnits());
    }
    return results ? "purchasing/deals :: dealsResults" : "purchasing/deals";
  }

  /**
   * Renders one deal with its tranches, deliveries and settlement (REQ-PUR-012 … REQ-PUR-017).
   *
   * @param id the deal
   * @param fragment {@code detail} renders only the detail fragment
   * @param model the model
   * @return the {@code purchasing/deal} view or its {@code dealDetail} fragment
   */
  @NotNull
  @GetMapping("/purchasing/deals/{id}")
  public String deal(
      @PathVariable UUID id,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    requireEnabled();
    DealDto deal = purchasingClient.deal(id);
    if (deal == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    model.addAttribute("deal", deal);
    model.addAttribute("purchasingLiveSyncTopic", LIVE_SYNC_DEALS);
    boolean detail = "detail".equalsIgnoreCase(fragment);
    if (!detail) {
      List<LocationReferenceDto> locations = orEmpty(purchasingClient.locations());
      model.addAttribute("locations", locations);
      model.addAttribute("partners", deal.isMine() ? activePartners() : List.<PartnerDto>of());
    }
    return detail ? "purchasing/deal :: dealDetail" : "purchasing/deal";
  }

  /**
   * Renders the partner list for the delegates and the governing leadership (REQ-PUR-004).
   *
   * @param query a name fragment
   * @param kind only this kind
   * @param status only this status
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code results} renders only the list fragment
   * @param model the model
   * @return the {@code purchasing/partners} view or its {@code partnersResults} fragment
   */
  @NotNull
  @GetMapping("/purchasing/partners")
  public String partners(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      @NotNull Model model) {
    requireEnabled();
    int effectiveSize = effectiveSize(size);
    int effectivePage = page == null || page < 0 ? 0 : page;
    String effectiveKind = kind != null && PARTNER_KINDS.contains(kind) ? kind : null;
    String effectiveStatus = status != null && PARTNER_STATUSES.contains(status) ? status : null;
    String effectiveQuery = query == null || query.isBlank() ? null : query.strip();
    PageResponse<PartnerDto> partners =
        orEmpty(
            purchasingClient.partners(
                effectiveQuery, effectiveKind, effectiveStatus, effectivePage, effectiveSize),
            effectivePage,
            effectiveSize);
    model.addAttribute("partnersPage", partners);
    model.addAttribute("partners", partners.content());
    model.addAttribute("query", effectiveQuery);
    model.addAttribute("selectedKind", effectiveKind);
    model.addAttribute("selectedStatus", effectiveStatus);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute(
        "paginationBaseUrl", partnersUrl(effectiveQuery, effectiveKind, effectiveStatus));
    model.addAttribute("purchasingLiveSyncTopic", LIVE_SYNC_DEALS);
    return "results".equalsIgnoreCase(fragment)
        ? "purchasing/partners :: partnersResults"
        : "purchasing/partners";
  }

  /**
   * Renders the Handelsbeauftragten with their limits (REQ-PUR-003).
   *
   * @param fragment {@code results} renders only the list fragment
   * @param model the model
   * @return the {@code purchasing/delegates} view or its {@code delegatesResults} fragment
   */
  @NotNull
  @GetMapping("/purchasing/delegates")
  public String delegates(@RequestParam(required = false) String fragment, @NotNull Model model) {
    requireEnabled();
    List<DelegateDto> delegates = orEmpty(purchasingClient.delegates());
    model.addAttribute("delegates", delegates);
    model.addAttribute("purchasingLiveSyncTopic", LIVE_SYNC_DEALS);
    return "results".equalsIgnoreCase(fragment)
        ? "purchasing/delegates :: delegatesResults"
        : "purchasing/delegates";
  }

  /**
   * Renders the purchasing report of a period (REQ-PUR-023).
   *
   * @param from the first month as {@code yyyy-MM}
   * @param to the last month as {@code yyyy-MM}
   * @param model the model
   * @return the {@code purchasing/report} view
   */
  @NotNull
  @GetMapping("/purchasing/report")
  public String report(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @NotNull Model model) {
    requireEnabled();
    String effectiveFrom = month(from);
    String effectiveTo = month(to);
    PurchasingReportDto report = purchasingClient.report(effectiveFrom, effectiveTo);
    model.addAttribute("report", report);
    model.addAttribute("from", report == null ? effectiveFrom : report.from());
    model.addAttribute("to", report == null ? effectiveTo : report.to());
    return "purchasing/report";
  }

  /**
   * Renders the admin page of the purchasing module: the switch, the settings and the risk classes
   * (REQ-PUR-001, REQ-PUR-002, REQ-PUR-011).
   *
   * @param model the model
   * @return the {@code purchasing/admin/purchasing-settings} view
   */
  @NotNull
  @GetMapping("/admin/purchasing")
  @PreAuthorize("hasRole('ADMIN')")
  public String adminSettings(@NotNull Model model) {
    PurchasingSettingsDto settings = purchasingClient.settings();
    List<QualityTierDto> tiers =
        qualityTierCatalog.all().stream().filter(QualityTierDto::active).toList();
    List<OrgUnitMembershipOptionDto> areas =
        orEmpty(purchasingClient.activeOrgUnits()).stream()
            .filter(unit -> "BEREICH".equals(unit.kind()))
            .toList();
    model.addAttribute("settings", settings);
    model.addAttribute("riskClasses", orEmpty(purchasingClient.riskClasses()));
    model.addAttribute("fundingAccounts", orEmpty(purchasingClient.fundingAccounts()));
    model.addAttribute("areas", areas);
    model.addAttribute("tiers", tiers);
    featureState.evict();
    return "purchasing/admin/purchasing-settings";
  }

  private void requireEnabled() {
    if (!featureState.enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private @NotNull PauseStateDto pauseStateOrNone() {
    try {
      PauseStateDto state = purchasingClient.pauseState();
      return state == null ? new PauseStateDto(false, null, null, List.of()) : state;
    } catch (RuntimeException _) {
      return new PauseStateDto(false, null, null, List.of());
    }
  }

  private @NotNull List<NeedDto> purchasableNeeds() {
    PageResponse<NeedDto> needs = supplyClient.purchasableNeeds();
    return needs == null ? List.of() : needs.content();
  }

  private @NotNull List<PartnerDto> activePartners() {
    PageResponse<PartnerDto> partners = purchasingClient.partners(null, null, "ACTIVE", 0, 200);
    return partners == null ? List.of() : partners.content();
  }

  private @NotNull List<SupplyUnitDto> pausableUnits() {
    try {
      List<SupplyUnitDto> units = supplyClient.units();
      return units == null
          ? List.of()
          : units.stream().filter(u -> u.canManage() && u.mayRaiseNeeds()).toList();
    } catch (RuntimeException _) {
      return List.of();
    }
  }

  private static @Nullable String month(@Nullable String value) {
    return value != null && MONTH.matcher(value).matches() ? value : null;
  }

  private static int effectiveSize(@Nullable Integer size) {
    return size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
  }

  private static <T> @NotNull List<T> orEmpty(@Nullable List<T> list) {
    return list == null ? List.of() : list;
  }

  private static <T> @NotNull PageResponse<T> orEmpty(
      @Nullable PageResponse<T> page, int pageNumber, int size) {
    return page != null ? page : new PageResponse<>(List.of(), pageNumber, size, 0L, 0, List.of());
  }

  private static @NotNull String dealsUrl(@Nullable String status, boolean mine, boolean overdue) {
    StringBuilder url = new StringBuilder("/purchasing/deals");
    String separator = "?";
    if (status != null) {
      url.append(separator).append("status=").append(status);
      separator = "&";
    }
    if (mine) {
      url.append(separator).append("mine=true");
      separator = "&";
    }
    if (overdue) {
      url.append(separator).append("overdue=true");
    }
    return url.toString();
  }

  private static @NotNull String partnersUrl(
      @Nullable String query, @Nullable String kind, @Nullable String status) {
    org.springframework.web.util.UriComponentsBuilder builder =
        org.springframework.web.util.UriComponentsBuilder.fromPath("/purchasing/partners");
    if (query != null) {
      builder.queryParam("query", query);
    }
    if (kind != null) {
      builder.queryParam("kind", kind);
    }
    if (status != null) {
      builder.queryParam("status", status);
    }
    return builder.encode().build().toUriString();
  }
}
