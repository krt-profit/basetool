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

import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefiningMethodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryGoodForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreItemForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * REST controller for RefineryOrderPageController endpoints.
 *
 * <p>Since the #924 L5 read/write split, this class only renders the refinery-order pages and
 * read-only AJAX lookups (plus the non-mutating import relay); the write half — the classic create
 * / update / delete / store handlers and their AJAX twins — lives in {@link
 * RefineryOrderWriteController}.
 */
@Controller
@RequestMapping("/refinery-orders")
@RequiredArgsConstructor
@Slf4j
public class RefineryOrderPageController {

  /** Selectable page sizes for the order list (shared REQ-INV-013 contract: 10/50/100). */
  private static final List<Integer> PAGE_SIZES = List.of(10, 50, 100);

  /** Page size applied when the request carries none (or a non-whitelisted one). */
  private static final int DEFAULT_PAGE_SIZE = 50;

  /**
   * The detail page's {@code ?fragment=} selector for the edit form / goods editor + action row.
   */
  private static final String FRAGMENT_ORDER = "order";

  /** The detail page's {@code ?fragment=} selector for the Einlagern dialog's form body. */
  private static final String FRAGMENT_STORE = "store";

  /** Captured generic type for decoding a paged {@code /refinery-orders} list response. */
  private static final ParameterizedTypeReference<PageResponse<RefineryOrderListDto>>
      REFINERY_ORDER_LIST_PAGE =
          new ParameterizedTypeReference<PageResponse<RefineryOrderListDto>>() {};

  /** Captured generic type for the per-material UEX yield map ({@code materialId -> percent}). */
  private static final ParameterizedTypeReference<Map<String, Integer>> STRING_INTEGER_MAP =
      new ParameterizedTypeReference<Map<String, Integer>>() {};

  /** Captured generic type for decoding the cached paged materials catalog. */
  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> MATERIAL_PAGE =
      new ParameterizedTypeReference<PageResponse<MaterialDto>>() {};

  /** Captured generic type for decoding the cached paged refining-methods catalog. */
  private static final ParameterizedTypeReference<PageResponse<RefiningMethodDto>>
      REFINING_METHOD_PAGE = new ParameterizedTypeReference<PageResponse<RefiningMethodDto>>() {};

  /** Captured generic type for decoding the cached paged locations catalog. */
  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE =
      new ParameterizedTypeReference<PageResponse<LocationDto>>() {};

  /** Captured generic type for the cached refinery-locations list. */
  private static final ParameterizedTypeReference<List<LocationDto>> LOCATION_LIST =
      new ParameterizedTypeReference<List<LocationDto>>() {};

  /** Captured generic type for decoding the paged missions catalog. */
  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_LIST_PAGE =
      new ParameterizedTypeReference<PageResponse<MissionListDto>>() {};

  /** Captured generic type for the OrgUnit-membership option rows backing the owner pickers. */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST =
          new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {};

  /** Captured generic type for the active job-order reference projections (store dropdown). */
  private static final ParameterizedTypeReference<List<JobOrderReferenceDto>>
      JOB_ORDER_REFERENCE_LIST = new ParameterizedTypeReference<List<JobOrderReferenceDto>>() {};

  private final BackendApiClient backendApiClient;
  private final RoleHierarchy roleHierarchy;
  private final IngestHandoffService ingestHandoffService;
  private final ParallelPageLoader parallelPageLoader;

  /**
   * Renders one server-side page of the refinery-order list ({@code /refinery-orders}). Default
   * filter is {@code OPEN}+{@code IN_PROGRESS} so the operational view is uncluttered by
   * completed/canceled orders. The {@code onlyMine} toggle switches between the all-orders endpoint
   * and the per-user endpoint; both are fetched one {@link #PAGE_SIZES}-bounded page at a time
   * (sorted by {@code startedAt} desc) instead of the former unbounded {@code size=1000} pull, so a
   * large order history no longer loads in a single response (REQ-REFINERY-019). {@code size} is
   * restricted to {@link #PAGE_SIZES} so a crafted query string cannot reinstate the unbounded
   * fetch.
   *
   * @param status optional list of statuses to include
   * @param onlyMine if true, restrict to the caller's own orders
   * @param page zero-based page index, defaulted/clamped to 0
   * @param size requested page size; only {@link #PAGE_SIZES} are honoured, else the default
   * @param fragment when {@code "results"}, only the results-table fragment is rendered for an
   *     in-place AJAX swap (epic #571 / REQ-FE-005); otherwise the full page
   * @param model Thymeleaf model populated with {@code orders}, the page envelope ({@code
   *     ordersPage}), the {@code pageSizes} and the filter-preserving {@code paginationBaseUrl},
   *     plus the selected statuses and the full status list for the filter UI
   * @param principal authenticated OIDC user (used to derive the logistician hint)
   * @return the {@code refinery-orders-index} view name, or its {@code refineryOrdersResults}
   *     fragment selector
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public String viewOrders(
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) Boolean onlyMine,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    if (status == null || status.isEmpty()) {
      status = List.of("OPEN", "IN_PROGRESS");
    }
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize = size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;

    boolean isLogistician = isLogistician(principal);

    List<RefineryOrderListDto> orders = new ArrayList<>();
    PageResponse<RefineryOrderListDto> p = null;
    try {
      String statusParam = String.join(",", status);
      // Everyone sees all orders (read-only for normal members); the per-user endpoint backs the
      // "Meine Auftraege" toggle. Both are paginated server-side now (REQ-REFINERY-019).
      String endpoint =
          Boolean.TRUE.equals(onlyMine)
              ? "/api/v1/refinery-orders/my-orders"
              : "/api/v1/refinery-orders/all";
      String url =
          endpoint
              + "?page="
              + effectivePage
              + "&size="
              + effectiveSize
              + "&sort=startedAt,desc&status="
              + statusParam;
      p = backendApiClient.get(url, REFINERY_ORDER_LIST_PAGE);
      if (p != null && p.content() != null) {
        orders = new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch refinery orders", e);
      model.addAttribute("errorToast", "error.refineryorder.load");
    }
    model.addAttribute("orders", orders);
    model.addAttribute("ordersPage", p);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("paginationBaseUrl", buildPaginationBaseUrl(status, onlyMine));
    model.addAttribute("selectedStatuses", status);
    model.addAttribute("onlyMine", Boolean.TRUE.equals(onlyMine));
    model.addAttribute("allStatuses", List.of("OPEN", "IN_PROGRESS", "COMPLETED", "CANCELED"));
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "refinery-orders-index :: refineryOrdersResults";
    }
    return "refinery-orders-index";
  }

  /**
   * Builds the {@code baseUrl} the pagination fragment threads {@code page}/{@code size} onto so
   * page and size links preserve the active filter. The repeatable {@code status} params and the
   * {@code onlyMine} flag are reproduced exactly as the filter form submits them; the status tokens
   * are fixed enum names, so no extra escaping is required.
   *
   * @param status the resolved (never empty) status filter
   * @param onlyMine the resolved own-orders toggle
   * @return {@code /refinery-orders?status=...&onlyMine=true} carrying the current filter
   */
  private static String buildPaginationBaseUrl(List<String> status, Boolean onlyMine) {
    List<String> queryParts = new ArrayList<>();
    for (String s : status) {
      queryParts.add("status=" + s);
    }
    if (Boolean.TRUE.equals(onlyMine)) {
      queryParts.add("onlyMine=true");
    }
    return "/refinery-orders?" + String.join("&", queryParts);
  }

