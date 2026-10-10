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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ItemDerivationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemHandoverForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.service.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import de.greluc.krt.profit.basetool.frontend.support.RelayParams;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the read side of the job-order pages ({@code /orders}, {@code
 * /orders/{id}}, {@code /orders/create}): page and fragment renders plus read-only JSON proxies.
 *
 * <p>Mutations live in {@link JobOrderWriteController}. The class-level {@code isAuthenticated()}
 * gate is the floor; method-level gates take precedence (REQ-SEC-052).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class JobOrderPageController {

  /** Sends every job-order read and the catalogue reads of the order pages to the backend. */
  private final JobOrderBackendClient jobOrderClient;

  private final RoleHierarchy roleHierarchy;
  private final ParallelPageLoader parallelPageLoader;
  private final QualityTierCatalog qualityTierCatalog;

  private static final List<String> VALID_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "REJECTED", "COMPLETED");

  /** The list scope listing the orders the caller's own org units requested (REQ-UI-027). */
  static final String SCOPE_MINE = "MINE";

  /**
   * The list scope listing the orders one of the caller's own units processes, "Zu bearbeiten"
   * (REQ-ORDERS-040).
   */
  static final String SCOPE_TO_PROCESS = "TO_PROCESS";

  /** The list scope listing the caller's whole visible order queue (REQ-UI-027). */
  static final String SCOPE_ALL = "ALL";

  /** The accepted values of the list's {@code scope} parameter. */
  static final Set<String> ORDER_SCOPES = Set.of(SCOPE_MINE, SCOPE_TO_PROCESS, SCOPE_ALL);

  /**
   * Selectable page sizes for the order list, large enough that the default active queue fits on
   * one page for drag-and-drop reordering (REQ-ORDERS-020).
   *
   * <p>Any other {@code size} falls back to {@link #DEFAULT_PAGE_SIZE}.
   */
  private static final List<Integer> PAGE_SIZES = List.of(50, 100, 200);

  /** Page size applied when the request carries none (or a non-whitelisted one). */
  private static final int DEFAULT_PAGE_SIZE = 100;

  /**
   * Renders the job-order list ({@code /orders}), filtered by scope, status and squadron.
   *
   * <p>Invalid or missing statuses fall back to {@code OPEN} + {@code IN_PROGRESS}; an empty
   * squadron selection means all squadrons (REQ-ORDERS-027). A caller who may see the queue chooses
   * its scope with {@code scope} (REQ-ORDERS-040): the own-orders scope reads the
   * requester-redacted list, the to-process scope reads the queue of the caller's own units; both
   * ignore the squadron filter.
   *
   * @param status optional explicit status filter
   * @param squadronId optional repeatable squadron display filter (empty = all squadrons)
   * @param scope {@code MINE}, {@code TO_PROCESS} or {@code ALL}; anything else, or a scope the
   *     caller lacks the capability for, falls back to the caller's default list
   * @param page zero-based page index, defaulted/clamped to 0 (REQ-ORDERS-020)
   * @param size requested page size; only {@link #PAGE_SIZES} are honoured, else {@link
   *     #DEFAULT_PAGE_SIZE}
   * @param fragment when {@code "results"}, only the results-table fragment is rendered
   *     (REQ-FE-005); otherwise the full page
   * @param model Thymeleaf model populated with the orders, page envelope, page sizes, pagination
   *     base URL, scope, selected filters and aging thresholds
   * @return the {@code orders-index} view name, or its {@code ordersResults} fragment selector
   */
  @NotNull
  @GetMapping
  public String viewOrders(
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) List<UUID> squadronId,
      @RequestParam(required = false) String scope,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      @ModelAttribute("canViewOwnJobOrders") boolean canViewOwnJobOrders,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean requesterView = !canViewJobOrders && canViewOwnJobOrders;
    if (!canViewJobOrders && !canViewOwnJobOrders) {
      return "redirect:/orders/create";
    }
    boolean scopeChoice = canViewJobOrders;
    boolean mineScopeOffered = canViewJobOrders && canViewOwnJobOrders;
    String effectiveScope =
        resolveScope(RelayParams.oneOfOrNull(scope, ORDER_SCOPES), requesterView, mineScopeOffered);
    boolean ownScope = SCOPE_MINE.equals(effectiveScope);
    boolean toProcessScope = SCOPE_TO_PROCESS.equals(effectiveScope);
    model.addAttribute("requesterView", requesterView);
    model.addAttribute("scopeChoice", scopeChoice);
    model.addAttribute("mineScopeOffered", mineScopeOffered);
    model.addAttribute("ownScope", ownScope);
    model.addAttribute("squadronFilterHidden", ownScope || toProcessScope);
    model.addAttribute("scope", effectiveScope);
    List<String> requestedStatuses = (status == null) ? List.of() : status;
    List<String> validStatuses =
        requestedStatuses.stream().filter(VALID_STATUSES::contains).toList();
    status = validStatuses.isEmpty() ? List.of("OPEN", "IN_PROGRESS") : validStatuses;

    List<UUID> selectedSquadronIds =
        (squadronId == null) ? List.of() : squadronId.stream().filter(Objects::nonNull).toList();
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize = size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;

    List<JobOrderDto> orders = new ArrayList<>();
    PageResponse<JobOrderDto> p = null;
    int yellowDays = 30;
    int redDays = 90;
    try {
      p =
          jobOrderClient.orders(
              ownScope, toProcessScope, effectivePage, effectiveSize, status, selectedSquadronIds);
      if (p != null && p.content() != null) {
        orders = new ArrayList<>(p.content());
        if (log.isDebugEnabled()) {
          for (JobOrderDto order : orders) {
            for (de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto mat :
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
        SystemSettingDto yellowSetting = jobOrderClient.ageYellowSetting();
        yellowDays = Integer.parseInt(yellowSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch yellow days setting, using default");
      }
      try {
        SystemSettingDto redSetting = jobOrderClient.ageRedSetting();
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
    model.addAttribute(
        "paginationBaseUrl",
        buildPaginationBaseUrl(
            status,
            ownScope || toProcessScope ? List.of() : selectedSquadronIds,
            scopeChoice && !SCOPE_ALL.equals(effectiveScope) ? effectiveScope : null));
    model.addAttribute("selectedStatuses", status);
    if (!requesterView) {
      model.addAttribute("squadrons", fetchActiveSquadrons());
      model.addAttribute("selectedSquadronIds", selectedSquadronIds);
    }
    model.addAttribute("ageYellowDays", yellowDays);
    model.addAttribute("ageRedDays", redDays);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "orders-index :: ordersResults";
    }
    return "orders-index";
  }

  /**
   * Resolves the list scope the caller gets: a requester-only caller always reads their own orders,
   * {@code MINE} needs both capabilities, and everything else falls back to {@code ALL}.
   *
   * @param requested the validated {@code scope} parameter, or {@code null} when absent or unknown
   * @param requesterView whether the caller may only read their own unit's orders
   * @param mineScopeOffered whether the caller may choose the own-orders scope
   * @return {@link #SCOPE_MINE}, {@link #SCOPE_TO_PROCESS} or {@link #SCOPE_ALL}
   */
  @NotNull
  private static String resolveScope(
      @Nullable String requested, boolean requesterView, boolean mineScopeOffered) {
    if (requesterView || (mineScopeOffered && SCOPE_MINE.equals(requested))) {
      return SCOPE_MINE;
    }
    return SCOPE_TO_PROCESS.equals(requested) ? SCOPE_TO_PROCESS : SCOPE_ALL;
  }

  /**
   * Builds the pagination base URL carrying the active scope, status and squadron filters.
   *
   * @param status the resolved (never empty) status filter
   * @param squadronIds the selected squadron ids (empty = no squadron filter)
   * @param scope the chosen scope carried as {@code scope=…}, or {@code null} to carry none
   * @return {@code /orders?status=...&squadronId=...} carrying the current filter
   */
  @NotNull
  private static String buildPaginationBaseUrl(
      @NotNull List<String> status, @NotNull List<UUID> squadronIds, @Nullable String scope) {
    List<String> queryParts = new ArrayList<>();
    if (scope != null) {
      queryParts.add("scope=" + scope);
    }
    for (String s : status) {
      queryParts.add("status=" + s);
    }
    for (UUID sid : squadronIds) {
      queryParts.add("squadronId=" + sid);
    }
    return "/orders?" + String.join("&", queryParts);
  }

  /**
   * Renders the order detail page ({@code /orders/{id}}). Loads the order plus the auxiliary
   * material-status and assignee lists and exposes the role-derived edit flags so the template
   * stays free of inline expression checks.
   *
   * @return the {@code order-detail} view name
   */
  @NotNull
  @GetMapping("/{id}")
  public String viewOrderDetail(
      @PathVariable UUID id,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      @ModelAttribute("canViewOwnJobOrders") boolean canViewOwnJobOrders,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) String fragment) {
    boolean requesterView = !canViewJobOrders && canViewOwnJobOrders;
    if (!canViewJobOrders && !canViewOwnJobOrders) {
      return fragment != null ? "orders-detail :: fragmentError" : "redirect:/orders/create";
    }
    model.addAttribute("requesterView", requesterView);
    try {
      JobOrderDto order = jobOrderClient.order(id);
      requesterView = order.redacted();
      model.addAttribute("requesterView", requesterView);
      model.addAttribute("order", order);
      model.addAttribute("currentUserId", getCurrentUserId(principal));
      model.addAttribute("itemsWithoutMaterials", itemsWithoutDerivedMaterials(order));

      boolean canAssign = !requesterView && isLogistician(principal);
      model.addAttribute("isLogistician", canAssign);
      if (requesterView || canAssign) {
        model.addAttribute("qualityTiers", qualityTierCatalog.all());
      }
      if (requesterView) {
        model.addAttribute("materials", fetchMaterials());
        if (!model.containsAttribute("jobOrderForm")) {
          JobOrderForm form = new JobOrderForm();
          form.setComment(order.comment());
          form.setVersion(order.version());
          form.getMaterials().clear();
          if (order.materials() != null) {
            for (de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto mat :
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
            for (de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto mat :
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

      if (canAssign && "ITEM".equals(order.type()) && fragment == null) {
        model.addAttribute("actingUser", fetchActingUser());
      }

      int yellowDays = 30;
      int redDays = 90;
      try {
        SystemSettingDto yellowSetting = jobOrderClient.ageYellowSetting();
        yellowDays = Integer.parseInt(yellowSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch yellow days setting, using default");
      }
      try {
        SystemSettingDto redSetting = jobOrderClient.ageRedSetting();
        redDays = Integer.parseInt(redSetting.value());
      } catch (Exception e) {
        log.warn("Could not fetch red days setting, using default");
      }
      model.addAttribute("ageYellowDays", yellowDays);
      model.addAttribute("ageRedDays", redDays);

      if (!model.containsAttribute("handoverForm")) {
        JobOrderHandoverForm handoverForm = new JobOrderHandoverForm();
        handoverForm.setRecipientSquadron(
            order.requestingOrgUnit() != null ? order.requestingOrgUnit().shorthand() : null);
        model.addAttribute("handoverForm", handoverForm);
      }

      if (!model.containsAttribute("itemHandoverForm")) {
        model.addAttribute("itemHandoverForm", new JobOrderItemHandoverForm());
      }
      model.addAttribute("hasOutstandingItemLines", hasOutstandingItemLines(order));
      model.addAttribute("isFullyDelivered", isFullyDelivered(order));
      model.addAttribute("kpi", computeKpi(order));

      if ("ITEM".equals(order.type())
          && (fragment == null || "blueprint-owners".equalsIgnoreCase(fragment))) {
        try {
          model.addAttribute("itemBlueprintOwners", jobOrderClient.itemBlueprintOwners(id));
        } catch (Exception e) {
          log.debug("Blueprint coverage unavailable for order {}: {}", id, e.getMessage());
        }
      }

      if ("ITEM".equals(order.type())
          && !requesterView
          && (fragment == null || "items".equalsIgnoreCase(fragment))) {
        try {
          List<JobOrderItemStockGroupDto> stock = jobOrderClient.itemStock(id);
          Map<UUID, JobOrderItemStockGroupDto> byGameItem = new LinkedHashMap<>();
          if (stock != null) {
            for (JobOrderItemStockGroupDto group : stock) {
              if (group.gameItem() != null && group.gameItem().id() != null) {
                byGameItem.put(group.gameItem().id(), group);
              }
            }
          }
          model.addAttribute("itemStockByGameItem", byGameItem);
        } catch (Exception e) {
          log.debug("Item stock unavailable for order {}: {}", id, e.getMessage());
        }
      }

      if (fragment == null) {
        try {
          model.addAttribute("orphanedInventory", jobOrderClient.orphanedInventory(id));
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
    if (fragment != null) {
      return switch (fragment.toLowerCase(java.util.Locale.ROOT)) {
        case "materials" -> "orders-detail :: materialsSection";
        case "aggregated" -> "orders-detail :: aggregatedSection";
        case "header" -> "orders-detail :: orderHeader";
        case "kpi" -> "orders-detail :: kpiSection";
        case "handovers" -> "orders-detail :: materialHandoverSection";
        case "items" -> "orders-detail :: itemsSection";
        case "item-handovers" -> "orders-detail :: itemHandoverSection";
        case "item-handover-lines" -> "orders-detail :: itemHandoverLines";
        case "blueprint-owners" -> "orders-detail :: blueprintOwnersSection";
        case "assignees" -> "orders-detail :: assigneesSection";
        default -> "orders-detail";
      };
    }
    return "orders-detail";
  }

  /**
   * Renders the create-order form ({@code /orders/create}) with the material and orderable-item
   * catalogs and both owner-pickers (ADR-0149).
   *
   * @param source optional origin marker for the post-save redirect
   * @param principal the caller
   * @param model Thymeleaf model populated with form and reference catalogs
   * @return the {@code order-create} view name
   */
  @NotNull
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
    model.addAttribute("qualityTiers", qualityTierCatalog.all());
    addOwnerPickerOptions(model);
    return "orders-create";
  }

  /**
   * Renders the item-order edit page: the create form in edit mode, with the stored responsible and
   * requesting org units preselected and the existing lines injected as {@code window.EDIT_ITEMS}.
   *
   * <p>Non-item orders and orders with an item handover redirect to the detail page.
   *
   * @param id the item-order id
   * @param model Thymeleaf model
   * @param redirectAttributes flash carrier for the block cases
   * @return the {@code orders-create} view (in edit mode) or a redirect to the detail page
   */
  @NotNull
  @GetMapping("/{id}/items/edit")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public String viewEditItemForm(
      @PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
    JobOrderDto order;
    try {
      order = jobOrderClient.order(id);
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

    if (!model.containsAttribute("jobOrderForm")) {
      model.addAttribute("jobOrderForm", new JobOrderForm());
    }
    if (!model.containsAttribute("jobOrderItemForm")) {
      JobOrderItemForm itemForm = new JobOrderItemForm();
      itemForm.setHandle(order.handle());
      itemForm.setComment(order.comment());
      itemForm.setResponsibleOrgUnitId(
          order.responsibleOrgUnit() != null ? order.responsibleOrgUnit().id() : null);
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
    model.addAttribute("qualityTiers", qualityTierCatalog.all());
    addOwnerPickerOptions(model);
    return "orders-create";
  }

  /**
   * Builds the {@code window.EDIT_ITEMS} prefill: one entry per item line with its id, booked
   * production, item, blueprint, amount, parent index and material-quality map.
   *
   * <p>The list index serves as the client line id.
   *
   * @param order the item order being edited
   * @return the JS-serializable prefill list
   */
  @NotNull
  private List<java.util.Map<String, Object>> buildEditItems(@NotNull JobOrderDto order) {
    List<de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto> items =
        order.items() != null ? order.items() : List.of();
    java.util.Map<UUID, Integer> idToIndex = new java.util.HashMap<>();
    for (int i = 0; i < items.size(); i++) {
      idToIndex.put(items.get(i).id(), i);
    }
    List<java.util.Map<String, Object>> result = new ArrayList<>();
    for (de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto line : items) {
      java.util.Map<String, String> qualities = new java.util.LinkedHashMap<>();
      if (line.materials() != null) {
        for (de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemMaterialDto m :
            line.materials()) {
          if (m.material() != null && m.material().id() != null) {
            qualities.put(m.material().id().toString(), m.qualityRequirement());
          }
        }
      }
      java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
      entry.put("id", line.id() != null ? line.id().toString() : null);
      entry.put("manufactured", line.manufacturedAmount() != null ? line.manufacturedAmount() : 0);
      entry.put("gameItemId", line.gameItem() != null ? line.gameItem().id().toString() : null);
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
      List<BlueprintReferenceDto> result = jobOrderClient.itemBlueprints(gameItemId);
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
  @Nullable
  @GetMapping("/item-derivation/{blueprintId}")
  @ResponseBody
  public ItemDerivationDto itemDerivation(
      @PathVariable UUID blueprintId,
      @RequestParam(required = false, defaultValue = "1") int amount) {
    try {
      return jobOrderClient.itemDerivation(blueprintId, amount);
    } catch (Exception e) {
      log.error("Failed to derive materials for blueprint {}", blueprintId, e);
      return null;
    }
  }

  /**
   * JSON proxy for the item-order picker's live search by item name.
   *
   * @param q the case-insensitive item-name search term ({@code null} / blank = first page)
   * @return up to {@link PickerSearch#PAGE_SIZE} matching orderable item references, or an empty
   *     list on failure
   */
  @GetMapping("/item-search")
  @ResponseBody
  public List<GameItemReferenceDto> itemSearch(@RequestParam(required = false) String q) {
    try {
      PageResponse<GameItemReferenceDto> page =
          jobOrderClient.searchOrderableItems(q == null ? "" : q, PickerSearch.PAGE_SIZE);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception e) {
      log.error("Failed to search orderable items", e);
      return List.of();
    }
  }

  /**
   * Returns {@code true} iff the option list contains a Spezialkommando entry; computed here for
   * the template.
   *
   * @param options the active-org-unit list, may be {@code null}
   * @return {@code true} when at least one row's {@code kind} is {@code SPECIAL_COMMAND}
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
  public List<InventoryItemDto> getInventoryItemsForMaterial(
      @PathVariable UUID id, @PathVariable UUID matId) {
    try {
      return jobOrderClient.inventoryForMaterial(id, matId);
    } catch (Exception e) {
      log.error("Failed to fetch inventory items for job order {} and material {}", id, matId, e);
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to load inventory items");
    }
  }

  @NotNull
  private List<MaterialDto> fetchMaterials() {
    try {
      List<MaterialDto> list = jobOrderClient.jobOrderMaterials();
      if (list != null) {
        return new ArrayList<>(list);
      }
    } catch (Exception e) {
      log.error("Failed to fetch job-order materials", e);
    }
    return new ArrayList<>();
  }

  /**
   * Resolves the acting user, whose id and name seed the production modal's owner picker
   * (REQ-INV-032).
   *
   * @return the acting user, or {@code null} when the lookup fails
   */
  @Nullable
  private UserDto fetchActingUser() {
    try {
      return jobOrderClient.currentUser();
    } catch (Exception e) {
      log.warn("Failed to resolve the acting user for the production book-in owner seed", e);
      return null;
    }
  }

  /**
   * Probes whether at least one orderable item exists, deciding between the item picker and the
   * empty banner; fails open on a backend error.
   *
   * @return {@code true} when at least one orderable item exists (or the probe could not decide)
   */
  private boolean hasOrderableItems() {
    try {
      PageResponse<GameItemReferenceDto> page = jobOrderClient.orderableItemProbe();
      return page == null || page.content() == null || !page.content().isEmpty();
    } catch (Exception e) {
      log.error("Failed to probe orderable items", e);
      return true;
    }
  }

  /**
   * Collects the names of an item order's lines whose blueprint derived no procurable material, for
   * the detail page's warning banner.
   *
   * @param order the loaded order (any kind)
   * @return distinct item names lacking derived materials, in line order; never {@code null}
   */
  @NotNull
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
   * Whether an item order has a line with manufactured but undelivered units, which gates the
   * item-handover button (REQ-ORDERS-025).
   *
   * @param order the loaded order (any kind)
   * @return {@code true} if any item line has a positive manufactured-but-undelivered quantity
   */
  private boolean hasOutstandingItemLines(JobOrderDto order) {
    if (order == null || !"ITEM".equals(order.type()) || order.items() == null) {
      return false;
    }
    for (JobOrderItemDto item : order.items()) {
      int manufactured = item.manufacturedAmount() != null ? item.manufacturedAmount() : 0;
      int delivered = item.deliveredAmount() != null ? item.deliveredAmount() : 0;
      if (manufactured - delivered > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether every line of an item order is fully delivered; always {@code false} for material
   * orders or orders without item lines.
   *
   * @param order the loaded order (any kind)
   * @return {@code true} if the order is an item order with at least one line and every line's
   *     delivered amount meets its ordered amount
   */
  private boolean isFullyDelivered(JobOrderDto order) {
    if (order == null
        || !"ITEM".equals(order.type())
        || order.items() == null
        || order.items().isEmpty()) {
      return false;
    }
    for (JobOrderItemDto item : order.items()) {
      int amount = item.amount() != null ? item.amount() : 0;
      int delivered = item.deliveredAmount() != null ? item.deliveredAmount() : 0;
      if (delivered < amount) {
        return false;
      }
    }
    return true;
  }

  /**
   * Computes the KPI strip values from the loaded order (REQ-ORDERS-026): the delivered-against-
   * required progress head, fulfilment, the open quantity split into SCU and pieces, and the claim
   * and handover counts.
   *
   * <p>A material order's requirement is the still-open line amount plus what its handovers
   * delivered, per unit; the progress is withheld when the order is redacted for its requester,
   * whose view carries no handovers. Missing stock or claims in that view count as zero.
   *
   * @param order the loaded order (any kind)
   * @return an insertion-ordered map of KPI values keyed for the fragment ({@code fulfilled},
   *     {@code total}, {@code fulfilledPct}, {@code delivered}, {@code amount}, {@code
   *     deliveredPct}, {@code deliveredScu}, {@code requiredScu}, {@code deliveredPiece}, {@code
   *     requiredPiece}, {@code progressPct}, {@code showProgress}, {@code openAmountScu}, {@code
   *     openAmountPiece}, {@code hasScuMaterial}, {@code hasPieceMaterial}, {@code claims}, {@code
   *     handovers}, {@code supportsClaims})
   */
  @NotNull
  private Map<String, Object> computeKpi(JobOrderDto order) {
    Map<String, Object> kpi = new LinkedHashMap<>();
    kpi.put("fulfilled", 0);
    kpi.put("total", 0);
    kpi.put("fulfilledPct", 0);
    kpi.put("delivered", 0);
    kpi.put("amount", 0);
    kpi.put("deliveredPct", 0);
    kpi.put("deliveredScu", 0.0);
    kpi.put("requiredScu", 0.0);
    kpi.put("deliveredPiece", 0.0);
    kpi.put("requiredPiece", 0.0);
    kpi.put("progressPct", 0);
    kpi.put("showProgress", false);
    kpi.put("openAmountScu", 0.0);
    kpi.put("openAmountPiece", 0.0);
    kpi.put("hasScuMaterial", false);
    kpi.put("hasPieceMaterial", false);
    kpi.put("claims", 0);
    kpi.put("handovers", 0);
    kpi.put("supportsClaims", false);
    if (order == null) {
      return kpi;
    }

    double openAmountScu = 0.0;
    double openAmountPiece = 0.0;
    boolean hasScuMaterial = false;
    boolean hasPieceMaterial = false;
    int claims = 0;
    boolean supportsClaims = false;
    if ("ITEM".equals(order.type())) {
      int delivered = 0;
      int amount = 0;
      if (order.items() != null) {
        for (JobOrderItemDto item : order.items()) {
          amount += item.amount() != null ? item.amount() : 0;
          delivered += item.deliveredAmount() != null ? item.deliveredAmount() : 0;
        }
      }
      if (order.aggregatedMaterials() != null) {
        for (var agg : order.aggregatedMaterials()) {
          double required = agg.totalQuantity() != null ? agg.totalQuantity() : 0.0;
          double stock = agg.currentStock() != null ? agg.currentStock() : 0.0;
          double open = Math.max(0.0, required - stock);
          if (agg.material() != null && "PIECE".equals(agg.material().quantityType())) {
            hasPieceMaterial = true;
            openAmountPiece += open;
          } else {
            hasScuMaterial = true;
            openAmountScu += open;
          }
          claims += agg.claims() != null ? agg.claims().size() : 0;
          if (agg.openAmount() != null) {
            supportsClaims = true;
          }
        }
      }
      kpi.put("delivered", delivered);
      kpi.put("amount", amount);
      kpi.put("deliveredPct", amount > 0 ? (delivered * 100 / amount) : 0);
      kpi.put("progressPct", percentOf(delivered, amount));
      kpi.put("showProgress", amount > 0);
      kpi.put("handovers", order.itemHandovers() != null ? order.itemHandovers().size() : 0);
    } else {
      putMaterialProgress(kpi, order);
      int fulfilled = 0;
      int total = 0;
      if (order.materials() != null) {
        total = order.materials().size();
        for (JobOrderMaterialDto mat : order.materials()) {
          double required = mat.amount() != null ? mat.amount() : 0.0;
          double stock = mat.currentStock() != null ? mat.currentStock() : 0.0;
          if (stock >= required) {
            fulfilled++;
          }
          double open = Math.max(0.0, required - stock);
          if (mat.material() != null && "PIECE".equals(mat.material().quantityType())) {
            hasPieceMaterial = true;
            openAmountPiece += open;
          } else {
            hasScuMaterial = true;
            openAmountScu += open;
          }
          claims += mat.claims() != null ? mat.claims().size() : 0;
          if (mat.openAmount() != null) {
            supportsClaims = true;
          }
        }
      }
      kpi.put("fulfilled", fulfilled);
      kpi.put("total", total);
      kpi.put("fulfilledPct", total > 0 ? (fulfilled * 100 / total) : 0);
      kpi.put("handovers", order.handovers() != null ? order.handovers().size() : 0);
    }
    kpi.put("openAmountScu", openAmountScu);
    kpi.put("openAmountPiece", openAmountPiece);
    kpi.put("hasScuMaterial", hasScuMaterial);
    kpi.put("hasPieceMaterial", hasPieceMaterial);
    kpi.put("claims", claims);
    kpi.put("supportsClaims", supportsClaims);
    return kpi;
  }

  /**
   * Puts a material order's delivered and required quantities per unit and its progress into the
   * KPI map (REQ-ORDERS-026).
   *
   * <p>A handover lowers its line's amount, so the requirement is the open line amount plus the
   * delivered amount. With both units present the progress is the mean of the two per-unit shares,
   * because SCU and pieces are never summed.
   *
   * @param kpi the KPI map to fill
   * @param order the loaded material order
   */
  private static void putMaterialProgress(
      @NotNull Map<String, Object> kpi, @NotNull JobOrderDto order) {
    double deliveredScu = 0.0;
    double deliveredPiece = 0.0;
    if (order.handovers() != null) {
      for (JobOrderHandoverDto handover : order.handovers()) {
        if (handover.items() == null) {
          continue;
        }
        for (JobOrderHandoverItemDto line : handover.items()) {
          double amount = line.amount() != null ? line.amount() : 0.0;
          if (line.material() != null && "PIECE".equals(line.material().quantityType())) {
            deliveredPiece += amount;
          } else {
            deliveredScu += amount;
          }
        }
      }
    }
    double requiredScu = deliveredScu;
    double requiredPiece = deliveredPiece;
    if (order.materials() != null) {
      for (JobOrderMaterialDto mat : order.materials()) {
        double open = mat.amount() != null ? mat.amount() : 0.0;
        if (mat.material() != null && "PIECE".equals(mat.material().quantityType())) {
          requiredPiece += open;
        } else {
          requiredScu += open;
        }
      }
    }
    int progressPct;
    if (requiredScu > 0 && requiredPiece > 0) {
      progressPct =
          (percentOf(deliveredScu, requiredScu) + percentOf(deliveredPiece, requiredPiece)) / 2;
    } else if (requiredPiece > 0) {
      progressPct = percentOf(deliveredPiece, requiredPiece);
    } else {
      progressPct = percentOf(deliveredScu, requiredScu);
    }
    kpi.put("deliveredScu", deliveredScu);
    kpi.put("requiredScu", requiredScu);
    kpi.put("deliveredPiece", deliveredPiece);
    kpi.put("requiredPiece", requiredPiece);
    kpi.put("progressPct", progressPct);
    kpi.put("showProgress", !order.redacted() && (requiredScu > 0 || requiredPiece > 0));
  }

  /**
   * Expresses a part of a whole as a whole-number percentage between 0 and 100.
   *
   * @param part the delivered quantity
   * @param whole the required quantity
   * @return the share in percent, floored and clamped; 0 when the whole is not positive
   */
  private static int percentOf(double part, double whole) {
    if (whole <= 0) {
      return 0;
    }
    return (int) Math.max(0, Math.min(100, Math.floor(part * 100.0 / whole + 1e-9)));
  }

  @NotNull
  private List<SquadronDto> fetchSquadrons() {
    try {
      PageResponse<SquadronDto> p = jobOrderClient.squadrons();
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch squadrons", e);
    }
    return new ArrayList<>();
  }

  /**
   * The active squadrons offered by the orders-overview multi-squadron filter (REQ-ORDERS-027),
   * sorted by name. Inactive squadrons are dropped so the picker never lists a retired Staffel.
   *
   * @return the active squadrons, name-sorted; never {@code null}
   */
  private List<SquadronDto> fetchActiveSquadrons() {
    return fetchSquadrons().stream()
        .filter(s -> Boolean.TRUE.equals(s.active()))
        .sorted(
            java.util.Comparator.comparing(
                s -> s.name() != null ? s.name() : "", String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Fetches every active Staffel and Spezialkommando with its profit-eligibility flag from {@code
   * GET /api/v1/org-units/active}; the fallback for {@link #fetchRequestingOrgUnitOptions}.
   *
   * @return picker options or empty list; never {@code null}
   */
  private List<OrgUnitMembershipOptionDto> fetchActiveOrgUnitOptions() {
    try {
      List<OrgUnitMembershipOptionDto> options = jobOrderClient.activeOrgUnits();
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch active org units for Job Order owner-picker", e);
      return List.of();
    }
  }

  /**
   * Resolves the requesting-org-unit picker options: every active org unit of all four kinds,
   * falling back to the Staffel/SK catalog on a backend failure.
   *
   * @return requesting-picker options; never {@code null}
   */
  private List<OrgUnitMembershipOptionDto> fetchRequestingOrgUnitOptions() {
    try {
      List<OrgUnitMembershipOptionDto> options = jobOrderClient.activeOrgUnitsAllKinds();
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
   * Populates the create/edit form model with the requesting options and their profit-eligible
   * subset as the responsible options.
   *
   * @param model the Thymeleaf model to populate
   */
  private void addOwnerPickerOptions(Model model) {
    applyOwnerPickerOptions(model, fetchRequestingOrgUnitOptions());
  }

  /**
   * Populates the two owner-picker model attributes from an already-fetched requesting-option list.
   *
   * @param model the Thymeleaf model to populate
   * @param requestingOptions the requesting options, from which the profit-eligible {@code
   *     responsibleOptions} are derived
   */
  private void applyOwnerPickerOptions(
      Model model, @NotNull List<OrgUnitMembershipOptionDto> requestingOptions) {
    List<OrgUnitMembershipOptionDto> responsibleOptions =
        requestingOptions.stream().filter(o -> Boolean.TRUE.equals(o.isProfitEligible())).toList();
    model.addAttribute("responsibleOptions", responsibleOptions);
    model.addAttribute("responsibleHasSpecialCommand", containsSpecialCommand(responsibleOptions));
    model.addAttribute("requestingOptions", requestingOptions);
    model.addAttribute("requestingHasSpecialCommand", containsSpecialCommand(requestingOptions));
  }

  @Contract("null -> null")
  @Nullable
  private UUID getCurrentUserId(OidcUser principal) {
    if (principal == null) {
      return null;
    }
    UUID fromToken = CurrentUser.userId(principal);
    if (fromToken != null) {
      return fromToken;
    }
    try {
      UserDto me = jobOrderClient.currentUser();
      return me != null ? me.id() : null;
    } catch (Exception ex) {
      log.warn("Failed to get current user ID from backend: {}", ex.getMessage());
      return null;
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
    log.debug(
        "JobOrder: Checking logistician status. Original authorities: {}."
            + " Reachable authorities: {}",
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
