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

import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.model.form.MissionFinanceEntryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
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
 * <p>Carved out from {@link MissionPageController} so the file stays manageable. Validation
 * failures re-render the mission-detail view inline by delegating to {@code
 * missionPageController.missionDetail(...)} — that keeps the BindingResult request-scoped (avoiding
 * the Redis-FlashMap self-reference crash) and preserves the modal-open flag so the user sees the
 * form with errors instead of an empty page. The injected {@link MissionPageController} is a Spring
 * proxy, so its method-level {@code @PreAuthorize} still fires when called via this delegation.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor. Every
 * handler here used to sit under a {@code permitAll} URL rule, and thirteen of them across this
 * package carried no gate of their own at all — protected by a matcher two folders away rather than
 * by anything next to the code. A method-level gate still wins where one is present.
 */
@Slf4j
@Controller
@RequestMapping("/missions/{id}/finance-entries")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MissionFinancePageController {

  private final BackendApiClient backendApiClient;
  private final MissionPageController missionPageController;
  private final FrontendAuthHelperService authHelper;

  /**
   * Creates a finance entry on a mission. {@code permitAll()} reflects the project's guest-mode for
   * mission finances — the backend still gates write access at the JWT layer when needed.
   *
   * @param id mission id
   * @param form finance-entry form
   * @param bindingResult validation errors carrier
   * @param model Thymeleaf model used for inline re-rendering (modal stays open)
   * @param redirectAttributes flash attributes carrier
   * @param principal OIDC user, may be {@code null} for guests
   * @return inline {@code mission-detail} view on validation failure, otherwise redirect
   */
  @PostMapping
  @PreAuthorize("permitAll()")
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
      Map<String, Object> body = new HashMap<>();
      body.put("missionId", id);
      body.put("participantId", form.getParticipantId());
      body.put("note", form.getNote());
      body.put("type", form.getType());
      body.put("amount", form.getAmount());

      backendApiClient.post("/api/v1/finance-entries", body, Void.class);
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
   * Updates a finance entry. The form carries the optimistic-lock version. Authenticated-only —
   * guest write is restricted to {@code POST} (create) above.
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
  @PreAuthorize("isAuthenticated()")
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
      Map<String, Object> body = new HashMap<>();
      body.put("note", form.getNote());
      body.put("type", form.getType());
      body.put("amount", form.getAmount());
      body.put("version", form.getVersion());

      backendApiClient.put("/api/v1/finance-entries/" + entryId, body, Void.class);
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
  @PostMapping("/{entryId}/delete")
  @PreAuthorize("isAuthenticated()")
  public String deleteFinanceEntry(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID entryId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/finance-entries/" + entryId, Void.class);
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
   * AJAX variant of {@link #addFinanceEntry}: creates a finance entry and returns the backend
   * payload as JSON so the mission-detail finance pane can swap in place without a full reload
   * (#574). The classic {@code POST} above stays the no-JavaScript fallback.
   *
   * @param id mission id (path)
   * @param body finance-entry JSON ({@code participantId}, {@code note}, {@code type}, {@code
   *     amount}); {@code missionId} is stamped from the path
   * @param principal OIDC user bound from the security context. The guest-vs-authenticated routing
   *     it used to steer is gone with the guest (ADR-0159)
   * @return {@code 200} with the created entry, or the upstream RFC 7807 error passed through
   */
  @PostMapping(value = "/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("permitAll()")
  public ResponseEntity<Object> addFinanceEntryAjax(
      @PathVariable @NotNull UUID id,
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      body.put("missionId", id);
      Object result = backendApiClient.post("/api/v1/finance-entries", body, Object.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug("Add finance entry (AJAX) failed: status={}", e.getStatusCode());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("UNEXPECTED ERROR in addFinanceEntryAjax for mission {}", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX variant of {@link #updateFinanceEntry}: updates a finance entry (carrying the optimistic-
   * lock version) and returns the updated entry as JSON for an in-place finance-pane swap (#574).
   *
   * @param id mission id (path)
   * @param entryId finance entry id (path)
   * @param body finance-entry JSON ({@code note}, {@code type}, {@code amount}, {@code version})
   * @return {@code 200} with the updated entry, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{entryId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> updateFinanceEntryAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID entryId,
      @RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put("/api/v1/finance-entries/" + entryId, body, Object.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug("Update finance entry (AJAX) failed: status={}", e.getStatusCode());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error(
          "UNEXPECTED ERROR in updateFinanceEntryAjax for mission {} entry {}", id, entryId, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX variant of {@link #deleteFinanceEntry}: deletes a finance entry and answers {@code 204} so
   * the mission-detail finance pane can swap in place without a full reload (#574).
   *
   * @param id mission id (path)
   * @param entryId finance entry id (path)
   * @return {@code 204} on success, or the upstream RFC 7807 error passed through
   */
  @DeleteMapping(value = "/{entryId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> deleteFinanceEntryAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID entryId) {
    try {
      backendApiClient.delete("/api/v1/finance-entries/" + entryId, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.debug("Delete finance entry (AJAX) failed: status={}", e.getStatusCode());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error(
          "UNEXPECTED ERROR in deleteFinanceEntryAjax for mission {} entry {}", id, entryId, e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