  /**
   * Renders the create-order form ({@code /refinery-orders/create}). Seeds an empty form with the
   * current user as the default owner; loads the materials, methods, locations, missions, user list
   * and the refinery rounding mode for the form's dropdowns and computations.
   *
   * @param source optional origin marker for the return-to-page link after save
   * @param model Thymeleaf model populated with the form and reference catalogs
   * @param principal authenticated OIDC user (used to derive the default owner)
   * @return the {@code refinery-orders-create} view name
   */
  @GetMapping("/create")
  @PreAuthorize("isAuthenticated()")
  public String viewCreateForm(
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String handoff,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    RefineryOrderForm form;
    if (model.containsAttribute("refineryOrderForm")) {
      // A flashed form (the classic upload redirect) wins over a fresh / handoff form.
      form = (RefineryOrderForm) model.getAttribute("refineryOrderForm");
      if (form != null && form.getSource() == null) {
        form.setSource(source);
      }
    } else {
      form = new RefineryOrderForm();
      form.setSource(source);
      UUID currentUserId = getCurrentUserId(principal);
      if (currentUserId != null) {
        form.setOwnerId(currentUserId);
      }
      // One-click ingest (epic #639, REQ-INGEST-004): a `?handoff=<id>` from the desktop extractor.
      // This navigational GET is a SAFE request and must NOT consume the single-use handoff: a
      // speculative browser prefetch or a duplicate top-level load of this URL would otherwise burn
      // the token on the first (often invisible) request and leave the real navigation showing the
      // ingest.handoff.notFound notice on every send (the 2026-07-19 Firefox double-GET incident).
      // Instead of consuming here, expose the id so the create page's JS can POST it to
      // importHandoff below -- a script-initiated request a page prefetch never issues -- which
      // performs the one-time consume and swaps the pre-filled fragment in place, exactly like the
      // manual upload. Consuming stays off every navigational GET.
      if (handoff != null && !handoff.isBlank()) {
        model.addAttribute("pendingHandoffId", handoff);
      }
    }
    populateCreateFormModel(model, form, principal);
    return "refinery-orders-create";
  }

  /**
   * Consumes a one-click ingest handoff and maps its staged refinery draft into a pre-filled form,
   * adding the same review attributes (issues + counters) the manual import renders. Returns {@code
   * null} when the handoff is unknown / expired / foreign / wrong-kind or carries no order, so the
   * caller falls back to the fresh form plus an inline notice.
   *
   * @param handoff the {@code ?handoff=} id from the extractor
   * @param principal the authenticated user (its Keycloak subject scopes the staged lookup)
   * @param model the model to enrich with the import review attributes on success
   * @return the pre-filled form, or {@code null} when there is nothing to hand off
   */
  private RefineryOrderForm applyRefineryHandoff(String handoff, OidcUser principal, Model model) {
    RefineryImportDraftDto draft =
        ingestHandoffService
            .consume(
                CurrentUser.userIdText(principal),
                handoff,
                HandoffKind.REFINERY,
                RefineryImportDraftDto.class)
            .orElse(null);
    if (draft == null || draft.order() == null) {
      return null;
    }
    model.addAttribute("importIssues", RefineryImportProxyController.generalIssues(draft.issues()));
    model.addAttribute("importRowIssues", RefineryImportProxyController.rowIssues(draft.issues()));
    model.addAttribute("importGoodsMatched", draft.goodsMatched());
    model.addAttribute("importGoodsTotal", draft.goodsTotal());
    model.addAttribute("importRowsSkipped", draft.rowsSkipped());
    return RefineryImportProxyController.toForm(draft.order());
  }

