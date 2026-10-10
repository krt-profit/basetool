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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.blueprint.client.BlueprintBackendClient;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBulkDeleteResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.RelayParams;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin counterpart of {@link PersonalInventoryBlueprintsPageController}: manages a selected user's
 * owned blueprints (list, multi-select add, note edit, remove, import) via {@code
 * /api/v1/personal-blueprints/admin/...}. ADMIN only.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/personal-blueprints")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminPersonalBlueprintsPageController {

  /** Page size for the owned-blueprint list — one row per product. */
  private static final int PAGE_SIZE = 200;

  /** Sends the member's blueprint requests. */
  private final BlueprintBackendClient blueprintClient;

  /**
   * Renders the admin Blueprints page: a user picker and, once a user is selected, their owned
   * blueprints with the add and import controls.
   *
   * <p>A {@code userSub} that is not a UUID selects no member (REQ-SEC-051).
   *
   * @param userSub target user's Keycloak {@code sub}, or {@code null} for the bare picker
   * @param q optional case-insensitive product-name filter
   * @param fragment {@code "results"} to render only the owned-blueprint table (REQ-FE-002)
   * @param model Thymeleaf model populated with the selection and the blueprint list
   * @return the {@code admin/personal-blueprints} view name, or its {@code results} fragment
   */
  @NotNull
  @GetMapping
  public String view(
      @RequestParam(required = false) String userSub,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean isFragment = "results".equals(fragment);
    UUID selectedSub = RelayParams.uuidOrNull(userSub);
    if (!isFragment && selectedSub != null) {
      model.addAttribute("selectedUser", fetchUser(selectedSub));
    }
    model.addAttribute("selectedUserSub", selectedSub);
    model.addAttribute("filterQuery", q == null ? "" : q);
    model.addAttribute("adminMode", Boolean.TRUE);

    if (selectedSub != null) {
      PageResponse<PersonalBlueprintDto> blueprints = fetchOwned(selectedSub, q);
      model.addAttribute(
          "blueprints", blueprints != null ? blueprints.content() : Collections.emptyList());
    } else {
      model.addAttribute("blueprints", Collections.emptyList());
    }
    return isFragment ? "admin/personal-blueprints :: results" : "admin/personal-blueprints";
  }

  /**
   * Multi-select batch add on behalf of the target user. Relays the staged keys to the admin batch
   * endpoint.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param productKeys the staged product keys
   * @return the batch result, or a zeroed result on backend failure
   */
  @PostMapping("/{userSub}/add-selected")
  @ResponseBody
  public PersonalBlueprintBatchResultDto addSelected(
      @PathVariable UUID userSub, @RequestBody List<String> productKeys) {
    List<String> keys = productKeys == null ? List.of() : productKeys;
    if (keys.isEmpty()) {
      return new PersonalBlueprintBatchResultDto(0, 0, 0);
    }
    try {
      PersonalBlueprintBatchResultDto result =
          blueprintClient.addMemberOwned(userSub, new PersonalBlueprintBatchCreateRequest(keys));
      return result == null ? new PersonalBlueprintBatchResultDto(0, 0, 0) : result;
    } catch (Exception e) {
      log.error("Admin batch blueprint add failed for user {}", userSub, e);
      return new PersonalBlueprintBatchResultDto(0, 0, 0);
    }
  }

  /**
   * Updates a target user's owned blueprint note (preserving the acquisition timestamp).
   *
   * @param userSub target user's Keycloak {@code sub} (redirect target)
   * @param id blueprint entry id
   * @param note the new note text
   * @param acquiredAt the preserved acquisition instant (ISO-8601), or blank
   * @param version the last seen optimistic-lock version
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin Blueprints page for the user
   */
  @NotNull
  @PostMapping("/{userSub}/items/{id}/update-note")
  public String updateNote(
      @PathVariable UUID userSub,
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String note,
      @RequestParam(required = false) String acquiredAt,
      @RequestParam Long version,
      RedirectAttributes redirectAttributes) {
    try {
      blueprintClient.updateMemberOwned(
          id,
          new PersonalBlueprintUpdateRequest(
              parseInstantOrNull(acquiredAt), StringNormalization.blankToNull(note), version));
      redirectAttributes.addFlashAttribute(
          "successToast", "personalInventory.blueprints.toast.noteUpdated");
    } catch (Exception e) {
      log.error("Admin failed to update blueprint note {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.blueprints.error.update"));
    }
    return redirectToUser(userSub);
  }

  /**
   * Removes a target user's owned blueprint.
   *
   * @param userSub target user's Keycloak {@code sub} (redirect target)
   * @param id blueprint entry id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin Blueprints page for the user
   */
  @NotNull
  @PostMapping("/{userSub}/items/{id}/delete")
  public String delete(
      @PathVariable UUID userSub,
      @PathVariable @NotNull UUID id,
      RedirectAttributes redirectAttributes) {
    try {
      blueprintClient.deleteMemberOwned(id);
      redirectAttributes.addFlashAttribute(
          "successToast", "personalInventory.blueprints.toast.removed");
    } catch (Exception e) {
      log.error("Admin failed to remove blueprint {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.blueprints.error.remove"));
    }
    return redirectToUser(userSub);
  }

  /**
   * Previews a blueprint export import for the target user; a backend refusal is relayed as {@code
   * application/problem+json} with its status, code and localised detail.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param file the uploaded blueprint export JSON
   * @return the per-name resolution preview, or the relayed refusal
   * @throws ResponseStatusException 500 when the upload cannot be read
   */
  @PostMapping(value = "/{userSub}/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseBody
  public ResponseEntity<Object> previewImport(
      @PathVariable UUID userSub, @RequestParam("file") @NotNull MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      log.error("Admin import preview proxy: the upload could not be read", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import preview.");
    }
    String filename =
        file.getOriginalFilename() != null ? file.getOriginalFilename() : "blueprints.json";
    return relay(
        log,
        "Admin blueprint import preview for user " + userSub,
        () -> ResponseEntity.ok(blueprintClient.memberImportPreview(userSub, filename, bytes)));
  }

  /**
   * Applies reviewed blueprint-import resolutions on behalf of the target user; a backend refusal
   * is relayed with its status, code and localised detail.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param resolutions the per-name resolutions
   * @return the apply summary, the relayed refusal, or an empty {@code 500} on an unexpected error
   */
  @PostMapping("/{userSub}/import/apply")
  @ResponseBody
  public ResponseEntity<Object> applyImport(
      @PathVariable UUID userSub, @RequestBody List<BlueprintImportResolutionDto> resolutions) {
    List<BlueprintImportResolutionDto> list = resolutions == null ? List.of() : resolutions;
    return relay(
        log,
        "Admin blueprint import apply for user " + userSub,
        () -> {
          BlueprintImportResultDto result =
              blueprintClient.memberImportApply(userSub, new BlueprintImportApplyRequest(list));
          return ResponseEntity.ok(
              result == null ? new BlueprintImportResultDto(0, 0, 0, 0, 0) : result);
        });
  }

  /**
   * Clears the removable owned blueprints of all users (REQ-INV-024); auto-granted defaults are
   * kept. No-JS fallback that redirects with a success toast.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin Blueprints page
   */
  @NotNull
  @PostMapping("/delete-all-users")
  public String deleteAllUsers(RedirectAttributes redirectAttributes) {
    try {
      blueprintClient.deleteAllMembersOwned();
      redirectAttributes.addFlashAttribute(
          "successToast", "admin.personalInventory.blueprints.purge.toast.done");
    } catch (Exception e) {
      log.error("Admin failed to clear all users' blueprints", e);
      redirectAttributes.addFlashAttribute(
          "errorToast", "admin.personalInventory.blueprints.purge.error");
    }
    return "redirect:/admin/personal-blueprints";
  }

  /**
   * AJAX twin of {@link #deleteAllUsers} that returns the removed count as JSON (REQ-INV-024); a
   * backend failure is relayed as {@code problem+json}.
   *
   * @return {@code 200} with the removed count, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/delete-all-users", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteAllUsersAjax() {
    return relay(
        log,
        "clear all users' blueprints (ajax)",
        () -> {
          PersonalBlueprintBulkDeleteResultDto result = blueprintClient.deleteAllMembersOwned();
          return ResponseEntity.ok(
              result == null ? new PersonalBlueprintBulkDeleteResultDto(0) : result);
        });
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
      return blueprintClient.user(userSub);
    } catch (Exception e) {
      log.warn(
          "Failed to fetch selected member {} for admin personal blueprints picker", userSub, e);
      return null;
    }
  }

  /**
   * Fetches a target user's owned blueprints via the admin list endpoint.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param q optional case-insensitive product-name filter
   * @return the owned-blueprint page, or an empty page on failure
   */
  private PageResponse<PersonalBlueprintDto> fetchOwned(UUID userSub, String q) {
    try {
      return blueprintClient.memberOwnedPage(userSub, PAGE_SIZE, q);
    } catch (Exception e) {
      log.error("Failed to fetch owned blueprints for user {}", userSub, e);
      return new PageResponse<>(new ArrayList<>(), 0, PAGE_SIZE, 0, 0, List.of());
    }
  }

  /**
   * Builds the redirect URL back to the admin Blueprints page for a user.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @return the redirect view string
   */
  @NotNull
  private String redirectToUser(UUID userSub) {
    return "redirect:/admin/personal-blueprints?userSub=" + userSub;
  }

  /**
   * Parses an ISO-8601 instant, tolerating blank / invalid input (returns {@code null}).
   *
   * @param iso the ISO-8601 instant string, or blank
   * @return the parsed instant, or {@code null}
   */
  @Nullable
  private static Instant parseInstantOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso.trim());
    } catch (Exception _) {
      return null;
    }
  }

  /**
   * Maps a 409 conflict to the optimistic-lock toast, else the supplied generic key.
   *
   * @param e the caught exception
   * @param defaultKey the fallback toast key
   * @return the resolved toast key
   */
  private String classifyError(Exception e, String defaultKey) {
    if (e instanceof BackendServiceException bse && bse.getStatusCode() == 409) {
      return "personalInventory.blueprints.error.conflict";
    }
    return defaultKey;
  }
}
