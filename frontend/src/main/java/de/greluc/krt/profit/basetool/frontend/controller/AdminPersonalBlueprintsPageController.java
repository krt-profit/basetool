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

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBulkDeleteResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
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
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin counterpart of {@link PersonalInventoryBlueprintsPageController} (#327, Phase 7): lets
 * administrators pick a target user and manage that user's acquired blueprints — owned list,
 * multi-select add, note edit / remove, and the blueprint import — mirroring {@code
 * /admin/personal-inventory}. Everything proxies to the admin backend surface ({@code
 * /api/v1/admin/personal-blueprints/...}) with the target {@code sub} from the path; the product
 * type-ahead reuses the shared user search endpoint. {@code @PreAuthorize("hasRole('ADMIN')")}
 * gates the whole controller.
 */
@Controller
@RequestMapping("/admin/personal-blueprints")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminPersonalBlueprintsPageController {

  /** Page size for the owned-blueprint list — one row per product. */
  private static final int PAGE_SIZE = 200;

  /** Response type for the paged admin owned-blueprint list read. */
  private static final ParameterizedTypeReference<PageResponse<PersonalBlueprintDto>>
      PERSONAL_BLUEPRINT_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final WebClient webClient;

  /**
   * Renders the admin Blueprints page: a user picker plus, once a user is selected, that user's
   * owned-blueprint list with the add bar and import controls.
   *
   * @param userSub target user's Keycloak {@code sub}, or {@code null} for the bare picker
   * @param q optional case-insensitive product-name filter
   * @param fragment when {@code "results"} only the owned-blueprint table fragment is rendered
   *     (AJAX filter swap, REQ-FE-002); the member picker sits outside the swap target, so the user
   *     list is then not fetched. Otherwise the full page is returned
   * @param model Thymeleaf model populated with users, the selection and the blueprint list
   * @return the {@code admin/personal-blueprints} view name, or its {@code results} fragment for an
   *     AJAX swap
   */
  @GetMapping
  public String view(
      @RequestParam(required = false) String userSub,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean isFragment = "results".equals(fragment);
    // The member picker is now a server-side searchable combobox (remote-users, #1193): instead of
    // preloading the whole roster, seed only the selected member's option (edit-mode label) via a
    // single lookup. It lives OUTSIDE the swap target, so a same-user filter swap does not touch
    // it.
    if (!isFragment && userSub != null && !userSub.isBlank()) {
      model.addAttribute("selectedUser", fetchUser(userSub));
    }
    model.addAttribute("selectedUserSub", userSub);
    model.addAttribute("filterQuery", q == null ? "" : q);
    model.addAttribute("adminMode", Boolean.TRUE);

    if (userSub != null && !userSub.isBlank()) {
      PageResponse<PersonalBlueprintDto> blueprints = fetchOwned(userSub, q);
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
      @PathVariable String userSub, @RequestBody List<String> productKeys) {
    List<String> keys = productKeys == null ? List.of() : productKeys;
    if (keys.isEmpty()) {
      return new PersonalBlueprintBatchResultDto(0, 0, 0);
    }
    try {
      PersonalBlueprintBatchResultDto result =
          backendApiClient.post(
              "/api/v1/admin/personal-blueprints/" + enc(userSub) + "/batch",
              new PersonalBlueprintBatchCreateRequest(keys),
              PersonalBlueprintBatchResultDto.class);
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
  @PostMapping("/{userSub}/items/{id}/update-note")
  public String updateNote(
      @PathVariable String userSub,
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String note,
      @RequestParam(required = false) String acquiredAt,
      @RequestParam Long version,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.put(
          "/api/v1/admin/personal-blueprints/items/" + id,
          new PersonalBlueprintUpdateRequest(
              parseInstantOrNull(acquiredAt), StringNormalization.blankToNull(note), version),
          PersonalBlueprintDto.class);
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
  @PostMapping("/{userSub}/items/{id}/delete")
  public String delete(
      @PathVariable String userSub,
      @PathVariable @NotNull UUID id,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/admin/personal-blueprints/items/" + id, Void.class);
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
   * Previews a blueprint export import (SCMDB or Basetool BP Extractor) on behalf of the target
   * user (multipart upload forwarded via the authenticated WebClient). Backend parse failures (400)
   * propagate.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param file the uploaded blueprint export JSON
   * @return the per-name resolution preview
   */
  @PostMapping(value = "/{userSub}/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseBody
  public BlueprintImportPreviewDto previewImport(
      @PathVariable String userSub, @RequestParam("file") @NotNull MultipartFile file) {
    try {
      byte[] bytes = file.getBytes();
      String filename =
          file.getOriginalFilename() != null ? file.getOriginalFilename() : "blueprints.json";
      MultipartBodyBuilder builder = new MultipartBodyBuilder();
      builder
          .part(
              "file",
              new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                  return filename;
                }
              })
          .contentType(MediaType.APPLICATION_OCTET_STREAM);

      return webClient
          .post()
          .uri("/api/v1/admin/personal-blueprints/" + enc(userSub) + "/import/preview")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(BodyInserters.fromMultipartData(builder.build()))
          .retrieve()
          .bodyToMono(BlueprintImportPreviewDto.class)
          .block();
    } catch (WebClientResponseException e) {
      log.warn("Admin import preview proxy: backend {} — {}", e.getStatusCode(), e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Admin import preview proxy: unexpected error", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import preview.");
    }
  }

  /**
   * Applies reviewed blueprint-import resolutions on behalf of the target user.
   *
   * @param userSub target user's Keycloak {@code sub}
   * @param resolutions the per-name resolutions
   * @return the apply summary
   */
  @PostMapping("/{userSub}/import/apply")
  @ResponseBody
  public BlueprintImportResultDto applyImport(
      @PathVariable String userSub, @RequestBody List<BlueprintImportResolutionDto> resolutions) {
    List<BlueprintImportResolutionDto> list = resolutions == null ? List.of() : resolutions;
    try {
      BlueprintImportResultDto result =
          backendApiClient.post(
              "/api/v1/admin/personal-blueprints/" + enc(userSub) + "/import/apply",
              new BlueprintImportApplyRequest(list),
              BlueprintImportResultDto.class);
      return result == null ? new BlueprintImportResultDto(0, 0, 0, 0, 0) : result;
    } catch (Exception e) {
      log.error("Admin import apply proxy failed for user {}", userSub, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import apply.");
    }
  }

  /**
   * Admin global purge: clears the removable owned blueprints of ALL users (REQ-INV-024). Guarded
   * in the UI by a type-to-confirm danger modal. No-JS fallback: flashes a countless success toast
   * and redirects to the bare admin Blueprints page (the AJAX twin below shows the removed count).
   * Auto-granted defaults (REQ-INV-016) are preserved by the backend.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the admin Blueprints page
   */
  @PostMapping("/delete-all-users")
  public String deleteAllUsers(RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/admin/personal-blueprints", PersonalBlueprintBulkDeleteResultDto.class);
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
   * Header-gated AJAX twin of {@link #deleteAllUsers}: clears every user's removable owned
   * blueprints (REQ-INV-024) and returns the removed count as JSON so {@code
   * admin/personal-blueprints.html} toasts "{n} Blueprints entfernt" and re-renders the current
   * member's owned-list fragment in place (REQ-FE-002) instead of a full-page reload. A backend
   * failure is relayed as {@code problem+json}.
   *
   * @return {@code 200} with the removed count on success, or the relayed backend {@code
   *     problem+json}
   */
  @PostMapping(value = "/delete-all-users", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteAllUsersAjax() {
    try {
      PersonalBlueprintBulkDeleteResultDto result =
          backendApiClient.delete(
              "/api/v1/admin/personal-blueprints", PersonalBlueprintBulkDeleteResultDto.class);
      return ResponseEntity.ok(
          result == null ? new PersonalBlueprintBulkDeleteResultDto(0) : result);
    } catch (BackendServiceException e) {
      log.debug("Admin failed to clear all users' blueprints (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Admin failed to clear all users' blueprints (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
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
      return backendApiClient.get("/api/v1/users/" + enc(userSub), UserDto.class);
    } catch (Exception e) {
      // REQ-OBS-004: log the id only, never the resolved name.
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
  private PageResponse<PersonalBlueprintDto> fetchOwned(String userSub, String q) {
    try {
      StringBuilder uri =
          new StringBuilder("/api/v1/admin/personal-blueprints/")
              .append(enc(userSub))
              .append("?size=")
              .append(PAGE_SIZE)
              .append("&sort=productName,asc");
      if (q != null && !q.isBlank()) {
        // Free-text term as a WebClient URI-template variable so it is percent-encoded exactly once
        // across the frontend->backend hop (REQ-FE-016); enc(...) form-encoding (space -> '+')
        // double-encodes umlauts / reserved chars when WebClient's TEMPLATE_AND_VALUES mode
        // re-encodes the '%', yielding zero matches. The enc(...) wrapper is why this site was
        // missed by the URLEncoder.encode( sweep in PR #1347.
        uri.append("&q={q}");
        return backendApiClient.get(uri.toString(), PERSONAL_BLUEPRINT_PAGE_TYPE, q);
      }
      return backendApiClient.get(uri.toString(), PERSONAL_BLUEPRINT_PAGE_TYPE);
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
  private String redirectToUser(String userSub) {
    return "redirect:/admin/personal-blueprints?userSub=" + enc(userSub);
  }

  /**
   * URL-encodes a path/query segment.
   *
   * @param value the raw value
   * @return the UTF-8 URL-encoded value
   */
  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Parses an ISO-8601 instant, tolerating blank / invalid input (returns {@code null}).
   *
   * @param iso the ISO-8601 instant string, or blank
   * @return the parsed instant, or {@code null}
   */
  private static Instant parseInstantOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso.trim());
    } catch (Exception e) {
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
