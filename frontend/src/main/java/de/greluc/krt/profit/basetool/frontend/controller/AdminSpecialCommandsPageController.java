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

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.form.MembershipFlagsForm;
import de.greluc.krt.profit.basetool.frontend.model.form.SpecialCommandForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
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
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the admin Spezialkommando-management page ({@code
 * /admin/special-commands}). Mirrors the Squadron section of {@link AdminMissionDataPageController}
 * field-for-field: list + create + update + soft-delete + re-activate, with the same
 * BindingResult-inline-rerender / 409-distinct-toast / generic-error- redirect pattern.
 *
 * <p>SK-specific differences from Squadron:
 *
 * <ul>
 *   <li>No promotion-feature toggle — Spezialkommandos never carry the promotion subsystem. The
 *       backend's V94 CHECK constraint plus the {@code SpecialCommand} setter override forbid the
 *       flag from ever being {@code true} on an SK row.
 *   <li>SK lives on a dedicated page rather than being one of three columns on {@code
 *       /admin/mission-data}. SK administration is a denser surface (separate detail page with a
 *       member roster lands in R5.c.b) so the dedicated page avoids cluttering the existing
 *       reference-data view.
 *   <li>No detail-page link yet — R5.c.b adds the per-SK detail page with the member roster, the
 *       add/remove modal, and the role-flag controls. This PR only adds the list-level CRUD.
 * </ul>
 */
