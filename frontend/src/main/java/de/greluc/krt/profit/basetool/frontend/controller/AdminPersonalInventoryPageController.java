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

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.PersonalInventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin counterpart of {@link PersonalInventoryPageController}: lets administrators pick a target
 * user from the squadron member list and manage that user's personal inventory. Authorization is
 * enforced by {@code @PreAuthorize("hasRole('ADMIN')")} at the class level; the GET handler
 * additionally exposes the explicit "ADMIN MODE" banner flag to the template so the visual
 * distinction is unambiguous.
 */
@Controller
@RequestMapping("/admin/personal-inventory")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminPersonalInventoryPageController {

  private final BackendApiClient backendApiClient;

  /** Response type for one paginated page of a target user's personal-inventory items. */
  private static final ParameterizedTypeReference<PageResponse<PersonalInventoryItemDto>>
      PERSONAL_INVENTORY_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /**
   * Renders the admin personal-inventory page.
   *
   * <p>Without a {@code userSub} selected the page shows the user picker and an empty item list.
   * With {@code userSub} the picker pre-selects that user and the item table shows their inventory.
   * {@code adminMode=true} drives the "ADMIN MODE" banner in the template so admins are visually
   * reminded they are looking at someone else's data.
   *
   * @param userSub Keycloak {@code sub} of the user whose inventory to show, or {@code null}
   * @param q optional free-text filter
   * @param page zero-based page index
   * @param size page size, defaults to 50
   * @param sort optional sort spec
   * @param fragment when {@code "results"} only the item-list fragment is rendered (AJAX member-
   *     select / filter swap, REQ-FE-002); the member dropdown sits outside the swap target, so the
   *     user list is then not fetched. Otherwise the full page is returned
   * @param model Thymeleaf model populated with users, items, page metadata and the admin banner
   * @return the {@code admin/personal-inventory} view name, or its {@code results} fragment for an
   *     AJAX swap
   */
  @GetMapping
  public String view(
      @RequestParam(required = false) String userSub,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("personalInventoryForm")) {
      model.addAttribute("personalInventoryForm", new PersonalInventoryForm());
    }
    final boolean isFragment = "results".equals(fragment);

    model.addAttribute("selectedUserSub", userSub);
    model.addAttribute("filterQuery", q == null ? "" : q);
    model.addAttribute("adminMode", Boolean.TRUE);

    if (userSub != null && !userSub.isBlank()) {
      PageResponse<PersonalInventoryItemDto> items = fetchItems(userSub, q, page, size, sort);
      model.addAttribute("items", items != null ? items.content() : Collections.emptyList());
      model.addAttribute("page", items);
      // The member picker is now a server-side searchable combobox (remote-users, #1193): instead
      // of
      // preloading the whole roster, seed only the selected member's option (edit-mode label) via a
      // single lookup. Lives OUTSIDE the AJAX swap target, so a results-fragment swap skips it.
      if (!isFragment) {
        model.addAttribute("selectedUser", fetchUser(userSub));
      }
    } else {
      model.addAttribute("items", Collections.emptyList());
    }

    return isFragment ? "admin/personal-inventory :: results" : "admin/personal-inventory";
  }

  /**
   * Creates a personal-inventory item for the target user. Unlike its non-admin counterpart this
   * handler is allowed to push the BindingResult through the redirect flash — the create flow here
   * always redirects back through {@link #redirectToList} so the modal state has to survive the
   * redirect.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param form form-bound DTO
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin list (with optional form + binding-result flash)
   */
  @PostMapping("/{userSub}/add")
  public String add(
      @PathVariable @NotNull String userSub,
      @Valid @ModelAttribute("personalInventoryForm") PersonalInventoryForm form,
      BindingResult bindingResult,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute(
          "org.springframework.validation.BindingResult.personalInventoryForm", bindingResult);
      redirectAttributes.addFlashAttribute("personalInventoryForm", form);
      redirectAttributes.addFlashAttribute("showItemModal", true);
      redirectAttributes.addFlashAttribute(
          "modalAction", "/admin/personal-inventory/" + userSub + "/add");
      return redirectToList(userSub);
    }

    try {
      PersonalInventoryItemCreateRequest request =
          new PersonalInventoryItemCreateRequest(
              form.getName(),
              form.getNote(),
              form.getLocationUexId(),
              form.getLocationType(),
              form.getQuantity());
      backendApiClient.post(
          "/api/v1/admin/personal-inventory/" + userSub, request, PersonalInventoryItemDto.class);
      redirectAttributes.addFlashAttribute("successToast", "personalInventory.toast.created");
    } catch (Exception e) {
      log.error("Admin failed to create personal inventory item for {}", userSub, e);
      redirectAttributes.addFlashAttribute("errorToast", "personalInventory.error.create");
      redirectAttributes.addFlashAttribute("personalInventoryForm", form);
    }
    return redirectToList(userSub);
  }

  /**
   * Updates a target user's personal-inventory item. A 409 surfaces as a dedicated optimistic-lock
   * toast via {@link #classifyError}.
   *
   * @param userSub target user's Keycloak {@code sub} (used only for the redirect target)
   * @param id inventory item id
   * @param form form-bound DTO
   * @param bindingResult validation errors carrier
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin list
   */
  @PostMapping("/{userSub}/{id}/update")
  public String update(
      @PathVariable @NotNull String userSub,
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("personalInventoryForm") PersonalInventoryForm form,
      BindingResult bindingResult,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      redirectAttributes.addFlashAttribute(
          "org.springframework.validation.BindingResult.personalInventoryForm", bindingResult);
      redirectAttributes.addFlashAttribute("personalInventoryForm", form);
      redirectAttributes.addFlashAttribute("showItemModal", true);
      redirectAttributes.addFlashAttribute(
          "modalAction", "/admin/personal-inventory/" + userSub + "/" + id + "/update");
      return redirectToList(userSub);
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
          "/api/v1/admin/personal-inventory/" + id, request, PersonalInventoryItemDto.class);
      redirectAttributes.addFlashAttribute("successToast", "personalInventory.toast.updated");
    } catch (Exception e) {
      log.error("Admin failed to update personal inventory item {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.error.update"));
    }
    return redirectToList(userSub);
  }

  /**
   * Deletes a target user's personal-inventory item.
   *
   * @param userSub target user's Keycloak {@code sub} (used only for the redirect target)
   * @param id inventory item id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin list
   */
  @PostMapping("/{userSub}/{id}/delete")
  public String delete(
      @PathVariable @NotNull String userSub,
      @PathVariable @NotNull UUID id,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/admin/personal-inventory/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "personalInventory.toast.deleted");
    } catch (Exception e) {
      log.error("Admin failed to delete personal inventory item {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.error.delete"));
    }
    return redirectToList(userSub);
  }

  private String redirectToList(String userSub) {
    return "redirect:/admin/personal-inventory?userSub="
        + URLEncoder.encode(userSub, StandardCharsets.UTF_8);
  }

  /**
   * Resolves a single member for the picker's edit-mode seed (#1193): the picker now searches
   * server-side rather than preloading the roster, so only the currently-selected member's option
   * is rendered and needs its display name. Returns {@code null} on any failure (a malformed sub is
   * rejected by the backend {@code UUID} binding), leaving the picker with just its placeholder.
   *
   * @param userSub the selected member's Keycloak {@code sub}; never {@code null}/blank here.
   * @return the member DTO for the seed option, or {@code null} when the lookup fails.
   */
  private UserDto fetchUser(String userSub) {
    try {
      return backendApiClient.get(
          "/api/v1/users/" + URLEncoder.encode(userSub, StandardCharsets.UTF_8), UserDto.class);
    } catch (Exception e) {
      // REQ-OBS-004: log the id only, never the resolved name.
      log.warn(
          "Failed to fetch selected member {} for admin personal inventory picker", userSub, e);
      return null;
    }
  }

  private PageResponse<PersonalInventoryItemDto> fetchItems(
      String userSub, String q, Integer page, Integer size, String sort) {
    try {
      StringBuilder uri =
          new StringBuilder("/api/v1/admin/personal-inventory/")
              .append(URLEncoder.encode(userSub, StandardCharsets.UTF_8))
              .append('?');
      if (page != null) {
        uri.append("page=").append(page).append('&');
      }
      uri.append("size=").append(size == null ? 50 : size);
      if (sort != null && !sort.isBlank()) {
        uri.append("&sort=").append(URLEncoder.encode(sort, StandardCharsets.UTF_8));
      }
      if (q != null && !q.isBlank()) {
        // Free-text term as a WebClient URI-template variable so it is percent-encoded exactly
        // once across the frontend->backend hop; URLEncoder form-encoding (space -> '+')
        // double-encodes umlauts / reserved chars when re-encoded on the hop, yielding zero
        // matches.
        uri.append("&q={q}");
        return backendApiClient.get(uri.toString(), PERSONAL_INVENTORY_PAGE_TYPE, q);
      }
      return backendApiClient.get(uri.toString(), PERSONAL_INVENTORY_PAGE_TYPE);
    } catch (Exception e) {
      log.error("Failed to fetch personal inventory items for {}", userSub, e);
      return new PageResponse<>(new ArrayList<>(), 0, size == null ? 50 : size, 0, 0, List.of());
    }
  }

  private String classifyError(Exception e, String defaultKey) {
    if (e instanceof BackendServiceException bse && bse.getStatusCode() == 409) {
      return "personalInventory.error.conflict";
    }
    return defaultKey;
  }
}
