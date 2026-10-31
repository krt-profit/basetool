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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages.CompleteCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronForm;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Controller for the Staffeln tab of the organisation structure ({@code
 * /admin/org-structure/squadrons}): the squadron catalogue with create, update, soft delete and
 * re-activate, and the per-squadron switches for the promotion system and job-order processing
 * (REQ-UI-026, REQ-ADMIN-001).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/org-structure/squadrons")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSquadronsPageController {

  /** Reads and writes the squadron catalogue. */
  private final CatalogueBackendClient catalogueClient;

  /** Clears the catalogue caches after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Renders the complete squadron catalogue (REQ-ADMIN-001); a failed load renders empty with
   * {@code error.admin.mission.data.load}, a catalogue hitting the page-walk cap sets {@code
   * squadronsTruncated} (REQ-ADMIN-002).
   *
   * @param includeInactiveSquadrons show soft-deleted squadrons
   * @param fragment {@code "squadrons-results"} to render only the table (REQ-FE-002); otherwise
   *     the full page
   * @param model Thymeleaf model populated with the list, the form and the toggle
   * @return the {@code catalogue/admin/squadrons} view name, or its table fragment
   */
  @NotNull
  @GetMapping
  public String listSquadrons(
      @RequestParam(required = false, defaultValue = "false") boolean includeInactiveSquadrons,
      @RequestParam(required = false) String fragment,
      Model model) {
    if (!model.containsAttribute("squadronForm")) {
      model.addAttribute("squadronForm", new SquadronForm("", "", "", 0L));
    }
    model.addAttribute("includeInactiveSquadrons", includeInactiveSquadrons);
    try {
      CompleteCatalog<SquadronDto> catalog = fetchSquadrons(includeInactiveSquadrons);
      model.addAttribute("squadrons", catalog.items());
      model.addAttribute("squadronsTruncated", catalog.truncated());
    } catch (BackendServiceException e) {
      log.debug("Error loading squadrons", e);
      model.addAttribute("error", "error.admin.mission.data.load");
    }
    return "squadrons-results".equals(fragment)
        ? "catalogue/admin/squadrons :: squadrons-results"
        : "catalogue/admin/squadrons";
  }

  /**
   * Fetches the complete squadron catalog (REQ-ADMIN-001) as {@link SquadronDto}s sorted by name,
   * with the truncation flag (REQ-ADMIN-002).
   */
  @NotNull
  private CompleteCatalog<SquadronDto> fetchSquadrons(boolean includeInactive) {
    CompleteCatalog<SquadronDto> catalog =
        CatalogPages.fetchAll(page -> catalogueClient.squadronPage(includeInactive, page));
    List<SquadronDto> squadrons =
        catalog.items().stream()
            .map(s ->
                new SquadronDto(
                    s.id(),
                    s.name(),
                    s.shorthand(),
                    s.description(),
                    Boolean.TRUE.equals(s.active()),
                    Boolean.TRUE.equals(s.isPromotionEnabled()),
                    Boolean.TRUE.equals(s.isProfitEligible()),
                    versionOrZero(s.version())))
            .collect(Collectors.toCollection(ArrayList::new));
    squadrons.sort(
        Comparator.comparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER));
    return new CompleteCatalog<>(squadrons, catalog.totalElements(), catalog.truncated());
  }

  /**
   * Creates a squadron; a validation failure re-renders the page with the modal open and a 409
   * shows the duplicate-name toast.
   *
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping
  public String createSquadron(
      @Valid @ModelAttribute("squadronForm") SquadronForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "squadron-modal");
      model.addAttribute("modalAction", "/admin/org-structure/squadrons");
      return listSquadrons(false, null, model);
    }
    try {
      SquadronDto body =
          new SquadronDto(
              null, form.name(), form.shorthand(), form.description(), true, true, false, 0L);
      catalogueClient.createSquadron(body);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Create Squadron failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.squadron");
        return "redirect:/admin/org-structure/squadrons";
      }
      return "redirect:/admin/org-structure/squadrons?error=CreateSquadronFailed";
    }
    return "redirect:/admin/org-structure/squadrons";
  }

  /**
   * Updates a squadron; a 409 shows the concurrency or duplicate-name toast.
   *
   * @param id squadron id
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @return the list page on validation failure, otherwise redirect
   */
  @NotNull
  @PostMapping("/{id}/update")
  public String updateSquadron(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("squadronForm") SquadronForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "squadron-modal");
      model.addAttribute("modalAction", "/admin/org-structure/squadrons/" + id + "/update");
      return listSquadrons(false, null, model);
    }
    try {
      SquadronDto body =
          new SquadronDto(
              id,
              form.name(),
              form.shorthand(),
              form.description(),
              true,
              true,
              false,
              form.version());
      catalogueClient.updateSquadron(id, body);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Update Squadron failed", e);
      if (e.getStatusCode() == 409) {
        if ("concurrency-conflict".equals(e.getProblemType())) {
          redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
        } else {
          redirectAttributes.addFlashAttribute("errorToast", "error.duplicate.squadron");
        }
        return "redirect:/admin/org-structure/squadrons";
      }
      return "redirect:/admin/org-structure/squadrons?error=UpdateSquadronFailed";
    }
    return "redirect:/admin/org-structure/squadrons";
  }

  /**
   * Soft-deletes a squadron; a 409 shows the in-use toast.
   *
   * @param id squadron id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/org-structure/squadrons}
   */
  @NotNull
  @PostMapping("/{id}/delete")
  public String deleteSquadron(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.deleteSquadron(id);
      cacheEviction.clearStaticDataCache();
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      log.debug("Delete Squadron failed", e);
      if (e.getStatusCode() == 409) {
        redirectAttributes.addFlashAttribute("errorToast", "error.delete.squadron.in_use");
        return "redirect:/admin/org-structure/squadrons";
      }
      return "redirect:/admin/org-structure/squadrons?error=DeleteSquadronFailed";
    }
    return "redirect:/admin/org-structure/squadrons";
  }

  /**
   * Re-activates a soft-deleted squadron.
   *
   * @param id squadron id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/org-structure/squadrons}
   */
  @NotNull
  @PostMapping("/{id}/activate")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String activateSquadron(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    catalogueClient.activateSquadron(id);
    cacheEviction.clearStaticDataCache();
    redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    return "redirect:/admin/org-structure/squadrons";
  }

  /**
   * In-place twin of {@link #createSquadron}.
   *
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createSquadronAjax(
      @Valid @ModelAttribute("squadronForm") SquadronForm form, BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(() ->
        catalogueClient.createSquadron(
            new SquadronDto(
                null, form.name(), form.shorthand(), form.description(), true, true, false, 0L)));
  }

  /**
   * In-place twin of {@link #updateSquadron}.
   *
   * @param id squadron id
   * @param form squadron form
   * @param bindingResult validation errors carrier
   * @return {@code 200} on success, {@code 422} on a validation failure, the relayed backend status
   *     on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateSquadronAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("squadronForm") SquadronForm form,
      BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.status(422).build();
    }
    return okOrRelay(() ->
        catalogueClient.updateSquadron(
            id,
            new SquadronDto(
                id,
                form.name(),
                form.shorthand(),
                form.description(),
                true,
                true,
                false,
                form.version())));
  }

  /**
   * In-place twin of {@link #deleteSquadron}.
   *
   * @param id squadron id
   * @return {@code 200} on success, the relayed backend status on a conflict / failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteSquadronAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.deleteSquadron(id));
  }

  /**
   * In-place twin of {@link #activateSquadron}.
   *
   * @param id squadron id
   * @return {@code 200} on success, the relayed backend status on failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/activate", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> activateSquadronAjax(@PathVariable @NotNull UUID id) {
    return okOrRelay(() -> catalogueClient.activateSquadron(id));
  }

  /**
   * Runs a reference-data backend write, clears the static-data cache on success, and maps the
   * outcome to an HTTP status: {@code 200} on success, the relayed backend problem on a {@link
   * BackendServiceException}, {@code 500} otherwise.
   *
   * @param backendCall the backend mutation to perform
   * @return the mapped {@link ResponseEntity}
   */
  private ResponseEntity<Object> okOrRelay(Runnable backendCall) {
    return relay(
        log,
        "squadron write (ajax)",
        () -> {
          backendCall.run();
          cacheEviction.clearStaticDataCache();
          return ResponseEntity.ok().build();
        });
  }

  @NotNull
  private static Long versionOrZero(@Nullable Long version) {
    return version == null ? 0L : version;
  }
}
