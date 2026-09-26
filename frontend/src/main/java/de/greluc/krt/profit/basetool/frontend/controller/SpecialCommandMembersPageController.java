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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.model.form.MembershipFlagsForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.MapPayloadValues;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the Spezialkommando member page ({@code
 * /organisation/special-commands/{id}}): the roster, adding and removing members and setting their
 * Logistiker / Einsatzmanager flags, each with an AJAX twin.
 *
 * <p>Gated to {@link Roles#ADMIN_OR_OFFICER}; the backend's per-SK {@code
 * SpecialCommandSecurityService#canManageMembers} admits only admins and the SK's lead. The SK-lead
 * toggle lives on {@link AdminSpecialCommandsPageController}. Also compare {@link
 * LeitungPageController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/organisation/special-commands")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize(Roles.ADMIN_OR_OFFICER)
public class SpecialCommandMembersPageController {

  /** Base path of this page; every redirect of this controller lands on a child of it. */
  private static final String PAGE_BASE = "/organisation/special-commands/";

  /** Where an admin returns to: the SK overview in the admin area. */
  private static final String ADMIN_BACK_URL = "/admin/special-commands";

  /** Where a non-admin SK lead returns to: the Leitung page that links them here. */
  private static final String LEITUNG_BACK_URL = "/organisation/leitung";

  /** Response type for a single raw-JSON SK read ({@code /special-commands/{id}}). */
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the raw-JSON SK member list read ({@code /special-commands/{id}/members}).
   */
  private static final ParameterizedTypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final FrontendAuthHelperService authHelperService;

  /**
   * Renders the SK member page with its roster.
   *
   * <p>The model carries {@code canToggleLead} (admins only) and {@code backUrl}. A backend 403
   * becomes an {@link AccessDeniedException}; any other failure or an empty SK read redirects to
   * {@code backUrl} with an {@code error} parameter.
   *
   * @param id Spezialkommando id.
   * @param fragment {@code "members"} to render only the roster fragment (REQ-FE-005); otherwise
   *     the full page.
   * @param model Thymeleaf model populated with the SK, the member roster, {@code canToggleLead}
   *     and {@code backUrl}.
   * @return the {@code organisation/special-command-detail} view name, its {@code membersResults}
   *     fragment for an AJAX swap, or a redirect to {@code backUrl} on a non-403 failure.
   * @throws AccessDeniedException when the backend refuses the SK or member read with 403.
   */
  @NotNull
  @GetMapping("/{id}")
  public String detail(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean admin = authHelperService.isAdmin();
    String backUrl = admin ? ADMIN_BACK_URL : LEITUNG_BACK_URL;
    model.addAttribute("canToggleLead", admin);
    model.addAttribute("backUrl", backUrl);
    try {
      SpecialCommandDto sc = fetchSpecialCommand(id);
      if (sc == null) {
        return "redirect:" + backUrl + "?error=SpecialCommandNotFound";
      }
      model.addAttribute("specialCommand", sc);
      model.addAttribute("members", fetchMembers(id));
    } catch (BackendServiceException e) {
      if (e.getStatusCode() == HttpStatus.FORBIDDEN.value()) {
        throw new AccessDeniedException(
            "The backend refused the Spezialkommando member page: the caller is neither an admin"
                + " nor the lead of this Spezialkommando.",
            e);
      }
      log.debug("Load SpecialCommand detail failed", e);
      return "redirect:" + backUrl + "?error=LoadSpecialCommandDetailFailed";
    } catch (Exception e) {
      log.error("Load SpecialCommand detail failed", e);
      return "redirect:" + backUrl + "?error=LoadSpecialCommandDetailFailed";
    }
    return "members".equals(fragment)
        ? "organisation/special-command-detail :: membersResults"
        : "organisation/special-command-detail";
  }

  /**
   * Adds a user to the Spezialkommando (no-JS fallback). The backend admits an admin or the lead of
   * this SK. A 409 means the user is already a member and surfaces as the dedicated toast.
   *
   * @param id Spezialkommando id.
   * @param userId user to add.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /organisation/special-commands/{id}}, with an {@code error} query
   *     parameter on a failure other than 409.
   */
  @NotNull
  @PostMapping("/{id}/members")
  public String addMember(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull UUID userId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.post(
          "/api/v1/special-commands/" + id + "/members/" + userId, null, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Add SpecialCommand member failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.specialcommand.member.duplicate");
        return "redirect:" + PAGE_BASE + id;
      }
      return "redirect:" + PAGE_BASE + id + "?error=AddMemberFailed";
    } catch (Exception e) {
      log.error("Add SpecialCommand member failed", e);
      return "redirect:" + PAGE_BASE + id + "?error=AddMemberFailed";
    }
    return "redirect:" + PAGE_BASE + id;
  }

  /**
   * Removes a user from the Spezialkommando (no-JS fallback). The backend admits an admin or the
   * lead of this SK.
   *
   * @param id Spezialkommando id.
   * @param userId user to remove.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /organisation/special-commands/{id}}, with an {@code error} query
   *     parameter on failure.
   */
  @NotNull
  @PostMapping("/{id}/members/{userId}/delete")
  public String removeMember(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/special-commands/" + id + "/members/" + userId, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Remove SpecialCommand member failed", e);
      return "redirect:" + PAGE_BASE + id + "?error=RemoveMemberFailed";
    }
    return "redirect:" + PAGE_BASE + id;
  }

  /**
   * Sets the per-membership Logistician and Mission Manager flags (no-JS fallback), always sending
   * both values and the optimistic-lock version; an unchecked box binds as {@code false} via {@link
   * MembershipFlagsForm}. A 409 surfaces as the concurrency-conflict toast.
   *
   * @param id Spezialkommando id.
   * @param userId user whose flags to set.
   * @param form bound form carrying both flag values and the optimistic-lock version.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /organisation/special-commands/{id}}, with an {@code error} query
   *     parameter on a failure other than 409.
   */
  @NotNull
  @PostMapping("/{id}/members/{userId}/flags")
  public String patchMemberFlags(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @ModelAttribute MembershipFlagsForm form,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.patch(
          "/api/v1/special-commands/" + id + "/members/" + userId, flagsBody(form), Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Patch SpecialCommand member flags failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        return "redirect:" + PAGE_BASE + id;
      }
      return "redirect:" + PAGE_BASE + id + "?error=PatchMemberFailed";
    } catch (Exception e) {
      log.error("Patch SpecialCommand member flags failed", e);
      return "redirect:" + PAGE_BASE + id + "?error=PatchMemberFailed";
    }
    return "redirect:" + PAGE_BASE + id;
  }

  /**
   * In-place twin of {@link #addMember}.
   *
   * @param id SK id.
   * @param userId user to add.
   * @return {@code 200} on success, the relayed backend status (incl. 409 already-member, 403 not
   *     the lead of this SK) on failure.
   */
  @ResponseBody
  @PostMapping(value = "/{id}/members", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> addMemberAjax(
      @PathVariable @NotNull UUID id, @RequestParam @NotNull UUID userId) {
    return okOrRelay(
        () ->
            backendApiClient.post(
                "/api/v1/special-commands/" + id + "/members/" + userId, null, Void.class));
  }

  /**
   * In-place twin of {@link #removeMember}.
   *
   * @param id SK id.
   * @param userId user to remove.
   * @return {@code 200} on success, the relayed backend status on failure.
   */
  @ResponseBody
  @PostMapping(value = "/{id}/members/{userId}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> removeMemberAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return okOrRelay(
        () ->
            backendApiClient.delete(
                "/api/v1/special-commands/" + id + "/members/" + userId, Void.class));
  }

  /**
   * In-place twin of {@link #patchMemberFlags}. Carries the optimistic-lock version; a conflict is
   * relayed as {@code OPTIMISTIC_LOCK} so the client offers the reload-confirm.
   *
   * @param id SK id.
   * @param userId user whose flags to set.
   * @param form bound form carrying both flag values and the version.
   * @return {@code 200} on success, the relayed backend status on a conflict / failure.
   */
  @ResponseBody
  @PostMapping(value = "/{id}/members/{userId}/flags", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> patchMemberFlagsAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @ModelAttribute MembershipFlagsForm form) {
    return okOrRelay(
        () ->
            backendApiClient.patch(
                "/api/v1/special-commands/" + id + "/members/" + userId,
                flagsBody(form),
                Void.class));
  }

  /**
   * Builds the backend flags-patch payload from the bound form: both flags as concrete booleans
   * plus the optimistic-lock version the page last rendered.
   *
   * @param form the bound flags form.
   * @return a mutable map with {@code isLogistician}, {@code isMissionManager} and {@code version}.
   */
  @NotNull
  private static Map<String, Object> flagsBody(@NotNull MembershipFlagsForm form) {
    Map<String, Object> body = new HashMap<>();
    body.put("isLogistician", form.isLogistician());
    body.put("isMissionManager", form.isMissionManager());
    body.put("version", form.version());
    return body;
  }

  /**
   * Runs a member-roster backend write and maps the outcome to an HTTP status: {@code 200} on
   * success, the relayed backend problem on a {@link BackendServiceException}, {@code 500}
   * otherwise. Shared by every AJAX twin of this page.
   *
   * @param backendCall the backend mutation to perform.
   * @return the mapped {@link ResponseEntity}.
   */
  @NotNull
  private ResponseEntity<Object> okOrRelay(@NotNull Runnable backendCall) {
    return relay(
        log,
        "specialCommand member write (ajax)",
        () -> {
          backendCall.run();
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Reads one Spezialkommando from the backend and maps its raw JSON onto {@link
   * SpecialCommandDto}.
   *
   * @param id Spezialkommando id.
   * @return the SK, or {@code null} when the backend answered with an empty body.
   * @throws BackendServiceException when the backend refuses or fails the read (403 when the caller
   *     is neither admin nor the lead of this SK).
   */
  @Nullable
  private SpecialCommandDto fetchSpecialCommand(@NotNull UUID id) {
    Map<String, Object> map = backendApiClient.get("/api/v1/special-commands/" + id, MAP_TYPE);
    if (map == null) {
      return null;
    }
    return new SpecialCommandDto(
        MapPayloadValues.uuidOrNull(map.get("id")),
        MapPayloadValues.stringOrNull(map.get("name")),
        MapPayloadValues.stringOrNull(map.get("shorthand")),
        MapPayloadValues.stringOrNull(map.get("description")),
        MapPayloadValues.booleanOrFalse(map.get("active")),
        MapPayloadValues.booleanOrFalse(map.get("isProfitEligible")),
        MapPayloadValues.longOrZero(map.get("version")));
  }

  /**
   * Reads the member roster of one Spezialkommando and maps it onto {@link OrgUnitMembershipDto},
   * sorted case-insensitively by display name.
   *
   * @param specialCommandId Spezialkommando id.
   * @return the sorted, mutable roster; empty when the backend answered with an empty body.
   * @throws BackendServiceException when the backend refuses or fails the read.
   */
  @NotNull
  private List<OrgUnitMembershipDto> fetchMembers(@NotNull UUID specialCommandId) {
    List<Map<String, Object>> raw =
        backendApiClient.get(
            "/api/v1/special-commands/" + specialCommandId + "/members", MAP_LIST_TYPE);
    if (raw == null) {
      return List.of();
    }
    List<OrgUnitMembershipDto> members =
        raw.stream()
            .map(
                m ->
                    new OrgUnitMembershipDto(
                        MapPayloadValues.uuidOrNull(m.get("userId")),
                        MapPayloadValues.stringOrNull(m.get("userDisplayName")),
                        MapPayloadValues.uuidOrNull(m.get("orgUnitId")),
                        parseKind(m.get("kind")),
                        MapPayloadValues.booleanOrFalse(m.get("isLogistician")),
                        MapPayloadValues.booleanOrFalse(m.get("isMissionManager")),
                        MapPayloadValues.booleanOrFalse(m.get("isLead")),
                        parseInstant(m.get("joinedAt")),
                        MapPayloadValues.longOrZero(m.get("version"))))
            .collect(Collectors.toCollection(ArrayList::new));
    members.sort(
        Comparator.comparing(
            m -> m.userDisplayName() == null ? "" : m.userDisplayName(),
            String.CASE_INSENSITIVE_ORDER));
    return members;
  }

  /**
   * Parses an ISO-8601 instant, or epoch millis as a fallback, into an {@link Instant}.
   *
   * @param o the raw {@code joinedAt} value.
   * @return the parsed instant, or {@code null} when absent or unparsable.
   */
  @Contract("null -> null")
  @Nullable
  private static Instant parseInstant(@Nullable Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Instant i) {
      return i;
    }
    try {
      return Instant.parse(String.valueOf(o));
    } catch (Exception ignored) {
      try {
        return Instant.ofEpochMilli(Long.parseLong(String.valueOf(o)));
      } catch (Exception ignoredToo) {
        return null;
      }
    }
  }

  /**
   * Parses the {@code kind} string into an {@link OrgUnitKind}, defaulting to {@link
   * OrgUnitKind#SPECIAL_COMMAND} for a missing or unknown value.
   *
   * @param o the raw {@code kind} value.
   * @return the parsed kind; never {@code null}.
   */
  @NotNull
  private static OrgUnitKind parseKind(@Nullable Object o) {
    if (o == null) {
      return OrgUnitKind.SPECIAL_COMMAND;
    }
    try {
      return OrgUnitKind.valueOf(String.valueOf(o));
    } catch (Exception ignored) {
      return OrgUnitKind.SPECIAL_COMMAND;
    }
  }
}
