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

import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserAttributesUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.MemberEditForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the squadron member-management pages ({@code /members}).
 *
 * <p>Lists, searches and edits squadron members. The member-edit page assigns up to two Staffeln
 * (REQ-ORG-017), each with its own Logistician / Mission-Manager flags (REQ-SEC-005); the flags are
 * persisted through the single membership-delta PATCH (see {@link #applyMemberUpdate}), not a
 * per-flag toggle. The flags are independent of Keycloak realm roles — the JWT converter promotes
 * them to {@code ROLE_LOGISTICIAN}/{@code ROLE_MISSION_MANAGER} on the next login.
 */
@Controller
@RequestMapping("/members")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class MemberManagementController {

  /** Response type for the paged {@code /users} and {@code /users/search} member listings. */
  private static final ParameterizedTypeReference<PageResponse<UserDto>> USER_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the {@code /users/{id}/memberships} org-unit membership-option list. */
  private static final ParameterizedTypeReference<
          List<de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto>>
      MEMBERSHIP_OPTION_LIST_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;

  /**
   * Renders the member list, optionally filtered by free-text search and paginated.
   *
   * @param search optional search query; switches the underlying endpoint from {@code /users} to
   *     {@code /users/search}
   * @param page zero-based page index
   * @param size page size
   * @param fragment when {@code "results"}, only the results+pagination fragment is rendered for an
   *     in-place AJAX swap (epic #571 / REQ-FE-005); otherwise the full page. A {@code String} (not
   *     {@code boolean}) so it binds the {@code krtFetch.swap} helper's {@code fragment=results}
   *     value — a {@code boolean} param cannot parse that and silently 400s the swap.
   * @param model Thymeleaf model populated with users, page metadata and the echoed search query
   * @return the {@code members} view name, or its {@code membersTableFragment} selector
   */
  @GetMapping
  public String listMembers(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    try {
      // L-1: build the URI via UriComponentsBuilder so query-param encoding is correct and a
      // crafted `search` cannot inject extra parameters (e.g. `foo&size=99999`).
      org.springframework.web.util.UriComponentsBuilder uriBuilder =
          (search == null || search.isBlank())
              ? org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/users")
              : org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/users/search")
                  .queryParam("query", search);
      if (page != null) {
        uriBuilder.queryParam("page", page);
      }
      if (size != null) {
        uriBuilder.queryParam("size", size);
      }
      uriBuilder.queryParam("sort", "username,asc");

      PageResponse<UserDto> pageResponse =
          backendApiClient.get(uriBuilder.toUriString(), USER_PAGE_TYPE);
      List<UserDto> users = pageResponse == null ? null : pageResponse.content();
      model.addAttribute("users", users);
      model.addAttribute("usersPage", pageResponse);
      model.addAttribute("search", search);

      // SPEZIALKOMMANDO_PLAN.md §7.5 — per-user SK shorthand list for the new column. N+1 is
      // bounded by the page size (default 25, max from /api/v1/users), and the admin list page is
      // rarely refreshed, so the round-trip cost is acceptable until a bulk endpoint lands.
      java.util.Map<
              UUID,
              List<de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto>>
          skMemberships = new java.util.HashMap<>();
      if (users != null) {
        for (UserDto u : users) {
          if (u == null || u.id() == null) {
            continue;
          }
          try {
            List<de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto> all =
                backendApiClient.get(
                    "/api/v1/users/" + u.id() + "/memberships", MEMBERSHIP_OPTION_LIST_TYPE);
            if (all == null) {
              skMemberships.put(u.id(), java.util.Collections.emptyList());
              continue;
            }
            skMemberships.put(
                u.id(), all.stream().filter(m -> "SPECIAL_COMMAND".equals(m.kind())).toList());
          } catch (Exception ex) {
            log.debug("Failed to load SK memberships for member-list row userId={}", u.id(), ex);
            skMemberships.put(u.id(), java.util.Collections.emptyList());
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
  @GetMapping("/api/search")
  @ResponseBody
  public List<UserDto> searchMembers(@RequestParam String query) {
    // L-1: UriComponentsBuilder so the user-supplied query is properly query-param-encoded.
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/users/search")
            .queryParam("query", query)
            .queryParam("size", 1000)
            .queryParam("sort", "username,asc")
            .toUriString();
    PageResponse<UserDto> page = backendApiClient.get(uri, USER_PAGE_TYPE);
    return page == null ? null : page.content();
  }

  /**
   * Renders the member edit page.
   *
   * <p>{@code source} threads through the form so a "Save" landing here from the profile page can
   * redirect back to {@code /profile} on success while a save reached through the member list stays
   * on the member list. If the model already carries a {@code MemberEditForm} (because a
   * validation-failure rerender happened) the source is patched in but the other form fields are
   * preserved.
   *
   * @param id user id
   * @param source optional origin marker ({@code "profile"} keeps the round-trip on profile)
   * @param model Thymeleaf model populated with {@code user} and {@code memberEditForm}
   * @param redirectAttributes flash attributes carrier for the error redirect
   * @return inline {@code member-edit} view, or redirect to {@code /members} on backend failure
   */
  @GetMapping("/{id}/edit")
  public String editMember(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String source,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      UserDto user = backendApiClient.get("/api/v1/users/" + id, UserDto.class);
      model.addAttribute("user", user);

      // Read-only Mitgliedschaften overview (names + SK manage links): the lean picker-option
      // projection is enough — each SK row links through to its detail page for Lead / removal
      // management. The editable Staffel slots below are seeded from the detail view instead.
      try {
        List<de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto>
            memberships =
                backendApiClient.get(
                    "/api/v1/users/" + id + "/memberships", MEMBERSHIP_OPTION_LIST_TYPE);
        model.addAttribute(
            "memberMemberships",
            memberships != null
                ? memberships
                : java.util.Collections
                    .<de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto>
                        emptyList());
      } catch (Exception ex) {
        log.debug("Failed to load memberships for member-edit panel", ex);
        model.addAttribute(
            "memberMemberships",
            java.util.Collections
                .<de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto>
                    emptyList());
      }

      // REQ-ORG-017 — seed up to two editable Staffel slots from the full membership detail, which
      // carries each Staffel's own Logistician / Mission-Manager flags (REQ-SEC-005). The detail
      // endpoint sorts Staffel-first then by name, so the SQUADRON rows are the leading entries.
      List<OrgUnitMembershipDto> staffelRows = List.of();
      boolean staffelDetailLoaded = false;
      try {
        MembershipDeltaResponse detail =
            backendApiClient.get(
                "/api/v1/users/" + id + "/memberships/detail", MembershipDeltaResponse.class);
        if (detail != null && detail.memberships() != null) {
          staffelRows =
              detail.memberships().stream().filter(m -> m.kind() == OrgUnitKind.SQUADRON).toList();
          // The authoritative Staffel set (incl. each Staffel's own flags) loaded — the two slots
          // below can be trusted as the complete desired set on save. A failed/empty load leaves
          // this false so applyMemberUpdate skips the Staffel reconcile (REQ-ORG-017): the blank
          // slots must NOT be misread as "remove every Staffel".
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
   * Persists member edits. Validation failure re-renders inline via {@link #editMember}. Successful
   * save with {@code source=profile} redirects back to the profile page; everything else lands on
   * the member list.
   *
   * @param id user id
   * @param form member edit form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return redirect target depending on source and outcome
   */
  @PostMapping("/{id}/edit")
  public String updateMember(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("memberEditForm") MemberEditForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Re-render the edit view directly; the BindingResult stays request-scoped so
      // it never goes through a Redis-serialised FlashMap (see RedisSessionConfig).
      return editMember(id, form.source(), model, redirectAttributes);
    }
    try {
      applyMemberUpdate(id, form);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
      if ("profile".equals(form.source())) {
        return "redirect:/profile";
      }
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
   * AJAX variant of {@link #updateMember}: applies the same save but answers with JSON instead of a
   * redirect, so the member-edit page saves in place (epic #571, REQ-FE-007). Selected over the
   * form handler only when {@code krtFetch}'s submit sends {@code X-Requested-With:
   * XMLHttpRequest}; a script-disabled browser still posts the HTML form and lands on {@link
   * #updateMember}, so the redirect path stays the no-JS fallback.
   *
   * <p>Validation failures map to a {@code 422} carrying a {@code {field: message}} object (each
   * message resolved against the request locale exactly as Thymeleaf {@code th:errors} would) so
   * the client paints per-field errors without a reload. On success the freshly bumped user-row
   * {@code version} is returned so the hidden form input stays current and a second consecutive
   * save does not 409. A backend failure is relayed with its original status plus a slim {@code
   * {code, detail}} body so the client can recognise {@code OPTIMISTIC_LOCK} and show the localized
   * reason.
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
  public ResponseEntity<Map<String, Object>> updateMemberAjax(
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
   * Applies a member edit to the backend: persists the attributes first (the backend bumps
   * {@code @Version} on the user row and returns an empty body), then — <em>only when the edit
   * form's authoritative Staffel detail loaded</em> ({@link MemberEditForm#staffelDetailLoaded()})
   * — sends the desired complete Staffel set as the {@code staffeln} half of one membership-delta
   * PATCH. The backend reconciles that set against the user's current Staffel memberships (add /
   * remove / per-squadron flag patch in one transaction). The reconcile is idempotent, so
   * re-posting an unchanged set is a no-op.
   *
   * <p>If the Staffel-detail fetch failed when the form was rendered, the two slots are blank and
   * sending them would be reconciled as "remove every Staffel" — so the PATCH is skipped entirely
   * and the Staffel memberships are left untouched (REQ-ORG-017). Shared by the redirect handler
   * and the AJAX twin; throws on a backend failure so each caller can map it to its own error
   * shape.
   *
   * @param id user id
   * @param form the validated member edit form
   */
  private void applyMemberUpdate(@NotNull UUID id, MemberEditForm form) {
    UserAttributesUpdateDto body =
        new UserAttributesUpdateDto(
            form.rank(), form.description(), form.displayName(), form.version(), form.joinDate());
    backendApiClient.put("/api/v1/users/" + id + "/attributes", body, Void.class);

    // REQ-ORG-017 — reconcile the Staffel set ONLY when the authoritative membership detail loaded
    // on the edit-form GET (form.staffelDetailLoaded()). If that fetch failed, the two slots render
    // blank, and a non-null staffeln list (even empty) is reconciled by the backend as the complete
    // desired set — i.e. it would strip every Staffel membership (MEMBERSHIP_REVOKED, org-chart
    // seats, inventory demotion). Skipping the PATCH entirely leaves the Staffel side untouched.
    if (!Boolean.TRUE.equals(form.staffelDetailLoaded())) {
      return;
    }

    // Fold the two fixed Staffel slots into the desired complete Staffel set. A second slot equal
    // to
    // the first is dropped here so the backend never sees the same squadron twice; an empty slot is
    // simply omitted. An empty list removes every Staffel membership.
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
    backendApiClient.patch(
        "/api/v1/users/" + id + "/memberships",
        new MembershipDeltaRequest(staffeln, null),
        MembershipDeltaResponse.class);
  }

  /**
   * Reads the user row's current {@code version} back from the backend after a write so the
   * in-place member-edit save can sync the freshly bumped value into the hidden form input. Falls
   * back to {@code priorVersion + 1} when the re-fetch is unavailable.
   *
   * @param id user id
   * @param priorVersion the version the client submitted (may be {@code null})
   * @return the current user-row version, or a best-effort {@code priorVersion + 1}
   */
  private Long currentUserVersion(@NotNull UUID id, Long priorVersion) {
    try {
      UserDto user = backendApiClient.get("/api/v1/users/" + id, UserDto.class);
      if (user != null && user.version() != null) {
        return user.version();
      }
    } catch (Exception ignored) {
      // Re-fetch failed — fall through to the best-effort increment below.
    }
    return (priorVersion == null ? 0L : priorVersion) + 1;
  }

  /**
   * Resolves a bound field error's message against the request locale, mirroring how Thymeleaf
   * {@code th:errors} renders it. Degrades to the error's own default message on a missing bundle
   * key so a translation gap never crashes the in-place save to a 500.
   *
   * @param fieldError the validation error to render
   * @param locale the request locale
   * @return the localized message, or the field error's default message as a fallback
   */
  private String resolveFieldMessage(FieldError fieldError, Locale locale) {
    try {
      return messageSource.getMessage(fieldError, locale);
    } catch (org.springframework.context.NoSuchMessageException e) {
      return fieldError.getDefaultMessage();
    }
  }

  /**
   * Relays a backend failure to the AJAX caller with its original HTTP status and a slim {@code
   * {code, detail}} body so {@code krtFetch} can recognise {@code OPTIMISTIC_LOCK} and show the
   * backend's localized message.
   *
   * @param logMessage context for the warn log line
   * @param e the backend service exception carrying the relayed status, problem code and detail
   * @return the relayed error response
   */
  private ResponseEntity<Map<String, Object>> relayBackendError(
      String logMessage, BackendServiceException e) {
    log.warn("{}: status={}, code={}", logMessage, e.getStatusCode(), e.getProblemCode());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("code", e.getProblemCode());
    payload.put("detail", e.getProblemDetail());
    int status = e.getStatusCode() > 0 ? e.getStatusCode() : 500;
    return ResponseEntity.status(status).body(payload);
  }

  /**
   * Deletes a user. Admin-only — the OFFICER role at the class level is intentionally narrowed
   * here.
   *
   * @param id user id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /members}
   */
  @PostMapping("/{id}/delete")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String deleteMember(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/users/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "success.user.delete");
    } catch (Exception e) {
      log.error("Delete failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.user.delete");
    }
    return "redirect:/members";
  }

  /**
   * AJAX variant of {@link #deleteMember}: deletes the user and answers with JSON so the member
   * list removes the row in place (epic #571, REQ-FE-005) instead of redirecting. On success the
   * page re-swaps the results fragment to keep pagination and the SK column coherent; a backend
   * refusal (e.g. the user still exists in Keycloak) is relayed with its status + {@code {code,
   * detail}} so the client shows the reason without navigating away. ADMIN-only, like the redirect
   * handler.
   *
   * @param id user id
   * @return {@code 200} on success, or the relayed backend error status with {@code {code, detail}}
   */
  @DeleteMapping("/{id}")
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Map<String, Object>> deleteMemberAjax(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete("/api/v1/users/" + id, Void.class);
      return ResponseEntity.ok(Map.of());
    } catch (BackendServiceException e) {
      return relayBackendError("AJAX member delete failed", e);
    } catch (Exception e) {
      log.error("AJAX member delete failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("code", "INTERNAL_ERROR"));
    }
  }
}
