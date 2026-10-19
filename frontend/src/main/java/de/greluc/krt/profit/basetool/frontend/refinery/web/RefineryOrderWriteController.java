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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.RefiningMethodDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MissionReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.security.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.refinery.client.RefineryBackendClient;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodForm;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderCreateRequest;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreForm;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreItemDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreItemForm;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderUpdateRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Write handlers of the {@code /refinery-orders} surface: create, update, delete and store, each as
 * a classic form handler and an {@code X-Requested-With} AJAX twin, relaying to the backend via
 * {@link RefineryBackendClient}. Page rendering stays in {@link RefineryOrderPageController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/refinery-orders")
@RequiredArgsConstructor
@Slf4j
public class RefineryOrderWriteController {

  /** The refinery domain's typed backend client. */
  private final RefineryBackendClient refineryClient;

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
  static Instant parseStartedAt(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return Instant.now();
    }
    String input = raw.trim();
    try {
      return Instant.parse(input);
    } catch (Exception _) {
    }
    try {
      return OffsetDateTime.parse(input).toInstant();
    } catch (Exception _) {
    }
    if (input.length() == 10) {
      return LocalDate.parse(input).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
    return LocalDateTime.parse(input).toInstant(ZoneOffset.UTC);
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
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.create.failed");
      redirectAttributes.addFlashAttribute("refineryOrderForm", form);
      return "redirect:/refinery-orders/create"
          + (form.getSource() != null ? "?source=" + form.getSource() : "");
    }
    try {
      RefineryOrderCreateRequest request = buildCreateRequest(form);

      if (request.goods().isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.material.invalid");
        redirectAttributes.addFlashAttribute("refineryOrderForm", form);
        return "redirect:/refinery-orders/create"
            + (form.getSource() != null ? "?source=" + form.getSource() : "");
      }

      log.debug("Sending refinery order request: {}", request);

      createFor(request, form.getOwnerId(), principal);
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
      RefineryOrderUpdateRequest request = buildUpdateRequest(form);
      if (request.goods().isEmpty()) {
        redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.material.invalid");
        redirectAttributes.addFlashAttribute("refineryOrderForm", form);
        return "redirect:/refinery-orders/" + id;
      }
      refineryClient.update(id, request);
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
      refineryClient.cancel(id);
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
      refineryClient.store(id, dto);
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
   * Builds the create request from the create form, dropping goods rows without an input material
   * or quantity. Shared by {@link #createOrder} and its AJAX twin.
   *
   * @param form the bound create form
   * @return the request ready to POST
   */
  @NotNull
  private RefineryOrderCreateRequest buildCreateRequest(RefineryOrderForm form) {
    return new RefineryOrderCreateRequest(
        locationOf(form),
        missionOf(form),
        parseStartedAt(form.getStartedAt()),
        durationMinutesOf(form),
        nullToZero(form.getExpenses()),
        nullToZero(form.getOtherExpenses()),
        nullToZero(form.getOreSales()),
        refiningMethodOf(form),
        goodsOf(form),
        form.getOwningOrgUnitId());
  }

  /**
   * Builds the update request from the edit form, dropping goods rows without an input material or
   * quantity. Shared by {@link #updateOrder} and its AJAX twin.
   *
   * @param form the bound edit form
   * @return the request ready to PUT, carrying the form's optimistic-lock version
   */
  @NotNull
  private RefineryOrderUpdateRequest buildUpdateRequest(RefineryOrderForm form) {
    return new RefineryOrderUpdateRequest(
        locationOf(form),
        missionOf(form),
        parseStartedAt(form.getStartedAt()),
        durationMinutesOf(form),
        nullToZero(form.getExpenses()),
        nullToZero(form.getOtherExpenses()),
        nullToZero(form.getOreSales()),
        refiningMethodOf(form),
        form.getStatus() != null ? form.getStatus() : RefineryOrderStatus.OPEN,
        goodsOf(form),
        form.getVersion());
  }

  /**
   * Stores a new order, for the caller or, when a logistician picked another member as the owner,
   * for that member through the per-target path.
   *
   * @param request the order to create
   * @param ownerId the owner picked in the form, or {@code null}
   * @param principal the authenticated user, or {@code null} when unknown
   */
  private void createFor(
      RefineryOrderCreateRequest request, UUID ownerId, @Nullable OidcUser principal) {
    UUID callerId = callerId(principal);
    if (ownerId != null && callerId != null && !ownerId.equals(callerId)) {
      refineryClient.createFor(ownerId, request);
    } else {
      refineryClient.create(request);
    }
  }

  /**
   * Resolves the caller's user id from the token, falling back to the backend's profile read.
   *
   * @param principal the authenticated user, or {@code null}
   * @return the user id, or {@code null} when it cannot be determined
   */
  @Nullable
  private UUID callerId(@Nullable OidcUser principal) {
    if (principal == null) {
      return null;
    }
    try {
      return CurrentUser.userId(principal);
    } catch (Exception _) {
      try {
        UserDto me = refineryClient.currentUser();
        return me != null ? me.id() : null;
      } catch (Exception e) {
        log.warn("Failed to get current user ID from backend: {}", e.getMessage());
        return null;
      }
    }
  }

  private static LocationDto locationOf(RefineryOrderForm form) {
    return form.getLocationId() != null
        ? new LocationDto(form.getLocationId(), null, null, false, false, null)
        : null;
  }

  private static MissionReferenceDto missionOf(RefineryOrderForm form) {
    return form.getMissionId() != null
        ? new MissionReferenceDto(form.getMissionId(), null, null, null)
        : null;
  }

  private static RefiningMethodDto refiningMethodOf(RefineryOrderForm form) {
    return form.getRefiningMethodId() != null
        ? new RefiningMethodDto(form.getRefiningMethodId(), null, null, null, null, null, null)
        : null;
  }

  private static long durationMinutesOf(RefineryOrderForm form) {
    return (long)
        ((form.getDurationHours() != null ? form.getDurationHours() : 0) * 60
            + (form.getDurationMinutes() != null ? form.getDurationMinutes() : 0));
  }

  private static List<RefineryGoodDto> goodsOf(RefineryOrderForm form) {
    List<RefineryGoodDto> goodsDto = new ArrayList<>();
    for (RefineryGoodForm g : form.getGoods()) {
      if (g.getInputMaterialId() != null) {
        MaterialDto inMat =
            new MaterialDto(
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
        MaterialDto outMat =
            g.getOutputMaterialId() != null
                ? new MaterialDto(
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
            new RefineryGoodDto(
                null,
                inMat,
                g.getInputQuantity() != null ? g.getInputQuantity() : 0,
                outMat,
                g.getOutputQuantity(),
                g.getQuality() != null ? g.getQuality() : 0,
                null));
      }
    }
    return goodsDto;
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
  @ResponseBody
  public ResponseEntity<Object> updateOrderAjax(
      @PathVariable UUID id,
      @Valid @ModelAttribute("refineryOrderForm") RefineryOrderForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.badRequest().build();
    }
    RefineryOrderUpdateRequest request = buildUpdateRequest(form);
    if (request.goods().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      refineryClient.update(id, request);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      return ResponseEntity.ok(Map.of("targetUrl", "/refinery-orders"));
    } catch (BackendServiceException bse) {
      log.debug("Failed to update refinery order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
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
  @ResponseBody
  public ResponseEntity<Object> storeOrderAjax(
      @PathVariable UUID id,
      @Valid @ModelAttribute("storeForm") RefineryOrderStoreForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors() || storePersonalWithJobOrder(form)) {
      return ResponseEntity.badRequest().build();
    }
    try {
      refineryClient.store(id, buildStoreDto(form));
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      liveSyncLocalBus.publish("inventory", INVENTORY_STOCK_SECTION);
      return ResponseEntity.ok(Map.of("targetUrl", "/refinery-orders"));
    } catch (BackendServiceException bse) {
      log.debug("Failed to store refinery order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
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
  @ResponseBody
  public ResponseEntity<Object> deleteOrderAjax(@PathVariable UUID id) {
    try {
      refineryClient.cancel(id);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      return ResponseEntity.ok(Map.of("targetUrl", "/refinery-orders"));
    } catch (BackendServiceException bse) {
      log.debug("Failed to cancel refinery order {} (ajax): {}", id, bse.getMessage());
      return propagateBackendError(bse);
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
  @ResponseBody
  public ResponseEntity<Object> createOrderAjax(
      @Valid @ModelAttribute("refineryOrderForm") RefineryOrderForm form,
      BindingResult bindingResult,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.badRequest().build();
    }
    RefineryOrderCreateRequest request = buildCreateRequest(form);
    if (request.goods().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      createFor(request, form.getOwnerId(), principal);
      liveSyncLocalBus.publish("refinery", REFINERY_QUEUE_SECTION);
      return ResponseEntity.ok(Map.of("targetUrl", "/refinery-orders"));
    } catch (BackendServiceException bse) {
      log.debug("Failed to create refinery order (ajax): {}", bse.getMessage());
      return propagateBackendError(bse);
    }
  }

  /**
   * Returns {@code 0.0} for {@code null}, so an emptied money field is saved as an explicit zero.
   */
  private static Double nullToZero(Double value) {
    return value != null ? value : 0d;
  }
}