  /**
   * Populates {@code model} with everything the refinery create form needs to render: the bound
   * {@code refineryOrderForm}, the logistician flag, the catalog dropdowns (materials, methods,
   * locations, missions, users), the rounding mode, the UEX yield map for the form's
   * currently-selected location and the owner-picker options. Shared by {@link #viewCreateForm}
   * (full page) and {@link #importExtractAjax} (in-place import fragment) so both render an
   * identical form.
   *
   * <p>The yield map is preloaded for any location already on the form (validation re-render or
   * screenshot import) so the bonus badges paint without an extra round-trip; a fresh form has no
   * location, falls through to an empty map, and the client-side {@code onLocationChange} handler
   * fetches it when the user picks a refinery.
   *
   * @param model the Thymeleaf model to populate
   * @param form the create form to bind (fresh, validation-failed or import-prefilled); a {@code
   *     null} form (defensive flash path) is replaced by a fresh owner-prefilled one so the
   *     template never iterates a null {@code *{goods}}
   * @param principal the authenticated user (drives the logistician flag and the owner options)
   */
  private void populateCreateFormModel(Model model, RefineryOrderForm form, OidcUser principal) {
    if (form == null) {
      form = new RefineryOrderForm();
      UUID currentUserId = getCurrentUserId(principal);
      if (currentUserId != null) {
        form.setOwnerId(currentUserId);
      }
    }
    // isLogistician reads the SecurityContext on the request thread; do it here, then fetch the
    // catalog lookups concurrently (missions, users, rounding mode, yields and the owner picker are
    // uncached round-trips). Each helper swallows its own failure and returns an empty list/map, so
    // join() never throws and the form degrades exactly as the serial version did.
    model.addAttribute("isLogistician", isLogistician(principal));
    model.addAttribute("refineryOrderForm", form);
    final RefineryOrderForm boundForm = form;
    UUID preselectedLocationId = boundForm.getLocationId();
    var materialsFuture = parallelPageLoader.loadAsync(this::fetchMaterials);
    var methodsFuture = parallelPageLoader.loadAsync(this::fetchMethods);
    var locationsFuture = parallelPageLoader.loadAsync(this::fetchLocations);
    var missionsFuture = parallelPageLoader.loadAsync(this::fetchMissions);
    // Owner picker is a server-side searchable combobox (remote-users, #1193): seed only the
    // current
    // owner's display name (the caller by default), not the whole roster.
    var seedNamesFuture =
        parallelPageLoader.loadAsync(
            () ->
                resolveSeedUserNames(
                    boundForm.getOwnerId() == null ? List.of() : List.of(boundForm.getOwnerId())));
    var roundingFuture = parallelPageLoader.loadAsync(this::fetchRoundingMode);
    var yieldsFuture =
        parallelPageLoader.loadAsync(() -> fetchYieldsForLocation(preselectedLocationId));
    var ownerFuture =
        parallelPageLoader.loadAsync(() -> fetchOwnerPickerOptions(boundForm, principal));
    CompletableFuture.allOf(
            materialsFuture,
            methodsFuture,
            locationsFuture,
            missionsFuture,
            seedNamesFuture,
            roundingFuture,
            yieldsFuture,
            ownerFuture)
        .join();
    model.addAttribute("materials", materialsFuture.join());
    model.addAttribute("methods", methodsFuture.join());
    model.addAttribute("locations", locationsFuture.join());
    model.addAttribute("missions", missionsFuture.join());
    model.addAttribute("seedUserNames", seedNamesFuture.join());
    model.addAttribute("roundingMode", roundingFuture.join());
    model.addAttribute("materialYieldBonuses", yieldsFuture.join());
    model.addAttribute("ownerOptions", ownerFuture.join());
  }

