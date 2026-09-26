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
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationInput;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.frontend.model.form.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.validation.Valid;
import java.util.List;
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
 * Spring MVC controller for the mutating inventory endpoints under {@code /inventory}: create,
 * book-out, transfer and personal rebooking, bulk checkout and rebooking, allocations, and notes.
 *
 * <p>Validation failures re-render the read views inline via {@link InventoryPageController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryWriteController {

  /** REST client for the backend inventory write endpoints under {@code /api/v1/inventory}. */
  private final BackendApiClient backendApiClient;

  /**
   * The read half of the inventory area, whose endpoint methods re-render the originating view
   * inline when a classic form handler fails validation.
   */
  private final InventoryPageController inventoryPageController;

  /**
   * Persists a new inventory item.
   *
   * <p>A personal entry cannot be assigned to a job order or mission. Validation failure re-renders
   * the input page inline; success redirects to the page named by {@code source}.
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
    validateCatalogMode(form, bindingResult);
    if (formPersonalWithAssignment(form)) {
      bindingResult.rejectValue(
          "personal",
          "error.inventory.personal.assignment",
          "Ein persönlicher Eintrag darf keinem Auftrag oder Einsatz zugeordnet sein.");
    }

    if (bindingResult.hasErrors()) {
      return inventoryPageController.viewInputPage(form.getSource(), model);
    }

    try {
      InventoryItemCreateDto request = buildCreateRequest(form);
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
   * AJAX twin of {@link #addInventoryItem}, routed by the {@code X-Requested-With} header: books an
   * item in and returns the listing URL to navigate to (REQ-FE-006).
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
    validateCatalogMode(form, bindingResult);
    if (formPersonalWithAssignment(form)) {
      return inventoryValidationError("INVENTORY_PERSONAL_ASSIGNMENT");
    }
    if (bindingResult.hasErrors()) {
      return inventoryValidationError("VALIDATION");
    }
    try {
      InventoryItemCreateDto request = buildCreateRequest(form);
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
   * Validates the create form's catalog mode (REQ-INV-029): exactly one of {@code materialId} and
   * {@code gameItemId} is set, and material mode requires a {@code quality}.
   *
   * @param form the bound create form
   * @param bindingResult the binding result the violations are recorded on
   */
  private static void validateCatalogMode(
      @NotNull InventoryForm form, BindingResult bindingResult) {
    boolean material = form.getMaterialId() != null;
    boolean item = form.getGameItemId() != null;
    if (material == item) {
      bindingResult.rejectValue(
          item ? "gameItemId" : "materialId",
          "error.inventory.catalog.required",
          "Bitte genau ein Material oder ein Item auswählen.");
      return;
    }
    if (material && form.getQuality() == null) {
      bindingResult.rejectValue(
          "quality", "error.inventory.quality.required", "Bitte eine Qualität angeben.");
    }
  }

  /**
   * Maps the validated create form to the backend create payload, clearing the fields of the
   * inactive catalog mode; item mode sends no quality, mission or merge opt-in.
   *
   * @param form the validated create form
   * @return the backend create payload
   */
  @NotNull
  private static InventoryItemCreateDto buildCreateRequest(@NotNull InventoryForm form) {
    boolean itemMode = form.getGameItemId() != null;
    return new InventoryItemCreateDto(
        Boolean.TRUE.equals(form.getIsGlobal()) ? form.getUserId() : null,
        itemMode ? null : form.getMaterialId(),
        itemMode ? form.getGameItemId() : null,
        form.getLocationId(),
        itemMode ? null : form.getQuality(),
        form.getAmount(),
        form.getPersonal(),
        itemMode ? null : form.getMissionId(),
        form.getJobOrderId(),
        form.getOwningOrgUnitId(),
        itemMode ? Boolean.FALSE : form.getMergeStock(),
        toAllocationInputs(form.getJobOrderAllocations(), form.getAmount()),
        itemMode ? List.of() : toAllocationInputs(form.getMissionAllocations(), form.getAmount()));
  }

  /**
   * Whether a create form marks the entry personal while carrying any job-order or mission
   * assignment, including split earmarks (REQ-INV-027).
   *
   * @param form the bound create form
   * @return {@code true} when the entry is personal and carries at least one assignment
   */
  private static boolean formPersonalWithAssignment(InventoryForm form) {
    if (!Boolean.TRUE.equals(form.getPersonal())) {
      return false;
    }
    return form.getJobOrderId() != null
        || form.getMissionId() != null
        || !toAllocationInputs(form.getJobOrderAllocations(), form.getAmount()).isEmpty()
        || !toAllocationInputs(form.getMissionAllocations(), form.getAmount()).isEmpty();
  }

  /**
   * Maps the create form's split-at-check-in rows to backend allocation inputs (REQ-INV-027),
   * dropping rows without a target or amount.
   *
   * <p>When a dimension names exactly one target with a blank amount, the whole entry amount is
   * earmarked to it.
   *
   * @param rows the bound allocation rows; may be {@code null}
   * @param entryAmount the amount being booked in, used for the single-target shorthand; may be
   *     {@code null}
   * @return the resolved allocation inputs; never {@code null}
   */
  private static List<InventoryAllocationInput> toAllocationInputs(
      List<InventoryForm.AllocationRow> rows, Double entryAmount) {
    if (rows == null) {
      return List.of();
    }
    List<InventoryForm.AllocationRow> targeted =
        rows.stream().filter(row -> row != null && row.getTargetId() != null).toList();
    if (targeted.size() == 1 && targeted.getFirst().getAmount() == null) {
      if (entryAmount == null || entryAmount <= 0d) {
        return List.of();
      }
      return List.of(new InventoryAllocationInput(targeted.getFirst().getTargetId(), entryAmount));
    }
    return targeted.stream()
        .filter(row -> row.getAmount() != null)
        .map(row -> new InventoryAllocationInput(row.getTargetId(), row.getAmount()))
        .toList();
  }

  /**
   * Maps the create form's {@code source} marker to the listing the client navigates to after an
   * in-place book-in, mirroring the redirect targets of the classic {@link #addInventoryItem}.
   *
   * @param source the form's {@code source} field ({@code my} / {@code admin} / {@code aggregated})
   * @return the same-origin listing path
   */
  @NotNull
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
   * Builds a {@code 422} {@code problem+json} response with a stable {@code code} the inventory
   * pages show as an inline toast.
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
   * Books out an inventory item (consume / transfer / sell, selected by the form's {@code type}).
   *
   * <p>Success redirects with the {@code Referer}'s filter parameters kept; validation failure
   * re-renders the originating listing inline without them.
   *
   * @param id inventory item id
   * @param form book-out form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering on validation failure
   * @param redirectAttributes flash attributes carrier
   * @param referer origin URL, used for the redirect target and the admin-vs-my detection
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
      model.addAttribute("errorToast", "error.validation.failed");
      model.addAttribute("showBookOutModal", id);
      if (fromAdminListing) {
        return inventoryPageController.viewAllInventory(
            null, null, null, null, null, null, null, false, model);
      }
      return inventoryPageController.viewMyInventory(
          null, null, null, null, null, null, null, false, false, false, model);
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
              form.getMergeStock(),
              null,
              null);
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
   * Builds a redirect to an inventory list view that keeps the filter query parameters of the given
   * Referer URL, dropping {@code fragment}.
   *
   * <p>Returns the plain base path when the referer is empty, unparseable or has no query.
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
   * AJAX proxy for a personal-marker rebooking (REQ-INV-007): splits the source row and inserts the
   * moved amount as a new row with the opposite {@code personal} flag.
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
   * AJAX proxy for booking out several owned inventory items in full, forwarding to {@code POST
   * /api/v1/inventory/bulk-checkout}.
   *
   * @param request the ids of the owned items to book out
   * @return {@code 204} on success, otherwise the propagated backend error
   */
  @PostMapping("/bulk-checkout")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> bulkCheckout(
      @RequestBody BulkCheckoutRequest request) {
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
   * AJAX proxy for the bulk rebooking of several owned inventory rows (REQ-INV-036), forwarding to
   * {@code POST /api/v1/inventory/bulk-rebook}.
   *
   * <p>An empty id list is rejected here with a 422 {@code problem+json}.
   *
   * @param request the marked ids, the rebooking mode and its target fields
   * @return {@code 200} with the moved/skipped counts, otherwise the propagated backend error
   */
  @PostMapping("/bulk-rebook")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> bulkRebook(
      @RequestBody BulkRebookRequest request) {
    if (request == null || request.itemIds() == null || request.itemIds().isEmpty()) {
      return inventoryValidationError("VALIDATION");
    }
    if (request.mode() == null) {
      return inventoryValidationError("VALIDATION");
    }
    try {
      BulkRebookResultDto result =
          backendApiClient.post(
              "/api/v1/inventory/bulk-rebook", request, BulkRebookResultDto.class);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Failed to bulk-rebook inventory items (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to bulk-rebook inventory items (ajax)", e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy that earmarks part of an entry's quantity to a job order or mission (REQ-INV-027),
   * relaying the backend status and RFC 7807 {@code code} verbatim.
   *
   * @param id the inventory entry id
   * @param dto the allocation write payload (dimension, target, amount, echoed version)
   * @return the updated entry on success, propagated backend status/body on failure
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
   * AJAX proxy that changes an allocation's amount (REQ-INV-027), relaying the backend status
   * verbatim.
   *
   * @param id the inventory entry id
   * @param dto the allocation write payload (dimension, target, new amount, echoed version)
   * @return the updated entry on success, propagated backend status/body on failure
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
   * AJAX proxy that removes an allocation (REQ-INV-027), sending the slice identity in the DELETE
   * body and relaying the backend status verbatim.
   *
   * @param id the inventory entry id
   * @param dto the allocation write payload (dimension, target, echoed version; amount ignored)
   * @return the updated entry on success, propagated backend status/body on failure
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
   * AJAX proxy that adds, edits or removes an inventory item's note; a blank note removes it.
   *
   * <p>Returns the updated {@link InventoryItemDto} with its new version. Authorisation is enforced
   * by the backend.
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
