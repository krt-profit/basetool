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
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Controller rendering the refinery-order pages, their read-only AJAX lookups and the
 * non-persisting import relays; writes live in {@link RefineryOrderWriteController}.
 */
@Controller
@UsesLayoutModel
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
   * Renders one page of the refinery-order list ({@code /refinery-orders}), filtered to {@code
   * OPEN} and {@code IN_PROGRESS} by default and sorted by {@code startedAt} descending
   * (REQ-REFINERY-019).
   *
   * @param status optional list of statuses to include
   * @param onlyMine whether to restrict to the caller's own orders
   * @param page zero-based page index, clamped to 0
   * @param size requested page size; only {@link #PAGE_SIZES} are honoured, else the default
   * @param fragment {@code "results"} renders only the results-table fragment (REQ-FE-005)
   * @param model model populated with the orders, the page envelope, page sizes, pagination base
   *     URL and filter state
   * @param principal authenticated OIDC user, used for the logistician hint
   * @return the {@code refinery-orders-index} view name, or its {@code refineryOrdersResults}
   *     fragment selector
   */
  @NotNull
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
   * Builds the pagination base URL that preserves the active status and own-orders filter.
   *
   * @param status the resolved, non-empty status filter
   * @param onlyMine the resolved own-orders toggle
   * @return {@code /refinery-orders?status=...&onlyMine=true} carrying the current filter
   */
  @NotNull
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
   * Renders the create-order form ({@code /refinery-orders/create}) with the current user as
   * default owner and the reference catalogs for its dropdowns.
   *
   * @param source optional origin marker for the return link after save
   * @param model model populated with the form and reference catalogs
   * @param principal authenticated OIDC user, the default owner
   * @return the {@code refinery-orders-create} view name
   */
  @NotNull
  @GetMapping("/create")
  @PreAuthorize("isAuthenticated()")
  public String viewCreateForm(
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String handoff,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    RefineryOrderForm form;
    if (model.containsAttribute("refineryOrderForm")) {
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
      if (handoff != null && !handoff.isBlank()) {
        model.addAttribute("pendingHandoffId", handoff);
      }
    }
    populateCreateFormModel(model, form, principal);
    return "refinery-orders-create";
  }

  /**
   * Consumes an ingest handoff and maps its staged refinery draft into a pre-filled form, adding
   * the import review attributes to the model.
   *
   * @param handoff the {@code ?handoff=} id from the extractor
   * @param principal the authenticated user, whose subject scopes the staged lookup
   * @param model the model enriched with the review attributes on success
   * @return the pre-filled form, or {@code null} when the handoff is unusable or carries no order
   */
  @Nullable
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
   * Populates the model with everything the refinery create form renders, shared by {@link
   * #viewCreateForm} and {@link #importExtractAjax}.
   *
   * <p>The yield map is preloaded when the form already has a location.
   *
   * @param model the model to populate
   * @param form the form to bind; {@code null} is replaced by a fresh owner-prefilled form
   * @param principal the authenticated user, for the logistician flag and owner options
   */
  private void populateCreateFormModel(Model model, RefineryOrderForm form, OidcUser principal) {
    if (form == null) {
      form = new RefineryOrderForm();
      UUID currentUserId = getCurrentUserId(principal);
      if (currentUserId != null) {
        form.setOwnerId(currentUserId);
      }
    }
    model.addAttribute("isLogistician", isLogistician(principal));
    model.addAttribute("refineryOrderForm", form);
    final RefineryOrderForm boundForm = form;
    UUID preselectedLocationId = boundForm.getLocationId();
    var materialsFuture = parallelPageLoader.loadAsync(this::fetchMaterials);
    var methodsFuture = parallelPageLoader.loadAsync(this::fetchMethods);
    var locationsFuture = parallelPageLoader.loadAsync(this::fetchLocations);
    var missionsFuture = parallelPageLoader.loadAsync(this::fetchMissions);
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
   * AJAX twin of {@link RefineryImportProxyController#importExtract}: relays the uploaded extract
   * and returns the pre-filled {@code refinery-orders-create :: refineryImportFormBody} fragment;
   * persists nothing.
   *
   * <p>Every failure returns the same fragment with a fresh form and {@code importErrorKey} or
   * {@code importErrorText}.
   *
   * @param file the uploaded {@code RefineryExtract} JSON
   * @param model the model populated with the pre-filled form and review flags, or an error key
   * @param principal the authenticated user, for the form catalogs and owner options
   * @return the create-form fragment view name
   */
  @NotNull
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
   * Consumes a single-use ingest handoff and returns the pre-filled create-form fragment
   * (REQ-INGEST-004).
   *
   * <p>The create page's script posts the id here once, so the navigational {@code GET} never
   * consumes it. A miss renders the fresh form with the {@code ingest.handoff.notFound} notice.
   *
   * @param handoff the handoff id from the {@code ?handoff=} parameter
   * @param model the model populated with the pre-filled form and review flags, or the not-found
   *     key
   * @param principal the authenticated user, whose subject scopes the consume
   * @return the {@code refinery-orders-create :: refineryImportFormBody} fragment view name
   */
  @NotNull
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
   * Renders a refinery order's detail page, or only its {@code order} or {@code store} section for
   * an in-place swap (REQ-FE-015).
   *
   * <p>{@code canEdit} holds for logisticians and the owner. An unknown fragment or a failed
   * fragment load renders an inline section error.
   *
   * @param id refinery order id
   * @param fragment {@code "order"} or {@code "store"} renders only that section
   * @param model model populated with the order, flags and dropdown catalogs
   * @param principal authenticated OIDC user, for the owner and logistician flags
   * @return the {@code refinery-orders-details} view name, a section fragment selector, or the
   *     {@code :: fragmentError} selector
   */
  @NotNull
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
                storeItem.setQuantityType("PIECE".equals(materialQuantityType) ? "PIECE" : "SCU");

                storeItem.setQuality(good.quality() != null ? good.quality() : 0);
                if (orderDto.location() != null) {
                  storeItem.setLocationId(orderDto.location().id());
                }
                if (currentUserId != null) {
                  storeItem.setUserId(currentUserId);
                }
                storeItem.setOwningOrgUnitId(orderDto.owningOrgUnitId());
                storeForm.getItems().add(storeItem);
              }
            }
          }
          model.addAttribute("storeForm", storeForm);
        }
      } catch (Exception e) {
        log.error("Failed to fetch refinery order details", e);
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
    LocationDto preserveLocation =
        model.getAttribute("order") instanceof RefineryOrderDto orderForPicker
            ? orderForPicker.location()
            : null;
    model.addAttribute("locations", fetchLocations(preserveLocation));
    model.addAttribute("allLocations", fetchAllLocations());
    RefineryOrderForm formInModel = (RefineryOrderForm) model.getAttribute("refineryOrderForm");
    if (!FRAGMENT_STORE.equals(section)) {
      UUID preserveMissionId = formInModel != null ? formInModel.getMissionId() : null;
      model.addAttribute("missions", fetchMissions(preserveMissionId));
    }
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
    if (!FRAGMENT_ORDER.equals(section)) {
      model.addAttribute("jobOrders", fetchActiveJobOrders());
    }
    model.addAttribute("roundingMode", fetchRoundingMode());

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
   * Proxies the backend's UEX yield map of a refinery location for the bonus badges.
   *
   * @param locationId target refinery location
   * @return percent per material UUID; empty on a backend error
   */
  @GetMapping("/locations/{locationId}/yields")
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public Map<String, Integer> getYieldsForLocation(@PathVariable UUID locationId) {
    return fetchYieldsForLocation(locationId);
  }

  /**
   * Proxies the backend's membership lookup so the store dialog's org-unit picker follows the
   * chosen receiver.
   *
   * @param userId the receiving member
   * @return the member's org-unit options; empty on a backend error, never {@code null}
   */
  @GetMapping("/users/{userId}/org-units")
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public List<OrgUnitMembershipOptionDto> getUserOrgUnits(@PathVariable UUID userId) {
    return fetchUserOrgUnitOptions(userId);
  }

  /**
   * Fetches the per-material UEX yield map of a location; a {@code null} id or any failure yields
   * an empty map.
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

  @NotNull
  private List<MaterialDto> fetchMaterials() {
    try {
      PageResponse<MaterialDto> p =
          backendApiClient.getCached(CachedCatalog.MATERIALS, MATERIAL_PAGE);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch materials", e);
    }
    return new ArrayList<>();
  }

  @NotNull
  private List<RefiningMethodDto> fetchMethods() {
    try {
      PageResponse<RefiningMethodDto> p =
          backendApiClient.getCached(CachedCatalog.REFINING_METHODS, REFINING_METHOD_PAGE);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch refining methods", e);
    }
    return new ArrayList<>();
  }

  @NotNull
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
   * Fetches the refinery locations of the "Raffinerie" dropdown: live refinery terminals that are
   * not hidden.
   *
   * @param preserveLocation a location to keep even when the backend omits it, or {@code null}
   * @return mutable list of selectable locations; empty on backend failure
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
   * Appends {@code preserveLocation} to {@code locations} when its id is missing from the list.
   *
   * @param locations the picker list; mutated and returned
   * @param preserveLocation the location to retain, or {@code null}
   * @return {@code locations}, with the preserved entry appended when it was missing
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

  @NotNull
  private List<MissionListDto> fetchMissions() {
    return fetchMissions(null);
  }

  /**
   * Fetches the missions of the last three months (future ones included) for the dropdowns, newest
   * first.
   *
   * @param preserveMissionId a mission to keep regardless of its start, or {@code null}
   * @return mutable list of missions, newest first; empty on backend failure
   */
  @NotNull
  private List<MissionListDto> fetchMissions(UUID preserveMissionId) {
    try {
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
   * Keeps missions with {@code plannedStartTime} on or after {@code cutoff}, sorts them newest
   * first, and appends {@code preserveMissionId}'s mission when it was filtered out.
   *
   * @param all unfiltered mission list; may be {@code null} or empty
   * @param cutoff inclusive lower bound for {@code plannedStartTime}
   * @param preserveMissionId mission id to retain regardless of the cutoff, or {@code null}
   * @return mutable list of missions, newest first
   */
  @NotNull
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
   * Resolves the owner-picker options of the create form for the form's chosen owner, else the
   * caller.
   *
   * @param form the refinery-order form; may be {@code null}
   * @param principal the caller, used when the form names no owner
   * @return picker options; empty on failure, never {@code null}
   */
  private List<OrgUnitMembershipOptionDto> fetchOwnerPickerOptions(
      RefineryOrderForm form, OidcUser principal) {
    UUID explicitOwnerId = form != null ? form.getOwnerId() : null;
    if (explicitOwnerId == null) {
      if (principal == null) {
        return List.of();
      }
      return fetchMyPickableOrgUnits();
    }
    return fetchUserOrgUnitOptions(explicitOwnerId);
  }

  /**
   * Fetches the caller's pickable owning org units, direct memberships plus leadership reach.
   *
   * @return the caller's pickable options; empty on failure, never {@code null}
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
   * Fetches a user's org-unit memberships as picker options.
   *
   * @param userId the user; {@code null} yields an empty list
   * @return the options, Staffel first then SK alphabetically; empty on failure, never {@code null}
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
   * Resolves display names of the users the owner and receiver pickers reference, to seed their
   * preselected options.
   *
   * @param ids the distinct referenced user ids; possibly empty
   * @return user id to display name; unknown or failed ids are absent
   */
  @NotNull
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
        log.warn("Failed to resolve seed user name for refinery picker (id {})", id, e);
      }
    }
    return names;
  }

  /**
   * Loads the active job orders for the store dialog's order dropdown from {@code
   * /api/v1/orders/lookup}, whose {@code requiredMaterialIds} covers both order kinds
   * (REQ-ORDERS-018).
   *
   * @return the active job orders visible to the caller; never {@code null}
   */
  @NotNull
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

  @Contract("null -> null")
  @Nullable
  private UUID getCurrentUserId(OidcUser principal) {
    if (principal == null) {
      return null;
    }
    try {
      return CurrentUser.userId(principal);
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
        de.greluc.krt.profit.basetool.frontend.model.dto.UserDto me =
            backendApiClient.get(
                "/api/v1/users/me", de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class);
        if (me != null && Boolean.TRUE.equals(me.isLogistician())) {
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
