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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceEntryForm;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for mission finance-entry CRUD ({@code /missions/{id}/finance-entries/**}).
 *
 * <p>Validation failures re-render the mission-detail view inline through {@link
 * MissionPageController}. The class-level {@code isAuthenticated()} gate is the floor for every
 * handler (REQ-SEC-052).
 */
@Slf4j
@Controller
@UsesLayoutModel
@RequestMapping("/missions/{id}/finance-entries")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MissionFinancePageController {

  /** The mission domain's typed backend client. */
  private final MissionBackendClient missionClient;

  /** Re-renders the mission detail inline on a validation failure. */
  private final MissionPageController missionPageController;

  /**
   * Creates a finance entry on a mission; the backend re-checks the write permission.
   *
   * @param id mission id
   * @param form finance-entry form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering (modal stays open)
   * @param redirectAttributes flash attributes carrier
   * @param principal OIDC user; never {@code null} below the class-level floor
   * @return inline {@code mission-detail} view on validation failure, otherwise redirect
   */
  @PostMapping
  public String addFinanceEntry(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("financeForm") MissionFinanceEntryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "finance-entry-modal");
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      missionClient.addFinanceEntry(
          new MissionFinanceEntryCreateDto(
              id, form.getParticipantId(), form.getNote(), form.getType(), form.getAmount()));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "addFinanceEntry", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.finance.add");
    } catch (Exception e) {
      log.error("Add finance entry failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.finance.add");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Updates a finance entry. The form carries the optimistic-lock version.
   *
   * @param id mission id (path)
   * @param entryId finance entry id (path)
   * @param form finance-entry form (carries the version field)
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering
   * @param redirectAttributes flash attributes carrier
   * @param principal OIDC user
   * @return inline {@code mission-detail} view on validation failure, otherwise redirect
   */
  @PostMapping("/{entryId}/update")
  public String updateFinanceEntry(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID entryId,
      @Valid @ModelAttribute("financeForm") MissionFinanceEntryForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "edit-finance-entry-modal");
      model.addAttribute(
          "modalAction", "/missions/" + id + "/finance-entries/" + entryId + "/update");
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      missionClient.updateFinanceEntry(
          entryId,
          new MissionFinanceEntryUpdateDto(
              form.getNote(), form.getType(), form.getAmount(), form.getVersion()));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "updateFinanceEntry", entryId, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.finance.update");
    } catch (Exception e) {
      log.error("Update finance entry failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.finance.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Deletes a finance entry.
   *
   * @param id mission id (path)
   * @param entryId finance entry id (path)
   * @param principal OIDC user
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{entryId}/delete")
  public String deleteFinanceEntry(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID entryId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.deleteFinanceEntry(entryId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "deleteFinanceEntry", entryId, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.finance.delete");
    } catch (Exception e) {
      log.error("Delete finance entry failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.finance.delete");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX variant of {@link #addFinanceEntry}, returning the created entry as JSON for an in-place
   * finance-pane update.
   *
   * @param id mission id (path)
   * @param body finance-entry JSON ({@code participantId}, {@code note}, {@code type}, {@code
   *     amount}); {@code missionId} is taken from the path
   * @param principal OIDC user; never {@code null} below the class-level floor
   * @return {@code 200} with the created entry, or the upstream RFC 7807 error passed through
   */
  @PostMapping(value = "/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addFinanceEntryAjax(
      @PathVariable @NotNull UUID id,
      @RequestBody MissionFinanceEntryCreateDto body,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "add finance entry (ajax) for mission " + id,
        () -> {
          MissionFinanceEntryDto result =
              missionClient.addFinanceEntryJson(
                  new MissionFinanceEntryCreateDto(
                      id, body.participantId(), body.note(), body.type(), body.amount()));
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX variant of {@link #updateFinanceEntry}, returning the updated entry as JSON for an
   * in-place finance-pane update.
   *
   * @param id mission id (path)
   * @param entryId finance entry id (path)
   * @param body finance-entry JSON ({@code note}, {@code type}, {@code amount}, {@code version})
   * @return {@code 200} with the updated entry, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{entryId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateFinanceEntryAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID entryId,
      @RequestBody MissionFinanceEntryUpdateDto body) {
    return relay(
        log,
        "update finance entry (ajax) for mission " + id + " entry " + entryId,
        () -> {
          MissionFinanceEntryDto result = missionClient.updateFinanceEntryJson(entryId, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX variant of {@link #deleteFinanceEntry}, answering {@code 204} for an in-place finance-pane
   * update.
   *
   * @param id mission id (path)
   * @param entryId finance entry id (path)
   * @return {@code 204} on success, or the upstream RFC 7807 error passed through
   */
  @DeleteMapping(value = "/{entryId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteFinanceEntryAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID entryId) {
    return relay(
        log,
        "delete finance entry (ajax) for mission " + id + " entry " + entryId,
        () -> {
          missionClient.deleteFinanceEntry(entryId);
          return ResponseEntity.noContent().build();
        });
  }
}
