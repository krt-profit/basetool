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

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ItemDerivationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderHandoverForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderItemForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderItemHandoverForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the job-order pages ({@code /orders}, {@code /orders/{id}}, {@code
 * /orders/create} plus a number of POST-only mutators).
 *
 * <p>Job orders are the central work unit of the logistics workflow: created by squadron members,
 * assigned to logisticians, materialized via inventory handovers, and finally completed. The
 * controller covers the entire lifecycle plus the priority/status mutations, the assignee
 * management, the handover creation flow, and the unlink endpoints for materials and inventory
 * items. The status filter on the list view is persisted in a 30-day cookie so a user keeps their
 * preferred filter across sessions.
 *
 * <p>Since the #924 L5 read/write controller split this class holds only the read side — the page
 * and fragment renders plus the read-only JSON proxies; every state-mutating {@code /orders}
 * endpoint (create/update/delete, priority, status, claims, assignees, handovers, unlinks) lives
 * unchanged in {@link JobOrderWriteController}.
 */
@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class JobOrderPageController {

  private final BackendApiClient backendApiClient;
  private final RoleHierarchy roleHierarchy;
  private final ParallelPageLoader parallelPageLoader;
  private final FrontendAuthHelperService authHelper;

  private static final List<String> VALID_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "REJECTED", "COMPLETED");

  /**
   * Accepted values for the orders-index squadron-scope toggle (MULTI_SQUADRON_PLAN.md section
   * 5.3). {@code mine} restricts to orders whose creating OR requesting squadron equals the
   * caller's active squadron; {@code all} returns the cross-staffel union (the backend's natural
   * behaviour for Job Orders). Persisted in the {@code orders_filter_scope} cookie alongside the
   * status filter.
   */
  private static final List<String> VALID_SQUADRON_SCOPES = List.of("mine", "all");

  /**
   * Selectable page sizes for the order list. Larger than the shared 10/50/100 contract
   * (REQ-INV-013) by design (REQ-ORDERS-020): the LOGISTICIAN drag-and-drop priority reorder works
   * within one page, and the active queue (the default {@code OPEN}+{@code IN_PROGRESS} filter)
   * must comfortably fit on the first page so reordering stays whole in the common case. A crafted
   * out-of-list {@code size} snaps back to {@link #DEFAULT_PAGE_SIZE}.
   */
  private static final List<Integer> PAGE_SIZES = List.of(50, 100, 200);

  /** Page size applied when the request carries none (or a non-whitelisted one). */
  private static final int DEFAULT_PAGE_SIZE = 100;

  /** Response type for the paginated order-list pull ({@code GET /api/v1/orders}). */
  private static final ParameterizedTypeReference<PageResponse<JobOrderDto>> PAGE_OF_JOB_ORDER =
      new ParameterizedTypeReference<PageResponse<JobOrderDto>>() {};

  /**
   * Response type for the inventory-item lists — the order's orphaned-inventory warning ({@code GET
   * /api/v1/orders/{id}/inventory/orphaned}) and the per-material link picker ({@code GET
   * /api/v1/orders/{id}/materials/{matId}/inventory}).
   */
  private static final ParameterizedTypeReference<List<InventoryItemDto>> LIST_OF_INVENTORY_ITEM =
      new ParameterizedTypeReference<List<InventoryItemDto>>() {};

  /**
   * Response type for the item-order blueprint picker ({@code GET
   * /api/v1/orders/item-catalog/{gameItemId}/blueprints}).
   */
  private static final ParameterizedTypeReference<List<BlueprintReferenceDto>>
      LIST_OF_BLUEPRINT_REFERENCE =
          new ParameterizedTypeReference<List<BlueprintReferenceDto>>() {};

  /**
   * Response type for the orderable-item catalog page ({@code GET /api/v1/orders/item-catalog}),
   * used by both the live item search and the has-orderable-items probe.
   */
  private static final ParameterizedTypeReference<PageResponse<GameItemReferenceDto>>
      PAGE_OF_GAME_ITEM_REFERENCE =
          new ParameterizedTypeReference<PageResponse<GameItemReferenceDto>>() {};

  /**
   * Response type for the job-order material catalog ({@code GET /api/v1/materials/job-order})
   * feeding the create/edit material picker.
   */
  private static final ParameterizedTypeReference<List<MaterialDto>> LIST_OF_MATERIAL =
      new ParameterizedTypeReference<List<MaterialDto>>() {};

  /**
   * Response type for the squadron-list pull ({@code GET /api/v1/squadrons}) feeding the
   * logistician squadron picker.
   */
  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> PAGE_OF_SQUADRON =
      new ParameterizedTypeReference<PageResponse<SquadronDto>>() {};

  /**
   * Response type for the active-org-unit catalogs backing the two owner-pickers ({@code GET
   * /api/v1/org-units/active} and {@code GET /api/v1/org-units/active-all-kinds}).
   */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      LIST_OF_ORG_UNIT_MEMBERSHIP_OPTION =
          new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {};

  /**
   * Renders the job-order list ({@code /orders}). Two persisted filters drive the view:
   *
   * <ul>
   *   <li>Status filter — three-stage precedence: explicit query parameter wins, otherwise the
   *       {@code orders_filter_status} cookie (validated against {@link #VALID_STATUSES}),
   *       otherwise the default of {@code OPEN} + {@code IN_PROGRESS}.
   *   <li>Squadron scope toggle — explicit {@code scope} query parameter wins ({@code mine} = only
   *       orders whose creating OR requesting squadron equals the caller's active squadron, {@code
   *       all} = cross-staffel union), otherwise the {@code orders_filter_scope} cookie, otherwise
   *       the plan-mandated default of {@code mine} for users with an active squadron context
   *       (MULTI_SQUADRON_PLAN.md section 5.3). Callers with no active context (admins in "all
   *       squadrons" mode, anonymous) collapse silently to {@code all} regardless of the requested
   *       scope.
   * </ul>
   *
   * <p>Cookie updates: both filters write a 30-day cookie scoped to {@code /orders} whenever the
   * user changes them explicitly. Re-rendering the page without an explicit value preserves the
   * cookie untouched so back/forward navigation keeps the persisted state.
   *
   * @param status optional explicit status filter
   * @param scope optional explicit squadron-scope filter ({@code mine}/{@code all})
   * @param cookieStatus previous persisted status filter from the cookie
   * @param cookieScope previous persisted squadron-scope filter from the cookie
   * @param activeSquadronId active squadron context surfaced by {@code OrgUnitContextAdvice}; used
   *     to translate {@code scope=mine} into a backend {@code squadronId} param.
   * @param page zero-based page index, defaulted/clamped to 0 (REQ-ORDERS-020)
   * @param size requested page size; only {@link #PAGE_SIZES} are honoured, else {@link
   *     #DEFAULT_PAGE_SIZE}
   * @param response servlet response, used to update the persistence cookies
   * @param fragment when {@code "results"}, only the results-table fragment is rendered for an
   *     in-place AJAX swap (epic #571 / REQ-FE-005); otherwise the full page
   * @param model Thymeleaf model populated with orders, the page envelope ({@code ordersPage}), the
   *     {@code pageSizes}, the filter-preserving {@code paginationBaseUrl}, the selected filters
   *     and the aging thresholds for the row-color rendering
   * @return the {@code orders-index} view name, or its {@code ordersResults} fragment selector
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public String viewOrders(
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String scope,
      @CookieValue(name = "orders_filter_status", required = false) String cookieStatus,
      @CookieValue(name = "orders_filter_scope", required = false) String cookieScope,
      @ModelAttribute("activeSquadronId") UUID activeSquadronId,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      @ModelAttribute("canViewOwnJobOrders") boolean canViewOwnJobOrders,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletResponse response,
      @RequestParam(required = false) String fragment,
      Model model) {
    // Non-profit ordering-squad members (canViewJobOrders=false) still see the orders THEY placed —
    // the requester-side "Meine Auftraege" list (REQ-ORDERS-023). Only a caller with neither the
    // queue nor the requester capability is routed to the create form. requesterView drives the
    // redacted list rendering + hides the reorder/progress affordances in the template.
    boolean requesterView = !canViewJobOrders && canViewOwnJobOrders;
    if (!canViewJobOrders && !canViewOwnJobOrders) {
      return "redirect:/orders/create";
    }
    model.addAttribute("requesterView", requesterView);
    if (status == null || status.isEmpty()) {
      if (cookieStatus != null && !cookieStatus.isBlank()) {
        List<String> parsed = Arrays.asList(cookieStatus.split("-"));
        boolean allValid = parsed.stream().allMatch(VALID_STATUSES::contains);
        if (allValid) {
          status = parsed;
        } else {
          status = List.of("OPEN", "IN_PROGRESS");
        }
      } else {
        status = List.of("OPEN", "IN_PROGRESS");
      }
    } else {
      Cookie cookie = new Cookie("orders_filter_status", String.join("-", status));
      cookie.setPath("/orders");
      cookie.setMaxAge(30 * 24 * 60 * 60); // 30 days
      cookie.setHttpOnly(true);
      cookie.setSecure(true);
      response.addCookie(cookie);
    }

    // Resolve the squadron-scope filter with the same three-stage precedence (param > cookie >
    // default). "mine" is the plan-mandated default per section 5.3; only callers with a resolvable
    // active squadron can act on it, so admins in "all squadrons" mode silently collapse to "all"
    // regardless of cookie / param value (no squadron context to filter on).
    String resolvedScope;
    if (scope != null && !scope.isBlank() && VALID_SQUADRON_SCOPES.contains(scope)) {
      resolvedScope = scope;
      Cookie scopeCookie = new Cookie("orders_filter_scope", resolvedScope);
      scopeCookie.setPath("/orders");
      scopeCookie.setMaxAge(30 * 24 * 60 * 60); // 30 days
      scopeCookie.setHttpOnly(true);
      scopeCookie.setSecure(true);
      response.addCookie(scopeCookie);
    } else if (cookieScope != null
        && !cookieScope.isBlank()
        && VALID_SQUADRON_SCOPES.contains(cookieScope)) {
      resolvedScope = cookieScope;
    } else {
      resolvedScope = "mine";
    }
    boolean filterToOwnSquadron = "mine".equals(resolvedScope) && activeSquadronId != null;
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize = size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;

    List<JobOrderDto> orders = new ArrayList<>();
    PageResponse<JobOrderDto> p = null;
    int yellowDays = 30;
    int redDays = 90;
    try {
      String statusParam = String.join(",", status);
      // The requester "Meine Auftraege" list (REQ-ORDERS-023) is keyed on the caller's own
      // requesting
      // org units and takes no squadron display filter; the main queue keeps the "involving my
      // squadron" toggle. Both are paginated server-side (REQ-ORDERS-020), sorted priority,asc.
      String squadronParam =
          !requesterView && filterToOwnSquadron ? "&squadronId=" + activeSquadronId : "";
      String listBase = requesterView ? "/api/v1/orders/requested" : "/api/v1/orders";
      p =
          backendApiClient.get(
              listBase
                  + "?page="
                  + effectivePage
                  + "&size="
                  + effectiveSize
                  + "&sort=priority,asc&status="
                  + statusParam
                  + squadronParam,
              PAGE_OF_JOB_ORDER);
      if (p != null && p.content() != null) {
        orders = new ArrayList<>(p.content());
        // The nested walk exists purely to emit a per-material debug line; skip the whole
        // orders×materials iteration (and its per-row varargs allocation) when debug is off.
        if (log.isDebugEnabled()) {
          for (JobOrderDto order : orders) {
            for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialDto mat :
                order.materials()) {
              log.debug(
                  "Received stock for job order #{} ({}): {}/{} (material: {})",
                  order.displayId(),
                  order.id(),
                  mat.currentStock(),
                  mat.amount(),
                  mat.material().name());
            }
          }
        }
      }

      try {
        SystemSettingDto yellowSetting =
            backendApiClient.getCached(
                CachedCatalog.SETTING_JOB_ORDER_AGE_YELLOW, SystemSettingDto.class);
        yellowDays = Integer.parseInt(yellowSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch yellow days setting, using default");
      }
      try {
        SystemSettingDto redSetting =
            backendApiClient.getCached(
                CachedCatalog.SETTING_JOB_ORDER_AGE_RED, SystemSettingDto.class);
        redDays = Integer.parseInt(redSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch red days setting, using default");
      }
    } catch (Exception e) {
      log.error("Failed to fetch orders", e);
      log.error("Failed to load job orders", e);
      model.addAttribute("error", "error.joborder.load");
    }

    model.addAttribute("orders", orders);
    model.addAttribute("ordersPage", p);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("paginationBaseUrl", buildPaginationBaseUrl(status, resolvedScope));
    model.addAttribute("selectedStatuses", status);
    model.addAttribute("selectedScope", resolvedScope);
    // Effective state, after collapsing "mine" to "all" when there is no active squadron context
    // (admin all-squadrons mode). The template uses this to render the toggle as informational-
    // only rather than active when the user cannot meaningfully act on "mine".
    model.addAttribute("scopeFilterApplied", filterToOwnSquadron);
    model.addAttribute("ageYellowDays", yellowDays);
    model.addAttribute("ageRedDays", redDays);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "orders-index :: ordersResults";
    }
    return "orders-index";
  }

  /**
   * Builds the {@code baseUrl} the pagination fragment threads {@code page}/{@code size} onto so
   * page and size links keep the active status + scope filter. The repeatable {@code status} params
   * and the {@code scope} radio value are reproduced exactly as the filter form submits them; the
   * tokens are fixed enum/keyword names, so no extra escaping is required.
   *
   * @param status the resolved (never empty) status filter
   * @param scope the resolved squadron-scope value ({@code mine}/{@code all})
   * @return {@code /orders?status=...&scope=...} carrying the current filter
   */
  private static String buildPaginationBaseUrl(List<String> status, String scope) {
    List<String> queryParts = new ArrayList<>();
    for (String s : status) {
      queryParts.add("status=" + s);
    }
    queryParts.add("scope=" + scope);
    return "/orders?" + String.join("&", queryParts);
  }

  /**
   * Renders the order detail page ({@code /orders/{id}}). Loads the order plus the auxiliary
   * material-status and assignee lists and exposes the role-derived edit flags so the template
   * stays free of inline expression checks.
   *
   * @return the {@code order-detail} view name
   */
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public String viewOrderDetail(
      @PathVariable UUID id,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      @ModelAttribute("canViewOwnJobOrders") boolean canViewOwnJobOrders,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) String fragment) {
    // A non-profit ordering-squad member (canViewJobOrders=false) may still open the detail of an
    // order THEY placed — the backend returns a redacted view via the requester escape
    // (REQ-ORDERS-023) or 403 for a foreign order (caught below). requesterView renders the limited
    // template (no Bearbeiter, no materials summary; comment + not-yet-delivered material edit
    // only).
    // Provisional, capability-based value: enough to gate the redirect below and to render the
    // error path if the order fetch fails. Once the order is loaded it is overridden with the
    // backend's authoritative PER-ORDER redaction signal (review finding 2).
    boolean requesterView = !canViewJobOrders && canViewOwnJobOrders;
    if (!canViewJobOrders && !canViewOwnJobOrders) {
      // Neither capability: route to the create form (only order surface open to them).
      return fragment != null ? "orders-detail :: fragmentError" : "redirect:/orders/create";
    }
    model.addAttribute("requesterView", requesterView);
    try {
      JobOrderDto order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
      // Finding 2: key the detail rendering off the backend's per-order redaction flag, not the
      // global capability. A profit-eligible member who is ALSO the requester of a
      // foreign-processed
      // order (canViewJobOrders=true, so the capability value above was false) still receives the
      // redacted data from the backend — this makes the limited template match it, instead of
      // rendering the full chrome over zeroed/absent values.
      requesterView = order.redacted();
      model.addAttribute("requesterView", requesterView);
      model.addAttribute("order", order);
      model.addAttribute("currentUserId", getCurrentUserId(principal));
      model.addAttribute("itemsWithoutMaterials", itemsWithoutDerivedMaterials(order));

      boolean canAssign = !requesterView && isLogistician(principal);
      model.addAttribute("isLogistician", canAssign);
      // The requester edit modal reuses the material editor, so it needs the materials catalogue to
      // populate the "add material" picker and a prefilled jobOrderForm (comment + material lines +
      // version). No user/squadron/owner-picker lookups — the requester touches none of those.
      if (requesterView) {
        model.addAttribute("materials", fetchMaterials());
        model.addAttribute("users", new ArrayList<>());
        if (!model.containsAttribute("jobOrderForm")) {
          JobOrderForm form = new JobOrderForm();
          form.setComment(order.comment());
          form.setVersion(order.version());
          form.getMaterials().clear();
          if (order.materials() != null) {
            for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialDto mat :
                order.materials()) {
              JobOrderForm.JobOrderMaterialForm mf = new JobOrderForm.JobOrderMaterialForm();
              mf.setMaterialId(mat.material().id());
              mf.setMinQuality(mat.minQuality());
              mf.setAmount(mat.amount());
              form.getMaterials().add(mf);
            }
          }
          if (form.getMaterials().isEmpty()) {
            form.getMaterials().add(new JobOrderForm.JobOrderMaterialForm());
          }
          model.addAttribute("jobOrderForm", form);
        }
      }

      if (canAssign) {
        // These logistician-only lookups are independent; fetch them concurrently and apply the
        // results on the request thread. Each fetch helper swallows its own failure and returns an
        // empty list, so join() never throws and the page degrades exactly as the serial version
        // did. The materials / squadrons / all-kinds org-unit lookups are cache-backed catalogue
        // reads. #1193: the assignee-add picker now searches users on demand (remote-users combobox
        // -> /users/search), so the former uncached /users?size=1000 load-all is gone.
        CompletableFuture<List<MaterialDto>> materialsFuture =
            parallelPageLoader.loadAsync(this::fetchMaterials);
        CompletableFuture<List<SquadronDto>> squadronsFuture =
            parallelPageLoader.loadAsync(this::fetchSquadrons);
        CompletableFuture<List<OrgUnitMembershipOptionDto>> requestingOptionsFuture =
            parallelPageLoader.loadAsync(this::fetchRequestingOrgUnitOptions);
        CompletableFuture.allOf(materialsFuture, squadronsFuture, requestingOptionsFuture).join();
        model.addAttribute("materials", materialsFuture.join());
        model.addAttribute("squadrons", squadronsFuture.join());
        applyOwnerPickerOptions(model, requestingOptionsFuture.join());

        if (!model.containsAttribute("jobOrderForm")) {
          JobOrderForm form = new JobOrderForm();
          form.setRequestingOrgUnitId(
              order.requestingOrgUnit() != null ? order.requestingOrgUnit().id() : null);
          form.setHandle(order.handle());
          form.setComment(order.comment());
          form.setVersion(order.version());
          form.getMaterials().clear();
          if (order.materials() != null) {
            for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialDto mat :
                order.materials()) {
              JobOrderForm.JobOrderMaterialForm mf = new JobOrderForm.JobOrderMaterialForm();
              mf.setMaterialId(mat.material().id());
              mf.setMinQuality(mat.minQuality());
              mf.setAmount(mat.amount());
              form.getMaterials().add(mf);
            }
          }
          if (form.getMaterials().isEmpty()) {
            form.getMaterials().add(new JobOrderForm.JobOrderMaterialForm());
          }
          model.addAttribute("jobOrderForm", form);
        }
      }

      int yellowDays = 30;
      int redDays = 90;
      try {
        SystemSettingDto yellowSetting =
            backendApiClient.getCached(
                CachedCatalog.SETTING_JOB_ORDER_AGE_YELLOW, SystemSettingDto.class);
        yellowDays = Integer.parseInt(yellowSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch yellow days setting, using default");
      }
      try {
        SystemSettingDto redSetting =
            backendApiClient.getCached(
                CachedCatalog.SETTING_JOB_ORDER_AGE_RED, SystemSettingDto.class);
        redDays = Integer.parseInt(redSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch red days setting, using default");
      }
      model.addAttribute("ageYellowDays", yellowDays);
      model.addAttribute("ageRedDays", redDays);

      if (!model.containsAttribute("handoverForm")) {
        JobOrderHandoverForm handoverForm = new JobOrderHandoverForm();
        // The time is pre-filled client-side in the user's browser
        // (see orders-detail.html, openHandoverModal) so that the user's
        // browser timezone (not the server/container) is used.
        handoverForm.setRecipientSquadron(
            order.requestingOrgUnit() != null ? order.requestingOrgUnit().shorthand() : null);
        model.addAttribute("handoverForm", handoverForm);
      }

      // Item orders carry their own handover form (per-line whole-unit delivery). Empty by default;
      // the modal renders one row per still-outstanding ordered-item line, bound by request-param
      // name. The flag gates the "log handover" button so it hides once every line is delivered.
      if (!model.containsAttribute("itemHandoverForm")) {
        model.addAttribute("itemHandoverForm", new JobOrderItemHandoverForm());
      }
      model.addAttribute("hasOutstandingItemLines", hasOutstandingItemLines(order));

      // Item-order blueprint coverage: which members of the responsible squadron/SK own the
      // blueprints for the ordered items. The backend gates this members-only (it returns 403 for a
      // non-member viewing an otherwise-public SK order), so the call is isolated in its own
      // try/catch — on any failure the section is simply omitted rather than failing the whole
      // page.
      if ("ITEM".equals(order.type())) {
        try {
          model.addAttribute(
              "itemBlueprintOwners",
              backendApiClient.get(
                  "/api/v1/orders/" + id + "/item-blueprint-owners",
                  JobOrderItemBlueprintOwnersDto.class));
        } catch (Exception e) {
          log.debug("Blueprint coverage unavailable for order {}: {}", id, e.getMessage());
        }
      }

      // Orphaned linked inventory (REQ-ORDERS-019): inventory linked to this order whose material
      // the order does not require — invisible in the material tables, surfaced as a warning so a
      // logistician can undo a mis-assignment. Only needed on the full page (not the in-place
      // section swaps), and isolated so a failure just omits the warning rather than failing the
      // page.
      if (fragment == null) {
        try {
          model.addAttribute(
              "orphanedInventory",
              backendApiClient.get(
                  "/api/v1/orders/" + id + "/inventory/orphaned", LIST_OF_INVENTORY_ITEM));
        } catch (Exception e) {
          log.debug("Orphaned inventory unavailable for order {}: {}", id, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error("Failed to fetch order", e);
      log.error("Failed to load job order", e);
      if (fragment != null) {
        return "orders-detail :: fragmentError";
      }
      model.addAttribute("error", "error.joborder.load.details");
      return "redirect:/orders";
    }
    // In-place section swap (#571/#575): re-render only the section the caller mutated. The full
    // model is already built above, so each fragment renders with every attribute it needs.
    if (fragment != null) {
      return switch (fragment.toLowerCase(java.util.Locale.ROOT)) {
        case "materials" -> "orders-detail :: materialsSection";
        case "aggregated" -> "orders-detail :: aggregatedSection";
        case "header" -> "orders-detail :: orderHeader";
        case "handovers" -> "orders-detail :: materialHandoverSection";
        case "items" -> "orders-detail :: itemsSection";
        case "item-handovers" -> "orders-detail :: itemHandoverSection";
        case "item-handover-lines" -> "orders-detail :: itemHandoverLines";
        case "blueprint-owners" -> "orders-detail :: blueprintOwnersSection";
        default -> "orders-detail";
      };
    }
    return "orders-detail";
  }

  /**
   * Renders the create-order form ({@code /orders/create}). Seeds the materials + orderable-item
   * catalogs and the two owner-pickers; the {@code source} parameter threads through so the
   * post-save redirect can return to the originating page. For an anonymous guest, the responsible
   * (processing) picker is pre-selected to the configured intake Spezialkommando (see {@link
   * #preselectIntakeForGuest}).
   *
   * @param source optional origin marker
   * @param principal the caller, or {@code null} for an anonymous guest
   * @param model Thymeleaf model populated with form and reference catalogs
   * @return the {@code order-create} view name
   */
  @GetMapping("/create")
  public String viewCreateForm(
      @RequestParam(required = false) String source,
      @AuthenticationPrincipal OidcUser principal,
      Model model) {
    if (!model.containsAttribute("jobOrderForm")) {
      JobOrderForm form = new JobOrderForm();
      form.setSource(source);
      model.addAttribute("jobOrderForm", form);
    } else {
      JobOrderForm form = (JobOrderForm) model.getAttribute("jobOrderForm");
      if (form != null && form.getSource() == null) {
        form.setSource(source);
      }
    }
    if (!model.containsAttribute("jobOrderItemForm")) {
      JobOrderItemForm itemForm = new JobOrderItemForm();
      itemForm.setSource(source);
      model.addAttribute("jobOrderItemForm", itemForm);
    }
    model.addAttribute("materials", fetchMaterials());
    model.addAttribute("hasOrderableItems", hasOrderableItems());
    model.addAttribute("squadrons", fetchSquadrons());
    addOwnerPickerOptions(model);
    // Anonymous guests have no org-unit context; default the responsible (processing) picker to the
    // configured intake Spezialkommando — the unit the backend routes a guest order to absent a
    // profit-eligible pick — so the form shows it up front. Authenticated callers pick their own.
    if (authHelper.isAnonymous()) {
      preselectIntakeForGuest(model);
    }
    return "orders-create";
  }

  /**
   * Renders the item-order edit page — the create form reused in edit mode. Loads the order, blocks
   * non-item orders and orders that already have an item-handover (those are frozen), prefills the
   * metadata form, and injects the existing item lines as {@code window.EDIT_ITEMS} so the create
   * form's JS rebuilds them (item + blueprint + amount + per-material quality + sub-assembly
   * parent).
   *
   * @param id the item-order id
   * @param model Thymeleaf model
   * @param redirectAttributes flash carrier for the block cases
   * @return the {@code orders-create} view (in edit mode) or a redirect to the detail page
   */
  @GetMapping("/{id}/items/edit")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public String viewEditItemForm(
      @PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
    JobOrderDto order;
    try {
      order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
    } catch (Exception e) {
      log.error("Failed to load item order for editing", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.load.details");
      return "redirect:/orders";
    }
    if (order == null || !"ITEM".equals(order.type())) {
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.item.edit.notItem");
      return "redirect:/orders/" + id;
    }
    if (order.itemHandovers() != null && !order.itemHandovers().isEmpty()) {
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.item.edit.hasHandovers");
      return "redirect:/orders/" + id;
    }

    // The shared create template renders both the (hidden) material form and the item form, so the
    // material form's th:object bean must exist even in item-edit mode.
    if (!model.containsAttribute("jobOrderForm")) {
      model.addAttribute("jobOrderForm", new JobOrderForm());
    }
    if (!model.containsAttribute("jobOrderItemForm")) {
      JobOrderItemForm itemForm = new JobOrderItemForm();
      itemForm.setHandle(order.handle());
      itemForm.setComment(order.comment());
      itemForm.setRequestingOrgUnitId(
          order.requestingOrgUnit() != null ? order.requestingOrgUnit().id() : null);
      itemForm.setVersion(order.version());
      model.addAttribute("jobOrderItemForm", itemForm);
    }
    model.addAttribute("editOrderId", id);
    model.addAttribute("editItems", buildEditItems(order));
    model.addAttribute("materials", fetchMaterials());
    model.addAttribute("hasOrderableItems", hasOrderableItems());
    model.addAttribute("squadrons", fetchSquadrons());
    addOwnerPickerOptions(model);
    return "orders-create";
  }

  /**
   * Builds the prefill payload for {@code window.EDIT_ITEMS}: one entry per ordered-item line with
   * its game item, blueprint, amount, the index of its sub-assembly parent line (or {@code null}),
   * and a material-id → quality map. The list index doubles as the client line id, so a child's
   * {@code parentId} is the list index of its parent.
   *
   * @param order the item order being edited
   * @return the JS-serializable prefill list (Thymeleaf inlines it as a JSON array)
   */
  private List<java.util.Map<String, Object>> buildEditItems(JobOrderDto order) {
    List<de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto> items =
        order.items() != null ? order.items() : List.of();
    java.util.Map<UUID, Integer> idToIndex = new java.util.HashMap<>();
    for (int i = 0; i < items.size(); i++) {
      idToIndex.put(items.get(i).id(), i);
    }
    List<java.util.Map<String, Object>> result = new ArrayList<>();
    for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto line : items) {
      java.util.Map<String, String> qualities = new java.util.LinkedHashMap<>();
      if (line.materials() != null) {
        for (de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemMaterialDto m :
            line.materials()) {
          if (m.material() != null && m.material().id() != null) {
            qualities.put(m.material().id().toString(), m.qualityRequirement());
          }
        }
      }
      java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
      entry.put("gameItemId", line.gameItem() != null ? line.gameItem().id().toString() : null);
      // The item picker now loads its options on demand, so the saved item's name must travel with
      // the prefill to seed the combobox's displayed label (the id alone would render blank).
      entry.put("gameItemName", line.gameItem() != null ? line.gameItem().name() : null);
      entry.put("blueprintId", line.blueprint() != null ? line.blueprint().id().toString() : null);
      entry.put("amount", line.amount() != null ? line.amount() : 1);
      entry.put(
          "parentId", line.parentItemId() != null ? idToIndex.get(line.parentItemId()) : null);
      entry.put("qualities", qualities);
      result.add(entry);
    }
    return result;
  }

  /**
   * JSON proxy for the create form's blueprint picker: returns the blueprints that produce the
   * given orderable item. Relays to the backend item-catalog endpoint.
   *
   * @param gameItemId the orderable item
   * @return the blueprint references producing it
   */
  @GetMapping("/item-blueprints/{gameItemId}")
  @ResponseBody
  public List<BlueprintReferenceDto> itemBlueprints(@PathVariable UUID gameItemId) {
    try {
      List<BlueprintReferenceDto> result =
          backendApiClient.get(
              "/api/v1/orders/item-catalog/" + gameItemId + "/blueprints",
              LIST_OF_BLUEPRINT_REFERENCE,
              true);
      return result != null ? result : List.of();
    } catch (Exception e) {
      log.error("Failed to fetch blueprints for item {}", gameItemId, e);
      return List.of();
    }
  }

  /**
   * JSON proxy for the create form's material-derivation preview: returns the resolved materials,
   * sub-assembly suggestions and unresolved-ingredient names for a blueprint at the given amount.
   *
   * @param blueprintId the chosen blueprint
   * @param amount the whole-unit amount to scale by (defaults to 1)
   * @return the derivation preview, or {@code null} on failure
   */
  @GetMapping("/item-derivation/{blueprintId}")
  @ResponseBody
  public ItemDerivationDto itemDerivation(
      @PathVariable UUID blueprintId,
      @RequestParam(required = false, defaultValue = "1") int amount) {
    try {
      return backendApiClient.get(
          "/api/v1/orders/item-catalog/blueprints/" + blueprintId + "/derivation?amount=" + amount,
          ItemDerivationDto.class,
          true);
    } catch (Exception e) {
      log.error("Failed to derive materials for blueprint {}", blueprintId, e);
      return null;
    }
  }

  /**
   * JSON proxy for the item-order picker's live search: looks up orderable items by a free-text
   * term and returns the matching references (capped server-side at 25). Replaces preloading the
   * first N items and filtering client-side, which silently hid every item past the alphabetical
   * cap once the catalog outgrew it. Public for parity with the anonymous create form; the term
   * rides as a URI variable so spaces and quotes survive the frontend&rarr;backend hop intact.
   *
   * @param q the case-insensitive item-name search term ({@code null} / blank = first page)
   * @return up to 25 matching orderable item references, or an empty list on failure
   */
  @GetMapping("/item-search")
  @ResponseBody
  public List<GameItemReferenceDto> itemSearch(@RequestParam(required = false) String q) {
    try {
      PageResponse<GameItemReferenceDto> page =
          backendApiClient.getPublic(
              "/api/v1/orders/item-catalog?search={q}&size=25&sort=name,asc",
              PAGE_OF_GAME_ITEM_REFERENCE,
              q == null ? "" : q);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception e) {
      log.error("Failed to search orderable items", e);
      return List.of();
    }
  }

  /**
   * Returns {@code true} iff the option list contains at least one Spezialkommando entry. Computed
   * server-side because Thymeleaf's SpEL backend cannot parse Java lambda syntax inside template
   * expressions ({@code .stream().anyMatch(o -> …)} fails with EL1042E), and pre-computing the flag
   * keeps the template free of {@code #lists.contains(list.![kind], …)}-style gymnastics.
   *
   * @param options the active-org-unit list, may be {@code null}.
   * @return {@code true} when at least one row's {@code kind} is {@code SPECIAL_COMMAND}.
   */
  private static boolean containsSpecialCommand(List<OrgUnitMembershipOptionDto> options) {
    if (options == null) {
      return false;
    }
    for (OrgUnitMembershipOptionDto o : options) {
      if ("SPECIAL_COMMAND".equals(o.kind())) {
        return true;
      }
    }
    return false;
  }

  /**
   * AJAX endpoint that lists inventory items eligible for linking to the given material on the
   * given order. Used by the order-detail page's "link inventory" picker.
   *
   * @return list of inventory items (raw JSON), or 500 on backend failure
   */
  @GetMapping("/{id}/materials/{matId}/inventory")
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public List<InventoryItemDto> getInventoryItemsForMaterial(
      @PathVariable UUID id, @PathVariable UUID matId) {
    try {
      return backendApiClient.get(
          "/api/v1/orders/" + id + "/materials/" + matId + "/inventory", LIST_OF_INVENTORY_ITEM);
    } catch (Exception e) {
      log.error("Failed to fetch inventory items for job order {} and material {}", id, matId, e);
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to load inventory items");
    }
  }

  private List<MaterialDto> fetchMaterials() {
    try {
      List<MaterialDto> list =
          backendApiClient.getCached(CachedCatalog.MATERIALS_JOB_ORDER, LIST_OF_MATERIAL, true);
      if (list != null) {
        return new ArrayList<>(list);
      }
    } catch (Exception e) {
      log.error("Failed to fetch job-order materials", e);
    }
    return new ArrayList<>();
  }

  /**
   * Probes whether the backend has at least one orderable item (a blueprint output with a
   * resolvable material). The item-order form needs only this existence flag at render time — to
   * choose between the picker and the "no orderable items" banner — because the picker itself now
   * searches the catalog live ({@code GET /orders/item-search}) instead of preloading every item. A
   * {@code size=1} probe stays cheap regardless of catalog size; a backend hiccup fails open
   * (assume items exist) so a transient blip never hides the live-search picker behind the empty
   * banner.
   *
   * @return {@code true} when at least one orderable item exists (or the probe could not decide)
   */
  private boolean hasOrderableItems() {
    try {
      PageResponse<GameItemReferenceDto> page =
          backendApiClient.getCached(CachedCatalog.ITEM_CATALOG, PAGE_OF_GAME_ITEM_REFERENCE, true);
      return page == null || page.content() == null || !page.content().isEmpty();
    } catch (Exception e) {
      log.error("Failed to probe orderable items", e);
      return true;
    }
  }

  /**
   * Collects the display names of an item order's lines whose blueprint derived no procurable
   * material (an empty {@code materials} snapshot). The persisted order keeps only resolved
   * requirements, so an empty list is the detail-time signal that a recipe's RESOURCE ingredients
   * were all unresolved or ITEM-only; the detail view surfaces these in a warning banner so the
   * logistician knows the aggregated-materials view is incomplete for that line. Returns an empty
   * list for material orders or when every line derived at least one material.
   *
   * @param order the loaded order (any kind)
   * @return distinct item names lacking derived materials, in line order; never {@code null}
   */
  private List<String> itemsWithoutDerivedMaterials(JobOrderDto order) {
    List<String> names = new ArrayList<>();
    if (order == null || !"ITEM".equals(order.type()) || order.items() == null) {
      return names;
    }
    for (JobOrderItemDto item : order.items()) {
      if (item.materials() == null || item.materials().isEmpty()) {
        String name = item.gameItem() != null ? item.gameItem().name() : null;
        if (name != null && !names.contains(name)) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /**
   * Reports whether an item order still has at least one ordered-item line with outstanding
   * (ordered minus delivered) whole units. Gates the item-handover button: once every line is fully
   * delivered the order is COMPLETED and the button is hidden. Always {@code false} for material
   * orders or an order with no item lines.
   *
   * @param order the loaded order (any kind)
   * @return {@code true} if any item line has a positive outstanding quantity
   */
  private boolean hasOutstandingItemLines(JobOrderDto order) {
    if (order == null || !"ITEM".equals(order.type()) || order.items() == null) {
      return false;
    }
    for (JobOrderItemDto item : order.items()) {
      int ordered = item.amount() != null ? item.amount() : 0;
      int delivered = item.deliveredAmount() != null ? item.deliveredAmount() : 0;
      if (ordered - delivered > 0) {
        return true;
      }
    }
    return false;
  }

  private List<SquadronDto> fetchSquadrons() {
    try {
      PageResponse<SquadronDto> p =
          backendApiClient.getCached(CachedCatalog.SQUADRONS, PAGE_OF_SQUADRON, true);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch squadrons", e);
    }
    return new ArrayList<>();
  }

  /**
   * Fetches the active-org-units catalog from {@code GET /api/v1/org-units/active} — the single
   * source for both Job Order owner-pickers. Job Orders are cross-staffel workspaces, so the picker
   * offers every active Staffel + Spezialkommando, not just the caller's memberships; each option
   * carries its {@code isProfitEligible} flag so {@link #addOwnerPickerOptions} can derive the
   * responsible picker from the requesting one without a second, authenticated SK-catalog call.
   * Read through the public client ({@code isPublic = true}) because the endpoint is {@code
   * permitAll}: the create form is reachable anonymously, and the authenticated client has no
   * bearer token for a guest — it would 401 and leave both pickers empty. Falls back to an empty
   * list on backend hiccup so the rest of the form stays renderable.
   *
   * @return picker options or empty list; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchActiveOrgUnitOptions() {
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.getCached(
              CachedCatalog.ORG_UNITS_ACTIVE, LIST_OF_ORG_UNIT_MEMBERSHIP_OPTION, true);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch active org units for Job Order owner-picker", e);
      return List.of();
    }
  }

  /**
   * Resolves the requesting (Auftraggeber) picker options. An authenticated caller gets every
   * active org unit of all four kinds — including Bereiche and the Organisationsleitung (epic #692)
   * — so a Bereichsleitung/OL member can place an order on behalf of their tier; this reads the
   * authenticated {@code /active-all-kinds} catalog. The anonymous public request form keeps the
   * Staffel/SK-only {@code permitAll} catalog: a guest is external and does not place orders for an
   * internal Bereich/OL, and the all-kinds endpoint is authenticated-only. The responsible picker
   * is the profit-eligible subset of whatever this returns, and Bereiche/OL are never
   * profit-eligible, so they can only ever be the customer, never the processor. Falls back to the
   * public Staffel/SK catalog on any backend hiccup so the form stays renderable.
   *
   * @return requesting-picker options; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchRequestingOrgUnitOptions() {
    if (!isAuthenticatedCaller()) {
      return fetchActiveOrgUnitOptions();
    }
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.getCached(
              CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS, LIST_OF_ORG_UNIT_MEMBERSHIP_OPTION);
      return options != null ? options : fetchActiveOrgUnitOptions();
    } catch (Exception e) {
      log.warn(
          "Failed to fetch all-kind org units for the Job Order requesting picker; falling back to"
              + " the Staffel/SK catalog",
          e);
      return fetchActiveOrgUnitOptions();
    }
  }

  /**
   * Whether the current request carries an authenticated (non-anonymous) principal. Mirrors the
   * {@code SecurityContextHolder} read used by {@link #isLogistician}; used to decide whether the
   * requesting picker may offer the Bereich/OL tiers.
   *
   * @return {@code true} for an authenticated caller, {@code false} for an anonymous guest.
   */
  private boolean isAuthenticatedCaller() {
    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    return auth != null
        && auth.isAuthenticated()
        && !(auth
            instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
  }

  /**
   * Populates the create/edit form model with the two owner-picker option lists, both derived from
   * a single {@link #fetchRequestingOrgUnitOptions} fetch: {@code requestingOptions} (the customer)
   * is the full list — every active Staffel/SK for a guest, plus the Bereich/OL tiers for an
   * authenticated caller (epic #692) — and {@code responsibleOptions} (only profit-eligible
   * squadrons + SKs may process orders) is the {@code isProfitEligible} subset, which excludes
   * Bereiche/OL because they are never profit-eligible. Each carries a boolean flag telling the
   * template whether to render the SK optgroup.
   *
   * @param model the Thymeleaf model to populate.
   */
  private void addOwnerPickerOptions(Model model) {
    applyOwnerPickerOptions(model, fetchRequestingOrgUnitOptions());
  }

  /**
   * Populates the two owner-picker model attributes from an already-fetched requesting-org-unit
   * option list. Split out from {@link #addOwnerPickerOptions(Model)} so the order-detail render
   * can fetch the list on a {@link ParallelPageLoader} worker thread alongside the other
   * logistician lookups and then apply the cheap in-memory derivation on the request thread; {@link
   * #addOwnerPickerOptions(Model)} remains the serial fetch-and-apply entry point for the
   * create/edit forms.
   *
   * @param model the Thymeleaf model to populate.
   * @param requestingOptions the requesting-org-unit options to expose, and to derive the
   *     profit-eligible {@code responsibleOptions} subset from.
   */
  private void applyOwnerPickerOptions(
      Model model, List<OrgUnitMembershipOptionDto> requestingOptions) {
    List<OrgUnitMembershipOptionDto> responsibleOptions =
        requestingOptions.stream().filter(o -> Boolean.TRUE.equals(o.isProfitEligible())).toList();
    model.addAttribute("responsibleOptions", responsibleOptions);
    model.addAttribute("responsibleHasSpecialCommand", containsSpecialCommand(responsibleOptions));
    model.addAttribute("requestingOptions", requestingOptions);
    model.addAttribute("requestingHasSpecialCommand", containsSpecialCommand(requestingOptions));
  }

  /**
   * Pre-selects the configured intake Spezialkommando as the responsible (processing) unit on the
   * anonymous create form's two form beans, but only when the caller has not already chosen one (a
   * re-render after a failed submit keeps their pick). Mirrors the backend's guest fallback — a
   * guest order with no profit-eligible pick routes to the intake SK — so the form shows up front
   * the unit the order will actually land on. Never invoked for authenticated callers (they pick
   * their own unit). No-op when no intake SK is configured.
   *
   * @param model the Thymeleaf model carrying the two form beans.
   */
  private void preselectIntakeForGuest(Model model) {
    UUID intakeId = fetchIntakeSpecialCommandId();
    if (intakeId == null) {
      return;
    }
    if (model.getAttribute("jobOrderForm") instanceof JobOrderForm form
        && form.getResponsibleOrgUnitId() == null) {
      form.setResponsibleOrgUnitId(intakeId);
    }
    if (model.getAttribute("jobOrderItemForm") instanceof JobOrderItemForm itemForm
        && itemForm.getResponsibleOrgUnitId() == null) {
      itemForm.setResponsibleOrgUnitId(intakeId);
    }
  }

  /**
   * Resolves the configured intake Spezialkommando id (system setting {@code
   * job_order.intake_special_command_id}) for the anonymous responsible-picker preselection. Read
   * through the public client because the settings endpoint is {@code permitAll} and the create
   * form is reachable by guests. Returns {@code null} when the setting is unset / blank / malformed
   * or the backend call fails, so the picker simply renders with nothing preselected.
   *
   * @return the intake SK id, or {@code null} when none is configured / resolvable.
   */
  private UUID fetchIntakeSpecialCommandId() {
    try {
      SystemSettingDto setting =
          backendApiClient.get(
              "/api/v1/settings/job_order.intake_special_command_id", SystemSettingDto.class, true);
      if (setting != null && setting.value() != null && !setting.value().isBlank()) {
        return UUID.fromString(setting.value().trim());
      }
    } catch (Exception e) {
      log.warn("Failed to resolve intake Spezialkommando id for responsible-picker preselection");
    }
    return null;
  }

  private UUID getCurrentUserId(OidcUser principal) {
    if (principal == null) {
      return null;
    }
    try {
      return UUID.fromString(principal.getSubject());
    } catch (Exception e) {
      try {
        UserDto me = backendApiClient.get("/api/v1/users/me", UserDto.class);
        return me != null ? me.id() : null;
      } catch (Exception ex) {
        log.warn("Failed to get current user ID from backend: {}", ex.getMessage());
        return null;
      }
    }
  }

  private boolean isLogistician(OidcUser principal) {
    if (principal == null) {
      return false;
    }

    org.springframework.security.core.Authentication auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    Collection<? extends GrantedAuthority> authorities =
        (auth != null) ? auth.getAuthorities() : principal.getAuthorities();

    Collection<? extends GrantedAuthority> reachableAuthorities =
        roleHierarchy.getReachableGrantedAuthorities(authorities);
    // REQ-OBS-004: log a stable short pseudonym, never principal.getName() (the Keycloak
    // preferred_username handle, which PiiMasker does not scrub).
    log.debug(
        "JobOrder: Checking logistician status for user u-{}. Original authorities: {}."
            + " Reachable authorities: {}",
        Integer.toHexString(java.util.Objects.hashCode(principal.getName())),
        authorities,
        reachableAuthorities);
    boolean result =
        reachableAuthorities.stream()
            .anyMatch(
                a ->
                    a.getAuthority().equals(Roles.authority(Roles.LOGISTICIAN))
                        || a.getAuthority().equals(Roles.authority(Roles.ADMIN))
                        || a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    log.debug("JobOrder: Is logistician: {}", result);
    return result;
  }
}
