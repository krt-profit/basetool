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
import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
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
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderHandoverForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderItemForm;
import de.greluc.krt.profit.basetool.frontend.model.form.JobOrderItemHandoverForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.support.MutationResponseHelper;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
 * Spring MVC controller for the state-mutating job-order endpoints under {@code /orders}: create,
 * update, delete, priority and status, claims, assignees, handovers, production and unlinks.
 *
 * <p>The class-level {@code isAuthenticated()} gate is the floor; method-level gates take
 * precedence (REQ-SEC-052).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
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
   * Server-side live-sync publisher that announces a new order to the {@code orders} queue room,
   * whose viewers may not include the creator (REQ-FE-015).
   */
  private final LiveSyncLocalBus liveSyncLocalBus;

  /** The single {@code orders} queue section a create/mutation pokes. */
  private static final List<String> ORDERS_QUEUE_SECTION = List.of("queue");

  /**
   * Persists a new item order, dropping lines without an item, blueprint or positive amount.
   *
   * @param form the bound item-order form
   * @param redirectAttributes flash carrier for toasts / re-render
   * @param principal the caller, or {@code null} for anonymous
   * @return redirect target
   */
  @NotNull
  @PostMapping("/items")
  public String createItemOrder(
      @ModelAttribute("jobOrderItemForm") JobOrderItemForm form,
      @ModelAttribute("canViewJobOrders") boolean canViewJobOrders,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      List<CreateJobOrderItemLineDto> lines = buildItemLineDtos(form);

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
      backendApiClient.post("/api/v1/orders/items", dto, JobOrderDto.class);
      liveSyncLocalBus.publish("orders", ORDERS_QUEUE_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.create");

      if (!canViewJobOrders) {
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
   * Builds the item-line DTOs from an item-order form, dropping incomplete lines and materials.
   *
   * <p>A line's persistent {@code id} is passed through so an edit updates it in place
   * (REQ-ORDERS-032).
   *
   * @param form the bound item-order form
   * @return the filtered item-line DTOs (preserving the persistent, client line + parent ids)
   */
  private static List<CreateJobOrderItemLineDto> buildItemLineDtos(@NotNull JobOrderItemForm form) {
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
                    l.getId(),
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
   * AJAX twin of {@link #createItemOrder}: creates an item order and returns the navigation target
   * as JSON; empty lines yield 400.
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
    return relay(
        log,
        "create item order (ajax)",
        () -> {
          CreateJobOrderItemRequestDto dto =
              new CreateJobOrderItemRequestDto(
                  form.getResponsibleOrgUnitId(),
                  form.getRequestingOrgUnitId(),
                  form.getHandle(),
                  form.getComment(),
                  lines,
                  form.getVersion());
          backendApiClient.post("/api/v1/orders/items", dto, JobOrderDto.class);
          liveSyncLocalBus.publish("orders", ORDERS_QUEUE_SECTION);
          return org.springframework.http.ResponseEntity.ok(
              java.util.Map.of(
                  "targetUrl", postCreateTarget(principal, canViewJobOrders, form.getSource())));
        });
  }

  /**
   * Persists an edit to an item order's lines and metadata via {@code PUT
   * /api/v1/orders/{id}/items}.
   *
   * @param id the item-order id
   * @param form the bound item-order form (lines rebuilt client-side)
   * @param redirectAttributes flash carrier
   * @return redirect to the detail page
   */
  @NotNull
  @PostMapping("/{id}/items/update")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  public String updateItemOrder(
      @PathVariable UUID id,
      @ModelAttribute("jobOrderItemForm") JobOrderItemForm form,
      RedirectAttributes redirectAttributes) {
    try {
      List<CreateJobOrderItemLineDto> lines = buildItemLineDtos(form);

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
   * AJAX twin of {@link #updateItemOrder}: saves an item-order edit and returns the detail URL as
   * JSON; empty lines yield 400.
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
    return relay(
        log,
        "update item order " + id + " (ajax)",
        () -> {
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
        });
  }

  /**
   * Persists a new job order. Validation failures re-render inline so the BindingResult stays
   * request-scoped. The source parameter determines the post-save redirect target.
   *
   * @return inline form view on failure, otherwise redirect
   */
  @NotNull
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
      backendApiClient.post("/api/v1/orders", dto, JobOrderDto.class);
      liveSyncLocalBus.publish("orders", ORDERS_QUEUE_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.create");

      if (!canViewJobOrders) {
        return "redirect:/orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }
      if ("index".equals(form.getSource())) {
        return "redirect:/orders";
      }
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
   * Returns the post-create navigation target: the order list for queue viewers, otherwise the
   * create form.
   *
   * @param principal the caller; {@code null} is treated as a non-viewer
   * @param canViewJobOrders whether the caller may browse the order queue
   * @param source the create-page source param to carry on the stay-on-create target
   * @return the URL the AJAX caller should navigate to on success
   */
  @NotNull
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
  private static List<CreateJobOrderMaterialDto> buildMaterialDtos(@NotNull JobOrderForm form) {
    return form.getMaterials().stream()
        .filter(m -> m.getMaterialId() != null && m.getAmount() != null && m.getAmount() > 0)
        .map(
            m -> new CreateJobOrderMaterialDto(m.getMaterialId(), m.getMinQuality(), m.getAmount()))
        .toList();
  }

  /**
   * AJAX twin of {@link #createOrder}: creates a material order and returns the navigation target
   * as JSON; empty materials yield 400.
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
    return relay(
        log,
        "create order (ajax)",
        () -> {
          CreateJobOrderDto dto =
              new CreateJobOrderDto(
                  form.getResponsibleOrgUnitId(),
                  form.getRequestingOrgUnitId(),
                  form.getHandle(),
                  form.getComment(),
                  materials,
                  form.getVersion());
          backendApiClient.post("/api/v1/orders", dto, JobOrderDto.class);
          liveSyncLocalBus.publish("orders", ORDERS_QUEUE_SECTION);
          return org.springframework.http.ResponseEntity.ok(
              java.util.Map.of(
                  "targetUrl", postCreateTarget(principal, canViewJobOrders, form.getSource())));
        });
  }

  /**
   * Reorders an order's priority (drag-and-drop between job orders). Backend uses pessimistic
   * locking on the entire job-order priority sequence to serialize concurrent reorders, per the
   * concurrency pattern in CLAUDE.md.
   *
   * @return redirect to {@code /orders}
   */
  @PostMapping("/{id}/priority")
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
   * AJAX twin of {@link #updatePriority}: persists a drag-and-drop reorder and returns the moved
   * order.
   *
   * <p>The backend reassigns every active order's slot, so the caller must re-render the whole
   * queue.
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
    return relay(
        log,
        "update priority (ajax) for order " + id,
        () -> {
          JobOrderDto result =
              backendApiClient.put(
                  "/api/v1/orders/" + id + "/priority?priority=" + priority,
                  null,
                  JobOrderDto.class);
          return org.springframework.http.ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX status transition endpoint. Updates the job order's status (OPEN → IN_PROGRESS →
   * COMPLETED, REJECTED). Backend enforces the state machine and rejects illegal transitions with a
   * 400.
   *
   * @return updated order on success, propagated backend status code on failure
   */
  @PostMapping("/{id}/status")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateStatus(
      @PathVariable UUID id, @RequestBody UpdateJobOrderStatusDto dto) {
    return relay(
        log,
        "update status for order " + id,
        () -> {
          JobOrderDto result =
              backendApiClient.put("/api/v1/orders/" + id + "/status", dto, JobOrderDto.class);
          return org.springframework.http.ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX toggle for an item order's blueprint-variant counting mode, relaying the backend status
   * (400, 403, 409) verbatim.
   *
   * @param id the order id
   * @param dto the requested counting mode + the order's expected version
   * @return the updated order on success, or the propagated backend error status
   */
  @PostMapping("/{id}/blueprint-variant-counting")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateBlueprintVariantCounting(
      @PathVariable UUID id, @RequestBody UpdateJobOrderBlueprintCountingDto dto) {
    return relay(
        log,
        "update blueprint variant counting for order " + id,
        () -> {
          JobOrderDto result =
              backendApiClient.patch(
                  "/api/v1/orders/" + id + "/blueprint-variant-counting", dto, JobOrderDto.class);
          return org.springframework.http.ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX create-or-update of a material claim on a public SK order, relaying the backend status and
   * RFC 7807 {@code code} verbatim (400, 403, 409).
   *
   * @param id the order id
   * @param dto the claim payload (material, quality bucket, claiming squadron, amount)
   * @return the persisted claim on success, or the propagated backend error status (incl. a 409 on
   *     a surviving claim race)
   */
  @PostMapping("/{id}/claims")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> upsertClaim(
      @PathVariable UUID id, @RequestBody CreateClaimDto dto) {
    return relay(
        log,
        "upsert claim on order " + id,
        () -> {
          ClaimDto result =
              backendApiClient.post("/api/v1/orders/" + id + "/claims", dto, ClaimDto.class);
          return org.springframework.http.ResponseEntity.status(
                  org.springframework.http.HttpStatus.CREATED)
              .body(result);
        });
  }

  /**
   * AJAX withdrawal of a material claim, relaying the backend status (404, 400, 403).
   *
   * @param id the order id
   * @param claimId the claim to withdraw
   * @return 204 on success, or the propagated backend error status
   */
  @PostMapping("/{id}/claims/{claimId}/withdraw")
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "')")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> withdrawClaim(
      @PathVariable UUID id, @PathVariable UUID claimId) {
    return relay(
        log,
        "withdraw claim " + claimId + " on order " + id,
        () -> {
          backendApiClient.delete("/api/v1/orders/" + id + "/claims/" + claimId, Void.class);
          return org.springframework.http.ResponseEntity.noContent().build();
        });
  }

  /**
   * Persists an edit to the order's metadata (note, target inventory location, materials list).
   * Restricted to logisticians at the controller boundary; backend additionally enforces a
   * fine-grained role check on the {@code @PreAuthorize}-annotated service method.
   *
   * @return redirect to the order detail page
   */
  @NotNull
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
   * AJAX twin of {@link #updateOrder}: edits a MATERIAL order and returns the updated order for an
   * in-place re-render; an empty material list yields 400.
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
      @PathVariable UUID id, @NotNull @RequestBody JobOrderForm form) {
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
    return relay(
        log,
        "update order " + id + " (ajax)",
        () -> {
          CreateJobOrderDto dto =
              new CreateJobOrderDto(
                  null,
                  form.getRequestingOrgUnitId(),
                  form.getHandle(),
                  form.getComment(),
                  materials,
                  form.getVersion());
          JobOrderDto updated =
              backendApiClient.put("/api/v1/orders/" + id, dto, JobOrderDto.class);
          return org.springframework.http.ResponseEntity.ok(updated);
        });
  }

  /**
   * Requester-side MATERIAL edit (REQ-ORDERS-023): relays comment, materials and version to {@code
   * PUT /api/v1/orders/{id}/requested}, which enforces the requester gate.
   *
   * @param id the order to update
   * @param form the edit payload (comment, version, materials[])
   * @param redirectAttributes flash carrier for the result toast
   * @return redirect back to the order detail
   */
  @NotNull
  @PostMapping("/{id}/requested-update")
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
   * AJAX twin of {@link #updateOrderAsRequester}: returns the updated redacted order for an
   * in-place re-render; an empty material list yields 400.
   *
   * @param id the order to update
   * @param form the edit payload (comment, version, materials[])
   * @return the updated redacted order on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(
      value = "/{id}/requested-update",
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateOrderAsRequesterAjax(
      @PathVariable UUID id, @NotNull @RequestBody JobOrderForm form) {
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
    return relay(
        log,
        "requester-update of order " + id + " (ajax)",
        () -> {
          CreateJobOrderDto dto =
              new CreateJobOrderDto(
                  null, null, null, form.getComment(), materials, form.getVersion());
          JobOrderDto updated =
              backendApiClient.put("/api/v1/orders/" + id + "/requested", dto, JobOrderDto.class);
          return org.springframework.http.ResponseEntity.ok(updated);
        });
  }

  /**
   * Cancels (soft-deletes) a job order. Backend rejects the delete when the order has linked
   * inventory items, per the {@code EntityInUseException} pattern.
   *
   * @return redirect to {@code /orders}
   */
  @PostMapping("/{id}/delete")
  public String deleteOrder(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    return mutationResponseHelper.mutate(
        redirectAttributes,
        "/orders",
        "success.joborder.delete",
        "error.joborder.delete.failed",
        () -> backendApiClient.delete("/api/v1/orders/" + id, Void.class));
  }

  /**
   * AJAX twin of {@link #deleteOrder}: cancels (soft-deletes) the order without a redirect.
   *
   * @param id the order to cancel
   * @return 204 on success, or the propagated RFC 7807 backend error
   */
  @DeleteMapping("/{id}")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> deleteOrderAjax(@PathVariable UUID id) {
    return relay(
        log,
        "delete order " + id + " (ajax)",
        () -> {
          backendApiClient.delete("/api/v1/orders/" + id, Void.class);
          return org.springframework.http.ResponseEntity.noContent().build();
        });
  }

  /**
   * Adds an assignee to the job order and re-renders the Bearbeiter section fragment.
   *
   * @param id job-order id
   * @param userId the user to add
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @NotNull
  @PostMapping("/{id}/assignees")
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
   * @return redirect to the order detail page
   */
  @NotNull
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
                          item.getInventoryItemId(), item.getAmount(), item.getMissionReductions()))
              .toList();

      if (items.isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.joborder.handover.noitems");
        return "redirect:/orders/" + id;
      }

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
            log.warn(
                "Could not parse handoverTime {}, using now()", LogSafe.text(rawHandoverTime, 64));
          }
        }
      }

      JobOrderHandoverCreateDto dto =
          new JobOrderHandoverCreateDto(
              handoverTime, form.getRecipientHandle(), form.getRecipientSquadron(), items);

      backendApiClient.post("/api/v1/orders/" + id + "/handovers", dto, JobOrderHandoverDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.joborder.handover");
    } catch (BackendServiceException bse) {
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
   * Records an item handover by relaying the per-line delivered quantities to the backend, which
   * auto-completes a fully delivered order.
   *
   * <p>Rows without a positive amount are dropped; an empty result yields an error toast.
   *
   * @param id the item order's id
   * @param form the bound item-handover form (per-line amounts + recipient + time)
   * @param redirectAttributes flash channel for the success/error toast
   * @return redirect to the order detail page
   */
  @NotNull
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
            log.warn(
                "Could not parse item handoverTime {}, using now()",
                LogSafe.text(rawHandoverTime, 64));
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
   * Parses the client-supplied handover time as a UTC instant, then as a local date-time, falling
   * back to now.
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
          log.warn("Could not parse handoverTime {}, using now()", LogSafe.text(raw, 64));
        }
      }
    }
    return Instant.now();
  }

  /**
   * AJAX twin of {@link #createHandover}: records a material handover and returns the refreshed
   * order for an in-place re-render; empty items yield 400.
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
      @PathVariable UUID id, @NotNull @RequestBody JobOrderHandoverForm form) {
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
                        item.getInventoryItemId(), item.getAmount(), item.getMissionReductions()))
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
   * AJAX twin of {@link #createItemHandover}: records an item handover and returns the refreshed
   * order for an in-place re-render; empty entries yield 400.
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
      @PathVariable UUID id, @NotNull @RequestBody JobOrderItemHandoverForm form) {
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
   * AJAX relay for booking a production run against one item line (REQ-ORDERS-025), returning the
   * refreshed order; 409 and 422 are relayed verbatim.
   *
   * @param id job-order id
   * @param itemId ordered item-line id
   * @param dto the production payload (amount, line version, per-entry consumption)
   * @return the refreshed order on success, or the relayed backend error
   */
  @PostMapping(
      value = "/{id}/items/{itemId}/production",
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
  public org.springframework.http.ResponseEntity<Object> bookProductionAjax(
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @RequestBody
          de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemProductionCreateDto dto) {
    try {
      backendApiClient.post(
          "/api/v1/orders/" + id + "/items/" + itemId + "/production",
          dto,
          de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto.class);
      JobOrderDto order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
      return org.springframework.http.ResponseEntity.ok(order);
    } catch (BackendServiceException bse) {
      de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging.warn(
          log, "POST /api/v1/orders/{id}/items/{itemId}/production (ajax)", id, bse);
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to book production (ajax) for order {} item {}", id, itemId, e);
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
  @NotNull
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
  @NotNull
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
   * AJAX twin of {@link #unlinkInventoryItem}: detaches the inventory item and returns the
   * refreshed order with its new version.
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
    return relay(
        log,
        "unlink inventory item " + inventoryItemId + " from order " + id + " (ajax)",
        () -> {
          backendApiClient.delete(
              "/api/v1/orders/" + id + "/inventory/" + inventoryItemId + "/unlink", Void.class);
          JobOrderDto order = backendApiClient.get("/api/v1/orders/" + id, JobOrderDto.class);
          return org.springframework.http.ResponseEntity.ok(order);
        });
  }

  /**
   * Removes an assignee from the job order and re-renders the Bearbeiter section fragment.
   *
   * @param id job-order id
   * @param userId the user to remove
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @NotNull
  @DeleteMapping("/{id}/assignees/{userId}")
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
   * Sets an assignee's note and re-renders the Bearbeiter section fragment; the backend allows only
   * the assignee or a Logistician and returns 409 on a stale version.
   *
   * @param id job-order id
   * @param userId the assignee whose note is changed
   * @param body the new note text + the assignee edge version last seen by the client
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @NotNull
  @PutMapping("/{id}/assignees/{userId}/note")
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
   * Clears an assignee's note and re-renders the Bearbeiter section fragment, with the same rules
   * as {@link #setAssigneeNote}.
   *
   * @param id job-order id
   * @param userId the assignee whose note is cleared
   * @param version the assignee edge version last seen by the client
   * @param model the view model populated for the fragment render
   * @param principal the authenticated caller
   * @return the {@code orders-detail :: assigneesSection} fragment view name
   */
  @NotNull
  @DeleteMapping("/{id}/assignees/{userId}/note")
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
   * Runs an assignee mutation and maps a backend failure to the matching HTTP status (409, 403, or
   * a generic error).
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
   * Populates the model attributes the {@code assigneesSection} fragment reads: the order, the
   * caller's user id and the Logistician flag.
   *
   * @param model the view model to populate
   * @param principal the authenticated caller
   * @param order the freshly mutated order
   */
  private void populateAssigneeSectionModel(
      @NotNull Model model, OidcUser principal, JobOrderDto order) {
    model.addAttribute("order", order);
    model.addAttribute("currentUserId", getCurrentUserId(principal));
    boolean canAssign = isLogistician(principal);
    model.addAttribute("isLogistician", canAssign);
  }

  /**
   * Request body for the assignee-note PUT endpoint (frontend mirror of the backend record).
   *
   * @param note the new note text (blank/{@code null} clears the note)
   * @param version the assignee edge version the client last saw
   */
  public record AssigneeNoteRequest(String note, Long version) {}

  /**
   * Resolves the caller's user id from the OIDC subject, falling back to {@code /api/v1/users/me}
   * when the subject is not a UUID.
   *
   * @param principal the authenticated caller, or {@code null} for a guest
   * @return the caller's user id, or {@code null} when unresolvable
   */
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
      UserDto me = backendApiClient.get("/api/v1/users/me", UserDto.class);
      return me != null ? me.id() : null;
    } catch (Exception ex) {
      log.warn("Failed to get current user ID from backend: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * Checks whether the caller reaches LOGISTICIAN, ADMIN or OFFICER through the role hierarchy.
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
