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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;
import static de.greluc.krt.profit.basetool.frontend.mission.web.MissionWriteValues.parseToInstant;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionParticipantDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.ParticipantForm;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdatePayoutPreferenceRequest;
import jakarta.validation.Valid;
import java.util.List;
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

/** Handles mission participation, check-in and payout preferences. */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionParticipantWriteController {

  private final MissionBackendClient missionClient;
  private final MissionPageController missionPageController;

  /**
   * Adds a registered or guest participant from a validated mission form.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/participant")
  public String addParticipant(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("participantForm") ParticipantForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "participant-modal");
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      AddExternalParticipantRequest request =
          new AddExternalParticipantRequest(
              form.userId(),
              form.guestName() != null && !form.guestName().isBlank() ? form.guestName() : null,
              form.desiredJobTypeId(),
              form.comment(),
              form.orgUnitIds() != null && !form.orgUnitIds().isEmpty() ? form.orgUnitIds() : null,
              form.payoutPreference() != null ? form.payoutPreference().name() : null);

      missionClient.addParticipant(id, request);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Add participant failed with status {}: {}", e.getStatusCode(), e.getMessage());
      String toastKey =
          (e.getStatusCode() == 409)
              ? "error.mission.participant.ambiguous"
              : "error.mission.participant.add";
      redirectAttributes.addFlashAttribute("errorToast", toastKey);
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Marks a mission participant as checked in.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/participants/{participantId}/check-in")
  public String checkInParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.checkInParticipant(id, participantId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "checkInParticipant", participantId, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that marks a participant as checked out.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/participants/{participantId}/check-out")
  public String checkOutParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.checkOutParticipant(id, participantId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "checkOutParticipant", participantId, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Updates one participant's payout preference ({@code PAYOUT} / {@code DONATE}) and returns that
   * participant row with its bumped version.
   *
   * @param id mission id
   * @param participantId the participant row
   * @param request the new preference
   * @param principal the signed-in member (unused; the backend decides from the token)
   * @return the updated participant row, or the backend's status and RFC 7807 problem relayed
   *     unchanged
   */
  @PostMapping("/{id}/participants/{participantId}/payout-preference")
  @ResponseBody
  public ResponseEntity<Object> updatePayoutPreference(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody UpdatePayoutPreferenceRequest request,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "update payout preference",
        () -> {
          MissionParticipantDto updatedParticipant =
              missionClient.updatePayoutPreference(id, participantId, request);
          return ResponseEntity.ok(updatedParticipant);
        });
  }

  /**
   * Form-post endpoint that removes a participant from a mission.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/participants/{participantId}/delete")
  public String deleteParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.deleteParticipant(id, participantId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "deleteParticipant", participantId, e);
      return "redirect:/missions/" + id + "?error=error.mission.participant.delete";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that edits a participant's metadata (job type, ship type, etc.).
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/participants/{participantId}/update")
  public String updateParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @Valid @ModelAttribute("participantForm") ParticipantForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "edit-participant-modal");
      model.addAttribute(
          "modalAction", "/missions/" + id + "/participants/" + participantId + "/update");
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      UpdateParticipantRequest request =
          new UpdateParticipantRequest(
              form.desiredJobTypeId(),
              form.plannedMissionJobTypeId(),
              form.comment(),
              null,
              parseToInstant(form.startTime()),
              parseToInstant(form.endTime()),
              form.orgUnitIds(),
              form.payoutPreference() != null ? form.payoutPreference().name() : null,
              form.version());

      missionClient.updateParticipant(id, participantId, request);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.error("Update participant failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): adds a participant via the Slim backend
   * endpoint and returns the resulting slim participant list so the mission detail page can refresh
   * without losing pending input in other sub-panels (Option A: sub-section writes must not bump
   * Mission.version).
   */
  @PostMapping(value = "/{id}/participants/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addParticipantAjax(
      @PathVariable @NotNull UUID id,
      @RequestBody AddExternalParticipantRequest body,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "add participant (ajax) for mission " + id,
        () -> {
          List<MissionParticipantDto> result = missionClient.addParticipantJson(id, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): updates a participant via the Slim
   * backend endpoint and returns the updated slim participant as JSON.
   */
  @PutMapping(
      value = "/{id}/participants/{participantId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody UpdateParticipantRequest body,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "update participant (ajax) for mission " + id + " participant " + participantId,
        () -> {
          MissionParticipantDto result =
              missionClient.updateParticipantJson(id, participantId, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): deletes a participant via the Slim
   * backend endpoint.
   */
  @DeleteMapping(
      value = "/{id}/participants/{participantId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "delete participant (ajax) for mission " + id + " participant " + participantId,
        () -> {
          missionClient.deleteParticipant(id, participantId);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): checks a participant in via the Slim
   * backend endpoint.
   */
  @PostMapping(
      value = "/{id}/participants/{participantId}/check-in/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> checkInParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "check in participant (ajax) for mission " + id + " participant " + participantId,
        () -> {
          MissionParticipantDto result = missionClient.checkInParticipantJson(id, participantId);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): checks a participant out via the Slim
   * backend endpoint.
   */
  @PostMapping(
      value = "/{id}/participants/{participantId}/check-out/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> checkOutParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "check out participant (ajax) for mission " + id + " participant " + participantId,
        () -> {
          MissionParticipantDto result = missionClient.checkOutParticipantJson(id, participantId);
          return ResponseEntity.ok(result);
        });
  }
}
