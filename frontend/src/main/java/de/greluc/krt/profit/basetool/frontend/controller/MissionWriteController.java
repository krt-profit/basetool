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
import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionActualTimeUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.model.form.CrewForm;
import de.greluc.krt.profit.basetool.frontend.model.form.MissionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ParticipantForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
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
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring MVC controller for every state-mutating {@code /missions} endpoint: participants, units
 * and crews, managers and ownership, party lead, frequencies, Ablauf steps, goals, payout
 * preference, actual times, and mission create/update/delete.
 *
 * <p>Classic form-post validation failures re-render through the injected {@link
 * MissionPageController}; AJAX failures relay the upstream RFC 7807 problem through {@link
 * MissionPageController#propagateBackendError}. The class-level {@code isAuthenticated()} gate is
 * the floor for every handler (REQ-SEC-052).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionWriteController {

  /**
   * Response type for the single-mission {@code /api/v1/missions/{id}} read that refreshes the
   * payload after a party-lead or owning-org-unit write.
   */
  private static final ParameterizedTypeReference<MissionDto> MISSION =
      new ParameterizedTypeReference<MissionDto>() {};

  /**
   * Typed backend REST facade carrying out every mission mutation (and the post-mutation re-reads
   * some AJAX handlers return), on the public WebClient for the guest-flow endpoints when no OIDC
   * principal is present.
   */
  private final BackendApiClient backendApiClient;

  /**
   * Resolves {@code @Valid} field-error messages for {@link #updateMissionAjax} exactly as {@code
   * th:errors} does.
   */
  private final MessageSource messageSource;

  /**
   * Read-side mission controller, used to re-render the detail or create view inline on a
   * validation failure of a classic form post.
   */
  private final MissionPageController missionPageController;

  /**
   * Server-side live-sync publish seam that notifies the global {@code missions} list room after a
   * mission create, core update or delete (REQ-FE-015).
   */
  private final LiveSyncLocalBus liveSyncLocalBus;

  /** The single {@code list} section of the global {@code missions} room a core mutation pokes. */
  private static final List<String> MISSIONS_LIST_SECTION = List.of("list");

  /**
   * Jackson mapper that parses the create form's {@code objectivesJson} / {@code stepsJson}
   * carriers, failing on unknown properties.
   */
  private final JsonMapper objectMapper =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /**
   * Registers a {@code StringTrimmerEditor(true)} for this controller's mission forms, overriding
   * the global String editor.
   *
   * @param binder Spring data binder for the current request
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
  }

  /**
   * Form-post endpoint that adds a participant to a mission. Public endpoint — anyone can join,
   * authenticated or not (a guest provides a handle, an authenticated user is auto-resolved).
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
      Map<String, Object> body = new HashMap<>();
      if (form.userId() != null) {
        body.put("userId", form.userId());
      }
      if (form.guestName() != null && !form.guestName().isBlank()) {
        body.put("guestName", form.guestName());
      }
      if (form.desiredJobTypeId() != null) {
        body.put("desiredJobTypeId", form.desiredJobTypeId());
      }
      if (form.orgUnitIds() != null && !form.orgUnitIds().isEmpty()) {
        body.put("orgUnitIds", form.orgUnitIds());
      }
      if (form.payoutPreference() != null) {
        body.put("payoutPreference", form.payoutPreference().name());
      }
      body.put("comment", form.comment());

      backendApiClient.post("/api/v1/missions/" + id + "/participants/add", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Add participant failed with status {}: {}", e.getStatusCode(), e.getMessage());
      String toastKey =
          (e.getStatusCode() == 409)
              ? "error.mission.participant.ambiguous"
              : "error.mission.participant.add";
      redirectAttributes.addFlashAttribute("errorToast", toastKey);
    } catch (Exception e) {
      log.error("Add participant failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.add");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Assigns or clears a mission's party lead (Partyleiter) from a form post, then reloads the page.
   *
   * <p>A picked member arrives as {@code userId}; free text arrives as {@code guestName} and is
   * resolved server-side. An empty submission clears the party lead.
   *
   * @param id mission id
   * @param userId resolved registered-user id from the autocomplete, or {@code null}
   * @param guestName free-text party-lead handle, or {@code null}
   * @param version expected {@code partyLeadVersion} echoed back from the rendered page
   * @param redirectAttributes flash-scoped toast carrier
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/party-lead")
  public String setPartyLead(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) UUID userId,
      @RequestParam(required = false) String guestName,
      @RequestParam(required = false) Long version,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      if (userId != null) {
        body.put("userId", userId);
      }
      if (guestName != null && !guestName.isBlank()) {
        body.put("guestName", guestName);
      }
      body.put("version", version != null ? version : 0L);
      backendApiClient.put("/api/v1/missions/" + id + "/party-lead", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Set party lead failed with status {}: {}", e.getStatusCode(), e.getMessage());
      String toastKey =
          (e.getStatusCode() == 409)
              ? "error.mission.party_lead.conflict"
              : "error.mission.party_lead.update";
      redirectAttributes.addFlashAttribute("errorToast", toastKey);
    } catch (Exception e) {
      log.error("Set party lead failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.party_lead.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX variant of {@link #setPartyLead}, returning the refreshed mission as JSON; a 409 is
   * relayed as RFC 7807.
   *
   * @param id mission id (path)
   * @param body party-lead JSON ({@code userId} and/or {@code guestName}, plus {@code version}); an
   *     empty {@code userId} and {@code guestName} clear the lead
   * @return {@code 200} with the refreshed mission, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{id}/party-lead/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> setPartyLeadAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "set party lead (ajax) for mission " + id,
        () -> {
          Map<String, Object> out = new HashMap<>();
          Object userId = body.get("userId");
          if (userId != null && !String.valueOf(userId).isBlank()) {
            out.put("userId", userId);
          }
          Object guestName = body.get("guestName");
          if (guestName != null && !String.valueOf(guestName).isBlank()) {
            out.put("guestName", guestName);
          }
          out.put("version", body.get("version") != null ? body.get("version") : 0L);
          backendApiClient.put("/api/v1/missions/" + id + "/party-lead", out, Void.class);
          MissionDto mission = backendApiClient.get("/api/v1/missions/" + id, MISSION);
          return ResponseEntity.ok(mission);
        });
  }

  /**
   * Form-post endpoint that marks a participant as checked in. Public for the guest-flow.
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
      backendApiClient.post(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/check-in/slim",
          null,
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "checkInParticipant", participantId, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    } catch (Exception e) {
      log.error("Check-in participant failed", e);
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
      backendApiClient.post(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/check-out/slim",
          null,
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "checkOutParticipant", participantId, e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    } catch (Exception e) {
      log.error("Check-out participant failed", e);
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
          Object updatedParticipant =
              backendApiClient.put(
                  "/api/v1/missions/"
                      + id
                      + "/participants/"
                      + participantId
                      + "/payout-preference/slim",
                  request,
                  Object.class);
          return ResponseEntity.ok(updatedParticipant);
        });
  }

  /**
   * Sets the mission's actual start or end time to the supplied UTC instant via {@code PATCH
   * /api/v1/missions/{id}/schedule}, locked on the client's {@code scheduleVersion}.
   */
  @PostMapping("/{id}/actual-time")
  @ResponseBody
  public ResponseEntity<MissionDto> updateActualTime(
      @PathVariable @NotNull UUID id, @Valid @RequestBody MissionActualTimeUpdateRequest request) {
    if (request == null
        || request.version() == null
        || (!"actualStartTime".equals(request.field())
            && !"actualEndTime".equals(request.field()))) {
      return ResponseEntity.badRequest().build();
    }
    try {
      MissionDto current = backendApiClient.get("/api/v1/missions/" + id, MissionDto.class);
      if (current == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      Instant newStart =
          "actualStartTime".equals(request.field()) ? request.value() : current.actualStartTime();
      Instant newEnd =
          "actualEndTime".equals(request.field()) ? request.value() : current.actualEndTime();

      Map<String, Object> schedulePatch = new LinkedHashMap<>();
      schedulePatch.put("meetingTime", current.meetingTime());
      schedulePatch.put("plannedStartTime", current.plannedStartTime());
      schedulePatch.put("plannedEndTime", current.plannedEndTime());
      schedulePatch.put("actualStartTime", newStart);
      schedulePatch.put("actualEndTime", newEnd);
      schedulePatch.put("version", request.version());

      backendApiClient.patch("/api/v1/missions/" + id + "/schedule", schedulePatch, Void.class);
      MissionDto refreshed = backendApiClient.get("/api/v1/missions/" + id, MissionDto.class);
      return ResponseEntity.ok(refreshed);
    } catch (BackendServiceException e) {
      log.debug("Update actual time failed with status {}: {}", e.getStatusCode(), e.getMessage());
      HttpStatus status;
      switch (e.getStatusCode()) {
        case 409 -> status = HttpStatus.CONFLICT;
        case 403, 401 -> status = HttpStatus.FORBIDDEN;
        case 404 -> status = HttpStatus.NOT_FOUND;
        default -> status = HttpStatus.INTERNAL_SERVER_ERROR;
      }
      return ResponseEntity.status(status).build();
    } catch (Exception e) {
      log.error("Update actual time failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
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
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/slim", Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "deleteParticipant", participantId, e);
      return "redirect:/missions/" + id + "?error=error.mission.participant.delete";
    } catch (Exception e) {
      log.error("Delete participant failed", e);
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
      Map<String, Object> body = new HashMap<>();
      if (form.desiredJobTypeId() != null) {
        body.put("desiredMissionJobTypeId", form.desiredJobTypeId());
      }
      if (form.plannedMissionJobTypeId() != null) {
        body.put("plannedMissionJobTypeId", form.plannedMissionJobTypeId());
      }
      if (form.orgUnitIds() != null) {
        body.put("orgUnitIds", form.orgUnitIds());
      }
      body.put("comment", form.comment());
      if (form.startTime() != null && !form.startTime().isBlank()) {
        Instant parsed = parseToInstant(form.startTime());
        if (parsed != null) {
          body.put("startTime", parsed.toString());
        }
      }
      if (form.endTime() != null && !form.endTime().isBlank()) {
        Instant parsed = parseToInstant(form.endTime());
        if (parsed != null) {
          body.put("endTime", parsed.toString());
        }
      }
      if (form.payoutPreference() != null) {
        body.put("payoutPreference", form.payoutPreference().name());
      }
      if (form.version() != null) {
        body.put("version", form.version());
      }

      backendApiClient.put(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/slim", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update participant failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that adds a unit (team grouping) to the mission.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/units")
  public String addUnit(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) UUID shipTypeId,
      @RequestParam(required = false) UUID shipId,
      @RequestParam(required = false, defaultValue = "false") boolean highValueUnit,
      @RequestParam(required = false) Double frequency,
      @RequestParam(required = false) UUID responsibleUserId,
      @RequestParam(required = false) String note,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("name", name);
      body.put("shipTypeId", shipTypeId);
      if (shipId != null) {
        body.put("shipId", shipId);
      }
      body.put("highValueUnit", highValueUnit);
      body.put("frequency", frequency);
      body.put("responsibleUserId", responsibleUserId);
      body.put("note", note);

      backendApiClient.post("/api/v1/missions/" + id + "/units/slim", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Add unit failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.unit.add";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that edits a unit's metadata.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/units/{unitId}/update")
  public String updateUnit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) UUID shipTypeId,
      @RequestParam(required = false) UUID shipId,
      @RequestParam(required = false, defaultValue = "false") boolean highValueUnit,
      @RequestParam(required = false) Double frequency,
      @RequestParam(required = false) UUID responsibleUserId,
      @RequestParam(required = false) String note,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("name", name);
      body.put("shipTypeId", shipTypeId);
      if (shipId != null) {
        body.put("shipId", shipId);
      }
      body.put("highValueUnit", highValueUnit);
      body.put("frequency", frequency);
      body.put("responsibleUserId", responsibleUserId);
      body.put("note", note);

      backendApiClient.put(
          "/api/v1/missions/" + id + "/units/" + unitId + "/slim", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update unit failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.unit.update";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that removes a unit from the mission. The backend reassigns any participants
   * in the deleted unit to the default unit so no participant becomes orphaned.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/units/{unitId}/delete")
  public String deleteUnit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/missions/" + id + "/units/" + unitId + "/slim", Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Delete unit failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.unit.delete";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that creates a crew (ship-grouping) under a unit.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/units/{unitId}/crew")
  public String addCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @Valid @ModelAttribute("crewForm") CrewForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "assign-crew-modal");
      model.addAttribute("modalAction", "/missions/" + id + "/units/" + unitId + "/crew");
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("participantId", form.participantId());
      if (form.jobTypeIds() != null && !form.jobTypeIds().isEmpty()) {
        body.put("jobTypeIds", form.jobTypeIds());
      }

      backendApiClient.post(
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew/slim", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Add crew failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.crew.add");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that edits a crew (ship choice, lead participant, etc.).
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/units/{unitId}/crew/{crewId}/update")
  public String updateCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId,
      @Valid @ModelAttribute("crewForm") CrewForm form,
      BindingResult bindingResult,
      Model model,
      RedirectAttributes redirectAttributes,
      @AuthenticationPrincipal OidcUser principal) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("openModal", "edit-crew-modal");
      model.addAttribute(
          "modalAction", "/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/update");
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      Map<String, Object> body = new HashMap<>();
      if (form.jobTypeIds() != null && !form.jobTypeIds().isEmpty()) {
        body.put("jobTypeIds", form.jobTypeIds());
      } else {
        body.put("jobTypeIds", List.of());
      }

      backendApiClient.put(
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/slim",
          body,
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update crew failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.crew.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that removes a crew. Participants assigned to the removed crew fall back to
   * the unit's default slot.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/units/{unitId}/crew/{crewId}/delete")
  public String deleteCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/slim", Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Delete crew failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.crew.delete";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that persists a new mission. Validation failures re-render the create form
   * inline (BindingResult stays request-scoped). The page hands the freshly-created mission's id
   * back via a redirect to its detail page on success.
   *
   * @return inline create view on failure, otherwise redirect to {@code /missions/{newId}}
   */
  @PostMapping
  public String createMission(
      @Valid @ModelAttribute("missionForm") MissionForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      return missionPageController.createMissionForm(model, principal, null);
    }
    try {
      Instant meetingTime =
          (form.meetingTime() != null && !form.meetingTime().isBlank())
              ? parseToInstant(form.meetingTime())
              : null;
      Instant plannedStartTime =
          (form.plannedStartTime() != null && !form.plannedStartTime().isBlank())
              ? parseToInstant(form.plannedStartTime())
              : null;
      Instant plannedEndTime =
          (form.plannedEndTime() != null && !form.plannedEndTime().isBlank())
              ? parseToInstant(form.plannedEndTime())
              : null;

      OperationDto operation =
          (form.operationId() != null && !form.operationId().isBlank())
              ? new OperationDto(
                  UUID.fromString(form.operationId()),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null)
              : null;

      List<CreateMissionRequest.NewObjective> objectives =
          parseCreateList(
              form.objectivesJson(),
              new TypeReference<List<CreateMissionRequest.NewObjective>>() {});
      List<CreateMissionRequest.NewStep> steps =
          parseCreateList(
              form.stepsJson(), new TypeReference<List<CreateMissionRequest.NewStep>>() {});

      CreateMissionRequest createRequest =
          new CreateMissionRequest(
              form.name(),
              form.description(),
              form.calendarLink(),
              form.status(),
              meetingTime,
              plannedStartTime,
              plannedEndTime,
              form.isInternal(),
              operation != null ? operation.id() : null,
              form.owningOrgUnitId(),
              form.meetingPoint(),
              objectives,
              steps);

      MissionDto created =
          backendApiClient.post("/api/v1/missions", createRequest, MissionDto.class);
      liveSyncLocalBus.publish("missions", MISSIONS_LIST_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
      return "redirect:/missions/" + created.id() + "?tab=verw";
    } catch (Exception e) {
      log.error("Create mission failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.create");
      redirectAttributes.addFlashAttribute("missionForm", form);
      return "redirect:/missions/new";
    }
  }

  /**
   * Parses one of the create form's JSON row carriers into a nested create-request list.
   *
   * @param json the hidden carrier's raw JSON, or {@code null}/blank when the section is empty
   * @param typeRef the target list element type
   * @param <T> the nested create-request element type ({@code NewObjective} / {@code NewStep})
   * @return the parsed list, or {@code null} when the carrier is blank or the array is empty
   * @throws JacksonException when the carrier holds malformed JSON or an unknown property
   */
  @Nullable
  private <T> List<T> parseCreateList(String json, TypeReference<List<T>> typeRef) {
    if (json == null || json.isBlank()) {
      return null;
    }
    List<T> list = objectMapper.readValue(json, typeRef);
    return (list == null || list.isEmpty()) ? null : list;
  }

  /**
   * Saves mission edits from the classic form post, re-rendering the detail page on a validation
   * failure; the no-JavaScript fallback for {@link #updateMissionAjax}.
   *
   * @return redirect to the mission detail page
   */
  @PostMapping("/{id}")
  public String updateMission(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("missionForm") MissionForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      return missionPageController.missionDetail(id, model, principal, null);
    }
    try {
      applyMissionUpdate(id, form);
      liveSyncLocalBus.publish("missions", MISSIONS_LIST_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update mission failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.update");
      redirectAttributes.addFlashAttribute("missionForm", form);
      return "redirect:/missions/" + id;
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Applies a validated {@link MissionForm} as up to three section PATCHes (schedule, core, flags),
   * each with its own version counter.
   *
   * <p>Schedule is patched first because the core patch's PLANNED → ACTIVE transition bumps the
   * schedule version. A section whose dirty flag is {@code false} is skipped; a {@code null} flag
   * means "save" (REQ-FE-014).
   *
   * @param id the mission id
   * @param form the validated submitted form
   */
  private void applyMissionUpdate(@NotNull UUID id, @NotNull MissionForm form) {
    boolean saveSchedule = form.dirtySchedule() == null || form.dirtySchedule();
    boolean saveCore = form.dirtyCore() == null || form.dirtyCore();
    boolean saveFlags = form.dirtyFlags() == null || form.dirtyFlags();

    if (saveSchedule) {
      Instant meetingTime =
          (form.meetingTime() != null && !form.meetingTime().isBlank())
              ? parseToInstant(form.meetingTime())
              : null;
      Instant plannedStartTime =
          (form.plannedStartTime() != null && !form.plannedStartTime().isBlank())
              ? parseToInstant(form.plannedStartTime())
              : null;
      Instant plannedEndTime =
          (form.plannedEndTime() != null && !form.plannedEndTime().isBlank())
              ? parseToInstant(form.plannedEndTime())
              : null;
      Instant actualStartTime =
          (form.actualStartTime() != null && !form.actualStartTime().isBlank())
              ? parseToInstant(form.actualStartTime())
              : null;
      Instant actualEndTime =
          (form.actualEndTime() != null && !form.actualEndTime().isBlank())
              ? parseToInstant(form.actualEndTime())
              : null;

      Map<String, Object> schedulePatch = new LinkedHashMap<>();
      schedulePatch.put("meetingTime", meetingTime);
      schedulePatch.put("plannedStartTime", plannedStartTime);
      schedulePatch.put("plannedEndTime", plannedEndTime);
      schedulePatch.put("actualStartTime", actualStartTime);
      schedulePatch.put("actualEndTime", actualEndTime);
      schedulePatch.put("version", form.scheduleVersion());
      backendApiClient.patch("/api/v1/missions/" + id + "/schedule", schedulePatch, Void.class);
    }

    if (saveCore) {
      UUID operationId =
          (form.operationId() != null && !form.operationId().isBlank())
              ? UUID.fromString(form.operationId())
              : null;
      Map<String, Object> corePatch = new LinkedHashMap<>();
      corePatch.put("name", form.name());
      corePatch.put("description", form.description());
      corePatch.put("calendarLink", form.calendarLink());
      corePatch.put("status", form.status());
      corePatch.put("operationId", operationId);
      corePatch.put("meetingPoint", form.meetingPoint());
      corePatch.put("version", form.coreVersion());
      backendApiClient.patch("/api/v1/missions/" + id + "/core", corePatch, Void.class);
    }

    if (saveFlags) {
      Map<String, Object> flagsPatch = new LinkedHashMap<>();
      flagsPatch.put("isInternal", form.isInternal() != null && form.isInternal());
      flagsPatch.put("version", form.flagsVersion());
      backendApiClient.patch("/api/v1/missions/" + id + "/flags", flagsPatch, Void.class);
    }
  }

  /**
   * AJAX twin of {@link #updateMission}, selected by the {@code X-Requested-With} header.
   *
   * <p>Returns {@code 422} with a {@code {field: message}} map on a validation failure; on success
   * returns the four fresh versions so a following save does not 409. Backend errors are relayed
   * via {@link #propagateBackendError}.
   *
   * @param id the mission id
   * @param form the bound + validated edit form
   * @param bindingResult the binding/validation result
   * @param locale the request locale for field-error message resolution
   * @return {@code 200} with the four versions, {@code 422} with the field-error map, or the
   *     propagated backend error
   */
  @PostMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> updateMissionAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("missionForm") MissionForm form,
      BindingResult bindingResult,
      Locale locale) {
    if (bindingResult.hasErrors()) {
      Map<String, String> fieldErrors = new LinkedHashMap<>();
      for (FieldError fe : bindingResult.getFieldErrors()) {
        String message;
        try {
          message = messageSource.getMessage(fe, locale);
        } catch (NoSuchMessageException ex) {
          message = fe.getDefaultMessage();
        }
        fieldErrors.putIfAbsent(fe.getField(), message);
      }
      return ResponseEntity.unprocessableContent().body(fieldErrors);
    }
    return relay(
        log,
        "update mission (ajax) for " + id,
        () -> {
          applyMissionUpdate(id, form);
          liveSyncLocalBus.publish("missions", MISSIONS_LIST_SECTION);
          MissionDto refreshed = backendApiClient.get("/api/v1/missions/" + id, MissionDto.class);
          Map<String, Object> versions = new LinkedHashMap<>();
          versions.put("version", refreshed.version());
          versions.put("coreVersion", refreshed.coreVersion());
          versions.put("scheduleVersion", refreshed.scheduleVersion());
          versions.put("flagsVersion", refreshed.flagsVersion());
          return ResponseEntity.ok(versions);
        });
  }

  /**
   * Form-post endpoint that deletes (or cancels) a mission. The backend cascades by detaching
   * inventory/refinery references rather than hard-deleting them, per the CHANGELOG entry for the
   * deleteMission change.
   *
   * @return redirect to {@code /missions}
   */
  @NotNull
  @PostMapping("/{id}/delete")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String deleteMission(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/missions/" + id, Void.class);
      liveSyncLocalBus.publish("missions", MISSIONS_LIST_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.mission_delete");
    } catch (Exception e) {
      log.error("Delete mission failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.delete");
      return "redirect:/missions/" + id;
    }
    return "redirect:/missions";
  }

  /**
   * AJAX endpoint that adds a co-manager to a mission. Co-managers can edit the mission like the
   * owner; the owner cannot be removed via this endpoint.
   *
   * @return 200 on success, propagated backend status on failure
   */
  @PostMapping("/{id}/managers/{userId}")
  @ResponseBody
  public ResponseEntity<Object> addManager(@PathVariable String id, @PathVariable String userId) {
    log.debug("START addManager - id: '{}', userId: '{}'", id, userId);
    try {
      if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
        log.debug("MISSING PARAMETERS - id: '{}', userId: '{}'", id, userId);
        return ResponseEntity.badRequest().build();
      }
      UUID missionUuid;
      UUID userUuid;
      try {
        missionUuid = UUID.fromString(id.trim());
      } catch (IllegalArgumentException e) {
        log.debug("INVALID MISSION ID FORMAT - id: '{}', Error: {}", id, e.getMessage());
        return ResponseEntity.badRequest().build();
      }
      try {
        userUuid = UUID.fromString(userId.trim());
      } catch (IllegalArgumentException e) {
        log.debug("INVALID USER ID FORMAT - userId: '{}', Error: {}", userId, e.getMessage());
        return ResponseEntity.badRequest().build();
      }

      log.debug("CALLING BACKEND - Mission: {}, User: {}", missionUuid, userUuid);
      try {
        backendApiClient.post(
            "/api/v1/missions/" + missionUuid + "/managers/" + userUuid + "/slim",
            null,
            String.class);
        log.debug("SUCCESS - Manager {} added to mission {}", userUuid, missionUuid);
        return ResponseEntity.ok().build();
      } catch (BackendServiceException e) {
        log.debug(
            "BACKEND ERROR adding manager for mission {} and user {}: Status={}, Message={},"
                + " Readable={}",
            missionUuid,
            userUuid,
            e.getStatusCode(),
            e.getMessage(),
            e.getReadableErrorMessage());
        return propagateBackendError(e);
      }
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in addManager: id='{}', userId='{}', error={}",
          id,
          userId,
          e.getMessage(),
          e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint that removes a co-manager. The owner is protected — removing the owner requires
   * {@link #setMissionOwner} instead.
   *
   * @return 200 on success, propagated backend status on failure
   */
  @DeleteMapping("/{id}/managers/{userId}")
  @ResponseBody
  public ResponseEntity<Object> removeManager(
      @PathVariable String id, @PathVariable String userId) {
    log.debug("START removeManager - id: '{}', userId: '{}'", id, userId);
    return relay(
        log,
        "remove manager " + userId + " from mission " + id,
        () -> {
          if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
            log.debug("MISSING PARAMETERS in removeManager - id: '{}', userId: '{}'", id, userId);
            return ResponseEntity.badRequest().build();
          }
          UUID missionUuid;
          UUID userUuid;
          try {
            missionUuid = UUID.fromString(id.trim());
          } catch (IllegalArgumentException e) {
            log.debug(
                "INVALID MISSION ID FORMAT in removeManager - id: '{}', Error: {}",
                id,
                e.getMessage());
            return ResponseEntity.badRequest().build();
          }
          try {
            userUuid = UUID.fromString(userId.trim());
          } catch (IllegalArgumentException e) {
            log.debug(
                "INVALID USER ID FORMAT in removeManager - userId: '{}', Error: {}",
                userId,
                e.getMessage());
            return ResponseEntity.badRequest().build();
          }

          log.debug("CALLING BACKEND DELETE - Mission: {}, User: {}", missionUuid, userUuid);
          backendApiClient.delete(
              "/api/v1/missions/" + missionUuid + "/managers/" + userUuid + "/slim", Object.class);
          log.debug("SUCCESS DELETE - Manager {} removed from mission {}", userUuid, missionUuid);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Hands a mission to another owner via {@code PUT /api/v1/missions/{id}/owner}, checked against
   * its {@code ownershipVersion}, and returns the refreshed mission.
   *
   * <p>The previous owner is not added to the co-managers. A stale version is relayed as the
   * backend's {@code 409}.
   *
   * @param id mission id (path)
   * @param body owner-change JSON: {@code userId} (a UUID string) plus {@code version}
   * @return {@code 200} with the refreshed mission, {@code 400} for a missing or malformed {@code
   *     userId}, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{id}/owner/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> setMissionOwner(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    UUID userId;
    try {
      Object raw = body.get("userId");
      if (raw == null || String.valueOf(raw).isBlank()) {
        return ResponseEntity.badRequest().build();
      }
      userId = UUID.fromString(String.valueOf(raw).trim());
    } catch (IllegalArgumentException e) {
      log.debug("Owner change refused: userId is not a UUID");
      return ResponseEntity.badRequest().build();
    }
    try {
      Map<String, Object> out = new HashMap<>();
      out.put("userId", userId);
      out.put("version", body.get("version") != null ? body.get("version") : 0L);
      backendApiClient.put("/api/v1/missions/" + id + "/owner", out, Void.class);
      MissionDto mission = backendApiClient.get("/api/v1/missions/" + id, MISSION);
      return ResponseEntity.ok(mission);
    } catch (BackendServiceException e) {
      if (e.getStatusCode() == 409) {
        log.debug("Owner change for mission {} conflicted with a concurrent change", id);
        return propagateBackendError(e);
      }
      log.debug("Owner change (AJAX) failed: status={}", e.getStatusCode());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in setMissionOwner for mission {}", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Reassigns a mission's owning org unit, checked against its {@code owningOrgUnitVersion}
   * (REQ-ORG-018).
   *
   * @param id mission id (path)
   * @param body reassignment JSON: {@code owningOrgUnitId} (a UUID string, or blank/absent for
   *     ownerless) plus {@code version}
   * @return {@code 200} on success, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{id}/owning-org-unit/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> setMissionOwningOrgUnit(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "set mission owning org unit for mission " + id,
        () -> {
          Map<String, Object> out = new HashMap<>();
          Object owningOrgUnitId = body.get("owningOrgUnitId");
          out.put(
              "owningOrgUnitId",
              (owningOrgUnitId != null && !String.valueOf(owningOrgUnitId).isBlank())
                  ? owningOrgUnitId
                  : null);
          out.put("version", body.get("version") != null ? body.get("version") : 0L);
          backendApiClient.put("/api/v1/missions/" + id + "/owning-org-unit", out, Void.class);
          MissionDto mission = backendApiClient.get("/api/v1/missions/" + id, MISSION);
          return ResponseEntity.ok(mission);
        });
  }

  /**
   * Form-post endpoint that creates or updates a frequency entry (radio channel) for the mission.
   * Same endpoint serves both create and update — the form's id field discriminates.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/frequencies")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public String addOrUpdateFrequency(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull UUID frequencyTypeId,
      @RequestParam @NotNull BigDecimal value,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("frequencyTypeId", frequencyTypeId);
      body.put("value", value);

      backendApiClient.post("/api/v1/missions/" + id + "/frequencies/slim", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Add or update frequency failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.frequency.update";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * Form-post endpoint that removes a frequency entry.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/frequencies/{frequencyId}/delete")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public String deleteFrequency(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID frequencyId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/frequencies/" + frequencyId + "/slim", Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Delete frequency failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.frequency.delete";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX endpoint for Paket 3B: submits a frequency add/update via the Slim backend endpoint and
   * returns the resulting slim list as JSON so that the mission detail page can update the DOM in
   * place without a full reload. Enables concurrent editing of the frequencies sub-panel without
   * forcing other users to re-enter their pending changes (Option A).
   */
  @PutMapping(value = "/{id}/frequencies/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public ResponseEntity<Object> addOrUpdateFrequencyAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "add or update frequency (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/frequencies/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3B: deletes a frequency via the Slim backend endpoint and returns the
   * resulting slim list as JSON.
   */
  @DeleteMapping(
      value = "/{id}/frequencies/{frequencyId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public ResponseEntity<Object> deleteFrequencyAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID frequencyId) {
    return relay(
        log,
        "delete frequency (ajax) for mission " + id + " freq " + frequencyId,
        () -> {
          Object result =
              backendApiClient.delete(
                  "/api/v1/missions/" + id + "/frequencies/" + frequencyId + "/slim", Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Adds a custom mission frequency (REQ-MISSION-014) and returns the updated frequency list.
   *
   * @param id the mission id
   * @param body the JSON payload ({@code name} + {@code value})
   * @return the updated frequency list, or the propagated backend error
   */
  @PostMapping(value = "/{id}/frequencies/custom/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public ResponseEntity<Object> addCustomFrequencyAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "add custom frequency (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/frequencies/custom/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Updates a custom mission frequency (REQ-MISSION-014) under its row version and returns the
   * updated frequency list.
   *
   * @param id the mission id
   * @param frequencyId the custom frequency row id
   * @param body the JSON payload ({@code name} + {@code value} + {@code version})
   * @return the updated frequency list, or the propagated backend error
   */
  @PutMapping(
      value = "/{id}/frequencies/custom/{frequencyId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public ResponseEntity<Object> updateCustomFrequencyAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID frequencyId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "update custom frequency (ajax) for mission " + id + " freq " + frequencyId,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/frequencies/custom/" + frequencyId + "/slim",
                  body,
                  Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C: adds a unit via the Slim backend endpoint and returns the resulting
   * slim unit list so the mission detail page can refresh without losing pending input in other
   * sub-panels (Option A).
   */
  @PostMapping(value = "/{id}/units/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addUnitAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "add unit (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.post("/api/v1/missions/" + id + "/units/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C: updates a unit via the Slim backend endpoint and returns the
   * updated slim unit as JSON.
   */
  @PutMapping(value = "/{id}/units/{unitId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateUnitAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "update unit (ajax) for mission " + id + " unit " + unitId,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/units/" + unitId + "/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /** AJAX endpoint for Paket 3C: deletes a unit via the Slim backend endpoint. */
  @DeleteMapping(value = "/{id}/units/{unitId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteUnitAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID unitId) {
    return relay(
        log,
        "delete unit (ajax) for mission " + id + " unit " + unitId,
        () -> {
          backendApiClient.delete(
              "/api/v1/missions/" + id + "/units/" + unitId + "/slim", Void.class);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Appends an Ablauf step and returns the ordered step list.
   *
   * @param id the mission id
   * @param body the step payload (title, optional meta, expected stepsVersion)
   * @return the ordered step list, or the propagated backend error
   */
  @PostMapping(value = "/{id}/steps/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addStepAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "add step (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.post("/api/v1/missions/" + id + "/steps/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: edits an Ablauf step's title / time-place hint and returns the ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param body the step payload (title, optional meta, expected stepsVersion)
   * @return the ordered step list, or the propagated backend error
   */
  @PutMapping(value = "/{id}/steps/{stepId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateStepAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "update step (ajax) for mission " + id + " step " + stepId,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/steps/" + stepId + "/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Removes an Ablauf step and returns the remaining ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param stepsVersion the expected steps-section version, passed as a query parameter
   * @return the ordered step list, or the propagated backend error
   */
  @DeleteMapping(value = "/{id}/steps/{stepId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteStepAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestParam @NotNull Long stepsVersion) {
    return relay(
        log,
        "delete step (ajax) for mission " + id + " step " + stepId,
        () -> {
          Object result =
              backendApiClient.delete(
                  "/api/v1/missions/"
                      + id
                      + "/steps/"
                      + stepId
                      + "/slim?stepsVersion="
                      + stepsVersion,
                  Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: reorders the mission's Ablauf steps and returns the new ordered step list.
   *
   * @param id the mission id
   * @param body the desired step-id order + expected stepsVersion
   * @return the ordered step list, or the propagated backend error
   */
  @PutMapping(value = "/{id}/steps/reorder/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> reorderStepsAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "reorder steps (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/steps/reorder/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Sets an Ablauf step's shared done flag and returns the ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param body the new done state + expected stepsVersion
   * @return the ordered step list, or the propagated backend error
   */
  @PatchMapping(
      value = "/{id}/steps/{stepId}/done/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> toggleStepDoneAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "toggle step done (ajax) for mission " + id + " step " + stepId,
        () -> {
          Object result =
              backendApiClient.patch(
                  "/api/v1/missions/" + id + "/steps/" + stepId + "/done/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Appends a goal (Ziel) and returns the ordered goal list.
   *
   * @param id the mission id
   * @param body the goal payload (title, kind, expected objectivesVersion)
   * @return the ordered goal list, or the propagated backend error
   */
  @PostMapping(value = "/{id}/objectives/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addObjectiveAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "add objective (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/objectives/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: edits a goal's text / classification and returns the ordered goal list.
   *
   * @param id the mission id
   * @param objectiveId the goal id
   * @param body the goal payload (title, kind, expected objectivesVersion)
   * @return the ordered goal list, or the propagated backend error
   */
  @PutMapping(
      value = "/{id}/objectives/{objectiveId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "update objective (ajax) for mission " + id + " objective " + objectiveId,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/objectives/" + objectiveId + "/slim",
                  body,
                  Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Removes a goal and returns the remaining ordered goal list.
   *
   * @param id the mission id
   * @param objectiveId the goal id
   * @param objectivesVersion the expected goals-section version, passed as a query parameter
   * @return the ordered goal list, or the propagated backend error
   */
  @DeleteMapping(
      value = "/{id}/objectives/{objectiveId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestParam @NotNull Long objectivesVersion) {
    return relay(
        log,
        "delete objective (ajax) for mission " + id + " objective " + objectiveId,
        () -> {
          Object result =
              backendApiClient.delete(
                  "/api/v1/missions/"
                      + id
                      + "/objectives/"
                      + objectiveId
                      + "/slim?objectivesVersion="
                      + objectivesVersion,
                  Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: reorders the mission's goals and returns the new ordered goal list.
   *
   * @param id the mission id
   * @param body the desired goal-id order + expected objectivesVersion
   * @return the ordered goal list, or the propagated backend error
   */
  @PutMapping(value = "/{id}/objectives/reorder/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> reorderObjectivesAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "reorder objectives (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/objectives/reorder/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
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
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "add participant (ajax) for mission " + id,
        () -> {
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/participants/slim", body, Object.class);
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
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal OidcUser principal) {
    return relay(
        log,
        "update participant (ajax) for mission " + id + " participant " + participantId,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/participants/" + participantId + "/slim",
                  body,
                  Object.class);
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
          backendApiClient.delete(
              "/api/v1/missions/" + id + "/participants/" + participantId + "/slim", Void.class);
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
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/participants/" + participantId + "/check-in/slim",
                  null,
                  Object.class);
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
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/participants/" + participantId + "/check-out/slim",
                  null,
                  Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option c - Crew): adds a crew member to a unit via the Slim backend
   * endpoint and returns the resulting slim crew list.
   */
  @PostMapping(
      value = "/{id}/units/{unitId}/crew/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addCrewAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "add crew (ajax) for mission " + id + " unit " + unitId,
        () -> {
          Object result =
              backendApiClient.post(
                  "/api/v1/missions/" + id + "/units/" + unitId + "/crew/slim", body, Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option c - Crew): updates a crew member via the Slim backend
   * endpoint and returns the updated slim crew entry.
   */
  @PutMapping(
      value = "/{id}/units/{unitId}/crew/{crewId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateCrewAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId,
      @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "update crew (ajax) for mission " + id + " unit " + unitId + " crew " + crewId,
        () -> {
          Object result =
              backendApiClient.put(
                  "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/slim",
                  body,
                  Object.class);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX endpoint for Paket 3C (Option c - Crew): deletes a crew member via the Slim backend
   * endpoint.
   */
  @DeleteMapping(
      value = "/{id}/units/{unitId}/crew/{crewId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteCrewAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId) {
    return relay(
        log,
        "delete crew (ajax) for mission " + id + " unit " + unitId + " crew " + crewId,
        () -> {
          backendApiClient.delete(
              "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/slim",
              Void.class);
          return ResponseEntity.noContent().build();
        });
  }

  /** Time zone in which zoneless mission schedule values are interpreted. */
  private static final ZoneId MISSION_TIME_ZONE = ZoneId.of("Europe/Berlin");

  /**
   * Parses a hidden datetime-input value into an {@link Instant}.
   *
   * <p>Accepts a zone- or offset-bearing value, a zoneless local datetime of any fractional-second
   * precision, or a bare date; zoneless values are interpreted in {@link #MISSION_TIME_ZONE}.
   *
   * @param dateTimeStr the hidden input value; {@code null}/blank yields {@code null}
   * @return the parsed instant, or {@code null} if the value is blank or unparseable
   */
  @Nullable
  private Instant parseToInstant(String dateTimeStr) {
    if (dateTimeStr == null || dateTimeStr.isBlank()) {
      return null;
    }
    final String value = dateTimeStr.trim();
    try {
      if (value.length() == 10) {
        return LocalDate.parse(value).atStartOfDay(MISSION_TIME_ZONE).toInstant();
      }
      TemporalAccessor parsed =
          DateTimeFormatter.ISO_DATE_TIME.parseBest(
              value, OffsetDateTime::from, LocalDateTime::from);
      return parsed instanceof OffsetDateTime odt
          ? odt.toInstant()
          : ((LocalDateTime) parsed).atZone(MISSION_TIME_ZONE).toInstant();
    } catch (Exception e) {
      log.warn("Failed to parse datetime string: {}", LogSafe.text(value, 64), e);
      return null;
    }
  }
}
