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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.leadership.web.LeitungPageController;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipFlagsForm;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

  /** Reads the Spezialkommando and its roster and sends the roster writes. */
  private final OrgUnitBackendClient orgUnitClient;

  /** Tells an admin apart from a Spezialkommando lead. */
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
      orgUnitClient.addSpecialCommandMember(id, userId);
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
      orgUnitClient.removeSpecialCommandMember(id, userId);
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
      orgUnitClient.patchSpecialCommandMemberFlags(id, userId, flagsBody(form));
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
    return okOrRelay(() -> orgUnitClient.addSpecialCommandMember(id, userId));
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
    return okOrRelay(() -> orgUnitClient.removeSpecialCommandMember(id, userId));
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
        () -> orgUnitClient.patchSpecialCommandMemberFlags(id, userId, flagsBody(form)));
  }

  /**
   * Builds the backend flags-patch payload from the bound form: both flags as concrete booleans
   * plus the optimistic-lock version the page last rendered.
   *
   * @param form the bound flags form.
   * @return the patch with {@code isLogistician}, {@code isMissionManager} and {@code version}.
   */
  @NotNull
  private static MembershipFlagsPatchRequest flagsBody(@NotNull MembershipFlagsForm form) {
    return new MembershipFlagsPatchRequest(
        form.isLogistician(), form.isMissionManager(), form.version());
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
   * Reads one Spezialkommando from the backend, rendering an absent flag as {@code false} and an
   * absent version as {@code 0}.
   *
   * @param id Spezialkommando id.
   * @return the SK, or {@code null} when the backend answered with an empty body.
   * @throws BackendServiceException when the backend refuses or fails the read (403 when the caller
   *     is neither admin nor the lead of this SK).
   */
  @Nullable
  private SpecialCommandDto fetchSpecialCommand(@NotNull UUID id) {
    SpecialCommandDto sc = orgUnitClient.specialCommand(id);
    if (sc == null) {
      return null;
    }
    return new SpecialCommandDto(
        sc.id(),
        sc.name(),
        sc.shorthand(),
        sc.description(),
        Boolean.TRUE.equals(sc.active()),
        Boolean.TRUE.equals(sc.isProfitEligible()),
        sc.version() == null ? 0L : sc.version());
  }

  /**
   * Reads the member roster of one Spezialkommando, sorted case-insensitively by display name, with
   * an absent flag as {@code false}, an absent version as {@code 0} and an absent kind as {@link
   * OrgUnitKind#SPECIAL_COMMAND}.
   *
   * @param specialCommandId Spezialkommando id.
   * @return the sorted, mutable roster; empty when the backend answered with an empty body.
   * @throws BackendServiceException when the backend refuses or fails the read.
   */
  @NotNull
  private List<OrgUnitMembershipDto> fetchMembers(@NotNull UUID specialCommandId) {
    List<OrgUnitMembershipDto> raw = orgUnitClient.specialCommandMembers(specialCommandId);
    if (raw == null) {
      return List.of();
    }
    List<OrgUnitMembershipDto> members =
        raw.stream()
            .map(
                m ->
                    new OrgUnitMembershipDto(
                        m.userId(),
                        m.userDisplayName(),
                        m.orgUnitId(),
                        m.kind() == null ? OrgUnitKind.SPECIAL_COMMAND : m.kind(),
                        Boolean.TRUE.equals(m.isLogistician()),
                        Boolean.TRUE.equals(m.isMissionManager()),
                        Boolean.TRUE.equals(m.isLead()),
                        m.joinedAt(),
                        m.version() == null ? 0L : m.version()))
            .collect(Collectors.toCollection(ArrayList::new));
    members.sort(
        Comparator.comparing(
            m -> m.userDisplayName() == null ? "" : m.userDisplayName(),
            String.CASE_INSENSITIVE_ORDER));
    return members;
  }
}
