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
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Write half of the {@code /refinery-orders} surface (#924, L5): the classic create / update /
 * delete / store form handlers and their {@code X-Requested-With}-routed AJAX twins, moved verbatim
 * out of {@link RefineryOrderPageController} so the read controller only renders pages. The
 * non-mutating {@code POST /refinery-orders/import} relay deliberately stays on the read controller
 * because it needs the whole create-page render machinery. Every endpoint here only relays the
 * bound form to the backend REST API via {@link BackendApiClient} — no other collaborator is
 * injected.
 */
@Controller
@RequestMapping("/refinery-orders")
@RequiredArgsConstructor
@Slf4j
public class RefineryOrderWriteController {

  /** Sole collaborator: relays the create/update/delete/store mutations to the backend API. */
  private final BackendApiClient backendApiClient;

  /**
   * Parses the start instant submitted by the form as a UTC {@link java.time.Instant}.
   *
   * <p>Why: All timestamps in the system are persisted and transported solely in UTC (AGENTS.md
   * "Consistent Date/Time/Zone Handling"). The frontend (datetime-splitter.js) therefore always
   * sends an ISO-Instant string with 'Z' or with an offset. Backward compatible forms are
   * additionally parsed (date only, local DateTime without zone - the latter is defensively
   * interpreted as UTC to avoid an implicit and DST-prone use of {@code ZoneId.systemDefault()}).
   */
  static java.time.Instant parseStartedAt(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return java.time.Instant.now();
    }
    String input = raw.trim();
    try {
      return java.time.Instant.parse(input);
    } catch (Exception ignored) {
      /* not an instant */
    }
    try {
      return java.time.OffsetDateTime.parse(input).toInstant();
    } catch (Exception ignored) {
      /* not offset date time */
    }
    if (input.length() == 10) {
      // Date only -> start of day in UTC
      return java.time.LocalDate.parse(input).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
    // LocalDateTime without zone -> defensively interpret as UTC to avoid a double
    // DST conversion. Correct inputs always carry 'Z' or an offset.
    return java.time.LocalDateTime.parse(input).toInstant(java.time.ZoneOffset.UTC);
  }

  /**
   * Persists a new refinery order from the create form.
   *
   * <p>Translates each form-bound {@code RefineryGoodForm} into a {@code RefineryGoodDto} with
   * minimal id-only Material/Location/User stubs (the backend re-hydrates the full records from the
   * ids). Empty goods short-circuit with a localized error before reaching the backend. On failure
   * the form is flashed back so the user keeps their input.
   *
   * @param form refinery-order create form
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /refinery-orders/create} on failure (preserves input), otherwise to
   *     the source page or the list
   */
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
      redirectAttributes.addFlashAttribute("successToast", "success.refineryorder.create");

      return "redirect:/refinery-orders";
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "POST /api/v1/refinery-orders", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.create.failed");
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
   * Persists an edit to an existing refinery order. Mirrors {@link #createOrder} for the
   * stubs-by-id pattern; additionally carries the optimistic-lock {@code version} from the form.
   * Empty goods list short-circuits with a localized error before the backend call.
   *
   * @param id refinery order id
   * @param form refinery-order edit form
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /refinery-orders/{id}} on failure, otherwise to the list
   */
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
      redirectAttributes.addFlashAttribute("successToast", "success.refineryorder.update");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "PUT /api/v1/refinery-orders/{id}", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.refineryorder.update.failed");
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
   * Cancels a refinery order. The backend treats this as a soft-cancel (status transition);
   * fine-grained authorization is enforced backend-side, the frontend only authenticates.
   *
   * @param id refinery order id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /refinery-orders}
   */
  @PostMapping("/{id}/delete")
  @PreAuthorize("isAuthenticated()")
  public String deleteOrder(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/refinery-orders/" + id, Void.class);
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
   * <p>The store form picks the target location and (optionally) the receiving user/job-order; the
   * backend computes the inventory rows from the order's goods and the chosen target. A validation
   * failure flashes the form back and re-opens the store modal on the detail page.
   *
   * @param id refinery order id
   * @param form store form
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the order detail on failure, otherwise to the list
   */
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
    try {
      RefineryOrderStoreDto dto = buildStoreDto(form);
      backendApiClient.post("/api/v1/refinery-orders/" + id + "/store", dto, Void.class);
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
   * Builds the {@link RefineryOrderDto} from a create/edit form (goods stubs + metadata), shared by
   * the classic {@link #createOrder} / {@link #updateOrder} handlers and their AJAX twins. Goods
   * rows without an input material/quantity are dropped. The create path passes a {@code null} id
   * plus the form's {@code owningOrgUnitId} to stamp the owner; the edit path passes the real
   * {@code id} and a {@code null} stamp so the backend preserves the original org-unit.
   *
   * @param id the refinery order id, or {@code null} when building a create DTO
   * @param form the bound create/edit form
   * @param owningOrgUnitId the owning org-unit stamp ({@code null} on the edit path)
   * @return the order DTO ready to POST/PUT
   */
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
              f.getOwningOrgUnitId()));
    }
    return new RefineryOrderStoreDto(dtoList);
  }

  /**
   * AJAX twin of {@link #updateOrder} (#575): saves a refinery-order edit and returns the
   * post-update navigation target as JSON, so the detail page navigates itself (and stays put with
   * an inline toast on a validation/backend failure) instead of a server redirect. Routed by the
   * {@code X-Requested-With} header; the classic form-POST handler stays the no-JS fallback.
   * Binding / empty-goods errors → 400.
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
   * AJAX twin of {@link #storeOrder} (#575): completes a refinery order (stores the refined output)
   * and returns the navigation target as JSON. Routed by the {@code X-Requested-With} header; the
   * classic handler stays the no-JS fallback. Binding errors → 400.
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
    if (bindingResult.hasErrors()) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      backendApiClient.post(
          "/api/v1/refinery-orders/" + id + "/store", buildStoreDto(form), Void.class);
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
   * AJAX twin of {@link #deleteOrder} (#575): soft-cancels a refinery order and returns the list
   * URL as JSON so the detail page navigates itself (after a KRT confirm) instead of a server
   * redirect. Routed by the {@code X-Requested-With} header; the classic handler stays the no-JS
   * fallback.
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
   * AJAX twin of {@link #createOrder} (#575): persists a new refinery order and returns the
   * post-create navigation target as JSON, so the create page navigates itself on success and stays
   * put with an inline toast (keeping the entered data) on a validation / empty-goods / backend
   * failure instead of a server redirect. Routed by the {@code X-Requested-With} header; the
   * classic form-POST handler stays the no-JS fallback. Binding / empty-goods errors → 400.
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
   * Returns {@code 0.0} when the value is {@code null}. Used when saving a refinery order for the
   * money fields ({@code expenses}, {@code otherExpenses}, {@code oreSales}): the frontend
   * pre-fills these with 0 and a blur-handler restores 0 when the user clears the field, but
   * Spring's form-binding still produces {@code null} for an empty submission. Normalising to 0
   * means the backend always sees an explicit numeric value and the displayed-vs-stored value never
   * disagrees on re-render.
   */
  private static Double nullToZero(Double value) {
    return value != null ? value : 0d;
  }
}
