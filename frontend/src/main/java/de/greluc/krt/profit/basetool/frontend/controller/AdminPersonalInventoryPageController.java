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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.PersonalInventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.RelayParams;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Admin counterpart of {@link PersonalInventoryPageController}: manages a selected user's personal
 * inventory, with an "ADMIN MODE" banner. ADMIN only.
 */
@Controller
@UsesLayoutModel
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
   * Renders the admin personal-inventory page: the user picker and, with {@code userSub}, that
   * user's inventory under the "ADMIN MODE" banner.
   *
   * <p>A {@code userSub} that is not a UUID selects no member (REQ-SEC-051).
   *
   * @param userSub Keycloak {@code sub} of the user whose inventory to show, or {@code null}
   * @param q optional free-text filter
   * @param page zero-based page index
   * @param size page size, defaults to 50
   * @param sort optional sort spec; ignored unless well-formed
   * @param fragment {@code "results"} to render only the item list (REQ-FE-002)
   * @param model Thymeleaf model populated with items, page metadata and the admin banner
   * @return the {@code admin/personal-inventory} view name, or its {@code results} fragment
   */
  @NotNull
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
    UUID selectedSub = RelayParams.uuidOrNull(userSub);

    model.addAttribute("selectedUserSub", selectedSub);
    model.addAttribute("filterQuery", q == null ? "" : q);
    model.addAttribute("adminMode", Boolean.TRUE);

    if (selectedSub != null) {
      PageResponse<PersonalInventoryItemDto> items = fetchItems(selectedSub, q, page, size, sort);
      model.addAttribute("items", items != null ? items.content() : Collections.emptyList());
      model.addAttribute("page", items);
      if (!isFragment) {
        model.addAttribute("selectedUser", fetchUser(selectedSub));
      }
    } else {
      model.addAttribute("items", Collections.emptyList());
    }

    return isFragment ? "admin/personal-inventory :: results" : "admin/personal-inventory";
  }

  /**
   * Creates a personal-inventory item for the target user; validation errors re-render the page
   * with the modal open.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param form form-bound DTO
   * @param bindingResult validation errors, passed to the view through the model
   * @param model Thymeleaf model used for the inline re-render
   * @param redirectAttributes flash attributes carrier
   * @return the inline {@code admin/personal-inventory} view on validation failure, otherwise a
   *     redirect to the admin list
   */
  @NotNull
  @PostMapping("/{userSub}/add")
  public String add(
      @PathVariable @NotNull UUID userSub,
      @Valid @ModelAttribute("personalInventoryForm") PersonalInventoryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      return renderWithModal(userSub, "/admin/personal-inventory/" + userSub + "/add", model);
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
   * Updates a target user's personal-inventory item; a 409 surfaces as the optimistic-lock toast
   * via {@link #classifyError}.
   *
   * @param userSub target user's Keycloak {@code sub}, used for the redirect
   * @param id inventory item id
   * @param form form-bound DTO
   * @param bindingResult validation errors, passed to the view through the model
   * @param model Thymeleaf model used for the inline re-render
   * @param redirectAttributes flash attributes carrier
   * @return the inline {@code admin/personal-inventory} view on validation failure, otherwise a
   *     redirect to the admin list
   */
  @NotNull
  @PostMapping("/{userSub}/{id}/update")
  public String update(
      @PathVariable @NotNull UUID userSub,
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("personalInventoryForm") PersonalInventoryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      return renderWithModal(
          userSub, "/admin/personal-inventory/" + userSub + "/" + id + "/update", model);
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
  @NotNull
  @PostMapping("/{userSub}/{id}/delete")
  public String delete(
      @PathVariable @NotNull UUID userSub,
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

  /**
   * Re-renders the target user's admin page with the item modal open after a failed validation; the
   * submitted form and its errors are already in {@code model}.
   *
   * @param userSub the target user, whose inventory the page lists
   * @param modalAction the URL the re-opened modal posts to
   * @param model the request's model, holding the form and its errors
   * @return the {@code admin/personal-inventory} view name
   */
  @NotNull
  private String renderWithModal(
      @NotNull UUID userSub, @NotNull String modalAction, @NotNull Model model) {
    model.addAttribute("showItemModal", true);
    model.addAttribute("modalAction", modalAction);
    return view(userSub.toString(), null, null, null, null, null, model);
  }

  @NotNull
  private String redirectToList(UUID userSub) {
    return "redirect:/admin/personal-inventory?userSub=" + userSub;
  }

  /**
   * Resolves the selected member for the picker's seed option.
   *
   * @param userSub the selected member's Keycloak {@code sub}; never {@code null} here.
   * @return the member DTO, or {@code null} when the lookup fails.
   */
  @Nullable
  private UserDto fetchUser(UUID userSub) {
    try {
      return backendApiClient.get("/api/v1/users/" + userSub, UserDto.class);
    } catch (Exception e) {
      log.warn(
          "Failed to fetch selected member {} for admin personal inventory picker", userSub, e);
      return null;
    }
  }

  private PageResponse<PersonalInventoryItemDto> fetchItems(
      UUID userSub, String q, Integer page, Integer size, String sort) {
    try {
      StringBuilder uri =
          new StringBuilder("/api/v1/admin/personal-inventory/").append(userSub).append('?');
      if (page != null) {
        uri.append("page=").append(page).append('&');
      }
      uri.append("size=").append(size == null ? 50 : size);
      String safeSort = RelayParams.sortSpecOrNull(sort);
      if (safeSort != null) {
        uri.append("&sort=").append(safeSort);
      }
      if (q != null && !q.isBlank()) {
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
