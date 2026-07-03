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

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UexLocationDto;
import de.greluc.krt.profit.basetool.frontend.model.form.PersonalInventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page controller backing the personal inventory user area. Renders the list view, the create/edit
 * modal (KRT-styled, no native confirm()) and proxies form submissions to the backend via {@link
 * BackendApiClient}.
 */
@Controller
@RequestMapping("/personal-inventory")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class PersonalInventoryPageController {

  /** Response type for the {@code /api/v1/uex/locations/search} typeahead read. */
  private static final ParameterizedTypeReference<List<UexLocationDto>> UEX_LOCATION_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the paged {@code /api/v1/personal-inventory} listing read. */
  private static final ParameterizedTypeReference<PageResponse<PersonalInventoryItemDto>>
      PERSONAL_INVENTORY_ITEM_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the personal-inventory list with the create/edit modal.
   *
   * @param q optional free-text filter; echoed back into the search input
   * @param page zero-based page index
   * @param size page size, defaults to 50
   * @param sort optional sort spec ({@code field,asc|desc}); whitelisted by the backend
   * @param fragment when {@code "results"} only the item-list fragment is rendered (AJAX filter
   *     swap, REQ-FE-002); otherwise the full page is returned
   * @param model Thymeleaf model populated with the form, the filter query, the item list and page
   *     metadata
   * @return the {@code personal-inventory} view name, or its {@code results} fragment for an AJAX
   *     filter swap
   */
  @GetMapping
  public String view(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("personalInventoryForm")) {
      model.addAttribute("personalInventoryForm", new PersonalInventoryForm());
    }
    populateListing(model, q, page, size, sort);
    return "results".equals(fragment) ? "personal-inventory :: results" : "personal-inventory";
  }

  /**
   * Creates a new personal-inventory item.
   *
   * <p>Validation errors render the list view inline (no redirect) — pushing the BindingResult into
   * a FlashAttribute would crash Spring Session's Jackson serialization because {@code
   * BeanPropertyBindingResult} holds a back-reference to its model map (a self-referencing cycle
   * that exceeds Jackson's 500-deep nesting cap).
   *
   * @param form form-bound DTO
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering on validation failure
   * @param redirectAttributes flash attributes carrier
   * @return inline {@code personal-inventory} view on validation failure, otherwise redirect
   */
  @PostMapping("/add")
  public String add(
      @Valid @ModelAttribute("personalInventoryForm") PersonalInventoryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Render the list view directly instead of redirecting. We must NOT push the
      // BindingResult into a FlashAttribute: BeanPropertyBindingResult holds a back-
      // reference to its model map (which in turn re-contains the BindingResult), and
      // the GenericJacksonJsonRedisSerializer used for Spring Session blows up on the
      // self-referencing cycle with `Document nesting depth (501) exceeds the maximum
      // allowed (500)`. Spring already exposes both `personalInventoryForm` and the
      // associated BindingResult through @ModelAttribute, so the modal can re-display
      // the user's input and the field errors without any flash hand-off.
      model.addAttribute("showItemModal", true);
      model.addAttribute("modalAction", "/personal-inventory/add");
      populateListing(model, null, null, null, null);
      return "personal-inventory";
    }

    try {
      PersonalInventoryItemCreateRequest request =
          new PersonalInventoryItemCreateRequest(
              form.getName(),
              form.getNote(),
              form.getLocationUexId(),
              form.getLocationType(),
              form.getQuantity());
      backendApiClient.post("/api/v1/personal-inventory", request, PersonalInventoryItemDto.class);
      redirectAttributes.addFlashAttribute("successToast", "personalInventory.toast.created");
    } catch (Exception e) {
      log.error("Failed to create personal inventory item", e);
      redirectAttributes.addFlashAttribute("errorToast", "personalInventory.error.create");
      redirectAttributes.addFlashAttribute("personalInventoryForm", form);
    }
    return "redirect:/personal-inventory";
  }

  /**
   * Updates an existing personal-inventory item. Same self-referencing-BindingResult workaround as
   * {@link #add}. A 409 surfaces as the dedicated optimistic-lock toast via {@link #classifyError}.
   *
   * @param id inventory item id
   * @param form form-bound DTO
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering on validation failure
   * @param redirectAttributes flash attributes carrier
   * @return inline {@code personal-inventory} view on validation failure, otherwise redirect
   */
  @PostMapping("/{id}/update")
  public String update(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("personalInventoryForm") PersonalInventoryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Same rationale as add(): render directly to avoid pushing the
      // self-referencing BindingResult through the Redis-backed FlashMap.
      model.addAttribute("showItemModal", true);
      model.addAttribute("modalAction", "/personal-inventory/" + id + "/update");
      populateListing(model, null, null, null, null);
      return "personal-inventory";
    }

    try {
      PersonalInventoryItemUpdateRequest request =
          new PersonalInventoryItemUpdateRequest(
              form.getName(),
              form.getNote(),
              form.getLocationUexId(),
              form.getLocationType(),
              form.getQuantity(),
              form.getVersion());
      backendApiClient.put(
          "/api/v1/personal-inventory/" + id, request, PersonalInventoryItemDto.class);
      redirectAttributes.addFlashAttribute("successToast", "personalInventory.toast.updated");
    } catch (Exception e) {
      log.error("Failed to update personal inventory item {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.error.update"));
    }
    return "redirect:/personal-inventory";
  }

  /**
   * Deletes a personal-inventory item. Failure surfaces as a 409-aware toast.
   *
   * @param id inventory item id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /personal-inventory}
   */
  @PostMapping("/{id}/delete")
  public String delete(@PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/personal-inventory/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "personalInventory.toast.deleted");
    } catch (Exception e) {
      log.error("Failed to delete personal inventory item {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.error.delete"));
    }
    return "redirect:/personal-inventory";
  }

  // ----------------------------------------------------- AJAX twins (epic #571 / REQ-FE-005)

  /**
   * Header-gated AJAX twin of {@link #add}: creates an item and returns {@code 204} so {@code
   * personal-inventory.html} re-renders the {@code #pi-results} list fragment via {@code GET
   * /personal-inventory?fragment=results} instead of the classic POST→redirect reload. The twin is
   * selected only for an {@code X-Requested-With=XMLHttpRequest} JSON request, so the classic
   * {@code @ModelAttribute} handler stays the no-JS fallback. The modal's HTML5 {@code required}
   * and the location typeahead guard the inputs client-side; an empty required field still yields a
   * {@code 422} so a crafted request cannot slip past, and a backend rejection is relayed as {@code
   * problem+json}.
   *
   * @param request the item payload submitted as JSON
   * @return {@code 204} on success, {@code 422} on a missing required field, or the relayed backend
   *     {@code problem+json}
   */
  @PostMapping(value = "/add", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> addAjax(@RequestBody PersonalInventoryItemCreateRequest request) {
    if (isInvalidCreate(request)) {
      return validationProblem();
    }
    try {
      backendApiClient.post("/api/v1/personal-inventory", request, PersonalInventoryItemDto.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.error("Failed to create personal inventory item (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to create personal inventory item (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #update}: updates an item and returns {@code 204} so the page
   * re-renders the {@code #pi-results} list fragment in place. The optimistic-lock {@code version}
   * travels in the JSON payload; a concurrent edit surfaces as a {@code 409} {@code problem+json}
   * carrying {@code OPTIMISTIC_LOCK}, which the client turns into the sanctioned reload-confirm.
   *
   * @param id inventory item id
   * @param request the item payload submitted as JSON (carries the last-seen {@code version})
   * @return {@code 204} on success, {@code 422} on a missing required field, or the relayed backend
   *     {@code problem+json}
   */
  @PostMapping(value = "/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> updateAjax(
      @PathVariable @NotNull UUID id, @RequestBody PersonalInventoryItemUpdateRequest request) {
    if (request == null
        || request.name() == null
        || request.name().isBlank()
        || request.locationUexId() == null
        || request.locationType() == null
        || request.quantity() == null
        || request.quantity() < 1) {
      return validationProblem();
    }
    try {
      backendApiClient.put(
          "/api/v1/personal-inventory/" + id, request, PersonalInventoryItemDto.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.error("Failed to update personal inventory item {} (ajax): {}", id, e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to update personal inventory item {} (ajax)", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #delete}: deletes an item and returns {@code 204} so the page
   * re-renders the {@code #pi-results} list fragment in place. A backend failure is relayed as
   * {@code problem+json}.
   *
   * @param id inventory item id
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteAjax(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete("/api/v1/personal-inventory/" + id, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.error("Failed to delete personal inventory item {} (ajax): {}", id, e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to delete personal inventory item {} (ajax)", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Mirrors the {@code PersonalInventoryForm} bean constraints for the JSON create twin: name must
   * be present, a location (UEX id + type) must be chosen via the typeahead and the quantity must
   * be at least one.
   *
   * @param request the create payload to validate
   * @return {@code true} when any required field is missing or out of range
   */
  private static boolean isInvalidCreate(PersonalInventoryItemCreateRequest request) {
    return request == null
        || request.name() == null
        || request.name().isBlank()
        || request.locationUexId() == null
        || request.locationType() == null
        || request.quantity() == null
        || request.quantity() < 1;
  }

  /**
   * Builds the {@code 422} {@code problem+json} carrying the stable {@code VALIDATION} code that
   * {@code personal-inventory.html} maps to an inline toast when a required field is missing on an
   * AJAX write, so the create/edit modal surfaces the error without a navigation.
   *
   * @return a {@code 422} {@code problem+json} response
   */
  private static ResponseEntity<Object> validationProblem() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", 422);
    body.put("code", "VALIDATION");
    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  /**
   * AJAX endpoint backing the KRT-styled UEX location typeahead. The frontend module deliberately
   * proxies through itself rather than letting the browser hit the backend directly so that the
   * same Spring Security session and CSRF policy apply.
   */
  @GetMapping("/uex-search")
  @ResponseBody
  public List<UexLocationDto> uexSearch(
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    try {
      String query = q == null ? "" : q;
      int effectiveLimit = limit == null ? 25 : Math.min(2000, Math.max(1, limit));
      String uri =
          "/api/v1/uex/locations/search?q="
              + URLEncoder.encode(query, StandardCharsets.UTF_8)
              + "&limit="
              + effectiveLimit;
      List<UexLocationDto> result = backendApiClient.get(uri, UEX_LOCATION_LIST_TYPE);
      return result == null ? Collections.emptyList() : result;
    } catch (Exception e) {
      log.warn("UEX location typeahead failed for query='{}': {}", q, e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * Populates the model attributes that the {@code personal-inventory} template needs to render the
   * listing under the modal: the filter query echoed into the search input, the fetched item list
   * and the page metadata used by the pagination fragment. Used both by the GET handler and by the
   * POST handlers when re-rendering after a validation error.
   */
  private void populateListing(Model model, String q, Integer page, Integer size, String sort) {
    model.addAttribute("filterQuery", q == null ? "" : q);
    PageResponse<PersonalInventoryItemDto> items = fetchItems(q, page, size, sort);
    model.addAttribute("items", items != null ? items.content() : Collections.emptyList());
    model.addAttribute("page", items);
  }

  private PageResponse<PersonalInventoryItemDto> fetchItems(
      String q, Integer page, Integer size, String sort) {
    try {
      StringBuilder uri = new StringBuilder("/api/v1/personal-inventory?");
      if (page != null) {
        uri.append("page=").append(page).append('&');
      }
      uri.append("size=").append(size == null ? 50 : size);
      if (sort != null && !sort.isBlank()) {
        uri.append("&sort=").append(URLEncoder.encode(sort, StandardCharsets.UTF_8));
      }
      if (q != null && !q.isBlank()) {
        uri.append("&q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
      }
      return backendApiClient.get(uri.toString(), PERSONAL_INVENTORY_ITEM_PAGE_TYPE);
    } catch (Exception e) {
      log.error("Failed to fetch personal inventory items", e);
      return new PageResponse<>(new ArrayList<>(), 0, size == null ? 50 : size, 0, 0, List.of());
    }
  }

  /**
   * Maps backend service exceptions into specific user-facing toast keys, falling back to the
   * supplied generic key for any error other than a 409 optimistic-locking conflict (the only case
   * worth distinguishing for the user).
   */
  private String classifyError(Exception e, String defaultKey) {
    if (e instanceof de.greluc.krt.profit.basetool.frontend.service.BackendServiceException bse
        && bse.getStatusCode() == 409) {
      return "personalInventory.error.conflict";
    }
    return defaultKey;
  }
}
