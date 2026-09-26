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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStoreItemDto;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryGoodForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreItemForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Write handlers of the {@code /refinery-orders} surface: create, update, delete and store, each as
 * a classic form handler and an {@code X-Requested-With} AJAX twin, relaying to the backend via
 * {@link BackendApiClient}. Page rendering stays in {@link RefineryOrderPageController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/refinery-orders")
@RequiredArgsConstructor
@Slf4j
public class RefineryOrderWriteController {

  /** Relays the create/update/delete/store mutations to the backend API. */
  private final BackendApiClient backendApiClient;

  /**
   * Publishes live-sync pokes server-side after each refinery mutation (REQ-FE-015, ADR-0094),
   * since every mutation navigates away before a client-side broadcast could go out.
   */
  private final LiveSyncLocalBus liveSyncLocalBus;

  /** The single {@code queue} section of the global {@code refinery} room a mutation pokes. */
  private static final List<String> REFINERY_QUEUE_SECTION = List.of("queue");

  /**
   * The shared Lager section poked in addition to the refinery queue when an order is stored,
   * because storing writes the refined output into the inventory.
   */
  private static final List<String> INVENTORY_STOCK_SECTION = List.of("stock");

  /**
   * Parses the start instant submitted by the form as a UTC {@link java.time.Instant}.
   *
   * <p>Accepts an ISO instant with {@code Z} or an offset, a date only, or a local date-time
   * without zone, which is interpreted as UTC.
   */
  static java.time.Instant parseStartedAt(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return java.time.Instant.now();
    }
    String input = raw.trim();
    try {
      return java.time.Instant.parse(input);
    } catch (Exception ignored) {
    }
    try {
      return java.time.OffsetDateTime.parse(input).toInstant();
    } catch (Exception ignored) {
    }
    if (input.length() == 10) {
      return java.time.LocalDate.parse(input).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
    return java.time.LocalDateTime.parse(input).toInstant(java.time.ZoneOffset.UTC);
  }

  /**
   * Persists a new refinery order from the create form.
   *
   * <p>Empty goods fail with a localized error before any backend call; on failure the form is
   * flashed back so the input is kept.
   *
   * @param form refinery-order create form
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /refinery-orders/create} on failure, otherwise to the source page or
   *     the list
   */
  @NotNull
  @PostMapping("/create")
  @PreAuthorize("isAuthenticated()")
  public String createOrder(
      @Valid @ModelAttribute("refineryOrderForm") RefineryOrderForm form,
      BindingResult bindingResult,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.create.failed");
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/create"
          + (form.getSource() != null ? "?source=" + form.getSource() : "");
    }
    try {
      RefineryOrderDto orderDto = buildRefineryOrderDto(null, form, form.getOwningOrgUnitId());

      if (orderDto.goods().isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.material.invalid");
        redirectAttributes.addFlashAttribute("refineryOrderForm", form);
        return "redirect:/refinery-orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }

      log.debug("Sending refinery order DTO: {}", orderDto);

      backendApiClient.post("/api/v1/refinery-orders", orderDto, RefineryOrderDto.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.refineryorder.create");

      return "redirect:/refinery-orders";
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "POST /api/v1/refinery-orders", e);
      redirectAttributes.addFlashAttribute(
          "errorToast", failureToastKey(e, "error.refineryorder.create.failed"));
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/create"
          + (form.getSource() != null ? "?source=" + form.getSource() : "");
    } catch (Exception e) {
      log.error("Failed to create refinery order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.create.failed");
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/create"
          + (form.getSource() != null ? "?source=" + form.getSource() : "");
    }
  }

  /**
   * Persists an edit to an existing refinery order, including its optimistic-lock {@code version};
   * empty goods fail with a localized error before any backend call.
   *
   * @param id refinery order id
   * @param form refinery-order edit form
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /refinery-orders/{id}} on failure, otherwise to the list
   */
  @NotNull
  @PostMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public String updateOrder(
      @PathVariable UUID id,
      @Valid @ModelAttribute("refineryOrderForm") RefineryOrderForm form,
      BindingResult bindingResult,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.update.failed");
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/" + id;
    }
    try {
      RefineryOrderDto orderDto = buildRefineryOrderDto(id, form, null);
      if (orderDto.goods().isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.material.invalid");
        redirectAttributes.addFlashAttribute("refineryOrderForm", form);
        return "redirect:/refinery-orders/" + id;
      }
      backendApiClient.put("/api/v1/refinery-orders/" + id, orderDto, RefineryOrderDto.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.refineryorder.update");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "PUT /api/v1/refinery-orders/{id}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", failureToastKey(e, "error.refineryorder.update.failed"));
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/" + id;
    } catch (Exception e) {
      log.error("Failed to update refinery order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.update.failed");
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/" + id;
    }
    return "redirect:/refinery-orders";
  }

  /**
   * Chooses the toast for a failed classic create or edit. A mission the order's owner does not
   * take part in (REQ-SEC-042) gets a message that names the mission field; every other backend
   * failure keeps the handler's generic one.
   *
   * @param e the backend failure
   * @param fallbackKey the handler's generic failure message key
   * @return the i18n key of the toast to flash
   */
  @NotNull
  static String failureToastKey(@NotNull BackendServiceException e, @NotNull String fallbackKey) {
    return BackendServiceException.CODE_MISSION_PARTICIPANT_REQUIRED.equals(e.getProblemCode())
        ? "error.refineryorder.mission.participant_required"
        : fallbackKey;
  }

  /**
   * Cancels a refinery order; the backend performs a status transition and enforces authorization.
   *
   * @param id refinery order id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /refinery-orders}
   */
  @NotNull
  @PostMapping("/{id}/delete")
  @PreAuthorize("isAuthenticated()")
  public String deleteOrder(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/refinery-orders/" + id, Void.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.refineryorder.cancel");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "DELETE /api/v1/refinery-orders/{id}", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.cancel.failed");
    } catch (Exception e) {
      log.error("Failed to cancel refinery order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.cancel.failed");
    }
    return "redirect:/refinery-orders";
  }

  /**
   * Completes a refinery order by storing the refined output as inventory entries.
   *
   * <p>A validation failure flashes the form back and re-opens the store modal; an item combining
   * the personal marker with a job order (REQ-INV-035) gets its own toast.
   *
   * @param id refinery order id
   * @param form store form
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the order detail on failure, otherwise to the list
   */
  @NotNull
  @PostMapping("/{id}/store")
  @PreAuthorize("isAuthenticated()")
  public String storeOrder(
      @PathVariable UUID id,
      @Valid @ModelAttribute("storeForm") RefineryOrderStoreForm form,
      BindingResult bindingResult,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.store.invalid");
      redirectAttributes.addFlashAttribute("storeForm", form);
      redirectAttributes.addFlashAttribute("showStoreModal", true);
      return "redirect:/refinery-orders/" + id;
    }
    if (storePersonalWithJobOrder(form)) {
      redirectAttributes.addFlashAttribute(
          "errorToast", "error.refineryorder.store.personal.assignment");
      redirectAttributes.addFlashAttribute("storeForm", form);
      redirectAttributes.addFlashAttribute("showStoreModal", true);
      return "redirect:/refinery-orders/" + id;
    }
    try {
      RefineryOrderStoreDto dto = buildStoreDto(form);
      backendApiClient.post("/api/v1/refinery-orders/" + id + "/store", dto, Void.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      liveSyncLocalBus.publish("inventory", INVENTORY_STOCK_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.refineryorder.store");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "POST /api/v1/refinery-orders/{id}/store", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.store.failed");
      redirectAttributes.addFlashAttribute("storeForm", form);
      redirectAttributes.addFlashAttribute("showStoreModal", true);
      return "redirect:/refinery-orders/" + id;
    } catch (Exception e) {
      log.error("Failed to store refinery order", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.store.failed");
      redirectAttributes.addFlashAttribute("storeForm", form);
      redirectAttributes.addFlashAttribute("showStoreModal", true);
      return "redirect:/refinery-orders/" + id;
    }
    return "redirect:/refinery-orders";
  }

  /**
   * Builds the {@link RefineryOrderDto} from a create or edit form, dropping goods rows without an
   * input material or quantity. Shared by {@link #createOrder}, {@link #updateOrder} and their AJAX
   * twins.
   *
   * @param id the refinery order id, or {@code null} when building a create DTO
   * @param form the bound create/edit form
   * @param owningOrgUnitId the owning org-unit stamp; {@code null} on the edit path, which keeps
   *     the original
   * @return the order DTO ready to POST/PUT
   */
  @NotNull
  private RefineryOrderDto buildRefineryOrderDto(
      UUID id, RefineryOrderForm form, UUID owningOrgUnitId) {
    List<de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto> goodsDto =
        new ArrayList<>();
    for (RefineryGoodForm g : form.getGoods()) {
      if (g.getInputMaterialId() != null && g.getInputQuantity() != null) {
        de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto inMat =
            new de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto(
                g.getInputMaterialId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto outMat =
            g.getOutputMaterialId() != null
                ? new de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto(
                    g.getOutputMaterialId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)
                : null;
        goodsDto.add(
            new de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto(
                null,
                inMat,
                g.getInputQuantity(),
                outMat,
                g.getOutputQuantity(),
                g.getQuality() != null ? g.getQuality() : 0,
                null));
      }
    }
    java.time.Instant startedAtTime = parseStartedAt(form.getStartedAt());
    return new RefineryOrderDto(
        id,
        form.getOwnerId() != null
            ? new de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto(
                form.getOwnerId(), null, null, null, null)
            : null,
        form.getLocationId() != null
            ? new de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto(
                form.getLocationId(), null, null, false, false, null)
            : null,
        form.getMissionId() != null
            ? new de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto(
                form.getMissionId(), null, null, null)
            : null,
        startedAtTime,
        (long)
            ((form.getDurationHours() != null ? form.getDurationHours() : 0) * 60
                + (form.getDurationMinutes() != null ? form.getDurationMinutes() : 0)),
        nullToZero(form.getExpenses()),
        nullToZero(form.getOtherExpenses()),
        nullToZero(form.getOreSales()),
        null,
        form.getRefiningMethodId() != null
            ? new de.greluc.krt.profit.basetool.frontend.model.dto.RefiningMethodDto(
                form.getRefiningMethodId(), null, null, null, null, null, null)
            : null,
        goodsDto,
        form.getStatus() != null
            ? form.getStatus()
            : de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus.OPEN,
        null,
        form.getVersion(),
        owningOrgUnitId);
  }

  /**
   * Builds the {@link RefineryOrderStoreDto} from a store form, shared by the classic {@link
   * #storeOrder} and its AJAX twin.
   *
   * @param form the bound store form
   * @return the store DTO ready to POST
   */
  @NotNull
  private static RefineryOrderStoreDto buildStoreDto(RefineryOrderStoreForm form) {
    List<RefineryOrderStoreItemDto> dtoList = new ArrayList<>();
    for (RefineryOrderStoreItemForm f : form.getItems()) {
      dtoList.add(
          new RefineryOrderStoreItemDto(
              f.getMaterialId(),
              f.getLocationId(),
              f.getQuality(),
              f.getAmount(),
              f.getUserId(),
              f.getJobOrderId(),
              f.getNote(),
              f.getOwningOrgUnitId(),
              Boolean.TRUE.equals(f.getPersonal())));
    }
    return new RefineryOrderStoreDto(dtoList);
  }

  /**
   * Answers whether any store row marks its output personal while also picking a job order, which
   * the backend rejects (REQ-INV-035).
   *
   * @param form the bound store form
   * @return {@code true} when at least one row is personal and carries a job order
   */
  private static boolean storePersonalWithJobOrder(RefineryOrderStoreForm form) {
    if (form.getItems() == null) {
      return false;
    }
    return form.getItems().stream()
        .anyMatch(f -> Boolean.TRUE.equals(f.getPersonal()) && f.getJobOrderId() != null);
  }

  /**
   * AJAX twin of {@link #updateOrder}: saves a refinery-order edit and returns the navigation
   * target as JSON; binding or empty-goods errors yield 400.
   *
   * @param id the refinery order id
   * @param form the bound edit form
   * @param bindingResult the binding/validation result
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateOrderAjax(
      @PathVariable UUID id,
      @Valid @ModelAttribute("refineryOrderForm") RefineryOrderForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    RefineryOrderDto orderDto = buildRefineryOrderDto(id, form, null);
    if (orderDto.goods().isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      backendApiClient.put("/api/v1/refinery-orders/" + id, orderDto, RefineryOrderDto.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("targetUrl", "/refinery-orders"));
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException bse) {
      log.debug("Failed to update refinery order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to update refinery order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX twin of {@link #storeOrder}: stores the refined output and returns the navigation target
   * as JSON; binding errors and a personal item with a job order (REQ-INV-035) yield 400.
   *
   * @param id the refinery order id
   * @param form the bound store form
   * @param bindingResult the binding/validation result
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/{id}/store", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> storeOrderAjax(
      @PathVariable UUID id,
      @Valid @ModelAttribute("storeForm") RefineryOrderStoreForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors() || storePersonalWithJobOrder(form)) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      backendApiClient.post(
          "/api/v1/refinery-orders/" + id + "/store", buildStoreDto(form), Void.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      liveSyncLocalBus.publish("inventory", INVENTORY_STOCK_SECTION);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("targetUrl", "/refinery-orders"));
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException bse) {
      log.debug("Failed to store refinery order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to store refinery order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX twin of {@link #deleteOrder}: cancels a refinery order and returns the list URL as JSON.
   *
   * @param id the refinery order id
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> deleteOrderAjax(@PathVariable UUID id) {
    try {
      backendApiClient.delete("/api/v1/refinery-orders/" + id, Void.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("targetUrl", "/refinery-orders"));
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException bse) {
      log.debug("Failed to cancel refinery order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to cancel refinery order {} (ajax)", id, e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * AJAX twin of {@link #createOrder}: persists a new refinery order and returns the navigation
   * target as JSON; binding or empty-goods errors yield 400.
   *
   * @param form the bound create form
   * @param bindingResult the binding/validation result
   * @return {@code {targetUrl}} on success, or the propagated RFC 7807 backend error
   */
  @PostMapping(value = "/create", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("isAuthenticated()")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Object> createOrderAjax(
      @Valid @ModelAttribute("refineryOrderForm") RefineryOrderForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    RefineryOrderDto orderDto = buildRefineryOrderDto(null, form, form.getOwningOrgUnitId());
    if (orderDto.goods().isEmpty()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      backendApiClient.post("/api/v1/refinery-orders", orderDto, RefineryOrderDto.class);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("targetUrl", "/refinery-orders"));
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException bse) {
      log.debug("Failed to create refinery order (ajax): {}", bse.getMessage());
      return propagateBackendError(bse);
    } catch (Exception e) {
      log.error("Failed to create refinery order (ajax)", e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Returns {@code 0.0} for {@code null}, so an emptied money field is saved as an explicit zero.
   */
  private static Double nullToZero(Double value) {
    return value != null ? value : 0d;
  }
}