  /**
   * AJAX twin of {@link RefineryImportProxyController#importExtract} (#591): relays the uploaded
   * {@code RefineryExtract} JSON to the backend matcher and returns the pre-filled create-form
   * fragment ({@code refinery-orders-create :: refineryImportFormBody}) so the create page swaps it
   * in place instead of doing a full {@code POST → redirect} reload. Routed by the {@code
   * X-Requested-With} header (more specific than the classic multipart handler in {@link
   * RefineryImportProxyController}); the classic handler stays the no-JS fallback. Persists
   * nothing.
   *
   * <p>Every branch — success and each failure (invalid/oversized file, unparseable JSON, backend
   * reject) — returns the same fragment with the appropriate {@code importErrorKey} / {@code
   * importErrorText} so the error surfaces inline in the swapped region, never as a redirect the
   * client would have to follow. On any failure the form falls back to a fresh owner-prefilled
   * form, matching the classic flow's fresh-{@code GET} render.
   *
   * <p>Deliberately kept on this read controller in the #924 L5 read/write split: it is
   * non-mutating (relays the extract to the backend matcher, persists nothing) and it is the only
   * POST that needs the whole create-page render machinery ({@link #populateCreateFormModel}, the
   * fetch* family, the parallel page loader and the logistician check).
   *
   * @param file the uploaded {@code RefineryExtract} JSON
   * @param model the model populated with the pre-filled form + review flags (or an error key)
   * @param principal the authenticated user (drives the create-form catalogs + owner options)
   * @return the create-form fragment view name
   */
  @PostMapping(
      value = "/import",
      headers = "X-Requested-With=XMLHttpRequest",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("isAuthenticated()")
  public String importExtractAjax(
      @RequestParam("file") MultipartFile file,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    RefineryOrderForm form = new RefineryOrderForm();
    UUID currentUserId = getCurrentUserId(principal);
    if (currentUserId != null) {
      form.setOwnerId(currentUserId);
    }
    JsonNode extract = RefineryImportProxyController.parseExtractObject(file);
    if (extract == null) {
      model.addAttribute("importErrorKey", "refineryImport.error.invalidFile");
      populateCreateFormModel(model, form, principal);
      return "refinery-orders-create :: refineryImportFormBody";
    }
    try {
      RefineryImportDraftDto draft =
          backendApiClient.post(
              "/api/v1/refinery-orders/import-extract", extract, RefineryImportDraftDto.class);
      if (draft == null || draft.order() == null) {
        model.addAttribute("importErrorKey", "refineryImport.error.failed");
      } else {
        form = RefineryImportProxyController.toForm(draft.order());
        model.addAttribute(
            "importIssues", RefineryImportProxyController.generalIssues(draft.issues()));
        model.addAttribute(
            "importRowIssues", RefineryImportProxyController.rowIssues(draft.issues()));
        model.addAttribute("importGoodsMatched", draft.goodsMatched());
        model.addAttribute("importGoodsTotal", draft.goodsTotal());
        model.addAttribute("importRowsSkipped", draft.rowsSkipped());
      }
    } catch (BackendServiceException e) {
      // Envelope-level reject (e.g. unsupported schemaVersion): the backend's problem detail is
      // already localized — show it verbatim; otherwise a generic failure message.
      String detail = e.getProblemDetail();
      if (detail != null && !detail.isBlank()) {
        model.addAttribute("importErrorText", detail);
      } else {
        model.addAttribute("importErrorKey", "refineryImport.error.failed");
      }
    } catch (Exception e) {
      log.error("Refinery import relay failed (ajax)", e);
      model.addAttribute("importErrorKey", "refineryImport.error.failed");
    }
    populateCreateFormModel(model, form, principal);
    return "refinery-orders-create :: refineryImportFormBody";
  }

  /**
   * Consumes a one-click ingest handoff and returns the pre-filled create-form fragment for an
   * in-place swap (epic #639, REQ-INGEST-004). This is the script-initiated counterpart to {@link
   * #viewCreateForm}'s handoff branch: the navigational {@code GET …/create?handoff=<id>} renders
   * the empty owner-prefilled form and never consumes, so a speculative browser prefetch or a
   * duplicate top-level load cannot burn the single-use token (the 2026-07-19 Firefox double-GET
   * incident); the create page's JS then POSTs the id here exactly once and swaps the returned
   * {@code refineryImportFormBody} fragment into place.
   *
   * <p>Mirrors {@link #importExtractAjax}: on a hit the pre-filled form + the same review flags
   * (issues, counters) render; on a miss — unknown, expired, already-consumed, wrong-kind or
   * foreign-{@code sub} — the fresh owner-prefilled form renders with the {@code
   * ingest.handoff.notFound} inline notice. Kept on this read controller for the same reason as
   * {@link #importExtractAjax}: it is non-mutating (the single-use consume deletes a transient
   * Redis pickup, it persists no squadron data) and needs the whole create-page render machinery.
   *
   * @param handoff the handoff id the extractor put on the {@code ?handoff=} parameter
   * @param model the model populated with the pre-filled form + review flags, or the not-found key
   * @param principal the authenticated user (its Keycloak subject scopes the single-use consume)
   * @return the {@code refinery-orders-create :: refineryImportFormBody} fragment view name
   */
  @PostMapping(value = "/import-handoff", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  public String importHandoff(
      @RequestParam("handoff") String handoff,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    RefineryOrderForm form = new RefineryOrderForm();
    UUID currentUserId = getCurrentUserId(principal);
    if (currentUserId != null) {
      form.setOwnerId(currentUserId);
    }
    RefineryOrderForm prefilled =
        principal != null ? applyRefineryHandoff(handoff, principal, model) : null;
    if (prefilled != null) {
      form = prefilled;
    } else {
      model.addAttribute("importErrorKey", "ingest.handoff.notFound");
    }
    populateCreateFormModel(model, form, principal);
    return "refinery-orders-create :: refineryImportFormBody";
  }

  /**
   * Renders the detail view of a single refinery order ({@code /refinery-orders/{id}}), either as
   * the full page or — when {@code fragment} names a section — as that section's fragment for an
   * in-place AJAX swap (REQ-FE-001 / REQ-FE-015).
   *
   * <p>The {@code canEdit} flag is resolved here (not in the template): logisticians can edit every
   * order; the order's owner can edit their own. Backend's PUT enforces the same rule so this is
   * purely a UX gate.
   *
   * <p>The two fragments are the seams the page's own save/store and its {@code
   * refinery-order:{id}} live-sync receiver re-render: {@code order} (the edit form, the goods
   * editor and the status-gated action row) and {@code store} (the Einlagern dialog's rows, derived
   * from the order's output goods). Each skips the catalog lookups the other one needs
   * (ADR-0078/ADR-0081 fragment-gating) — a {@code store} refresh never pays for the missions
   * catalog, an {@code order} refresh never pays for the active-job-order lookup. An unknown
   * fragment name, or a backend failure while rendering one, degrades to a section-sized inline
   * error rather than a redirect, so a flaky peer-refresh never dumps a whole page into a small
   * swap container.
   *
   * @param id refinery order id
   * @param fragment when {@code "order"} or {@code "store"}, only that section's fragment is
   *     rendered; otherwise the full page
   * @param model Thymeleaf model populated with order, edit/role flags and the dropdown catalogs
   * @param principal authenticated OIDC user (used to derive owner and logistician flags)
   * @return the {@code refinery-orders-details} view name, one of its section fragment selectors,
   *     or {@code :: fragmentError} on an unknown fragment / failed fragment load
   */
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public String viewOrderDetail(
      @PathVariable UUID id,
      @RequestParam(required = false) String fragment,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    String section = fragment == null ? null : fragment.toLowerCase(java.util.Locale.ROOT);
    if (section != null && !FRAGMENT_ORDER.equals(section) && !FRAGMENT_STORE.equals(section)) {
      return "refinery-orders-details :: fragmentError";
    }
    boolean isLogistician = isLogistician(principal);
    // Set unconditionally, not only on the fresh-load branch below: the store dialog's receiver
    // picker is gated on this flag (REQ-SEC-039), and on the flash-attribute re-render after a
    // validation error the branch is skipped — which would silently demote a logistician to the
    // read-only receiver field. A missing flag renders as "not a logistician", so the failure mode
    // is safe but wrong, and only visible after a failed submit.
    model.addAttribute("isLogistician", isLogistician);

    if (!model.containsAttribute("refineryOrderForm") || !model.containsAttribute("storeForm")) {
      try {
        RefineryOrderDto orderDto =
            backendApiClient.get("/api/v1/refinery-orders/" + id, RefineryOrderDto.class);
        UUID currentUserId = getCurrentUserId(principal);

        boolean isOwner =
            currentUserId != null
                && orderDto.owner() != null
                && orderDto.owner().id() != null
                && orderDto.owner().id().equals(currentUserId);

        boolean canEdit = isLogistician || isOwner;
        model.addAttribute("canEdit", canEdit);
        model.addAttribute("order", orderDto);

        // Store-dialog owning-org-unit picker (#596): options for the default receiver (the caller)
        // are server-rendered; the JS rebuilds them per row when the receiver dropdown changes. The
        // inherited default pre-selects the order's own owning OrgUnit so a same-OrgUnit self-store
        // needs no manual choice. A receiver who is not a member of that OrgUnit simply gets no
        // pre-selection and must pick — the §5.5.1 "force choice" fallback.
        model.addAttribute("storeOrgUnitOptions", fetchUserOrgUnitOptions(currentUserId));
        model.addAttribute("orderOwningOrgUnitId", orderDto.owningOrgUnitId());

        if (!model.containsAttribute("refineryOrderForm")) {
          RefineryOrderForm form = new RefineryOrderForm();

          if (orderDto.startedAt() != null) {
            form.setStartedAt(orderDto.startedAt().toString());
          }
          if (orderDto.durationMinutes() != null) {
            form.setDurationHours((int) (orderDto.durationMinutes() / 60));
            form.setDurationMinutes((int) (orderDto.durationMinutes() % 60));
          }
          form.setExpenses(orderDto.expenses() != null ? orderDto.expenses() : 0d);
          form.setOtherExpenses(orderDto.otherExpenses() != null ? orderDto.otherExpenses() : 0d);
          form.setOreSales(orderDto.oreSales() != null ? orderDto.oreSales() : 0d);
          if (orderDto.location() != null) {
            form.setLocationId(orderDto.location().id());
          }
          if (orderDto.mission() != null) {
            form.setMissionId(orderDto.mission().id());
          }
          if (orderDto.refiningMethod() != null) {
            form.setRefiningMethodId(orderDto.refiningMethod().id());
          }
          if (orderDto.owner() != null) {
            form.setOwnerId(orderDto.owner().id());
          }
          form.setStatus(orderDto.status());
          form.setVersion(orderDto.version());

          if (orderDto.goods() != null && !orderDto.goods().isEmpty()) {
            List<RefineryGoodForm> goodsForm = new ArrayList<>();
            for (de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto goodDto :
                orderDto.goods()) {
              RefineryGoodForm goodForm = new RefineryGoodForm();
              if (goodDto.inputMaterial() != null) {
                goodForm.setInputMaterialId(goodDto.inputMaterial().id());
              }
              if (goodDto.outputMaterial() != null) {
                goodForm.setOutputMaterialId(goodDto.outputMaterial().id());
              }
              goodForm.setInputQuantity(goodDto.inputQuantity());
              goodForm.setOutputQuantity(goodDto.outputQuantity());
              goodForm.setQuality(goodDto.quality());
              goodsForm.add(goodForm);
            }
            form.setGoods(goodsForm);
          }

          model.addAttribute("refineryOrderForm", form);
        }

        if (!model.containsAttribute("storeForm")) {
          RefineryOrderStoreForm storeForm = new RefineryOrderStoreForm();
          if (orderDto.goods() != null) {
            String roundingMode = fetchRoundingMode();
            for (de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto good :
                orderDto.goods()) {
              if (good.outputMaterial() != null) {
                RefineryOrderStoreItemForm storeItem = new RefineryOrderStoreItemForm();
                storeItem.setMaterialId(good.outputMaterial().id());
                storeItem.setMaterialName(good.outputMaterial().name());

                // The output quantity from the refinery good is stored as units (centi-SCU)
                // when the material is SCU-measured: 100 units == 1 SCU. We divide by 100 to
                // get the SCU value the user will enter in the store dialog. Default to the
                // SCU branch whenever the material's quantityType is anything other than the
                // explicit string "PIECE": UEX-imported materials historically have a NULL
                // quantity_type (the UEX sync never set the field — see issue #230), and a
                // refinery never produces piece-counted goods anyway, so treating "unknown"
                // as SCU here is both safer (avoids 100x over-booking) and matches the
                // domain. Backed by the V95 migration that backfills NULL -> 'SCU'.
                double amount = 0.0;
                String materialQuantityType = good.outputMaterial().quantityType();
                if (good.outputQuantity() != null) {
                  if ("PIECE".equals(materialQuantityType)) {
                    amount = good.outputQuantity();
                  } else {
                    java.math.RoundingMode rm;
                    try {
                      rm = java.math.RoundingMode.valueOf(roundingMode);
                    } catch (Exception e) {
                      rm = java.math.RoundingMode.HALF_UP;
                    }
                    amount =
                        java.math.BigDecimal.valueOf(good.outputQuantity() / 100.0)
                            .setScale(3, rm)
                            .doubleValue();
                  }
                  storeItem.setAmountFixed(true);
                } else {
                  storeItem.setAmountFixed(false);
                }
                storeItem.setAmount(amount);
                // Normalize the per-row quantityType the template sees so the unit label
                // ("(SCU)" / "(Stück)") matches the converted amount above.
                storeItem.setQuantityType("PIECE".equals(materialQuantityType) ? "PIECE" : "SCU");

                storeItem.setQuality(good.quality() != null ? good.quality() : 0);
                if (orderDto.location() != null) {
                  storeItem.setLocationId(orderDto.location().id());
                }
                if (currentUserId != null) {
                  storeItem.setUserId(currentUserId);
                }
                // Inherit the order's own owning OrgUnit as the picker's default selection (#596).
                // Valid only when the default receiver (the caller) is a member of it; if not, the
                // option is absent from storeOrgUnitOptions and the picker renders unselected,
                // forcing an explicit choice per the §5.5.1 matrix.
                storeItem.setOwningOrgUnitId(orderDto.owningOrgUnitId());
                storeForm.getItems().add(storeItem);
              }
            }
          }
          model.addAttribute("storeForm", storeForm);
        }
      } catch (Exception e) {
        log.error("Failed to fetch refinery order details", e);
        // A fragment request must never answer with a redirect: krtFetch.swap would bail silently
        // and leave the section stale. Answer section-sized instead (the full page still
        // redirects).
        if (section != null) {
          return "refinery-orders-details :: fragmentError";
        }
        model.addAttribute("errorToast", "error.refineryorder.load");
        return "redirect:/refinery-orders";
      }
    }
    model.addAttribute("orderId", id);
    model.addAttribute("materials", fetchMaterials());
    model.addAttribute("methods", fetchMethods());
    // Keep this order's own refinery in the dropdown even after an admin hides that location:
    // the picker source excludes hidden entries, and the location <select> is `required`, so a
    // dropped option would leave the field on "-- please choose --" and block every subsequent
    // save of an order that was perfectly valid when it was created.
    LocationDto preserveLocation =
        model.getAttribute("order") instanceof RefineryOrderDto orderForPicker
            ? orderForPicker.location()
            : null;
    model.addAttribute("locations", fetchLocations(preserveLocation));
    model.addAttribute("allLocations", fetchAllLocations());
    RefineryOrderForm formInModel = (RefineryOrderForm) model.getAttribute("refineryOrderForm");
    // The missions catalog is a size=1000 uncached read and is referenced only by the `order`
    // section's "Einsatz" picker, so a `store` fragment refresh skips it entirely.
    if (!FRAGMENT_STORE.equals(section)) {
      UUID preserveMissionId = formInModel != null ? formInModel.getMissionId() : null;
      model.addAttribute("missions", fetchMissions(preserveMissionId));
    }
    // The owner + per-item receiver pickers are now server-side searchable comboboxes
    // (remote-users,
    // #1193): instead of preloading the whole roster, seed only the display names of the users the
    // form actually references (the owner and each store item's receiver — all default to the
    // caller). Bounded by the distinct referenced ids, not the roster size.
    Set<UUID> seedIds = new LinkedHashSet<>();
    if (formInModel != null && formInModel.getOwnerId() != null) {
      seedIds.add(formInModel.getOwnerId());
    }
    if (model.getAttribute("storeForm") instanceof RefineryOrderStoreForm storeFormInModel) {
      for (RefineryOrderStoreItemForm storeItem : storeFormInModel.getItems()) {
        if (storeItem.getUserId() != null) {
          seedIds.add(storeItem.getUserId());
        }
      }
    }
    model.addAttribute("seedUserNames", resolveSeedUserNames(seedIds));
    // The active-job-order lookup backs only the store dialog's per-row "Auftrag" picker, so an
    // `order` fragment refresh skips it.
    if (!FRAGMENT_ORDER.equals(section)) {
      model.addAttribute("jobOrders", fetchActiveJobOrders());
    }
    model.addAttribute("roundingMode", fetchRoundingMode());

    // Pre-load the UEX yield map for the order's current location so the detail page can render
    // the bonus badge for every input material in the dropdown on first paint, without an extra
    // network round-trip. The same map is then refreshed client-side via the AJAX endpoint below
    // whenever the user picks a different refinery from the location dropdown.
    UUID currentLocationId = null;
    Object orderAttr = model.getAttribute("order");
    if (orderAttr instanceof RefineryOrderDto orderForLookup && orderForLookup.location() != null) {
      currentLocationId = orderForLookup.location().id();
    }
    model.addAttribute("materialYieldBonuses", fetchYieldsForLocation(currentLocationId));
    if (FRAGMENT_ORDER.equals(section)) {
      return "refinery-orders-details :: refineryOrderSection";
    }
    if (FRAGMENT_STORE.equals(section)) {
      return "refinery-orders-details :: refineryStoreSection";
    }
    return "refinery-orders-details";
  }

  /**
   * AJAX endpoint that proxies the backend's {@code GET
   * /api/v1/refinery-orders/locations/{locationId}/yields} so the detail page's bonus badges can
   * refresh client-side when the user picks a different refinery without a full page reload.
   * Returns the UEX {@code materialId -> percent} map as JSON (positive = bonus, negative = malus,
   * 0 = explicit baseline, absent key = no UEX row known for the (location, material) pair). Errors
   * land as an empty map rather than a 5xx so a transient backend hiccup just falls back to an
   * empty badge instead of breaking the form.
   *
   * @param locationId target refinery location
   * @return per-material yield map keyed by material UUID
   */
  @GetMapping("/locations/{locationId}/yields")
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public Map<String, Integer> getYieldsForLocation(@PathVariable UUID locationId) {
    return fetchYieldsForLocation(locationId);
  }

  /**
   * AJAX endpoint (#596) that proxies the backend's {@code GET /api/v1/users/{userId}/memberships}
   * so the store dialog's per-item owning-org-unit picker can refresh its options client-side when
   * the receiving-member dropdown changes — without a full page reload. Returns the receiver's
   * OrgUnit memberships as the picker-friendly option rows; an empty list on a backend hiccup keeps
   * the picker from breaking the dialog.
   *
   * @param userId the receiving member whose OrgUnit memberships back the picker
   * @return the member's OrgUnit options as JSON; never {@code null}
   */
  @GetMapping("/users/{userId}/org-units")
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public List<OrgUnitMembershipOptionDto> getUserOrgUnits(@PathVariable UUID userId) {
    return fetchUserOrgUnitOptions(userId);
  }

  /**
   * Pulls the per-material UEX bonus/malus map from the backend for {@code locationId}. Wraps every
   * failure mode in an empty map: a {@code null} id (the order has no location picked yet), an
   * unknown id (the backend returns an empty map by design), or a transient network/backend error
   * (logged at WARN). The shared yield-badge JS module treats "empty map" identically to "no UEX
   * data" so all three branches fall through cleanly on both the create and the detail page.
   */
  private Map<String, Integer> fetchYieldsForLocation(UUID locationId) {
    if (locationId == null) {
      return Map.of();
    }
    try {
      Map<String, Integer> yields =
          backendApiClient.get(
              "/api/v1/refinery-orders/locations/" + locationId + "/yields", STRING_INTEGER_MAP);
      return yields != null ? yields : Map.of();
    } catch (Exception e) {
      log.warn("Failed to fetch refinery yields for location {}: {}", locationId, e.getMessage());
      return Map.of();
    }
  }

  private List<MaterialDto> fetchMaterials() {
    try {
      PageResponse<MaterialDto> p =
          backendApiClient.getCached(CachedCatalog.MATERIALS, MATERIAL_PAGE, true);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch materials", e);
    }
    return new ArrayList<>();
  }

  private List<RefiningMethodDto> fetchMethods() {
    try {
      PageResponse<RefiningMethodDto> p =
          backendApiClient.getCached(CachedCatalog.REFINING_METHODS, REFINING_METHOD_PAGE, true);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch refining methods", e);
    }
    return new ArrayList<>();
  }

  private List<LocationDto> fetchAllLocations() {
    try {
      PageResponse<LocationDto> p =
          backendApiClient.getCached(CachedCatalog.LOCATIONS, LOCATION_PAGE);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch all locations", e);
    }
    return new ArrayList<>();
  }

  private List<LocationDto> fetchLocations() {
    return fetchLocations(null);
  }

  /**
   * Fetches the refinery-location catalog backing the "Raffinerie" dropdown. The backend source
   * ({@code GET /api/v1/locations/refineries}) lists only locations that host a live refinery
   * terminal <em>and</em> are not hidden, so an admin who hides a location removes it from this
   * picker as well as from the storage pickers.
   *
   * <p>Pass {@code preserveLocation} to retain one specific location regardless of that filter —
   * used on the detail page so an existing order's own refinery survives in the dropdown after its
   * location was hidden. Without it the {@code required} select would render unselected and no
   * further edit of that order could be saved.
   *
   * @param preserveLocation location to keep in the result even when the backend omits it, or
   *     {@code null} to take the backend list as-is.
   * @return mutable list of selectable refinery locations; empty on backend failure.
   */
  private List<LocationDto> fetchLocations(LocationDto preserveLocation) {
    List<LocationDto> locs = new ArrayList<>();
    try {
      List<LocationDto> fetched =
          backendApiClient.getCached(CachedCatalog.LOCATIONS_REFINERIES, LOCATION_LIST);
      if (fetched != null) {
        locs = new ArrayList<>(fetched);
      }
    } catch (Exception e) {
      log.error("Failed to fetch refinery locations", e);
    }
    return withPreservedLocation(locs, preserveLocation);
  }

  /**
   * Appends {@code preserveLocation} to {@code locations} when it carries an id that the list does
   * not already contain, so a filtered-out but still-referenced location stays selectable.
   *
   * <p>Package-private for direct unit testing — the surrounding {@link
   * #fetchLocations(LocationDto)} would otherwise hide this behind the API client mock.
   *
   * @param locations the backend's picker list; mutated and returned.
   * @param preserveLocation the location to retain, or {@code null} for no preservation.
   * @return {@code locations}, with the preserved entry appended when it was missing.
   */
  static List<LocationDto> withPreservedLocation(
      List<LocationDto> locations, LocationDto preserveLocation) {
    if (preserveLocation == null || preserveLocation.id() == null) {
      return locations;
    }
    if (locations.stream().noneMatch(l -> preserveLocation.id().equals(l.id()))) {
      locations.add(preserveLocation);
    }
    return locations;
  }

  private List<MissionListDto> fetchMissions() {
    return fetchMissions(null);
  }

  /**
   * Fetches the missions catalog for the refinery-order dropdowns, restricted to the last three
   * months of {@code plannedStartTime} (future-scheduled missions are included) and sorted
   * newest-first. Older missions are dropped so the dropdown does not balloon with historical
   * operations the user is unlikely to pick. Pass {@code preserveMissionId} to retain a specific
   * mission regardless of its plannedStartTime — used on the detail page so an existing order's
   * linked mission stays visible even when it falls outside the three-month window.
   *
   * @param preserveMissionId mission id to keep in the result regardless of its plannedStartTime,
   *     or {@code null} to apply the cut-off without preservation.
   * @return mutable list of missions in newest-first order; empty on backend failure.
   */
  private List<MissionListDto> fetchMissions(UUID preserveMissionId) {
    try {
      // The explicit newest-first sort is load-bearing: without it the backend's default is
      // plannedStartTime ASCENDING, so once more than 1000 missions exist the single fetched page
      // holds the 1000 OLDEST rows and the three-month window below silently empties — the recent
      // missions the dropdown is for would sit beyond the page boundary.
      PageResponse<MissionListDto> p =
          backendApiClient.get(
              "/api/v1/missions?size=1000&sort=plannedStartTime,desc", MISSION_LIST_PAGE);
      if (p == null) {
        return new ArrayList<>();
      }
      java.time.Instant cutoff =
          java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMonths(3).toInstant();
      return filterAndSortMissionsForDropdown(p.content(), cutoff, preserveMissionId);
    } catch (Exception e) {
      log.error("Failed to fetch missions", e);
    }
    return new ArrayList<>();
  }

  /**
   * Filters the given missions to those whose {@code plannedStartTime} is on or after {@code
   * cutoff} (missions without a planned start are dropped) and sorts the result newest-first. If
   * {@code preserveMissionId} is non-null and the matching mission is in {@code all} but missing
   * from the filtered slice, it is appended to the end so the caller can keep it as a selected
   * option without losing it from the dropdown.
   *
   * <p>Package-private for direct unit testing — the date arithmetic in the surrounding {@link
   * #fetchMissions(UUID)} would otherwise be hidden behind the API client mock.
   *
   * @param all unfiltered mission list from the backend (may be {@code null} or empty).
   * @param cutoff inclusive lower bound for {@code plannedStartTime}.
   * @param preserveMissionId mission id to retain regardless of the cut-off, or {@code null}.
   * @return mutable list of missions in newest-first order.
   */
  static List<MissionListDto> filterAndSortMissionsForDropdown(
      List<MissionListDto> all, java.time.Instant cutoff, UUID preserveMissionId) {
    if (all == null || all.isEmpty()) {
      return new ArrayList<>();
    }
    List<MissionListDto> filtered =
        new ArrayList<>(
            all.stream()
                .filter(m -> m.plannedStartTime() != null && !m.plannedStartTime().isBefore(cutoff))
                .sorted(Comparator.comparing(MissionListDto::plannedStartTime).reversed())
                .toList());
    if (preserveMissionId != null
        && filtered.stream().noneMatch(m -> preserveMissionId.equals(m.id()))) {
      all.stream()
          .filter(m -> preserveMissionId.equals(m.id()))
          .findFirst()
          .ifPresent(filtered::add);
    }
    return filtered;
  }

  /**
   * Resolves the {@link OrgUnitMembershipOptionDto} list that drives the R5.d owner-picker fragment
   * on the refinery-order create form. The target user is the form's {@code ownerId} when a
   * logistician has explicitly picked another user; otherwise the calling user (a self-entry).
   *
   * <p>Falls back to an empty list when the lookup fails — the fragment collapses to a hidden state
   * for an empty option list, so a transient backend hiccup does not break the form render.
   *
   * @param form the inbound refinery-order form (may be {@code null} on the very first GET before
   *     binding).
   * @param principal the OIDC principal of the calling user; used to derive the fallback target
   *     user when the form does not carry an explicit owner.
   * @return picker options or empty list; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchOwnerPickerOptions(
      RefineryOrderForm form, OidcUser principal) {
    UUID explicitOwnerId = form != null ? form.getOwnerId() : null;
    if (explicitOwnerId == null) {
      // Self-entry → the caller's pickable org units: direct memberships plus their cascading
      // leadership reach (own Bereich/OL + overseen subordinate Staffeln/SKs), epic #692 Phase 5.
      // Unchanged for an ordinary member. Resolved server-side for the caller.
      if (principal == null) {
        return List.of();
      }
      return fetchMyPickableOrgUnits();
    }
    // A logistician explicitly picking another owner → that user's DIRECT memberships (their own
    // stock); the create-on-behalf cascade applies to the caller, not to a third-party owner.
    return fetchUserOrgUnitOptions(explicitOwnerId);
  }

  /**
   * Fetches the calling user's pickable owning-org-unit options (direct memberships ∪ cascading
   * leadership reach) from {@code /api/v1/users/me/pickable-org-units} (epic #692 Phase 5). Wraps
   * failures in an empty list so a transient backend hiccup leaves the picker empty rather than
   * breaking the form.
   *
   * @return the caller's pickable options, sorted by the backend; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchMyPickableOrgUnits() {
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get(
              "/api/v1/users/me/pickable-org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch pickable org units for refinery-order owner-picker", e);
      return List.of();
    }
  }

  /**
   * Fetches a user's OrgUnit memberships as picker-friendly option rows, backing both the
   * create-form owner picker (via {@link #fetchOwnerPickerOptions}) and the store dialog's per-item
   * owning-org-unit picker (#596). Wraps every failure in an empty list — a {@code null} id or a
   * transient backend hiccup leaves the picker empty rather than breaking the page.
   *
   * @param userId the user whose memberships to list; {@code null} yields an empty list.
   * @return the picker options, sorted Staffel-first then SK alphabetical by the backend; never
   *     {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchUserOrgUnitOptions(UUID userId) {
    if (userId == null) {
      return List.of();
    }
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get(
              "/api/v1/users/" + userId + "/memberships", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch memberships for refinery org-unit picker", e);
      return List.of();
    }
  }

  /**
   * Resolves the display names of exactly the users the owner + per-item receiver pickers
   * reference, so those server-side searchable comboboxes (remote-users, #1193) can seed their
   * preselected option without preloading the whole roster. Bounded by the distinct referenced ids
   * (owner + store receivers — all default to the caller, so usually one), never the roster size. A
   * failed or unknown id is simply absent from the map (the picker then shows its placeholder
   * rather than a raw id); {@code effectiveName} falls back to the username.
   *
   * @param ids the distinct user ids the form references; never {@code null}, possibly empty.
   * @return a map from user id to display name for the referenced users; never {@code null}.
   */
  private Map<UUID, String> resolveSeedUserNames(Collection<UUID> ids) {
    Map<UUID, String> names = new HashMap<>();
    for (UUID id : ids) {
      if (id == null || names.containsKey(id)) {
        continue;
      }
      try {
        UserDto user = backendApiClient.get("/api/v1/users/" + id, UserDto.class);
        if (user != null) {
          names.put(id, user.effectiveName() != null ? user.effectiveName() : user.username());
        }
      } catch (Exception e) {
        // REQ-OBS-004: log the id only, never the resolved name.
        log.warn("Failed to resolve seed user name for refinery picker (id {})", id, e);
      }
    }
    return names;
  }

  /**
   * Loads the active (OPEN / IN_PROGRESS) job orders that back the store dialog's per-item
   * "Auftrag" dropdown. Uses the lightweight {@code /api/v1/orders/lookup} reference projection
   * rather than the full {@code /api/v1/orders} list because the projection's {@code
   * requiredMaterialIds} is the authoritative, kind-agnostic required-material set: it is populated
   * for ITEM orders too (whose {@code materials} line list is always empty), so the template can
   * hide an order that does not require the stored output material across <em>both</em> order kinds
   * (REQ-ORDERS-018). The old {@code materials}-based filter silently dropped every ITEM order,
   * leaving the dropdown empty.
   *
   * @return the active job orders visible to the caller as reference projections; never {@code
   *     null}
   */
  private List<JobOrderReferenceDto> fetchActiveJobOrders() {
    try {
      List<JobOrderReferenceDto> content =
          backendApiClient.get("/api/v1/orders/lookup", JOB_ORDER_REFERENCE_LIST);
      if (content != null) {
        return new ArrayList<>(content);
      }
    } catch (Exception e) {
      log.error("Failed to fetch job orders", e);
    }
    return new ArrayList<>();
  }

  private String fetchRoundingMode() {
    try {
      SystemSettingDto setting =
          backendApiClient.get("/api/v1/settings/refinery.rounding.mode", SystemSettingDto.class);
      return setting.value();
    } catch (Exception e) {
      log.warn("Failed to fetch refinery rounding mode, using default UP");
      return "UP";
    }
  }

  private UUID getCurrentUserId(OidcUser principal) {
    if (principal == null) {
      return null;
    }
    try {
      // Try the subject directly (fastest path)
      return CurrentUser.userId(principal);
    } catch (Exception e) {
      // Fallback: ask the backend for our id
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

    // REQ-OBS-004: never log principal.getName() (the Keycloak preferred_username handle — PII the
    // appender PiiMasker does not scrub). Log the same stable short pseudonym used for the
    // backend-flag grant below (BackendRoleSyncFilter.maskPrincipal shape).
    log.debug(
        "Checking logistician status for user u-{}, authorities: {}",
        Integer.toHexString(java.util.Objects.hashCode(principal.getName())),
        authorities);
    Collection<? extends GrantedAuthority> reachableAuthorities =
        roleHierarchy.getReachableGrantedAuthorities(authorities);
    log.debug("Reachable authorities: {}", reachableAuthorities);
    boolean result =
        reachableAuthorities.stream()
            .anyMatch(
                a ->
                    a.getAuthority().equals(Roles.authority(Roles.LOGISTICIAN))
                        || a.getAuthority().equals(Roles.authority(Roles.ADMIN))
                        || a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (!result) {
      try {
        // Fallback: check the DB flag from the backend
        de.greluc.krt.profit.basetool.frontend.model.dto.UserDto me =
            backendApiClient.get(
                "/api/v1/users/me", de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class);
        if (me != null && Boolean.TRUE.equals(me.isLogistician())) {
          // M-16: do NOT log {@code principal.getName()} (the Keycloak username — PII). Log only
          // a stable short pseudonym derived from the principal name's hashCode, in the same
          // shape as {@code BackendRoleSyncFilter.maskPrincipal}. The narrower {@code log.debug}
          // a few lines down does not carry the principal at all.
          log.info(
              "Granting logistician by backend flag for user: u-{}",
              Integer.toHexString(java.util.Objects.hashCode(principal.getName())));
          result = true;
        }
      } catch (Exception e) {
        log.debug("Fallback check for logistician via /users/me failed: {}", e.getMessage());
      }
    }
    log.debug("Is logistician (final): {}", result);
    return result;
  }
}
