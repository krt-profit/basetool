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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.model.dto.ClaimDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderHandoverForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderItemForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderItemHandoverForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.MutationResponseHelper;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the state-mutating job-order endpoints under {@code /orders}: order and
 * item-order create/update/delete, the priority and status mutations, the blueprint-variant
 * counting toggle, material claims, assignee management (add/remove plus per-assignee notes), the
 * material and item handover flows, and the unlink endpoints for materials and inventory items.
 *
 * <p>Split out of {@link JobOrderPageController} in the #924 L5 read/write controller split; every
 * route, security annotation, view name and response contract moved over unchanged, so the page
 * templates and the order-detail AJAX layer keep working against the exact same surface. The small
 * leaf helpers shared with the read side ({@code fetchUsers}, {@code getCurrentUserId}, {@code
 * isLogistician} and the {@code PAGE_OF_USER} response type) are duplicated verbatim per the
 * campaign precedent instead of introducing a new shared type.
 */
@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class JobOrderWriteController {

  /** Typed WebClient wrapper relaying every job-order mutation to the backend REST API. */
  private final BackendApiClient backendApiClient;

  /**
   * Role hierarchy consulted by the duplicated {@link #isLogistician} check so implied authorities
   * (ADMIN/OFFICER reaching LOGISTICIAN) gate the assignee-section rendering exactly as on the read
   * side.
   */
  private final RoleHierarchy roleHierarchy;

  /**
   * Shared redirect/flash-toast helper backing the classic (non-AJAX) priority and delete
   * mutations.
   */
  private final MutationResponseHelper mutationResponseHelper;

  /**
   * Auth helper resolving whether the SecurityContext is anonymous, backing the guest-routing
   * checks on the order-create handlers.
   */
  private final FrontendAuthHelperService authHelper;

  /**
   * Response type for the user-list pull backing the assignee picker ({@code GET /api/v1/users}).
   * Mirrors the read-side {@code JobOrderPageController} original (#924 split — duplicated with the
   * {@link #fetchUsers} leaf helper).
   */
  private static final ParameterizedTypeReference<PageResponse<UserDto>> PAGE_OF_USER =
      new ParameterizedTypeReference<PageResponse<UserDto>>() {};

  /**
   * Persists a new item order. Builds the backend item-order payload from the dynamically-bound
   * item editor (lines that lack an item, blueprint, or positive amount are dropped) and posts it
   * to the backend. Mirrors {@link #createOrder} for redirect / flash behaviour.
   *
   * @param form the bound item-order form
   * @param redirectAttributes flash carrier for toasts / re-render
   * @param principal the caller, or {@code null} for anonymous
   * @return redirect target
   */
  @PostMapping("/items")
  public String createItemOrder(
      @ModelAttribute("jobOrderItemForm") JobOrderItemForm form,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      List<CreateJobOrderItemLineDto> lines =
          form.getItems().stream()
              .filter(
                  l ->
                      l.getGameItemId() != null
                          && l.getBlueprintId() != null
                          && l.getAmount() != null
                          && l.getAmount() > 0)
              .map(
                  l ->
                      new CreateJobOrderItemLineDto(
                          l.getGameItemId(),
                          l.getBlueprintId(),
                          l.getAmount(),
                          l.getMaterials().stream()
                              .filter(m -> m.getMaterialId() != null && m.getQuality() != null)
                              .map(
                                  m ->
                                      new CreateJobOrderItemMaterialDto(
                                          m.getMaterialId(), m.getQuality()))
                              .toList(),
                          l.getClientLineId(),
                          l.getParentClientLineId()))
              .toList();

      if (lines.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.item.invalid");
        redirectAttributes.addFlashAttribute("jobOrderItemForm", form);
        return "redirect:/orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }

      CreateJobOrderItemRequestDto dto =
          new CreateJobOrderItemRequestDto(
              form.getResponsibleOrgUnitId(),
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              lines,
              form.getVersion());
      backendApiClient.post("/api/v1/orders/items", dto, JobOrderDto.class, true);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.create");

      // Anonymous guests and non-profit members cannot browse the queue, so keep them on the create
      // form (with the success toast) instead of bouncing them to a list they may not see.
      if (authHelper.isAnonymous() || !canViewJobOrders) {
        return "redirect:/orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }
      return "redirect:/orders";
    } catch (Exception e) {
      log.error("Failed to create item order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.create.failed");
      redirectAttributes.addFlashAttribute("jobOrderItemForm", form);
      return "redirect:/orders/create"
          + (form.getSource() != null ? "?source=" + form.getSource() : "");
    }
  }

  /**
   * Builds the validated {@link CreateJobOrderItemLineDto} list from an item-order form, dropping
   * lines without a game item, blueprint or positive amount, and per-line materials without a
   * material id or quality. Shared by the item create + edit AJAX twins.
   *
   * @param form the bound item-order form
   * @return the filtered item-line DTOs (preserving the client line + parent ids)
   */
  private static List<CreateJobOrderItemLineDto> buildItemLineDtos(JobOrderItemForm form) {
    return form.getItems().stream()
        .filter(
            l ->
                l.getGameItemId() != null
                    && l.getBlueprintId() != null
                    && l.getAmount() != null
                    && l.getAmount() > 0)
        .map(
            l ->
                new CreateJobOrderItemLineDto(
                    l.getGameItemId(),
                    l.getBlueprintId(),
                    l.getAmount(),
                    l.getMaterials().stream()
                        .filter(m -> m.getMaterialId() != null && m.getQuality() != null)
                        .map(
                            m ->
                                new CreateJobOrderItemMaterialDto(
                                    m.getMaterialId(), m.getQuality()))
                        .toList(),
                    l.getClientLineId(),
                    l.getParentClientLineId()))
        .toList();
  }

  /**
   * AJAX twin of {@link #createItemOrder} (#575): creates an item order from the form-encoded body
   * and returns the post-create navigation target as JSON. Routed by the {@code X-Requested-With}
   * header; the classic handler stays the no-JS fallback. Empty lines → 400.
   *
   * @param form the bound item-order form
   * @param canViewJobOrders whether the caller may browse the order queue
   * @param principal the authenticated caller, or null for a guest
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/items", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> createItemOrderAjax(
      @ModelAttribute("jobOrderItemForm") JobOrderItemForm form,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      @AuthenticationPrincipal OidcUser principal) {
    List<CreateJobOrderItemLineDto> lines = buildItemLineDtos(form);
    if (lines.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      CreateJobOrderItemRequestDto dto =
          new CreateJobOrderItemRequestDto(
              form.getResponsibleOrgUnitId(),
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              lines,
              form.getVersion());
      backendApiClient.post("/api/v1/orders/items", dto, JobOrderDto.class, true);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of(
              "targetUrl", postCreateTarget(principal, canViewJobOrders, form.getSource())));
    } catch (BackendServiceException bse) {
      log.debug("Failed to create item order (ajax): {}", bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to create item order (ajax)", e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Persists an edit to an item order's lines + metadata. Mirrors {@link #createItemOrder}'s
   * payload build but relays to the backend item-edit endpoint ({@code PUT
   * /api/v1/orders/{id}/items}) and redirects back to the detail page.
   *
   * @param id the item-order id
   * @param form the bound item-order form (lines rebuilt client-side)
   * @param redirectAttributes flash carrier
   * @return redirect to the detail page
   */
  @PostMapping("/{id}/items/update")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public String updateItemOrder(
      @PathVariable UUID id,
      @ModelAttribute("jobOrderItemForm") JobOrderItemForm form,
      RedirectAttributes redirectAttributes) {
    try {
      List<CreateJobOrderItemLineDto> lines =
          form.getItems().stream()
              .filter(
                  l ->
                      l.getGameItemId() != null
                          && l.getBlueprintId() != null
                          && l.getAmount() != null
                          && l.getAmount() > 0)
              .map(
                  l ->
                      new CreateJobOrderItemLineDto(
                          l.getGameItemId(),
                          l.getBlueprintId(),
                          l.getAmount(),
                          l.getMaterials().stream()
                              .filter(m -> m.getMaterialId() != null && m.getQuality() != null)
                              .map(
                                  m ->
                                      new CreateJobOrderItemMaterialDto(
                                          m.getMaterialId(), m.getQuality()))
                              .toList(),
                          l.getClientLineId(),
                          l.getParentClientLineId()))
              .toList();

      if (lines.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.item.invalid");
        return "redirect:/orders/" + id + "/items/edit";
      }

      CreateJobOrderItemRequestDto dto =
          new CreateJobOrderItemRequestDto(
              null,
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              lines,
              form.getVersion());
      backendApiClient.put("/api/v1/orders/" + id + "/items", dto, JobOrderDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.update");
      return "redirect:/orders/" + id;
    } catch (Exception e) {
      log.error("Failed to update item order {}", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.update.failed");
      return "redirect:/orders/" + id + "/items/edit";
    }
  }

  /**
   * AJAX twin of {@link #updateItemOrder} (#575): saves an item-order edit and returns the detail-
   * page URL as JSON so the editor navigates itself. Routed by the {@code X-Requested-With} header;
   * the classic handler stays the no-JS fallback. Empty lines → 400.
   *
   * @param id the item-order id
   * @param form the bound item-order form
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/{id}/items/update", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateItemOrderAjax(
      @PathVariable UUID id, @ModelAttribute("jobOrderItemForm") JobOrderItemForm form) {
    List<CreateJobOrderItemLineDto> lines = buildItemLineDtos(form);
    if (lines.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      CreateJobOrderItemRequestDto dto =
          new CreateJobOrderItemRequestDto(
              null,
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              lines,
              form.getVersion());
      backendApiClient.put("/api/v1/orders/" + id + "/items", dto, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("targetUrl", "/orders/" + id));
    } catch (BackendServiceException bse) {
      log.debug("Failed to update item order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to update item order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Persists a new job order. Validation failures re-render inline so the BindingResult stays
   * request-scoped. The source parameter determines the post-save redirect target.
   *
   * @return inline form view on failure, otherwise redirect
   */
  @PostMapping("/create")
  public String createOrder(
      @ModelAttribute("jobOrderForm") JobOrderForm form,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      List<CreateJobOrderMaterialDto> materials =
          form.getMaterials().stream()
              .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
              .map(
                  m ->
                      new CreateJobOrderMaterialDto(
                          m.getMaterialId(), m.getMinQuality(), m.getAmount()))
              .toList();

      if (materials.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.material.invalid");
        redirectAttributes.addFlashAttribute("jobOrderForm", form);
        return "redirect:/orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }

      CreateJobOrderDto dto =
          new CreateJobOrderDto(
              form.getResponsibleOrgUnitId(),
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              materials,
              form.getVersion());
      backendApiClient.post("/api/v1/orders", dto, JobOrderDto.class, true);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.create");

      // Anonymous guests and non-profit members cannot browse the queue, so keep them on the create
      // form (with the success toast) instead of bouncing them to a list they may not see.
      if (authHelper.isAnonymous() || !canViewJobOrders) {
        return "redirect:/orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }
      if ("index".equals(form.getSource())) {
        return "redirect:/orders";
      }
      // fallback
      return "redirect:/orders";
    } catch (Exception e) {
      log.error("Failed to create order", e);
      log.error("Failed to create job order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.create.failed");
      redirectAttributes.addFlashAttribute("jobOrderForm", form);
      return "redirect:/orders/create"
          + (form.getSource() != null ? "?source=" + form.getSource() : "");
    }
  }

  /**
   * Shared post-create navigation target (#575): viewers go to the order list, anonymous guests and
   * non-profit members stay on the create form (mirrors the classic {@link #createOrder} / {@link
   * #createItemOrder} redirects, since they cannot browse the queue).
   *
   * @param principal the caller (null for an anonymous guest)
   * @param canViewJobOrders whether the caller may browse the order queue
   * @param source the create-page source param to carry on the stay-on-create target
   * @return the URL the AJAX caller should navigate to on success
   */
  private static String postCreateTarget(
      OidcUser principal, boolean canViewJobOrders, String source) {
    if (principal == null || !canViewJobOrders) {
      return "/orders/create" + (source != null ? "?source=" + source : "");
    }
    return "/orders";
  }

  /**
   * Builds the validated {@link CreateJobOrderMaterialDto} list from a material-order form,
   * dropping rows without a material id or a positive amount.
   *
   * @param form the bound material-order form
   * @return the filtered material requirement DTOs
   */
  private static List<CreateJobOrderMaterialDto> buildMaterialDtos(JobOrderForm form) {
    return form.getMaterials().stream()
        .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
        .map(
            m -> new CreateJobOrderMaterialDto(m.getMaterialId(), m.getMinQuality(), m.getAmount()))
        .toList();
  }

  /**
   * AJAX twin of {@link #createOrder} (#575): creates a material order from the form-encoded body
   * and returns the post-create navigation target as JSON, so the create page navigates itself
   * instead of a server redirect (and stays put with an inline toast on a validation/backend
   * failure). Routed by the {@code X-Requested-With} header so the classic form-POST handler stays
   * the no-JS fallback. Empty materials → 400 (the page also pre-validates).
   *
   * @param form the bound material-order form
   * @param canViewJobOrders whether the caller may browse the order queue (decides the target)
   * @param principal the authenticated caller, or null for a guest
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/create", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> createOrderAjax(
      @ModelAttribute("jobOrderForm") JobOrderForm form,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      @AuthenticationPrincipal OidcUser principal) {
    List<CreateJobOrderMaterialDto> materials = buildMaterialDtos(form);
    if (materials.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      CreateJobOrderDto dto =
          new CreateJobOrderDto(
              form.getResponsibleOrgUnitId(),
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              materials,
              form.getVersion());
      backendApiClient.post("/api/v1/orders", dto, JobOrderDto.class, true);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of(
              "targetUrl", postCreateTarget(principal, canViewJobOrders, form.getSource())));
    } catch (BackendServiceException bse) {
      log.debug("Failed to create order (ajax): {}", bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to create order (ajax)", e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Reorders an order's priority (drag-and-drop between job orders). Backend uses pessimistic
   * locking on the entire job-order priority sequence to serialize concurrent reorders, per the
   * concurrency pattern in CLAUDE.md.
   *
   * @return redirect to {@code /orders}
   */
  @PostMapping("/{id}/priority")
  @PreAuthorize("isAuthenticated()")
  public String updatePriority(
      @PathVariable UUID id,
      @RequestParam Integer priority,
      RedirectAttributes redirectAttributes) {
    return mutationResponseHelper.mutate(
        redirectAttributes,
        "/orders",
        "success.joborder.priority",
        "error.joborder.priority.failed",
        () ->
            backendApiClient.put(
                "/api/v1/orders/" + id + "/priority?priority=" + priority,
                null,
                JobOrderDto.class));
  }

  /**
   * AJAX twin of {@link #updatePriority}: persists a drag-drop reorder without a full-page reload
   * (epic #571 / #575). Returns the dragged order so the caller can confirm success; the
   * order-index JS then re-renders the whole queue via a {@code ?fragment=results} swap, because
   * the backend reshuffles <em>every</em> active order's priority slot (not just this one), so
   * sibling rows' {@code data-priority} attributes must refresh or the next drag computes a stale
   * target slot. The gate is tightened to {@code LOGISTICIAN} to match the backend so a
   * non-logistician gets a clean propagated 403 toast instead of a redirect.
   *
   * @param id the order whose priority slot changed
   * @param priority the new 1-based target slot
   * @return the updated order on success, or the propagated RFC 7807 backend error
   */
  @PutMapping("/{id}/priority/ajax")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updatePriorityAjax(
      @PathVariable UUID id, @RequestParam Integer priority) {
    try {
      JobOrderDto result =
          backendApiClient.put(
              "/api/v1/orders/" + id + "/priority?priority=" + priority, null, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (BackendServiceException bse) {
      log.debug("Failed to update priority (ajax) for order {}: {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to update priority (ajax) for order {}", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX status transition endpoint. Updates the job order's status (OPEN → IN_PROGRESS →
   * COMPLETED, REJECTED). Backend enforces the state machine and rejects illegal transitions with a
   * 400.
   *
   * @return updated order on success, propagated backend status code on failure
   */
  @PostMapping("/{id}/status")
  @PreAuthorize("isAuthenticated()")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateStatus(
      @PathVariable UUID id, @RequestBody UpdateJobOrderStatusDto dto) {
    try {
      JobOrderDto result =
          backendApiClient.put("/api/v1/orders/" + id + "/status", dto, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (BackendServiceException bse) {
      log.debug("Failed to update status for order {}: {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to update status for order {}", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX toggle for the item-order blueprint-coverage variant counting (issue #822). Relays the
   * requested mode + version to the backend's PATCH endpoint and propagates its status code (400
   * not an item order, 403 forbidden, 409 optimistic-lock conflict) so the order-detail JS can show
   * a clean toast and re-render the coverage panel in place. The coarse {@code
   * hasRole('LOGISTICIAN')} gate here mirrors the backend; the fine per-order edit scope is
   * enforced backend-side.
   *
   * @param id the order id.
   * @param dto the requested counting mode + the order's expected version.
   * @return the updated order on success, or the propagated backend error status.
   */
  @PostMapping("/{id}/blueprint-variant-counting")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateBlueprintVariantCounting(
      @PathVariable UUID id, @RequestBody UpdateJobOrderBlueprintCountingDto dto) {
    try {
      JobOrderDto result =
          backendApiClient.patch(
              "/api/v1/orders/" + id + "/blueprint-variant-counting", dto, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (BackendServiceException bse) {
      log.debug(
          "Failed to update blueprint variant counting for order {}: {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to update blueprint variant counting for order {}", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX create-or-update of a material claim ("Eintragung") on a public SK order (Phase 6, #346).
   * Relays the payload to the backend's claim upsert and propagates its status code so the
   * order-detail JS can show a clean toast — 400 (not an open SK order / unknown bucket /
   * overclaim), 403 (may not act for the claiming squadron), 409 (concurrent claim modification) —
   * instead of a stack trace. The coarse {@code hasRole('LOGISTICIAN')} gate here is the same one
   * the backend carries; the fine per-squadron / responsible-SK matrix is enforced backend-side.
   *
   * <p>The 409 branch is load-bearing: the backend upsert is a bounded-retry orchestrator over a
   * find-or-create on a {@code @Version}ed, uniquely-indexed row, so a concurrent same-squadron
   * first-claim race that outlasts the retry bound surfaces a truthful 409 ({@code OPTIMISTIC_LOCK}
   * on the UPDATE race, {@code DATA_INTEGRITY_VIOLATION} on the INSERT race). {@link
   * #propagateBackendError} mirrors that status <em>and</em> its RFC 7807 {@code code} verbatim —
   * so {@code krt-fetch.js} still decides "stale data, reload?" vs. plain-toast from the code —
   * rather than collapsing the conflict into a 500 (the trap the payout-toggle proxy hit in #1111).
   *
   * @param id the order id.
   * @param dto the claim payload (material, quality bucket, claiming squadron, amount).
   * @return the persisted claim on success, or the propagated backend error status (incl. a 409 on
   *     a surviving claim race).
   */
  @PostMapping("/{id}/claims")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> upsertClaim(
      @PathVariable UUID id, @RequestBody CreateClaimDto dto) {
    try {
      ClaimDto result =
          backendApiClient.post("/api/v1/orders/" + id + "/claims", dto, ClaimDto.class);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.CREATED)
          .body(result);
    } catch (BackendServiceException bse) {
      // Mirrors every backend status faithfully, including a 409 that survived the backend's
      // bounded
      // claim-upsert retry — its RFC 7807 code is preserved so the client toasts/reloads correctly
      // instead of the race being collapsed into a 500.
      log.debug("Failed to upsert claim on order {}: {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to upsert claim on order {}", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX withdrawal of a material claim (Phase 6, #346). Relays to the backend's claim delete and
   * propagates the status code (404 unknown claim, 400 terminal/non-SK order, 403 forbidden) for a
   * clean toast.
   *
   * @param id the order id.
   * @param claimId the claim to withdraw.
   * @return 204 on success, or the propagated backend error status.
   */
  @PostMapping("/{id}/claims/{claimId}/withdraw")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> withdrawClaim(
      @PathVariable UUID id, @PathVariable UUID claimId) {
    try {
      backendApiClient.delete("/api/v1/orders/" + id + "/claims/" + claimId, Void.class);
      return org.springframework.http.ResponseEntity.noContent().build();
    } catch (BackendServiceException bse) {
      log.debug("Failed to withdraw claim {} on order {}: {}", claimId, id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to withdraw claim {} on order {}", claimId, id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Persists an edit to the order's metadata (note, target inventory location, materials list).
   * Restricted to logisticians at the controller boundary; backend additionally enforces a
   * fine-grained role check on the {@code @PreAuthorize}-annotated service method.
   *
   * @return redirect to the order detail page
   */
  @PostMapping("/{id}/update")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public String updateOrder(
      @PathVariable UUID id,
      @ModelAttribute("jobOrderForm") JobOrderForm form,
      RedirectAttributes redirectAttributes) {
    try {
      List<CreateJobOrderMaterialDto> materials =
          form.getMaterials().stream()
              .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
              .map(
                  m ->
                      new CreateJobOrderMaterialDto(
                          m.getMaterialId(), m.getMinQuality(), m.getAmount()))
              .toList();

      if (materials.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.material.invalid");
        redirectAttributes.addFlashAttribute("jobOrderForm", form);
        return "redirect:/orders/" + id;
      }

      CreateJobOrderDto dto =
          new CreateJobOrderDto(
              null,
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              materials,
              form.getVersion());
      backendApiClient.put("/api/v1/orders/" + id, dto, JobOrderDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.update");
      return "redirect:/orders/" + id;
    } catch (Exception e) {
      log.error("Failed to update order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.update.failed");
      redirectAttributes.addFlashAttribute("jobOrderForm", form);
      return "redirect:/orders/" + id;
    }
  }

  /**
   * AJAX twin of {@link #updateOrder} (#575): edits a MATERIAL order's requesting unit / handle /
   * comment / materials and returns the updated order so the detail page can re-render the header +
   * material sections in place (and pick up the bumped {@code @Version}) instead of a full reload.
   * Distinguished from the classic form-POST handler by {@code consumes=application/json}; that one
   * stays the no-JS fallback. An empty material list is a 400 (the client also pre-validates).
   *
   * @param id the order to update
   * @param form the edit payload (requestingOrgUnitId, handle, comment, version, materials[])
   * @return the updated order on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(
      value = "/{id}/update",
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateOrderAjax(
      @PathVariable UUID id, @RequestBody JobOrderForm form) {
    List<CreateJobOrderMaterialDto> materials =
        form.getMaterials().stream()
            .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
            .map(
                m ->
                    new CreateJobOrderMaterialDto(
                        m.getMaterialId(), m.getMinQuality(), m.getAmount()))
            .toList();
    if (materials.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      CreateJobOrderDto dto =
          new CreateJobOrderDto(
              null,
              form.getRequestingOrgUnitId(),
              form.getHandle(),
              form.getComment(),
              materials,
              form.getVersion());
      JobOrderDto updated = backendApiClient.put("/api/v1/orders/" + id, dto, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (BackendServiceException bse) {
      log.debug("Failed to update order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to update order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Classic no-JS fallback for the requester-side MATERIAL edit (REQ-ORDERS-023): a member of the
   * order's requesting org unit changes quantities, adds/removes not-yet-delivered materials and
   * edits the comment. Relays to {@code PUT /api/v1/orders/{id}/requested}; only the comment +
   * materials + version reach the backend (handle / org unit / status / priority are ignored
   * server-side). No {@code hasRole('LOGISTICIAN')} — authorisation is the backend requester gate.
   *
   * @param id the order to update
   * @param form the edit payload (comment, version, materials[])
   * @param redirectAttributes flash carrier for the result toast
   * @return redirect back to the order detail
   */
  @PostMapping("/{id}/requested-update")
  @PreAuthorize("isAuthenticated()")
  public String updateOrderAsRequester(
      @PathVariable UUID id,
      @ModelAttribute("jobOrderForm") JobOrderForm form,
      RedirectAttributes redirectAttributes) {
    try {
      List<CreateJobOrderMaterialDto> materials =
          form.getMaterials().stream()
              .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
              .map(
                  m ->
                      new CreateJobOrderMaterialDto(
                          m.getMaterialId(), m.getMinQuality(), m.getAmount()))
              .toList();
      if (materials.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.material.invalid");
        redirectAttributes.addFlashAttribute("jobOrderForm", form);
        return "redirect:/orders/" + id;
      }
      CreateJobOrderDto dto =
          new CreateJobOrderDto(null, null, null, form.getComment(), materials, form.getVersion());
      backendApiClient.put("/api/v1/orders/" + id + "/requested", dto, JobOrderDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.update");
      return "redirect:/orders/" + id;
    } catch (Exception e) {
      log.error("Failed to update order {} as requester", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.update.failed");
      redirectAttributes.addFlashAttribute("jobOrderForm", form);
      return "redirect:/orders/" + id;
    }
  }

  /**
   * AJAX twin of {@link #updateOrderAsRequester}: the requester-side MATERIAL edit that returns the
   * updated (redacted) order so the detail page re-renders the header + material sections in place
   * (REQ-FE) and picks up the bumped {@code @Version}. Relays to {@code PUT
   * /api/v1/orders/{id}/requested}; an empty material list is a 400. The RFC 7807 backend error
   * (incl. 409 optimistic-lock, 400 frozen-after-delivery) is propagated so the page shows a toast
   * / conflict confirm and stays put.
   *
   * @param id the order to update
   * @param form the edit payload (comment, version, materials[])
   * @return the updated redacted order on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(
      value = "/{id}/requested-update",
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateOrderAsRequesterAjax(
      @PathVariable UUID id, @RequestBody JobOrderForm form) {
    List<CreateJobOrderMaterialDto> materials =
        form.getMaterials().stream()
            .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
            .map(
                m ->
                    new CreateJobOrderMaterialDto(
                        m.getMaterialId(), m.getMinQuality(), m.getAmount()))
            .toList();
    if (materials.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      CreateJobOrderDto dto =
          new CreateJobOrderDto(null, null, null, form.getComment(), materials, form.getVersion());
      JobOrderDto updated =
          backendApiClient.put("/api/v1/orders/" + id + "/requested", dto, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (BackendServiceException bse) {
      log.debug("Failed requester-update of order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed requester-update of order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Cancels (soft-deletes) a job order. Backend rejects the delete when the order has linked
   * inventory items, per the {@code EntityInUseException} pattern.
   *
   * @return redirect to {@code /orders}
   */
  @PostMapping("/{id}/delete")
  @PreAuthorize("isAuthenticated()")
  public String deleteOrder(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    return mutationResponseHelper.mutate(
        redirectAttributes,
        "/orders",
        "success.joborder.delete",
        "error.joborder.delete.failed",
        () -> backendApiClient.delete("/api/v1/orders/" + id, Void.class));
  }

  /**
   * AJAX twin of {@link #deleteOrder} (#575): cancels (soft-deletes) the order without a server
   * redirect, so the order-detail JS can confirm via the KRT dialog and then navigate to the list
   * itself. On a backend rejection (e.g. the order still has linked inventory) the RFC 7807 error
   * is propagated so the page shows a toast and stays put instead of redirect-reflashing. The
   * classic {@code POST}→redirect above stays the no-JS fallback.
   *
   * @param id the order to cancel
   * @return 204 on success, or the propagated RFC 7807 backend error
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> deleteOrderAjax(@PathVariable UUID id) {
    try {
      backendApiClient.delete("/api/v1/orders/" + id, Void.class);
      return org.springframework.http.ResponseEntity.noContent().build();
    } catch (BackendServiceException bse) {
      log.debug("Failed to delete order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to delete order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Adds an assignee to the job order and re-renders the Bearbeiter section as an AJAX fragment (no
   * full-page reload). The user-picker is fed by {@link UserProxyController}'s search endpoint.
   *
   * @param id job-order id
   * @param userId the user to add
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @PostMapping("/{id}/assignees")
  @PreAuthorize("isAuthenticated()")
  public String addAssignee(
      @PathVariable UUID id,
      @RequestParam UUID userId,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    JobOrderDto order =
        callAssigneeMutation(
            "add assignee",
            () ->
                backendApiClient.post(
                    "/api/v1/orders/" + id + "/assignees/" + userId, null, JobOrderDto.class));
    populateAssigneeSectionModel(model, principal, order);
    return "orders-detail :: assigneesSection";
  }

  /**
   * Creates a material handover for the job order.
   *
   * <p>The backend service uses the {@code …WithinTransaction} concurrency pattern (see CLAUDE.md):
   * it iterates the order's materials, collects ids that need a bulk clearing update, runs the bulk
   * update exactly once after the loop, and re-fetches the aggregate root before the completion
   * check — without that pattern a second {@code save()} on a detached child would silently merge
   * and bump {@code @Version} a second time, triggering 409s for clean callers.
   *
   * @return redirect to the order detail page
   */
  @PostMapping("/{id}/handovers")
  @PreAuthorize(
      "hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')")
  public String createHandover(
      @PathVariable UUID id,
      @ModelAttribute("handoverForm") JobOrderHandoverForm form,
      RedirectAttributes redirectAttributes) {
    try {
      List<JobOrderHandoverItemCreateDto> items =
          form.getItems().stream()
              .filter(
                  item ->
                      item.getInventoryItemId() != null
                          && item.getAmount() != null
                          && item.getAmount() > 0)
              .map(
                  item ->
                      new JobOrderHandoverItemCreateDto(
                          item.getInventoryItemId(), item.getAmount()))
              .toList();

      if (items.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.noitems");
        return "redirect:/orders/" + id;
      }

      // The frontend hidden input transmits the handover time as a UTC ISO-Instant (e.g.
      // "2026-04-25T10:04:00.000Z") that is produced client-side from the user's browser-local
      // date/time inputs by datetime-splitter.js. Parsing as Instant preserves the absolute
      // point in time and avoids timezone drift (previously LocalDateTime.parse threw on the
      // trailing "Z" and silently fell back to Instant.now(), which not only ignored the user
      // input but also produced wrong displayed times for users in DST/non-UTC zones).
      Instant handoverTime = Instant.now();
      String rawHandoverTime = form.getHandoverTime();
      if (rawHandoverTime != null && !rawHandoverTime.isBlank()) {
        try {
          handoverTime = Instant.parse(rawHandoverTime);
        } catch (Exception eiso) {
          try {
            handoverTime =
                LocalDateTime.parse(rawHandoverTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
          } catch (Exception elocal) {
            log.warn("Could not parse handoverTime {}, using now()", rawHandoverTime);
          }
        }
      }

      JobOrderHandoverCreateDto dto =
          new JobOrderHandoverCreateDto(
              handoverTime, form.getRecipientHandle(), form.getRecipientSquadron(), items);

      backendApiClient.post("/api/v1/orders/" + id + "/handovers", dto, JobOrderHandoverDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.handover");
    } catch (BackendServiceException bse) {
      // Backend already returned an RFC7807 Problem+JSON response. Log status, problem code,
      // correlationId and field errors so a 400 VALIDATION_FAILED can be diagnosed from the
      // frontend log without having to reproduce the request. No PII (rejected values) is logged.
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "POST /api/v1/orders/{id}/handovers", id, bse);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.failed");
    } catch (Exception e) {
      log.error(
          "Failed to create handover for jobOrder={} via POST /api/v1/orders/{}/handovers",
          id,
          id,
          e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.failed");
    }
    return "redirect:/orders/" + id;
  }

  /**
   * Records an item handover for an item order: relays the per-line delivered whole-unit quantities
   * to the Phase 3 backend endpoint, which decrements outstanding amounts and auto-completes the
   * order once every line is fully delivered. Rows with a null or non-positive amount are dropped;
   * an empty result short-circuits with an error toast. On success the page redirects (a full
   * reload), so the refreshed {@code @Version} is picked up and no stale-version 409 can follow.
   *
   * @param id the item order's id
   * @param form the bound item-handover form (per-line amounts + recipient + time)
   * @param redirectAttributes flash channel for the success/error toast
   * @return redirect to the order detail page
   */
  @PostMapping("/{id}/item-handovers")
  @PreAuthorize(
      "hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')")
  public String createItemHandover(
      @PathVariable UUID id,
      @ModelAttribute("itemHandoverForm") JobOrderItemHandoverForm form,
      RedirectAttributes redirectAttributes) {
    try {
      List<JobOrderItemHandoverEntryCreateDto> entries =
          form.getEntries().stream()
              .filter(
                  e -> e.getJobOrderItemId() != null && e.getAmount() != null && e.getAmount() > 0)
              .map(
                  e -> new JobOrderItemHandoverEntryCreateDto(e.getJobOrderItemId(), e.getAmount()))
              .toList();

      if (entries.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.noitems");
        return "redirect:/orders/" + id;
      }

      // handoverTime arrives as a client-produced UTC ISO-Instant (see orders-detail.html); parse
      // as Instant to preserve the absolute point in time, falling back to local-datetime parsing
      // and finally now() so a malformed value never blocks the handover. Mirrors createHandover.
      Instant handoverTime = Instant.now();
      String rawHandoverTime = form.getHandoverTime();
      if (rawHandoverTime != null && !rawHandoverTime.isBlank()) {
        try {
          handoverTime = Instant.parse(rawHandoverTime);
        } catch (Exception eiso) {
          try {
            handoverTime =
                LocalDateTime.parse(rawHandoverTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
          } catch (Exception elocal) {
            log.warn("Could not parse item handoverTime {}, using now()", rawHandoverTime);
          }
        }
      }

      JobOrderItemHandoverCreateDto dto =
          new JobOrderItemHandoverCreateDto(handoverTime, form.getRecipientHandle(), entries);
      backendApiClient.post(
          "/api/v1/orders/" + id + "/item-handovers", dto, JobOrderItemHandoverDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.handover");
    } catch (BackendServiceException bse) {
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "POST /api/v1/orders/{id}/item-handovers", id, bse);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.failed");
    } catch (Exception e) {
      log.error(
          "Failed to create item handover for jobOrder={} via POST"
              + " /api/v1/orders/{}/item-handovers",
          id,
          id,
          e);
      redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.failed");
    }
    return "redirect:/orders/" + id;
  }

  /**
   * Parses the client-supplied handover time (a UTC ISO-Instant produced by datetime-splitter.js),
   * falling back to local-datetime parsing and finally {@code now()} so a malformed value never
   * blocks the handover. Extracted so the AJAX handover twins share the classic handlers' exact
   * timezone handling.
   *
   * @param raw the raw {@code handoverTime} form value (may be null/blank)
   * @return the parsed instant, or {@code Instant.now()} when absent/unparseable
   */
  private Instant parseHandoverTime(String raw) {
    if (raw != null && !raw.isBlank()) {
      try {
        return Instant.parse(raw);
      } catch (Exception eiso) {
        try {
          return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
              .atZone(ZoneId.systemDefault())
              .toInstant();
        } catch (Exception elocal) {
          log.warn("Could not parse handoverTime {}, using now()", raw);
        }
      }
    }
    return Instant.now();
  }

  /**
   * AJAX twin of {@link #createHandover} (#575): records a material handover and returns the
   * refreshed order so the detail page can re-render the material requirement table, the handover
   * history and the header (status + bumped {@code @Version}) in place instead of a full reload.
   * The backend's {@code …WithinTransaction} bulk-update pattern is unchanged (one proxied call).
   * Empty items → 400 (the client pre-validates); other failures propagate RFC 7807. Classic POST
   * kept.
   *
   * @param id the order id
   * @param form the handover payload (handoverTime, recipientHandle, recipientSquadron, items[])
   * @return the refreshed order on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(
      value = "/{id}/handovers",
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize(
      "hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> createHandoverAjax(
      @PathVariable UUID id, @RequestBody JobOrderHandoverForm form) {
    List<JobOrderHandoverItemCreateDto> items =
        form.getItems().stream()
            .filter(
                item ->
                    item.getInventoryItemId() != null
                        && item.getAmount() != null
                        && item.getAmount() > 0)
            .map(
                item ->
                    new JobOrderHandoverItemCreateDto(item.getInventoryItemId(), item.getAmount()))
            .toList();
    if (items.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      JobOrderHandoverCreateDto dto =
          new JobOrderHandoverCreateDto(
              parseHandoverTime(form.getHandoverTime()),
              form.getRecipientHandle(),
              form.getRecipientSquadron(),
              items);
      backendApiClient.post("/api/v1/orders/" + id + "/handovers", dto, JobOrderHandoverDto.class);
      JobOrderDto order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(order);
    } catch (BackendServiceException bse) {
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "POST /api/v1/orders/{id}/handovers (ajax)", id, bse);
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to create handover (ajax) for order {}", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX twin of {@link #createItemHandover} (#575): records an item handover and returns the
   * refreshed order so the detail page can re-render the ordered-items table
   * (delivered/outstanding), the item-handover history, the modal (rows for the still-outstanding
   * lines) and the header in place. Empty entries → 400; other failures propagate RFC 7807. Classic
   * POST kept as the no-JS fallback.
   *
   * @param id the item order id
   * @param form the item-handover payload (handoverTime, recipientHandle, entries[])
   * @return the refreshed order on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(
      value = "/{id}/item-handovers",
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize(
      "hasRole('"
          + Roles.LOGISTICIAN
          + "') or hasRole('"
          + Roles.OFFICER
          + "') or hasRole('"
          + Roles.ADMIN
          + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> createItemHandoverAjax(
      @PathVariable UUID id, @RequestBody JobOrderItemHandoverForm form) {
    List<JobOrderItemHandoverEntryCreateDto> entries =
        form.getEntries().stream()
            .filter(
                e -> e.getJobOrderItemId() != null && e.getAmount() != null && e.getAmount() > 0)
            .map(e -> new JobOrderItemHandoverEntryCreateDto(e.getJobOrderItemId(), e.getAmount()))
            .toList();
    if (entries.isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      JobOrderItemHandoverCreateDto dto =
          new JobOrderItemHandoverCreateDto(
              parseHandoverTime(form.getHandoverTime()), form.getRecipientHandle(), entries);
      backendApiClient.post(
          "/api/v1/orders/" + id + "/item-handovers", dto, JobOrderItemHandoverDto.class);
      JobOrderDto order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(order);
    } catch (BackendServiceException bse) {
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "POST /api/v1/orders/{id}/item-handovers (ajax)", id, bse);
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to create item handover (ajax) for order {}", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Removes a material requirement from the order without deleting any associated inventory items
   * (the items keep their job-order link cleared by the backend).
   *
   * @return redirect to the order detail page
   */
  @PostMapping("/{id}/materials/unlink")
  @PreAuthorize(
      "hasAnyRole('" + Roles.LOGISTICIAN + "', '" + Roles.OFFICER + "', '" + Roles.ADMIN + "')")
  public String unlinkMaterial(
      @PathVariable UUID id, @RequestParam UUID materialId, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/orders/" + id + "/materials/" + materialId, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "orders.detail.material.unlink.success");
    } catch (BackendServiceException bse) {
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "DELETE /api/v1/orders/{id}/materials/{materialId}", id, bse);
      redirectAttributes.addFlashAttribute("errorToast", "orders.detail.material.unlink.error");
    } catch (Exception e) {
      log.error("Failed to unlink material {} from jobOrder={}", materialId, id, e);
      redirectAttributes.addFlashAttribute("errorToast", "orders.detail.material.unlink.error");
    }
    return "redirect:/orders/" + id;
  }

  /**
   * Detaches an inventory item from the job order. The item stays in the user's inventory but no
   * longer counts towards the order's progress.
   *
   * @return redirect to the order detail page
   */
  @PostMapping("/{id}/inventory/{inventoryItemId}/unlink")
  @PreAuthorize(
      "hasAnyRole('" + Roles.LOGISTICIAN + "', '" + Roles.OFFICER + "', '" + Roles.ADMIN + "')")
  public String unlinkInventoryItem(
      @PathVariable UUID id,
      @PathVariable UUID inventoryItemId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/orders/" + id + "/inventory/" + inventoryItemId + "/unlink", Void.class);
      redirectAttributes.addFlashAttribute(
          "successToast", "orders.detail.inventory.unlink.success");
    } catch (BackendServiceException bse) {
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "DELETE /api/v1/orders/{id}/inventory/{inventoryItemId}/unlink", id, bse);
      redirectAttributes.addFlashAttribute("errorToast", "orders.detail.inventory.unlink.error");
    } catch (Exception e) {
      log.error("Failed to unlink inventory item {} from jobOrder={}", inventoryItemId, id, e);
      redirectAttributes.addFlashAttribute("errorToast", "orders.detail.inventory.unlink.error");
    }
    return "redirect:/orders/" + id;
  }

  /**
   * AJAX twin of {@link #unlinkInventoryItem} (#575): detaches the inventory item and returns the
   * refreshed order so the detail page can re-render the material section in place AND pick up the
   * bumped order {@code @Version} (the detach mutates the aggregate, so a subsequent status/
   * handover write would otherwise 409). The classic {@code POST}→redirect above stays the no-JS
   * fallback.
   *
   * @param id the order id
   * @param inventoryItemId the inventory item to detach
   * @return the refreshed order on success, or the propagated RFC 7807 backend error
   */
  @DeleteMapping("/{id}/inventory/{inventoryItemId}/unlink/ajax")
  @PreAuthorize(
      "hasAnyRole('" + Roles.LOGISTICIAN + "', '" + Roles.OFFICER + "', '" + Roles.ADMIN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> unlinkInventoryItemAjax(
      @PathVariable UUID id, @PathVariable UUID inventoryItemId) {
    try {
      backendApiClient.delete(
          "/api/v1/orders/" + id + "/inventory/" + inventoryItemId + "/unlink", Void.class);
      JobOrderDto order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(order);
    } catch (BackendServiceException bse) {
      log.debug(
          "Failed to unlink inventory item {} from order {} (ajax): {}",
          inventoryItemId,
          id,
          bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to unlink inventory item {} from order {} (ajax)", inventoryItemId, id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Removes an assignee from the job order and re-renders the Bearbeiter section as an AJAX
   * fragment (no full-page reload).
   *
   * @param id job-order id
   * @param userId the user to remove
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @DeleteMapping("/{id}/assignees/{userId}")
  @PreAuthorize("isAuthenticated()")
  public String removeAssignee(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    JobOrderDto order =
        callAssigneeMutation(
            "remove assignee",
            () ->
                backendApiClient.delete(
                    "/api/v1/orders/" + id + "/assignees/" + userId, JobOrderDto.class));
    populateAssigneeSectionModel(model, principal, order);
    return "orders-detail :: assigneesSection";
  }

  /**
   * Sets (creates or replaces) the current user's — or, for a Logistician+, any assignee's — note
   * on the order, then re-renders the Bearbeiter section as an AJAX fragment. The backend enforces
   * the self-or-logistician rule and the optimistic lock on the assignee edge (HTTP 409 on stale
   * input, relayed here so the page JS can prompt a reload).
   *
   * @param id job-order id
   * @param userId the assignee whose note is changed
   * @param body the new note text + the assignee edge version last seen by the client
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @PutMapping("/{id}/assignees/{userId}/note")
  @PreAuthorize("isAuthenticated()")
  public String setAssigneeNote(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @RequestBody AssigneeNoteRequest body,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    JobOrderDto order =
        callAssigneeMutation(
            "set assignee note",
            () ->
                backendApiClient.put(
                    "/api/v1/orders/" + id + "/assignees/" + userId + "/note",
                    body,
                    JobOrderDto.class));
    populateAssigneeSectionModel(model, principal, order);
    return "orders-detail :: assigneesSection";
  }

  /**
   * Clears an assignee's note and re-renders the Bearbeiter section as an AJAX fragment. Same
   * self-or-logistician + optimistic-lock semantics as {@link #setAssigneeNote}.
   *
   * @param id job-order id
   * @param userId the assignee whose note is cleared
   * @param version the assignee edge version last seen by the client
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @DeleteMapping("/{id}/assignees/{userId}/note")
  @PreAuthorize("isAuthenticated()")
  public String deleteAssigneeNote(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @RequestParam(required = false) Long version,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    String query = version != null ? "?version=" + version : "";
    JobOrderDto order =
        callAssigneeMutation(
            "delete assignee note",
            () ->
                backendApiClient.delete(
                    "/api/v1/orders/" + id + "/assignees/" + userId + "/note" + query,
                    JobOrderDto.class));
    populateAssigneeSectionModel(model, principal, order);
    return "orders-detail :: assigneesSection";
  }

  /**
   * Runs an assignee-section mutation against the backend and translates a backend failure into the
   * matching HTTP status so the order-detail page JS can react (409 → reload prompt, 403 →
   * forbidden toast, else generic error). Keeps the four AJAX endpoints free of duplicated
   * try/catch.
   *
   * @param action short action label for the log line
   * @param call the backend call returning the updated order
   * @return the updated order on success
   */
  private JobOrderDto callAssigneeMutation(
      String action, java.util.function.Supplier<JobOrderDto> call) {
    try {
      return call.get();
    } catch (BackendServiceException bse) {
      log.warn("Failed to {} (status {})", action, bse.getStatusCode());
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.valueOf(bse.getStatusCode()));
    } catch (Exception e) {
      log.error("Failed to {}", action, e);
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Populates the model attributes the {@code assigneesSection} fragment reads — the updated order,
   * the caller's user id, the Logistician flag and (for Logisticians) the full user list backing
   * the add-user picker. Shared by the four assignee AJAX endpoints and mirrors what {@link
   * JobOrderPageController#viewOrderDetail} sets for the initial full-page render.
   *
   * @param model the view model to populate
   * @param principal the authenticated caller
   * @param order the freshly mutated order
   */
  private void populateAssigneeSectionModel(Model model, OidcUser principal, JobOrderDto order) {
    model.addAttribute("order", order);
    model.addAttribute("currentUserId", getCurrentUserId(principal));
    boolean canAssign = isLogistician(principal);
    model.addAttribute("isLogistician", canAssign);
    model.addAttribute("users", canAssign ? fetchUsers() : new ArrayList<>());
  }

  /**
   * Request body for the assignee-note PUT endpoint (frontend mirror of the backend record).
   *
   * @param note the new note text (blank/{@code null} clears the note)
   * @param version the assignee edge version the client last saw
   */
  public record AssigneeNoteRequest(String note, Long version) {}

  /**
   * Fetches the full user list backing the assignee picker, degrading to an empty list when the
   * caller lacks the required rights or the backend call fails. Verbatim duplicate of the read-side
   * {@code JobOrderPageController#fetchUsers} original (#924 split precedent: duplicate small leaf
   * helpers instead of introducing a shared type).
   *
   * @return the users, or an empty list on failure; never {@code null}
   */
  private List<UserDto> fetchUsers() {
    try {
      PageResponse<UserDto> p = backendApiClient.get("/api/v1/users?size=1000", PAGE_OF_USER);
      if (p != null && p.content() != null) {
        return new ArrayList<>(p.content());
      }
    } catch (Exception e) {
      log.warn("Failed to fetch users (might not be an admin/officer)");
    }
    return new ArrayList<>();
  }

  /**
   * Resolves the caller's user id from the OIDC subject, falling back to a {@code /api/v1/users/me}
   * lookup when the subject is not a UUID. Verbatim duplicate of the read-side {@code
   * JobOrderPageController#getCurrentUserId} original (#924 split precedent).
   *
   * @param principal the authenticated caller, or {@code null} for a guest
   * @return the caller's user id, or {@code null} when unresolvable
   */
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

  /**
   * Checks whether the caller reaches the LOGISTICIAN, ADMIN or OFFICER authority through the role
   * hierarchy, gating the assignee-picker rendering. Verbatim duplicate of the read-side {@code
   * JobOrderPageController#isLogistician} original (#924 split precedent).
   *
   * @param principal the authenticated caller, or {@code null} for a guest
   * @return {@code true} when the caller holds one of the three authorities
   */
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