@Controller
@RequestMapping("/admin/special-commands")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSpecialCommandsPageController {

  /**
   * Response type for the paged SK catalog read ({@code /special-commands?...}). A shared static
   * {@link ParameterizedTypeReference} is behaviourally identical to a fresh anonymous instance per
   * call (Q10).
   */
  private static final ParameterizedTypeReference<PageResponse<Map<String, Object>>> MAP_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for a single raw-JSON SK read ({@code /special-commands/{id}}). */
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  /**
   * Response type for the raw-JSON list reads (SK members and the user lookup). Both endpoints emit
   * the same {@code List<Map<String, Object>>} shape, so one shared constant serves both.
   */
  private static final ParameterizedTypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the SK overview list. Re-seeds an empty form when the model does not already carry one
   * (which would be the case after a validation re-render). {@code includeInactive=true} surfaces
   * soft-deleted SKs so the admin can reactivate them.
   *
   * @param includeInactive show soft-deleted SKs.
   * @param fragment when {@code "results"} only the SK-list fragment is rendered (AJAX
   *     include-inactive filter swap, REQ-FE-002); otherwise the full page is returned.
   * @param model Thymeleaf model populated with the SK list, the form and the toggle.
   * @return the {@code admin/special-commands} view name, or its {@code results} fragment for an
   *     AJAX swap.
   */
  @GetMapping
  public String listSpecialCommands(
      @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("specialCommandForm")) {
      model.addAttribute("specialCommandForm", new SpecialCommandForm("", "", "", 0L));
    }
    model.addAttribute("includeInactive", includeInactive);

    try {
      model.addAttribute("specialCommands", fetchSpecialCommands(includeInactive));
    } catch (Exception e) {
      log.error("Error loading SpecialCommands", e);
      model.addAttribute("specialCommands", List.of());
      model.addAttribute("error", "error.admin.specialcommands.load");
    }
    return "results".equals(fragment)
        ? "admin/special-commands :: results"
        : "admin/special-commands";
  }

  /**
   * Fetches the SK catalog from the backend and transforms the raw payload into a sorted list of
   * {@link SpecialCommandDto} records. Mirrors {@link AdminMissionDataPageController}'s {@code
   * fetchSquadrons} parsing path.
   *
   * @param includeInactive forward to the backend's {@code includeInactive} query param.
   * @return list of SKs sorted case-insensitively by name; never {@code null}.
   */
  private List<SpecialCommandDto> fetchSpecialCommands(boolean includeInactive) {
    PageResponse<Map<String, Object>> page =
        backendApiClient.get(
            "/api/v1/special-commands?size=1000&sort=name,asc&includeInactive=" + includeInactive,
            MAP_PAGE_TYPE);
    if (page == null || page.content() == null) {
      return List.of();
    }
    List<SpecialCommandDto> commands =
        page.content().stream()
            .map(
                m ->
                    new SpecialCommandDto(
                        parseUuid(m.get("id")),
                        parseString(m.get("name")),
                        parseString(m.get("shorthand")),
                        parseString(m.get("description")),
                        parseBoolean(m.get("active")),
                        parseBoolean(m.get("isProfitEligible")),
                        parseLong(m.get("version"))))
            .collect(Collectors.toCollection(ArrayList::new));
    commands.sort(
        Comparator.comparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER));
    return commands;
  }

  /**
   * Creates a new Spezialkommando. Validation failure re-renders the list inline with the create
   * modal re-opened; a 409 from the backend's duplicate-name check surfaces as the dedicated toast;
   * all other failures redirect with an error query param.
   *
   * @param form SK form payload.
   * @param bindingResult validation errors carrier.
   * @param model Thymeleaf model used for inline re-rendering on validation failure.
   * @param redirectAttributes flash-attribute carrier for the success / error toast.
   * @return inline list page on validation failure, otherwise redirect to {@code
   *     /admin/special-commands}.
   */
  @PostMapping
  public String createSpecialCommand(
      @Valid @ModelAttribute("specialCommandForm") SpecialCommandForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "specialcommand-modal");
      model.addAttribute("modalAction", "/admin/special-commands");
      return listSpecialCommands(false, null, model);
    }
    try {
      SpecialCommandDto body =
          new SpecialCommandDto(
              null, form.name(), form.shorthand(), form.description(), true, false, 0L);
      backendApiClient.post("/api/v1/special-commands", body, Void.class);
      evictOrgUnitCatalogueCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Create SpecialCommand failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.specialcommand");
        return "redirect:/admin/special-commands";
      }
      return "redirect:/admin/special-commands?error=CreateSpecialCommandFailed";
    } catch (Exception e) {
      log.error("Create SpecialCommand failed", e);
      return "redirect:/admin/special-commands?error=CreateSpecialCommandFailed";
    }
    return "redirect:/admin/special-commands";
  }

  /**
   * Updates an existing Spezialkommando. Distinguishes optimistic-locking conflict ({@code
   * concurrency-conflict} problem type) from a duplicate-name 409 so the user gets the right toast.
   *
   * @param id SK id.
   * @param form SK form (carries the version).
   * @param bindingResult validation errors carrier.
   * @param model Thymeleaf model used for inline re-rendering.
   * @param redirectAttributes flash-attribute carrier.
   * @return inline list page on failure, otherwise redirect.
   */
  @PostMapping("/{id}/update")
  public String updateSpecialCommand(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("specialCommandForm") SpecialCommandForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "specialcommand-modal");
      model.addAttribute("modalAction", "/admin/special-commands/" + id + "/update");
      return listSpecialCommands(false, null, model);
    }
    try {
      SpecialCommandDto body =
          new SpecialCommandDto(
              id, form.name(), form.shorthand(), form.description(), true, false, form.version());
      backendApiClient.put("/api/v1/special-commands/" + id, body, Void.class);
      evictOrgUnitCatalogueCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Update SpecialCommand failed", e);
      if (e.getStatusCode() == 409) {
        if ("concurrency-conflict".equals(e.getProblemType())) {
          redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        } else {
          redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.specialcommand");
        }
        return "redirect:/admin/special-commands";
      }
      return "redirect:/admin/special-commands?error=UpdateSpecialCommandFailed";
    } catch (Exception e) {
      log.error("Update SpecialCommand failed", e);
      return "redirect:/admin/special-commands?error=UpdateSpecialCommandFailed";
    }
    return "redirect:/admin/special-commands";
  }

  /**
   * Soft-deletes a Spezialkommando (flips {@code active = false}). A 409 from the backend (would
   * indicate a future referential-integrity guard once aggregates can be owned by SKs) surfaces as
   * the dedicated "in use" toast.
   *
   * @param id SK id.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands}.
   */
  @PostMapping("/{id}/delete")
  public String deleteSpecialCommand(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/special-commands/" + id, Void.class);
      evictOrgUnitCatalogueCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      log.debug("Delete SpecialCommand failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.delete.specialcommand.in_use");
        return "redirect:/admin/special-commands";
      }
      return "redirect:/admin/special-commands?error=DeleteSpecialCommandFailed";
    } catch (Exception e) {
      log.error("Delete SpecialCommand failed", e);
      return "redirect:/admin/special-commands?error=DeleteSpecialCommandFailed";
    }
    return "redirect:/admin/special-commands";
  }

  /**
   * Re-activates a soft-deleted Spezialkommando.
   *
   * @param id SK id.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands}.
   */
  @PostMapping("/{id}/activate")
  public String activateSpecialCommand(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.post("/api/v1/special-commands/" + id + "/activate", null, Void.class);
      evictOrgUnitCatalogueCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Activate SpecialCommand failed", e);
      return "redirect:/admin/special-commands?error=ActivateSpecialCommandFailed";
    }
    return "redirect:/admin/special-commands";
  }

  // ----------------------------------------------------------------------------
  // R5.c.b — per-SK detail page with the member roster + add / remove / patch /
  // Lead-toggle modals. Calls the R5.b backend endpoints under
  // /api/v1/special-commands/{id}/members.
  // ----------------------------------------------------------------------------

  /**
   * Renders the per-SK detail page with the member roster. Loads the SK + its members in two
   * sequential backend calls (the roster is small and serial latency is dominated by render time,
   * not by the round-trips). The add-member modal preloads the user-lookup list so the picker has a
   * starting set without an AJAX fetch.
   *
   * @param id Spezialkommando id.
   * @param fragment when {@code "members"} only the member-roster fragment is rendered (AJAX
   *     re-swap after an in-place member mutation, REQ-FE-005); otherwise the full page.
   * @param model Thymeleaf model populated with the SK, the member roster + every known user for
   *     the add-member picker.
   * @return the {@code admin/special-command-detail} view name, or its {@code membersResults}
   *     fragment for an AJAX swap.
   */
  @GetMapping("/{id}")
  public String detail(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String fragment,
      Model model) {
    boolean membersFragment = "members".equals(fragment);
    try {
      SpecialCommandDto sc = fetchSpecialCommand(id);
      if (sc == null) {
        return "redirect:/admin/special-commands?error=SpecialCommandNotFound";
      }
      model.addAttribute("specialCommand", sc);
      model.addAttribute("members", fetchMembers(id));
      // The add-member picker (allUsers) lives outside the swappable roster fragment, so the
      // re-swap path skips that lookup.
      if (!membersFragment) {
        model.addAttribute("allUsers", fetchUserLookup());
      }
    } catch (Exception e) {
      log.error("Load SpecialCommand detail failed", e);
      return "redirect:/admin/special-commands?error=LoadSpecialCommandDetailFailed";
    }
    return membersFragment
        ? "admin/special-command-detail :: membersResults"
        : "admin/special-command-detail";
  }

  /**
   * Adds a user to the Spezialkommando. ADMIN-only at the class level. A 409 indicates the user is
   * already a member; surfaces as the dedicated toast.
   *
   * @param id Spezialkommando id.
   * @param userId user to add.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands/{id}}.
   */
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
        return "redirect:/admin/special-commands/" + id;
      }
      return "redirect:/admin/special-commands/" + id + "?error=AddMemberFailed";
    } catch (Exception e) {
      log.error("Add SpecialCommand member failed", e);
      return "redirect:/admin/special-commands/" + id + "?error=AddMemberFailed";
    }
    return "redirect:/admin/special-commands/" + id;
  }

  /**
   * Removes a user from the Spezialkommando.
   *
   * @param id Spezialkommando id.
   * @param userId user to remove.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands/{id}}.
   */
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
      return "redirect:/admin/special-commands/" + id + "?error=RemoveMemberFailed";
    }
    return "redirect:/admin/special-commands/" + id;
  }

  /**
   * Flips the per-membership Logistician + Mission Manager flags. Bound via {@code @ModelAttribute}
   * on a {@link MembershipFlagsForm} so Spring's data binder honours the {@code _<field>} hidden
   * marker that the form template emits before each checkbox: an unchecked box surfaces as {@code
   * false} instead of being missing from the payload, which is what the {@code @RequestParam
   * Boolean} signature used to do — and which silently broke the demote-via-uncheck path because
   * the backend interpreted the missing field as "no change".
   *
   * <p>Both flag values are forwarded as concrete {@code true} / {@code false} to the backend. The
   * backend DTO still accepts boxed Booleans with null-means-no-change semantics for direct API
   * callers, but the admin UI never partial-updates — every form submission carries both
   * checkboxes, so the explicit value is the right signal.
   *
   * @param id Spezialkommando id.
   * @param userId user whose flags to patch.
   * @param form bound form carrying both flag values and the optimistic-lock version.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands/{id}}.
   */
  @PostMapping("/{id}/members/{userId}/flags")
  public String patchMemberFlags(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @ModelAttribute MembershipFlagsForm form,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("isLogistician", form.isLogistician());
      body.put("isMissionManager", form.isMissionManager());
      body.put("version", form.version());
      backendApiClient.patch(
          "/api/v1/special-commands/" + id + "/members/" + userId, body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Patch SpecialCommand member flags failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        return "redirect:/admin/special-commands/" + id;
      }
      return "redirect:/admin/special-commands/" + id + "?error=PatchMemberFailed";
    } catch (Exception e) {
      log.error("Patch SpecialCommand member flags failed", e);
      return "redirect:/admin/special-commands/" + id + "?error=PatchMemberFailed";
    }
    return "redirect:/admin/special-commands/" + id;
  }

  /**
   * Toggles the Spezialkommando-Lead flag on a member's membership row. ADMIN-only at the
   * controller level — a Lead cannot promote themselves or another member (backend additionally
   * hard-gates the endpoint to {@code hasRole('ADMIN')}).
   *
   * @param id Spezialkommando id.
   * @param userId user whose membership to update.
   * @param isLead new Lead state.
   * @param version current optimistic-lock version held by the form.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands/{id}}.
   */
  @PostMapping("/{id}/members/{userId}/lead")
  public String toggleMemberLead(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestParam @NotNull Boolean isLead,
      @RequestParam @NotNull Long version,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("isLead", isLead);
      body.put("version", version);
      backendApiClient.patch(
          "/api/v1/special-commands/" + id + "/members/" + userId + "/lead", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Toggle SpecialCommand member lead failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        return "redirect:/admin/special-commands/" + id;
      }
      return "redirect:/admin/special-commands/" + id + "?error=ToggleLeadFailed";
    } catch (Exception e) {
      log.error("Toggle SpecialCommand member lead failed", e);
      return "redirect:/admin/special-commands/" + id + "?error=ToggleLeadFailed";
    }
    return "redirect:/admin/special-commands/" + id;
  }

  // In-place (AJAX) twins (#582). Routed ahead of their classic POST->redirect siblings by the
  // X-Requested-With header (no-JS forms keep their redirect fallback). They return 200 on success
  // — the list page re-swaps the SK-list fragment and the detail page re-swaps the member-roster
  // fragment, which re-render the correct derived state (active badges, role badges, lead state)
  // and fresh @Version data so the next action does not 409. Conflicts are relayed as
  // application/problem+json (duplicate/in-use toast, or OPTIMISTIC_LOCK reload-confirm).

  /**
   * In-place twin of {@link #createSpecialCommand}.
   *
   * @param form SK form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createSpecialCommandAjax(
      @Valid @ModelAttribute("specialCommandForm") SpecialCommandForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () -> {
          backendApiClient.post(
              "/api/v1/special-commands",
              new SpecialCommandDto(
                  null, form.name(), form.shorthand(), form.description(), true, false, 0L),
              Void.class);
          evictOrgUnitCatalogueCache();
        });
  }

  /**
   * In-place twin of {@link #updateSpecialCommand}.
   *
   * @param id SK id
   * @param form SK form (carries the version)
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateSpecialCommandAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("specialCommandForm") SpecialCommandForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(
        () -> {
          backendApiClient.put(
              "/api/v1/special-commands/" + id,
              new SpecialCommandDto(
                  id,
                  form.name(),
                  form.shorthand(),
                  form.description(),
                  true,
                  false,
                  form.version()),
              Void.class);
          evictOrgUnitCatalogueCache();
        });
  }

  /**
   * In-place twin of {@link #deleteSpecialCommand}.
   *
   * @param id SK id
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteSpecialCommandAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(
        () -> {
          backendApiClient.delete("/api/v1/special-commands/" + id, Void.class);
          evictOrgUnitCatalogueCache();
        });
  }

  /**
   * In-place twin of {@link #activateSpecialCommand}.
   *
   * @param id SK id
   * @return {@code 200} on success, the relayed backend status on failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/activate", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> activateSpecialCommandAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(
        () -> {
          backendApiClient.post("/api/v1/special-commands/" + id + "/activate", null, Void.class);
          evictOrgUnitCatalogueCache();
        });
  }

  /**
   * In-place twin of {@link #addMember}.
   *
   * @param id SK id
   * @param userId user to add
   * @return {@code 200} on success, the relayed backend status (incl. 409 already-member) on
   *     failure
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
   * @param id SK id
   * @param userId user to remove
   * @return {@code 200} on success, the relayed backend status on failure
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
   * @param id SK id
   * @param userId user whose flags to patch
   * @param form bound form carrying both flag values and the version
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/members/{userId}/flags", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> patchMemberFlagsAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @ModelAttribute MembershipFlagsForm form) {
    return okOrRelay(
        () -> {
          Map<String, Object> body = new HashMap<>();
          body.put("isLogistician", form.isLogistician());
          body.put("isMissionManager", form.isMissionManager());
          body.put("version", form.version());
          backendApiClient.patch(
              "/api/v1/special-commands/" + id + "/members/" + userId, body, Void.class);
        });
  }

  /**
   * In-place twin of {@link #toggleMemberLead}.
   *
   * @param id SK id
   * @param userId user whose membership to update
   * @param isLead new Lead state
   * @param version current optimistic-lock version
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/members/{userId}/lead", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> toggleMemberLeadAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestParam @NotNull Boolean isLead,
      @RequestParam @NotNull Long version) {
    return okOrRelay(
        () -> {
          Map<String, Object> body = new HashMap<>();
          body.put("isLead", isLead);
          body.put("version", version);
          backendApiClient.patch(
              "/api/v1/special-commands/" + id + "/members/" + userId + "/lead", body, Void.class);
        });
  }

  /**
   * Runs a Spezialkommando backend write and maps the outcome to an HTTP status: {@code 200} on
   * success, the relayed backend problem on a {@link BackendServiceException}, {@code 500}
   * otherwise. Shared by every SK / member AJAX twin.
   *
   * @param backendCall the backend mutation to perform
   * @return the mapped {@link ResponseEntity}
   */
  private ResponseEntity<Object> okOrRelay(Runnable backendCall) {
    try {
      backendCall.run();
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("SpecialCommand write (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("SpecialCommand write (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Evicts {@code STATIC_DATA_CACHE} after an SK <b>lifecycle</b> change (create / update /
   * soft-delete / re-activate / profit-eligible flip). The SK name, shorthand, active flag and
   * {@code isProfitEligible} feed the cached org-units owner-pickers ({@code GET
   * /api/v1/org-units/active…}) and the admin switcher's SK catalogue that {@code
   * SquadronContextAdvice} reads, so every catalogue-changing mutation must drop the shared cache
   * or those surfaces stay stale up to the 10-minute TTL. This is the eviction that REQ-DATA-007
   * gates SK-catalogue cacheability on. Member-roster mutations (add / remove / flags / lead) do
   * not touch the catalogue fields, so they deliberately do not evict.
   */
  private void evictOrgUnitCatalogueCache() {
    backendApiClient.evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
  }

  // ---------- helper fetchers for the detail page ---------------------------------

  private SpecialCommandDto fetchSpecialCommand(UUID id) {
    Map<String, Object> map = backendApiClient.get("/api/v1/special-commands/" + id, MAP_TYPE);
    if (map == null) {
      return null;
    }
    return new SpecialCommandDto(
        parseUuid(map.get("id")),
        parseString(map.get("name")),
        parseString(map.get("shorthand")),
        parseString(map.get("description")),
        parseBoolean(map.get("active")),
        parseBoolean(map.get("isProfitEligible")),
        parseLong(map.get("version")));
  }

  private List<OrgUnitMembershipDto> fetchMembers(UUID specialCommandId) {
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
                        parseUuid(m.get("userId")),
                        parseString(m.get("userDisplayName")),
                        parseUuid(m.get("orgUnitId")),
                        parseKind(m.get("kind")),
                        parseBoolean(m.get("isLogistician")),
                        parseBoolean(m.get("isMissionManager")),
                        parseBoolean(m.get("isLead")),
                        parseInstant(m.get("joinedAt")),
                        parseLong(m.get("version"))))
            .collect(Collectors.toCollection(ArrayList::new));
    members.sort(
        Comparator.comparing(
            m -> m.userDisplayName() == null ? "" : m.userDisplayName(),
            String.CASE_INSENSITIVE_ORDER));
    return members;
  }

  private List<UserReferenceDto> fetchUserLookup() {
    List<Map<String, Object>> raw = backendApiClient.get("/api/v1/users/lookup", MAP_LIST_TYPE);
    if (raw == null) {
      return List.of();
    }
    List<UserReferenceDto> users =
        raw.stream()
            .map(
                m ->
                    new UserReferenceDto(
                        parseUuid(m.get("id")),
                        parseString(m.get("username")),
                        parseString(m.get("displayName")),
                        parseString(m.get("effectiveName")),
                        parseInt(m.get("rank"))))
            .collect(Collectors.toCollection(ArrayList::new));
    users.sort(
        Comparator.comparing(
            u -> u.effectiveName() == null ? "" : u.effectiveName(),
            String.CASE_INSENSITIVE_ORDER));
    return users;
  }

  // ---------- payload-parsing helpers (mirror AdminMissionDataPageController) -----------

  private static String parseString(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static UUID parseUuid(Object o) {
    if (o == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(o));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean parseBoolean(Object o) {
    if (o == null) {
      return false;
    }
    if (o instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(o));
  }

  private static Long parseLong(Object o) {
    if (o == null) {
      return 0L;
    }
    if (o instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(o));
    } catch (Exception ignored) {
      return 0L;
    }
  }

  /**
   * Parses a {@link Number} or string-encoded integer into {@link Integer}. Used for the user's
   * {@code rank} field on the member-picker dropdown — Jackson decodes JSON integers as either
   * {@link Integer} or {@link Long} depending on size, so the helper accepts both.
   */
  private static Integer parseInt(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(o));
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Parses an ISO-8601 instant (or one of the alternative forms Jackson emits — long epoch millis
   * as a fallback) into {@link Instant}. The membership wire shape carries {@code joinedAt} as
   * ISO-8601; the conservative branching makes the helper resilient to a future format change
   * without crashing the detail page.
   */
  private static Instant parseInstant(Object o) {
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
   * Parses the {@code kind} string into the typed {@link OrgUnitKind} enum. Defaults to {@link
   * OrgUnitKind#SPECIAL_COMMAND} on unknown values — the detail page only ever renders SK
   * memberships (the parent SK existence gate in the backend filters anything else out), so a
   * malformed payload from a future schema change still lands as the most plausible value.
   */
  private static OrgUnitKind parseKind(Object o) {
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
