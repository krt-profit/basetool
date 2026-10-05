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
import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.model.form.SpecialCommandForm;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.service.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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
 * Controller for the admin Spezialkommando page ({@code /admin/special-commands}): list, create,
 * update, soft-delete and re-activate, plus the admin-only SK-lead toggle. Spezialkommandos carry
 * no promotion toggle.
 *
 * <p>The SK member page is {@link SpecialCommandMembersPageController} (REQ-ORG-005).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/special-commands")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSpecialCommandsPageController {

  /** Base path of the SK member page the lead toggle and the old detail URL redirect to. */
  private static final String MEMBER_PAGE_BASE = "/organisation/special-commands/";

  /** Reads the Spezialkommandos and sends their lifecycle writes. */
  private final OrgUnitBackendClient orgUnitClient;

  /** Drops the cached org-unit catalogues after a lifecycle write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Renders the SK list, seeding an empty form unless a validation re-render already supplied one.
   *
   * @param includeInactive whether to include soft-deleted SKs.
   * @param fragment {@code "results"} to render only the SK list (REQ-FE-002).
   * @param model Thymeleaf model populated with the SK list, the form and the toggle.
   * @return the {@code admin/special-commands} view name, or its {@code results} fragment.
   */
  @NotNull
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
      CompleteCatalog<SpecialCommandDto> catalog = fetchSpecialCommands(includeInactive);
      model.addAttribute("specialCommands", catalog.items());
      model.addAttribute("catalogTruncated", catalog.truncated());
    } catch (BackendServiceException e) {
      log.debug("Error loading SpecialCommands", e);
      model.addAttribute("specialCommands", List.of());
      model.addAttribute("error", "error.admin.specialcommands.load");
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
   * Fetches every page of the SK catalog from the backend (REQ-ADMIN-001) as {@link
   * SpecialCommandDto} records.
   *
   * @param includeInactive forwarded as the backend's {@code includeInactive} parameter.
   * @return SKs sorted case-insensitively by name plus the truncation flag; never {@code null}.
   */
  @NotNull
  private CompleteCatalog<SpecialCommandDto> fetchSpecialCommands(boolean includeInactive) {
    CompleteCatalog<SpecialCommandDto> catalog =
        CatalogPages.fetchAll(page -> orgUnitClient.specialCommandPage(includeInactive, page));
    List<SpecialCommandDto> commands =
        catalog.items().stream()
            .map(AdminSpecialCommandsPageController::withDefaults)
            .collect(Collectors.toCollection(ArrayList::new));
    commands.sort(
        Comparator.comparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER));
    return new CompleteCatalog<>(commands, catalog.totalElements(), catalog.truncated());
  }

  /**
   * Fills the flags and the version of a decoded Spezialkommando that the backend sent as {@code
   * null}, so the list renders {@code false} and {@code 0} for them.
   *
   * @param sc the decoded Spezialkommando
   * @return the Spezialkommando with {@code active}, {@code isProfitEligible} and {@code version}
   *     never {@code null}
   */
  @NotNull
  private static SpecialCommandDto withDefaults(@NotNull SpecialCommandDto sc) {
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
   * Creates a Spezialkommando. Validation failures re-render the list with the create modal open; a
   * duplicate-name 409 shows its own toast; other failures redirect with an error parameter.
   *
   * @param form SK form payload.
   * @param bindingResult validation errors carrier.
   * @param model Thymeleaf model used for the inline re-render.
   * @param redirectAttributes flash-attribute carrier for the toast.
   * @return the inline list page on validation failure, otherwise a redirect to {@code
   *     /admin/special-commands}.
   */
  @NotNull
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
      orgUnitClient.createSpecialCommand(body);
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
   * Updates a Spezialkommando, telling an optimistic-lock conflict apart from a duplicate-name 409.
   *
   * @param id SK id.
   * @param form SK form, carrying the version.
   * @param bindingResult validation errors carrier.
   * @param model Thymeleaf model used for the inline re-render.
   * @param redirectAttributes flash-attribute carrier.
   * @return the inline list page on failure, otherwise a redirect.
   */
  @NotNull
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
      orgUnitClient.updateSpecialCommand(id, body);
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
   * Soft-deletes a Spezialkommando; a backend 409 shows the "in use" toast.
   *
   * @param id SK id.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to {@code /admin/special-commands}.
   */
  @NotNull
  @PostMapping("/{id}/delete")
  public String deleteSpecialCommand(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      orgUnitClient.deleteSpecialCommand(id);
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
  @NotNull
  @PostMapping("/{id}/activate")
  public String activateSpecialCommand(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      orgUnitClient.activateSpecialCommand(id);
      evictOrgUnitCatalogueCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Activate SpecialCommand failed", e);
      return "redirect:/admin/special-commands?error=ActivateSpecialCommandFailed";
    }
    return "redirect:/admin/special-commands";
  }

  /**
   * Redirects the former SK detail URL to the member page, dropping query parameters.
   *
   * @param id Spezialkommando id.
   * @return redirect to {@code /organisation/special-commands/{id}}.
   */
  @NotNull
  @GetMapping("/{id}")
  public String detailRedirect(@PathVariable @NotNull UUID id) {
    return "redirect:" + MEMBER_PAGE_BASE + id;
  }

  /**
   * Toggles the Spezialkommando-Lead flag on a membership (no-JS fallback). ADMIN only here; a lead
   * can never promote anyone.
   *
   * @param id Spezialkommando id.
   * @param userId user whose membership to update.
   * @param isLead new Lead state.
   * @param version current optimistic-lock version held by the form.
   * @param redirectAttributes flash-attribute carrier.
   * @return redirect to the SK member page, with an {@code error} parameter on a failure other than
   *     409.
   */
  @NotNull
  @PostMapping("/{id}/members/{userId}/lead")
  public String toggleMemberLead(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestParam @NotNull Boolean isLead,
      @RequestParam @NotNull Long version,
      RedirectAttributes redirectAttributes) {
    try {
      orgUnitClient.toggleSpecialCommandLead(
          id, userId, new MembershipLeadToggleRequest(isLead, version));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Toggle SpecialCommand member lead failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        return "redirect:" + MEMBER_PAGE_BASE + id;
      }
      return "redirect:" + MEMBER_PAGE_BASE + id + "?error=ToggleLeadFailed";
    } catch (Exception e) {
      log.error("Toggle SpecialCommand member lead failed", e);
      return "redirect:" + MEMBER_PAGE_BASE + id + "?error=ToggleLeadFailed";
    }
    return "redirect:" + MEMBER_PAGE_BASE + id;
  }

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
          orgUnitClient.createSpecialCommand(
              new SpecialCommandDto(
                  null, form.name(), form.shorthand(), form.description(), true, false, 0L));
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
          orgUnitClient.updateSpecialCommand(
              id,
              new SpecialCommandDto(
                  id,
                  form.name(),
                  form.shorthand(),
                  form.description(),
                  true,
                  false,
                  form.version()));
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
          orgUnitClient.deleteSpecialCommand(id);
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
          orgUnitClient.activateSpecialCommand(id);
          evictOrgUnitCatalogueCache();
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
          orgUnitClient.toggleSpecialCommandLead(
              id, userId, new MembershipLeadToggleRequest(isLead, version));
        });
  }

  /**
   * Runs a Spezialkommando backend write and maps the outcome to an HTTP status: {@code 200} on
   * success, the relayed backend problem on a {@link BackendServiceException}, {@code 500}
   * otherwise. Shared by every SK-lifecycle AJAX twin and the lead-toggle twin.
   *
   * @param backendCall the backend mutation to perform
   * @return the mapped {@link ResponseEntity}
   */
  private ResponseEntity<Object> okOrRelay(Runnable backendCall) {
    return relay(
        log,
        "specialCommand write (ajax)",
        () -> {
          backendCall.run();
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Evicts the {@link CacheDomain#SQUADRON} and {@link CacheDomain#ORG_UNIT} caches after an SK
   * lifecycle change, so cached org-unit pickers and catalogues pick it up (REQ-DATA-007). Member
   * roster changes do not need it.
   */
  private void evictOrgUnitCatalogueCache() {
    cacheEviction.evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
  }
}
