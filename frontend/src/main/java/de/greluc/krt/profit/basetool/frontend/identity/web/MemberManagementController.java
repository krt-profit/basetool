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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.bank.model.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.MemberEditForm;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserAttributesUpdateDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserRsiHandleResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserSyncResultDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Controller for the member-management pages ({@code /members}): list, search and edit members,
 * including up to two Staffeln with per-Staffel Logistician and Mission-Manager flags (REQ-ORG-017,
 * REQ-SEC-005).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/members")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class MemberManagementController {

  /** Reads and writes the members on the backend. */
  private final IdentityBackendClient identityClient;

  private final MessageSource messageSource;

  /**
   * Publishes live-sync pokes to the {@code members} room after a member edit, delete or Keycloak
   * sync (REQ-FE-015).
   */
  private final LiveSyncLocalBus liveSyncLocalBus;

  /** The single {@code roster} section of the global {@code members} room a mutation pokes. */
  private static final List<String> MEMBERS_ROSTER_SECTION = List.of("roster");

  /**
   * Renders the member list, optionally filtered by free-text search and paginated.
   *
   * @param search optional search query; switches to {@code /users/search}
   * @param page zero-based page index
   * @param size page size
   * @param fragment {@code "results"} renders only the results and pagination fragment; otherwise
   *     the full page
   * @param model Thymeleaf model populated with users, page metadata and the echoed search query
   * @return the {@code members} view name, or its {@code membersTableFragment} selector
   */
  @NotNull
  @GetMapping
  public String listMembers(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    try {
      boolean hasSearch = search != null && !search.isBlank();
      PageResponse<UserDto> pageResponse =
          hasSearch
              ? identityClient.memberSearchPage(page, size, search)
              : identityClient.memberPage(page, size);
      List<UserDto> users = pageResponse == null ? null : pageResponse.content();
      model.addAttribute("users", users);
      model.addAttribute("usersPage", pageResponse);
      model.addAttribute("search", search);

      Map<UUID, List<OrgUnitMembershipOptionDto>> skMemberships = new HashMap<>();
      if (users != null) {
        for (UserDto u : users) {
          if (u == null || u.id() == null) {
            continue;
          }
          try {
            List<OrgUnitMembershipOptionDto> all = identityClient.memberships(u.id());
            if (all == null) {
              skMemberships.put(u.id(), Collections.emptyList());
              continue;
            }
            skMemberships.put(
                u.id(), all.stream().filter(m -> "SPECIAL_COMMAND".equals(m.kind())).toList());
          } catch (Exception ex) {
            log.debug("Failed to load SK memberships for member-list row userId={}", u.id(), ex);
            skMemberships.put(u.id(), Collections.emptyList());
          }
        }
      }
      model.addAttribute("userSkMemberships", skMemberships);
    } catch (Exception e) {
      log.error("Could not fetch members", e);
      model.addAttribute("error", "error.members.load");
    }
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "members :: membersTableFragment";
    }
    return "members";
  }

  /**
   * AJAX search endpoint backing the member-picker typeahead. Returns the unwrapped content list
   * (single hard-coded page of size 1000 — autocompletes are short, one page is enough).
   *
   * @param query free-text query forwarded to the backend
   * @return matching users or {@code null} when the backend returns no page
   */
  @Nullable
  @GetMapping("/api/search")
  @ResponseBody
  public List<UserDto> searchMembers(@RequestParam String query) {
    PageResponse<UserDto> page = identityClient.memberTypeahead(query);
    return page == null ? null : page.content();
  }

  /**
   * Renders the member edit page; a form already in the model from a failed validation is kept.
   *
   * @param id user id
   * @param source optional origin marker ({@code "profile"} returns to the profile after saving)
   * @param model Thymeleaf model populated with {@code user} and {@code memberEditForm}
   * @param redirectAttributes flash attributes carrier for the error redirect
   * @return inline {@code member-edit} view, or redirect to {@code /members} on backend failure
   */
  @NotNull
  @GetMapping("/{id}/edit")
  public String editMember(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String source,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      UserDto user = identityClient.user(id);
      model.addAttribute("user", user);

      String memberRsiHandle = null;
      try {
        UserRsiHandleResponse handle = identityClient.userRsiHandle(id);
        if (handle != null && handle.rsiHandle() != null) {
          memberRsiHandle = handle.rsiHandle();
        }
      } catch (Exception ex) {
        log.debug("Failed to load the RSI handle for the member-edit page", ex);
      }
      model.addAttribute("memberRsiHandle", memberRsiHandle);

      try {
        List<OrgUnitMembershipOptionDto> memberships = identityClient.memberships(id);
        model.addAttribute(
            "memberMemberships",
            memberships != null
                ? memberships
                : Collections.<OrgUnitMembershipOptionDto>emptyList());
      } catch (Exception ex) {
        log.debug("Failed to load memberships for member-edit panel", ex);
        model.addAttribute(
            "memberMemberships", Collections.<OrgUnitMembershipOptionDto>emptyList());
      }

      List<OrgUnitMembershipDto> staffelRows = List.of();
      boolean staffelDetailLoaded = false;
      try {
        MembershipDeltaResponse detail = identityClient.membershipDetail(id);
        if (detail != null && detail.memberships() != null) {
          staffelRows =
              detail.memberships().stream().filter(m -> m.kind() == OrgUnitKind.SQUADRON).toList();
          staffelDetailLoaded = true;
        }
      } catch (Exception ex) {
        log.debug("Failed to load membership detail for member-edit Staffel slots", ex);
      }

      if (!model.containsAttribute("memberEditForm")) {
        OrgUnitMembershipDto slot1 = staffelRows.size() > 0 ? staffelRows.get(0) : null;
        OrgUnitMembershipDto slot2 = staffelRows.size() > 1 ? staffelRows.get(1) : null;
        model.addAttribute(
            "memberEditForm",
            new MemberEditForm(
                user.rank(),
                user.description(),
                user.displayName(),
                user.version(),
                source,
                user.joinDate(),
                slot1 != null ? slot1.orgUnitId() : null,
                slot1 != null ? slot1.isLogistician() : null,
                slot1 != null ? slot1.isMissionManager() : null,
                slot2 != null ? slot2.orgUnitId() : null,
                slot2 != null ? slot2.isLogistician() : null,
                slot2 != null ? slot2.isMissionManager() : null,
                staffelDetailLoaded));
      } else {
        MemberEditForm form = (MemberEditForm) model.getAttribute("memberEditForm");
        if (form != null && form.source() == null) {
          model.addAttribute(
              "memberEditForm",
              new MemberEditForm(
                  form.rank(),
                  form.description(),
                  form.displayName(),
                  form.version(),
                  source,
                  form.joinDate(),
                  form.staffel1Id(),
                  form.staffel1Logistician(),
                  form.staffel1MissionManager(),
                  form.staffel2Id(),
                  form.staffel2Logistician(),
                  form.staffel2MissionManager(),
                  form.staffelDetailLoaded()));
        }
      }
      return "member-edit";
    } catch (Exception e) {
      log.error("Could not fetch member details", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.member.details.load");
      return "redirect:/members";
    }
  }

  /**
   * Saves member edits; a validation failure re-renders the form, success redirects to the profile
   * for {@code source=profile} and to the member list otherwise.
   *
   * @param id user id
   * @param form member edit form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return redirect target depending on source and outcome
   */
  @NotNull
  @PostMapping("/{id}/edit")
  public String updateMember(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("memberEditForm") MemberEditForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      return editMember(id, form.source(), model, redirectAttributes);
    }
    try {
      applyMemberUpdate(id, form);
      liveSyncLocalBus.publish("members", MEMBERS_ROSTER_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
      if ("profile".equals(form.source())) {
        return "redirect:/profile";
      }
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "updateMember", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.member.update.failed");
      return "redirect:/members/"
          + id
          + "/edit"
          + (form.source() != null ? "?source=" + form.source() : "");
    } catch (Exception e) {
      log.error("Update failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.member.update.failed");
      return "redirect:/members/"
          + id
          + "/edit"
          + (form.source() != null ? "?source=" + form.source() : "");
    }
    return "redirect:/members";
  }

  /**
   * AJAX variant of {@link #updateMember}, selected by {@code X-Requested-With: XMLHttpRequest},
   * answering with JSON instead of a redirect (REQ-FE-007).
   *
   * @param id user id
   * @param form member edit form bound from the multipart/form-encoded body
   * @param bindingResult validation-errors carrier
   * @param locale request locale used to resolve field-error messages
   * @return {@code 200 {version}} on success; {@code 422 {field: message}} on validation failure;
   *     otherwise the relayed backend error status with {@code {code, detail}}
   */
  @PostMapping(value = "/{id}/edit", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> updateMemberAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("memberEditForm") MemberEditForm form,
      BindingResult bindingResult,
      Locale locale) {
    if (bindingResult.hasErrors()) {
      Map<String, Object> errors = new LinkedHashMap<>();
      for (FieldError fieldError : bindingResult.getFieldErrors()) {
        errors.putIfAbsent(fieldError.getField(), resolveFieldMessage(fieldError, locale));
      }
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
          .contentType(MediaType.APPLICATION_JSON)
          .body(errors);
    }
    try {
      applyMemberUpdate(id, form);
      liveSyncLocalBus.publish("members", MEMBERS_ROSTER_SECTION);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("version", currentUserVersion(id, form.version()));
      return ResponseEntity.ok(body);
    } catch (BackendServiceException e) {
      return relayBackendError("AJAX member update failed", e);
    } catch (Exception e) {
      log.error("AJAX member update failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("code", "INTERNAL_ERROR"));
    }
  }

  /**
   * Saves a member's attributes and then sends the complete Staffel set as one membership-delta
   * PATCH. The PATCH is skipped when the Staffel detail did not load, so memberships are never
   * cleared by accident (REQ-ORG-017).
   *
   * @param id user id
   * @param form the validated member edit form
   */
  private void applyMemberUpdate(@NotNull UUID id, @NotNull MemberEditForm form) {
    UserAttributesUpdateDto body =
        new UserAttributesUpdateDto(
            form.rank(), form.description(), form.displayName(), form.version(), form.joinDate());
    identityClient.updateAttributes(id, body);

    if (!Boolean.TRUE.equals(form.staffelDetailLoaded())) {
      return;
    }

    List<MembershipDeltaRequest.StaffelChange> staffeln = new ArrayList<>();
    if (form.staffel1Id() != null) {
      staffeln.add(
          new MembershipDeltaRequest.StaffelChange(
              form.staffel1Id(), form.staffel1Logistician(), form.staffel1MissionManager()));
    }
    if (form.staffel2Id() != null && !form.staffel2Id().equals(form.staffel1Id())) {
      staffeln.add(
          new MembershipDeltaRequest.StaffelChange(
              form.staffel2Id(), form.staffel2Logistician(), form.staffel2MissionManager()));
    }
    identityClient.updateMemberships(id, new MembershipDeltaRequest(staffeln, null));
  }

  /**
   * Reads the user row's current version after a write, falling back to {@code priorVersion + 1}.
   *
   * @param id user id
   * @param priorVersion the version the client submitted (may be {@code null})
   * @return the current user-row version, or a best-effort {@code priorVersion + 1}
   */
  private Long currentUserVersion(@NotNull UUID id, Long priorVersion) {
    try {
      UserDto user = identityClient.user(id);
      if (user != null && user.version() != null) {
        return user.version();
      }
    } catch (Exception _) {
    }
    return (priorVersion == null ? 0L : priorVersion) + 1;
  }

  /**
   * Resolves a field error's message for the request locale, falling back to its default message.
   *
   * @param fieldError the validation error to render
   * @param locale the request locale
   * @return the localized message, or the field error's default message as a fallback
   */
  private String resolveFieldMessage(FieldError fieldError, Locale locale) {
    try {
      return messageSource.getMessage(fieldError, locale);
    } catch (NoSuchMessageException _) {
      return fieldError.getDefaultMessage();
    }
  }

  /**
   * Logs a backend AJAX failure and relays it as {@code application/problem+json} via {@link
   * de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses}.
   *
   * @param logMessage context for the warn log line
   * @param e the backend service exception carrying the relayed status, problem code and detail
   * @return the relayed error as a {@code problem+json} response mirroring the backend status
   */
  private ResponseEntity<Object> relayBackendError(
      String logMessage, @NotNull BackendServiceException e) {
    log.warn("{}: status={}, code={}", logMessage, e.getStatusCode(), e.getProblemCode());
    return propagateBackendError(e);
  }

  /**
   * Deletes a user; admin-only.
   *
   * @param id user id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /members}
   */
  @NotNull
  @PostMapping("/{id}/delete")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String deleteMember(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    try {
      identityClient.deleteUser(id);
      liveSyncLocalBus.publish("members", MEMBERS_ROSTER_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "success.user.delete");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "deleteMember", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.user.delete");
    } catch (Exception e) {
      log.error("Delete failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.user.delete");
    }
    return "redirect:/members";
  }

  /**
   * AJAX variant of {@link #deleteMember}: deletes the user and answers with JSON; admin-only.
   *
   * @param id user id
   * @return {@code 200} on success, or the relayed backend error status with {@code {code, detail}}
   */
  @DeleteMapping("/{id}")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> deleteMemberAjax(@PathVariable @NotNull UUID id) {
    try {
      identityClient.deleteUser(id);
      liveSyncLocalBus.publish("members", MEMBERS_ROSTER_SECTION);
      return ResponseEntity.ok(Map.of());
    } catch (BackendServiceException e) {
      return relayBackendError("AJAX member delete failed", e);
    } catch (Exception e) {
      log.error("AJAX member delete failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("code", "INTERNAL_ERROR"));
    }
  }

  /**
   * Triggers the backend's manual Keycloak user sync and answers with JSON; admin-only.
   *
   * @return {@code 200} with {@code {syncedCount}} on success, or the relayed backend error status
   */
  @PostMapping("/sync")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> syncMembersAjax() {
    try {
      UserSyncResultDto result = identityClient.syncUsers();
      int syncedCount = result != null ? result.syncedCount() : 0;
      liveSyncLocalBus.publish("members", MEMBERS_ROSTER_SECTION);
      return ResponseEntity.ok(Map.of("syncedCount", syncedCount));
    } catch (BackendServiceException e) {
      return relayBackendError("AJAX member sync failed", e);
    } catch (Exception e) {
      log.error("AJAX member sync failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("code", "INTERNAL_ERROR"));
    }
  }

  /**
   * Merges the duplicate account in the path into the chosen surviving account and answers with
   * JSON; admin-only (REQ-SEC-055).
   *
   * @param id the duplicate account to dissolve
   * @param body the surviving account's id and the duplicate's optimistic-lock version
   * @return {@code 200} with the surviving account, or the relayed backend error status
   */
  @PostMapping("/{id}/consolidate")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> consolidateMemberAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) ConsolidateAccountRequest body) {
    try {
      UserDto survivor = identityClient.consolidate(id, body);
      liveSyncLocalBus.publish("members", MEMBERS_ROSTER_SECTION);
      return ResponseEntity.ok(survivor);
    } catch (BackendServiceException e) {
      return relayBackendError("AJAX member consolidate failed", e);
    } catch (Exception e) {
      log.error("AJAX member consolidate failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("code", "INTERNAL_ERROR"));
    }
  }
}
