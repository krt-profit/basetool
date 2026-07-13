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
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.form.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the mutating half of the inventory area ({@code /inventory} prefix),
 * split out of {@link InventoryPageController} in the #924 read/write controller split (L5).
 *
 * <p>Write paths cover create (classic input-form post plus its {@code X-Requested-With} AJAX
 * twin), book-out (consume / transfer / sell), inline transfer and personal rebooking (AJAX), bulk
 * checkout, association update (re-bind to a different mission/job-order), note update, and the
 * delivered-status toggle. Validation-failure paths re-render the read views inline by delegating
 * to the read controller, so the {@code BindingResult} stays request-scoped exactly as before the
 * split.
 */
@Controller
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryWriteController {

  /** REST client for the backend inventory write endpoints under {@code /api/v1/inventory}. */
  private final BackendApiClient backendApiClient;

  /**
   * The read half of the inventory area. Validation failures in the classic form handlers re-render
   * the originating read view inline (input form / personal listing / admin listing) instead of
   * redirecting, so the {@code BindingResult} stays request-scoped; those inline renders are
   * delegated to the read controller's public endpoint methods ({@code viewInputPage}, {@code
   * viewMyInventory}, {@code viewAllInventory}), which own the catalog fetching and model seeding.
   */
  private final InventoryPageController inventoryPageController;

  /**
   * Persists a new inventory item.
   *
   * <p>Enforces a cross-field invariant before the backend call: a personal entry cannot be
   * assigned to a job order or mission. Validation failure re-renders the input page inline so the
   * BindingResult stays request-scoped. On success, redirects to the page the user came from
   * (encoded in {@code source}).
   *
   * @param form inventory form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return inline {@code inventory-input} view on failure, otherwise redirect to the source page
   */
  @PostMapping("/input")
  public String addInventoryItem(
      @Valid @ModelAttribute("inventoryForm") InventoryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (Boolean.TRUE.equals(form.getPersonal())
        && (form.getJobOrderId() != null || form.getMissionId() != null)) {
      bindingResult.rejectValue(
          "personal",
          "error.inventory.personal.assignment",
          "Ein persönlicher Eintrag darf keinem Auftrag oder Einsatz zugeordnet sein.");
    }

    if (bindingResult.hasErrors()) {
      // Render directly; BindingResult stays request-scoped (see RedisSessionConfig).
      return inventoryPageController.viewInputPage(form.getSource(), model);
    }

    try {
      InventoryItemCreateDto request =
          new InventoryItemCreateDto(
              Boolean.TRUE.equals(form.getIsGlobal()) ? form.getUserId() : null,
              form.getMaterialId(),
              form.getLocationId(),
              form.getQuality(),
              form.getAmount(),
              form.getPersonal(),
              form.getMissionId(),
              form.getJobOrderId(),
              form.getOwningOrgUnitId(),
              form.getMergeStock());
      backendApiClient.post("/api/v1/inventory", request, InventoryItemDto.class);
      redirectAttributes.addFlashAttribute("successToast", "success.inventory.add");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "addInventoryItem", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.inventory.add.failed");
      redirectAttributes.addFlashAttribute("inventoryForm", form);
      return "redirect:/inventory/input";
    } catch (Exception e) {
      log.error("Failed to add inventory item", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.inventory.add.failed");
      redirectAttributes.addFlashAttribute("inventoryForm", form);
      return "redirect:/inventory/input";
    }

    if ("my".equals(form.getSource())) {
      return "redirect:/inventory/my";
    } else if ("admin".equals(form.getSource())) {
      return "redirect:/inventory/all";
    } else if ("aggregated".equals(form.getSource())) {
      return "redirect:/inventory";
    }

    return "redirect:/inventory";
  }

  /**
   * AJAX twin of {@link #addInventoryItem} (#577): books an item into the inventory in place.
   * Routed by the {@code X-Requested-With} header (the classic {@code POST /inventory/input}
   * redirect stays the no-JavaScript fallback). The form is multipart/form-encoded (the create page
   * has the SCU-decimal amount + the owner-picker), so it binds the same {@code @ModelAttribute}
   * the classic handler does. On success it returns the source listing URL the client navigates to
   * (navigate-after-AJAX, REQ-FE-006 — book-in is a standalone page with no local list to patch); a
   * cross-field or bind error returns {@code 422} {@code problem+json} with a {@code code} the
   * client maps to an inline toast, and a backend failure is propagated as {@code problem+json}.
   *
   * @param form the bound inventory form
   * @param bindingResult the binding/validation result
   * @return {@code 200} with {@code {targetUrl}}, {@code 422} on validation, or the propagated
   *     error
   */
  @PostMapping(value = "/input", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> addInventoryItemAjax(
      @Valid @ModelAttribute("inventoryForm") InventoryForm form, BindingResult bindingResult) {
    if (Boolean.TRUE.equals(form.getPersonal())
        && (form.getJobOrderId() != null || form.getMissionId() != null)) {
      return inventoryValidationError("INVENTORY_PERSONAL_ASSIGNMENT");
    }
    if (bindingResult.hasErrors()) {
      return inventoryValidationError("VALIDATION");
    }
    try {
      InventoryItemCreateDto request =
          new InventoryItemCreateDto(
              Boolean.TRUE.equals(form.getIsGlobal()) ? form.getUserId() : null,
              form.getMaterialId(),
              form.getLocationId(),
              form.getQuality(),
              form.getAmount(),
              form.getPersonal(),
              form.getMissionId(),
              form.getJobOrderId(),
              form.getOwningOrgUnitId(),
              form.getMergeStock());
      backendApiClient.post("/api/v1/inventory", request, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(
          java.util.Map.of("targetUrl", inventorySourceTarget(form.getSource())));
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Failed to add inventory item (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to add inventory item (ajax)", e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Maps the create form's {@code source} marker to the listing the client navigates to after an
   * in-place book-in, mirroring the redirect targets of the classic {@link #addInventoryItem}.
   *
   * @param source the form's {@code source} field ({@code my} / {@code admin} / {@code aggregated})
   * @return the same-origin listing path
   */
  private static String inventorySourceTarget(String source) {
    if ("my".equals(source)) {
      return "/inventory/my";
    }
    if ("admin".equals(source)) {
      return "/inventory/all";
    }
    return "/inventory";
  }

  /**
   * Builds a {@code 422} {@code problem+json} response carrying a stable {@code code} the inventory
   * pages map to a localized inline toast — used by {@link #addInventoryItemAjax} for the
   * server-side cross-field rule (a personal entry cannot carry an order/mission) and for a bind
   * failure, so the create page surfaces the error without a navigation.
   *
   * @param code the validation code ({@code INVENTORY_PERSONAL_ASSIGNMENT} / {@code VALIDATION})
   * @return a {@code 422} {@code problem+json} response
   */
  private static org.springframework.http.ResponseEntity<Object> inventoryValidationError(
      String code) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("status", 422);
    body.put("code", code);
    return org.springframework.http.ResponseEntity.unprocessableContent()
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  /**
   * Books out an inventory item (consume / transfer / sell). The {@code type} field on the form
   * selects the operation; the backend computes the resulting state changes (decrement, transfer to
   * another user/location, or sell with terminal + price).
   *
   * <p>The redirect target preserves filter query parameters from the {@code Referer} header (see
   * {@link #buildInventoryRedirectFromReferer}) so the user does not lose their active filters
   * after a successful book-out. Validation failures drop the filter state and re-render the
   * originating listing inline — acceptable for a rare validation path.
   *
   * @param id inventory item id
   * @param form book-out form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering on validation failure
   * @param redirectAttributes flash attributes carrier
   * @param referer browser-supplied origin URL, used to derive the redirect target and the
   *     admin-vs-my detection
   * @return inline rerendered listing on failure, otherwise redirect preserving filters
   */
  @PostMapping("/{id}/book-out")
  public String bookOutInventoryItem(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("inventoryBookOutForm")
          de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @RequestHeader(value = "Referer", required = false) String referer) {
    boolean fromAdminListing = referer != null && referer.contains("/inventory/all");
    String basePath = fromAdminListing ? "/inventory/all" : "/inventory/my";
    String redirectPath = buildInventoryRedirectFromReferer(basePath, referer);

    if (bindingResult.hasErrors()) {
      // Render the originating listing directly so the BindingResult stays
      // request-scoped (see RedisSessionConfig). The user-side trade-off is that
      // the filter state from the referer URL is dropped — acceptable for a rare
      // validation error path; the modal re-opens with the input + field errors.
      model.addAttribute("errorToast", "error.validation.failed");
      model.addAttribute("showBookOutModal", id);
      if (fromAdminListing) {
        return inventoryPageController.viewAllInventory(null, null, null, null, false, model);
      }
      return inventoryPageController.viewMyInventory(
          null, null, null, null, false, false, false, model);
    }

    try {
      InventoryItemBookOutDto request =
          new InventoryItemBookOutDto(
              form.getAmount(),
              form.getTargetUserId(),
              form.getTargetLocationId(),
              form.getType(),
              form.getTerminal(),
              form.getSellAmount(),
              form.getVersion(),
              form.getTargetOwningOrgUnitId(),
              form.getMergeStock());
      backendApiClient.post("/api/v1/inventory/" + id + "/book-out", request, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "success.inventory.bookout");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "bookOutInventoryItem", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.inventory.bookout.failed");
    } catch (Exception e) {
      log.error("Failed to book out inventory item", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.inventory.bookout.failed");
    }
    return "redirect:" + redirectPath;
  }

  /**
   * Builds a redirect target for inventory list views that preserves the filter query parameters
   * (e.g. {@code materialIds}, {@code minQuality}, {@code jobOrderIds}, {@code missionIds}, {@code
   * page}, {@code size}, {@code sort}) taken from the given Referer URL. This is the single source
   * of truth for filter state (URL-based) and guarantees that users keep their active filters after
   * write actions such as book-out / transfer / sell.
   *
   * <p>If the referer is empty, not parseable, or contains no query string, the plain base path is
   * returned. The {@code fragment} parameter is intentionally stripped since the redirect always
   * targets the full page view.
   */
  @org.jetbrains.annotations.NotNull
  static String buildInventoryRedirectFromReferer(
      @org.jetbrains.annotations.NotNull String basePath,
      @org.jetbrains.annotations.Nullable String referer) {
    if (referer == null || referer.isBlank()) {
      return basePath;
    }
    String query;
    try {
      java.net.URI uri = java.net.URI.create(referer);
      query = uri.getRawQuery();
    } catch (IllegalArgumentException ex) {
      return basePath;
    }
    if (query == null || query.isBlank()) {
      return basePath;
    }
    StringBuilder rebuilt = new StringBuilder();
    for (String raw : query.split("&")) {
      if (raw.isEmpty()) {
        continue;
      }
      int eq = raw.indexOf('=');
      String name = eq < 0 ? raw : raw.substring(0, eq);
      if (name.isEmpty() || "fragment".equals(name)) {
        continue;
      }
      if (!rebuilt.isEmpty()) {
        rebuilt.append('&');
      }
      rebuilt.append(raw);
    }
    if (rebuilt.isEmpty()) {
      return basePath;
    }
    return basePath + "?" + rebuilt;
  }

  /**
   * AJAX endpoint that proxies a transfer (owner or location change) for an inventory item to the
   * backend book-out endpoint. Used by the material collection page for inline reassignment.
   */
  @PostMapping("/{id}/transfer")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> transferInventoryItem(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryItemBookOutDto dto) {
    try {
      InventoryItemDto result =
          backendApiClient.post(
              "/api/v1/inventory/" + id + "/book-out", dto, InventoryItemDto.class);
      if (result == null) {
        // Item was fully consumed (deleted) – return 204 so the frontend can reload
        return org.springframework.http.ResponseEntity.noContent().build();
      }
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Failed to transfer inventory item: status={}, {}", e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to transfer inventory item", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that proxies a personal-marker rebooking (Umbuchung, REQ-INV-007) to the backend.
   * Drives the PERSONAL mode of the Umbuchen modal: the source row's quantity is split and the
   * moved amount inserted as a new row with the opposite {@code personal} flag. Always returns the
   * new row (the source row may have been depleted), so the page re-swaps the grouped table on
   * success.
   *
   * @param id the source inventory row id
   * @param dto the rebooking payload (amount, version, optional target org unit)
   * @return {@code 200} with the new row, or the propagated backend error
   */
  @PostMapping("/{id}/personal-rebook")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> rebookPersonalInventoryItem(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryItemPersonalRebookDto dto) {
    try {
      InventoryItemDto result =
          backendApiClient.post(
              "/api/v1/inventory/" + id + "/personal-rebook", dto, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Failed to rebook inventory item: status={}, {}", e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to rebook inventory item", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX proxy for the bulk book-out of several fully-consumed inventory items in one call (#577,
   * part 2). The personal-inventory page collects the checked item ids and posts them here; this
   * forwards to the backend {@code POST /api/v1/inventory/bulk-checkout} (which discards each item
   * in full and enforces per-item ownership). It replaces the page's former direct browser call to
   * the backend {@code /api/v1/...} path, which had no matching frontend route — so the bulk action
   * never reached the backend. On a backend failure the {@code problem+json} is propagated (so the
   * client's {@code krtFetch.handleProblem} can drive the optimistic-lock reload-confirm or an
   * error toast); on success {@code 204} lets the page re-swap the grouped table in place rather
   * than reload.
   *
   * @param request the ids of the owned items to book out
   * @return {@code 204} on success, otherwise the propagated backend error
   */
  @PostMapping("/bulk-checkout")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> bulkCheckout(
      @RequestBody BulkCheckoutRequest request) {
    // Guard the empty/missing id list here (rather than via @Valid, which the frontend
    // GlobalExceptionHandler would surface as a 500) so the page gets a clean 422 problem+json —
    // mirroring addInventoryItemAjax's manual validation. The page already blocks an empty
    // selection client-side; the backend re-validates per item.
    if (request == null || request.itemIds() == null || request.itemIds().isEmpty()) {
      return inventoryValidationError("VALIDATION");
    }
    try {
      backendApiClient.post("/api/v1/inventory/bulk-checkout", request, Void.class);
      return org.springframework.http.ResponseEntity.noContent().build();
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Failed to bulk-checkout inventory items (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to bulk-checkout inventory items (ajax)", e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint that updates the soft associations of an inventory item (mission, job order,
   * note). Distinct from {@link #transferInventoryItem} — this is a metadata-only update, no
   * quantity changes. Propagates the backend's status code verbatim so the AJAX layer can map a 409
   * to a dedicated optimistic-lock toast.
   *
   * @param id inventory item id
   * @param dto update payload
   * @return the updated item on success, propagated backend status on failure
   */
  @PutMapping("/{id}/update-associations")
  @ResponseBody
  public org.springframework.http.ResponseEntity<InventoryItemDto> updateAssociations(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryItemUpdateDto dto) {
    try {
      InventoryItemDto updated =
          backendApiClient.put("/api/v1/inventory/" + id, dto, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Failed to update inventory item associations: status={}, {}",
          e.getStatusCode(),
          e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
      log.error("Failed to update inventory item associations: {}", e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Failed to update inventory item associations", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that proxies an inventory allocation add (Variante C, REQ-INV-027) to the
   * backend: earmarks part of an entry's quantity to a job order or mission. Relays the backend
   * status verbatim through {@link
   * de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses#propagateBackendError} so
   * the RFC&nbsp;7807 {@code code} survives — the AJAX layer keeps its 409-reload vs 422-toast
   * (over-allocation) distinction.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, amount, echoed version).
   * @return the updated entry on success, propagated backend status/body on failure.
   */
  @PostMapping("/{id}/allocation")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> addAllocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryAllocationWriteDto dto) {
    try {
      InventoryItemDto updated =
          backendApiClient.post(
              "/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (BackendServiceException e) {
      log.debug(
          "Failed to add inventory allocation: status={}, {}", e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
      log.error("Failed to add inventory allocation: {}", e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Failed to add inventory allocation", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that proxies an inventory allocation amount change (Variante C, REQ-INV-027).
   * Same verbatim status relay as {@link #addAllocation}.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, new amount, echoed version).
   * @return the updated entry on success, propagated backend status/body on failure.
   */
  @PatchMapping("/{id}/allocation")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> changeAllocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryAllocationWriteDto dto) {
    try {
      InventoryItemDto updated =
          backendApiClient.patch(
              "/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (BackendServiceException e) {
      log.debug(
          "Failed to change inventory allocation: status={}, {}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
      log.error("Failed to change inventory allocation: {}", e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Failed to change inventory allocation", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that proxies an inventory allocation removal (Variante C, REQ-INV-027). The
   * backend DELETE carries the slice identity (dimension + target + echoed version) in the body, so
   * the payload is relayed through the body-carrying {@code delete} client overload. Same verbatim
   * status relay as {@link #addAllocation}.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, echoed version; amount ignored).
   * @return the updated entry on success, propagated backend status/body on failure.
   */
  @DeleteMapping("/{id}/allocation")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> removeAllocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryAllocationWriteDto dto) {
    try {
      InventoryItemDto updated =
          backendApiClient.delete(
              "/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (BackendServiceException e) {
      log.debug(
          "Failed to remove inventory allocation: status={}, {}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
      log.error("Failed to remove inventory allocation: {}", e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Failed to remove inventory allocation", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that proxies a note update (add/edit/remove) for an inventory item to the
   * backend. Authorisation is enforced by the backend (owner or {@code LOGISTICIAN}/{@code
   * OFFICER}/ {@code ADMIN} via role hierarchy). A blank or empty {@code note} removes the note. On
   * success, returns the updated {@link InventoryItemDto} (including the incremented version) so
   * the frontend can synchronize {@code data-version} DOM attributes.
   */
  @PutMapping("/{id}/note")
  @ResponseBody
  public org.springframework.http.ResponseEntity<InventoryItemDto> updateInventoryItemNote(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryItemNoteUpdateRequest request) {
    try {
      InventoryItemDto updated =
          backendApiClient.put(
              "/api/v1/inventory/" + id + "/note", request, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      // Propagate backend status (e.g. 409 Conflict from Optimistic Locking, 400 Validation,
      // 403 Forbidden) to the browser instead of masking it as 500, so the JS note modal
      // can react appropriately (toast + reload on 409).
      log.debug(
          "Failed to update inventory item note: status={}, {}", e.getStatusCode(), e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
      log.error("Failed to update inventory item note: {}", e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Failed to update inventory item note", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that proxies a delivered-status update for an inventory item to the backend. On
   * success, returns the updated {@link InventoryItemDto} (including the incremented version) so
   * the frontend can synchronize {@code data-version} DOM attributes.
   */
  @PatchMapping("/{id}/delivered")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateDelivered(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid
          de.greluc.krt.profit.basetool.frontend.model.dto.UpdateDeliveredRequest request) {
    try {
      InventoryItemDto updated =
          backendApiClient.patch(
              "/api/v1/inventory/" + id + "/delivered", request, InventoryItemDto.class);
      return org.springframework.http.ResponseEntity.ok(updated);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Failed to update delivered status: status={}, {}", e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
      log.error("Failed to update delivered status: {}", e.getMessage());
      return org.springframework.http.ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Failed to update delivered status", e);
      return org.springframework.http.ResponseEntity.status(500).build();
    }
  }
}
