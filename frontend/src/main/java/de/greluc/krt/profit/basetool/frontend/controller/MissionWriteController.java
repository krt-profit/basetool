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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.model.form.CrewForm;
import de.greluc.krt.profit.basetool.frontend.model.form.MissionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ParticipantForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.context.MessageSource;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for every state-mutating {@code /missions} endpoint: participant
 * join/edit/delete/check-in/check-out (classic form posts and their slim AJAX twins), units and
 * crews, managers/owner/owning-org-unit, party lead, frequencies (typed and custom), Ablauf steps,
 * goals (Ziele), payout preference, actual-time stamping and the mission create/update/delete
 * flows.
 *
 * <p>Carved out of {@link MissionPageController} in the #924 L5 read/write split. Every handler
 * body moved over verbatim - routes, security annotations and behaviour are unchanged; validation
 * failures of the classic form posts re-render the mission-detail or create view by delegating to
 * the injected read controller, and AJAX failures re-emit the upstream RFC 7807 problem through
 * {@link MissionPageController#propagateBackendError}. Several participant endpoints (add,
 * check-in/check-out, payout preference and the participant slim-AJAX family) deliberately carry no
 * {@code @PreAuthorize} so anonymous guests can join missions and manage their own entries; adding
 * security there is a known live-bug regression.
 */
@Controller
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
public class MissionWriteController {

  /**
   * Response type for the single-mission {@code /api/v1/missions/{id}} read. Verbatim private
   * mirror of the read-side {@code MissionPageController} constant (#924, L5): the party-lead and
   * owning-org-unit AJAX writes re-read the mission after a successful mutation to return the
   * refreshed payload to the in-place fragment update.
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
   * Resolves Jakarta {@code @Valid} field-error messages for the {@link #updateMissionAjax} twin's
   * {@code {field: message}} JSON contract, using the same {@code
   * messageSource.getMessage(fieldError, locale)} resolution Thymeleaf's {@code th:errors} performs
   * — so an inline validation message is byte-identical to the classic full-page re-render and the
   * well-tested validation UX is preserved.
   */
  private final MessageSource messageSource;

  /**
   * Read-side mission controller. A Jakarta {@code @Valid} failure on one of the classic form posts
   * must re-render the fully populated mission-detail (or create) view inline - the BindingResult
   * stays request-scoped and the modal re-opens with the field errors - so those handlers delegate
   * to {@code missionPageController.missionDetail(...)} / {@code createMissionForm(...)}, following
   * the precedent set by {@link MissionFinancePageController}. The injected instance is a Spring
   * proxy, so the read methods' own security annotations still fire when called via this
   * delegation.
   */
  private final MissionPageController missionPageController;

  /**
   * Centralised anonymous-principal predicate. The guest-flow endpoints route to the public
   * WebClient when no OIDC principal is present; this helper replaces the inlined
   * {@code @AuthenticationPrincipal OidcUser principal == null} guard with a single, mock-friendly
   * seam (Q10) — on this frontend the only authenticated principal type is the Keycloak {@code
   * OidcUser}, so a null principal and an anonymous security context are the same condition.
   */
  private final FrontendAuthHelperService authHelper;

  /**
   * Parses the create form's {@code objectivesJson} / {@code stepsJson} hidden carriers into the
   * backend create request's nested {@code objectives} / {@code steps} lists. A controller-owned
   * instance — the frontend application context registers no {@code ObjectMapper} bean, and
   * Jackson's mapper is thread-safe once configured — initialised at declaration so Lombok's {@code
   * RequiredArgsConstructor} keeps it out of the generated constructor signature.
   */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Web binder configuration scoped to this controller. Registers any custom property editors
   * needed for the mission forms (currently only the inherited default editors).
   *
   * <p>Verbatim duplicate of the read-side {@code MissionPageController} binder (#924, L5): the
   * write handlers bind the mission forms and must keep the {@code StringTrimmerEditor(true)}
   * semantics - the global {@code GlobalBindingAdvice} registers a different String editor, so
   * dropping this local binder would silently change String binding on every form post.
   *
   * @param binder Spring data binder for the current request
   */
  @InitBinder
  public void initBinder(WebDataBinder binder) {
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
      // Render directly; BindingResult stays request-scoped (see RedisSessionConfig).
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

      boolean isPublic = authHelper.isAnonymous();
      backendApiClient.post(
          "/api/v1/missions/" + id + "/participants/add", body, Void.class, isPublic);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Add participant failed with status {}: {}", e.getStatusCode(), e.getMessage());
      // 409 Conflict = backend found more than one registered member matching the free-text name
      // -> show a dedicated, localized hint that the user should pick an entry from the
      // autocomplete.
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
   * Form-post endpoint that assigns or clears a mission's party lead (Partyleiter). Reuses the same
   * resolution mechanic as participant-add: the autocomplete fills the hidden {@code userId} when a
   * registered member is picked, otherwise the free-text {@code guestName} is submitted and
   * resolved server-side (a unique member match is linked, an unknown name is kept as a guest
   * handle). An empty submission clears the party lead. {@code version} carries the mission's
   * current {@code partyLeadVersion} for optimistic-lock validation. A full reload follows so the
   * freshly bumped version is re-rendered without manual DOM version sync.
   *
   * @param id mission id
   * @param userId resolved registered-user id from the autocomplete, or {@code null}
   * @param guestName free-text party-lead handle, or {@code null}
   * @param version expected {@code partyLeadVersion} echoed back from the rendered page
   * @param redirectAttributes flash-scoped toast carrier
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/party-lead")
  @PreAuthorize("isAuthenticated()")
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
      backendApiClient.put("/api/v1/missions/" + id + "/party-lead", body, Void.class, false);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Set party lead failed with status {}: {}", e.getStatusCode(), e.getMessage());
      // 409 = either an ambiguous free-text name (matches more than one member) or a stale
      // partyLeadVersion (someone else changed it meanwhile); a single conflict toast covers both
      // and the reload below shows the current value.
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
   * AJAX variant of {@link #setPartyLead}: assigns or clears the party lead and returns the
   * refreshed mission as JSON so the mission-detail page can patch the party-lead display + bumped
   * {@code partyLeadVersion} in place without a full reload (#574). The classic form-POST above
   * stays the no-JavaScript fallback; the 409 (ambiguous name / stale version) is passed through as
   * RFC 7807 so the shared {@code krtFetch} conflict UX fires.
   *
   * @param id mission id (path)
   * @param body party-lead JSON ({@code userId} and/or {@code guestName}, plus {@code version}); an
   *     empty {@code userId}+{@code guestName} clears the lead
   * @return {@code 200} with the refreshed mission, or the upstream RFC 7807 error passed through
   */
  @PutMapping(
      value = "/{id}/party-lead/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> setPartyLeadAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    try {
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
      backendApiClient.put("/api/v1/missions/" + id + "/party-lead", out, Void.class, false);
      MissionDto mission = backendApiClient.get("/api/v1/missions/" + id, MISSION, false);
      return org.springframework.http.ResponseEntity.ok(mission);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Set party lead (AJAX) failed: status={}", e.getStatusCode());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in setPartyLeadAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Form-post endpoint that marks a participant as checked in. Public for the guest-flow.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/participants/{participantId}/check-in")
  public String checkInParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      backendApiClient.post(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/check-in",
          null,
          Void.class,
          isPublic);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
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
  @PostMapping("/{id}/participants/{participantId}/check-out")
  public String checkOutParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      backendApiClient.post(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/check-out",
          null,
          Void.class,
          isPublic);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Check-out participant failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.participant.update");
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX endpoint that updates a participant's payout preference (target wallet / split rules).
   * Public so guests can change their own preference on missions they joined unauthenticated.
   *
   * @return the updated mission, or the propagated backend status on failure
   */
  @PostMapping("/{id}/participants/{participantId}/payout-preference")
  @ResponseBody
  public org.springframework.http.ResponseEntity<MissionDto> updatePayoutPreference(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody UpdatePayoutPreferenceRequest request,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      MissionDto updatedMission =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/participants/" + participantId + "/payout-preference",
              request,
              MissionDto.class,
              isPublic);
      return org.springframework.http.ResponseEntity.ok(updatedMission);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Update payout preference failed with status {}: {}", e.getStatusCode(), e.getMessage());
      if (e.getStatusCode() == 403 || e.getStatusCode() == 401) {
        return org.springframework.http.ResponseEntity.status(
                org.springframework.http.HttpStatus.FORBIDDEN)
            .build();
      }
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    } catch (Exception e) {
      log.error("Update payout preference failed", e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Sets the actual start or end time of a mission to the supplied UTC instant and saves
   * immediately. The client sends the current schedule-section counter ({@code scheduleVersion})
   * from the DOM; this engages optimistic locking on the dedicated schedule section, which means a
   * concurrent edit of the core or flags section never triggers a 409 here (and vice versa).
   *
   * <p>The endpoint fetches the current schedule values from the backend, overlays the single field
   * the client requested ({@code actualStartTime} or {@code actualEndTime}) and dispatches a single
   * {@code PATCH /api/v1/missions/{id}/schedule}.
   */
  @PostMapping("/{id}/actual-time")
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<MissionDto> updateActualTime(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody
          de.greluc.krt.profit.basetool.frontend.model.dto.MissionActualTimeUpdateRequest request) {
    if (request == null
        || request.version() == null
        || (!"actualStartTime".equals(request.field())
            && !"actualEndTime".equals(request.field()))) {
      return org.springframework.http.ResponseEntity.badRequest().build();
    }
    try {
      MissionDto current = backendApiClient.get("/api/v1/missions/" + id, MissionDto.class);
      if (current == null) {
        return org.springframework.http.ResponseEntity.status(
                org.springframework.http.HttpStatus.NOT_FOUND)
            .build();
      }

      Instant newStart =
          "actualStartTime".equals(request.field()) ? request.value() : current.actualStartTime();
      Instant newEnd =
          "actualEndTime".equals(request.field()) ? request.value() : current.actualEndTime();

      Map<String, Object> schedulePatch = new java.util.LinkedHashMap<>();
      schedulePatch.put("meetingTime", current.meetingTime());
      schedulePatch.put("plannedStartTime", current.plannedStartTime());
      schedulePatch.put("plannedEndTime", current.plannedEndTime());
      schedulePatch.put("actualStartTime", newStart);
      schedulePatch.put("actualEndTime", newEnd);
      schedulePatch.put("version", request.version()); // scheduleVersion from the DOM

      backendApiClient.patch("/api/v1/missions/" + id + "/schedule", schedulePatch, Void.class);
      MissionDto refreshed = backendApiClient.get("/api/v1/missions/" + id, MissionDto.class);
      return org.springframework.http.ResponseEntity.ok(refreshed);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update actual time failed with status {}: {}", e.getStatusCode(), e.getMessage());
      org.springframework.http.HttpStatus status;
      switch (e.getStatusCode()) {
        case 409 -> status = org.springframework.http.HttpStatus.CONFLICT;
        case 403, 401 -> status = org.springframework.http.HttpStatus.FORBIDDEN;
        case 404 -> status = org.springframework.http.HttpStatus.NOT_FOUND;
        default -> status = org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
      }
      return org.springframework.http.ResponseEntity.status(status).build();
    } catch (Exception e) {
      log.error("Update actual time failed", e);
      return org.springframework.http.ResponseEntity.status(
              org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
          .build();
    }
  }

  /**
   * Form-post endpoint that removes a participant from a mission.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/participants/{participantId}/delete")
  public String deleteParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/participants/" + participantId, Void.class, isPublic);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
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
        java.time.Instant parsed = parseToInstant(form.startTime());
        if (parsed != null) {
          body.put("startTime", parsed.toString());
        }
      }
      if (form.endTime() != null && !form.endTime().isBlank()) {
        java.time.Instant parsed = parseToInstant(form.endTime());
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

      boolean isPublic = authHelper.isAnonymous();
      backendApiClient.put(
          "/api/v1/missions/" + id + "/participants/" + participantId, body, Void.class, isPublic);
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
  @PostMapping("/{id}/units")
  @PreAuthorize("isAuthenticated()")
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

      backendApiClient.post("/api/v1/missions/" + id + "/units", body, Void.class);
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
  @PostMapping("/{id}/units/{unitId}/update")
  @PreAuthorize("isAuthenticated()")
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

      backendApiClient.put("/api/v1/missions/" + id + "/units/" + unitId, body, Void.class);
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
  @PostMapping("/{id}/units/{unitId}/delete")
  @PreAuthorize("isAuthenticated()")
  public String deleteUnit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/missions/" + id + "/units/" + unitId, Void.class);
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
  @PreAuthorize("isAuthenticated()")
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
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew", body, Void.class);
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
  @PreAuthorize("isAuthenticated()")
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
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId, body, Void.class);
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
  @PostMapping("/{id}/units/{unitId}/crew/{crewId}/delete")
  @PreAuthorize("isAuthenticated()")
  public String deleteCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId, Void.class);
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
  @PreAuthorize("isAuthenticated()")
  public String createMission(
      @Valid @ModelAttribute("missionForm") MissionForm form,
      BindingResult bindingResult,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      // Render the create form directly; BindingResult stays request-scoped. The submitted form
      // (already in the model) carries any operationId, so pass null here.
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
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
      // Land the user straight on the freshly-created mission's Verwaltung tab (?tab=verw deeplink)
      // so they can keep planning (crew, refine goals/steps) without hunting for it in the list.
      return "redirect:/missions/" + created.id() + "?tab=verw";
    } catch (Exception e) {
      log.error("Create mission failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.create");
      redirectAttributes.addFlashAttribute("missionForm", form);
      return "redirect:/missions/new";
    }
  }

  /**
   * Parses one of the create form's JSON row carriers ({@code objectivesJson} / {@code stepsJson})
   * into a nested create-request list. A blank carrier — the common case, no goals/steps entered —
   * yields {@code null}, as does an empty array, so the backend seeds nothing. A malformed body
   * propagates the {@link JsonProcessingException} to the create handler's catch, which surfaces
   * the generic create error and re-flashes the form (the carriers ride along, so nothing is lost).
   *
   * @param json the hidden carrier's raw JSON, or {@code null}/blank when the section is empty
   * @param typeRef the target list element type
   * @param <T> the nested create-request element type ({@code NewObjective} / {@code NewStep})
   * @return the parsed list, or {@code null} when the carrier is blank or the array is empty
   * @throws JsonProcessingException when the carrier holds malformed JSON
   */
  private <T> List<T> parseCreateList(String json, TypeReference<List<T>> typeRef)
      throws JsonProcessingException {
    if (json == null || json.isBlank()) {
      return null;
    }
    List<T> list = objectMapper.readValue(json, typeRef);
    return (list == null || list.isEmpty()) ? null : list;
  }

  /**
   * Classic form-post endpoint that persists edits to a mission ({@code !isNew}). On a Jakarta
   * {@code @Valid} failure it re-renders the whole detail page with inline {@code th:errors}; on
   * success it fans the form out into the three section PATCHes via {@link #applyMissionUpdate} and
   * flash-redirects. Stays the no-JavaScript fallback for {@link #updateMissionAjax} (#589).
   *
   * @return redirect to the mission detail page
   */
  @PostMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
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
   * Fans a validated {@link MissionForm} out into the three section-scoped PATCHes (schedule → core
   * → flags), each carrying its own optimistic-lock counter ({@code scheduleVersion} / {@code
   * coreVersion} / {@code flagsVersion}) so concurrent editors of other sections don't invalidate
   * each other's saves. Shared by the classic {@link #updateMission} and its AJAX twin {@link
   * #updateMissionAjax} so the two can never drift.
   *
   * <p>Schedule is patched first because the status-driven PLANNED → ACTIVE auto-transition in the
   * core patch additionally bumps the schedule version — running schedule first lets the caller's
   * plain time edits land before the auto-stamp kicks in, avoiding an internal 409. Because that
   * auto-bump leaves {@code scheduleVersion} stale on the caller's side, the AJAX twin re-reads the
   * mission afterwards to return the four fresh versions.
   *
   * <p><b>Dirty-section-aware (#1136, REQ-FE-014).</b> Each section's PATCH is skipped when {@code
   * form.dirtyCore()} / {@code dirtySchedule()} / {@code dirtyFlags()} is explicitly {@code false};
   * the edit page's JS sets these to whether the user actually touched that header section. This
   * stops a peer's concurrent schedule bump from 409ing a name-only edit that never touched the
   * schedule, and it never re-writes untouched schedule/flags values (so a PLANNED → ACTIVE
   * auto-stamped {@code actualStartTime} is not silently erased by a later core-only save). A
   * {@code null} flag (the no-JavaScript classic fallback, or an older cached page) means "save
   * this section", preserving the pre-#1136 full-fan-out behaviour. The schedule-before-core
   * ordering is kept for saves that genuinely touch both.
   *
   * @param id the mission id
   * @param form the validated submitted form
   */
  private void applyMissionUpdate(@NotNull UUID id, MissionForm form) {
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

      Map<String, Object> schedulePatch = new java.util.LinkedHashMap<>();
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
      Map<String, Object> corePatch = new java.util.LinkedHashMap<>();
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
      Map<String, Object> flagsPatch = new java.util.LinkedHashMap<>();
      flagsPatch.put("isInternal", form.isInternal() != null && form.isInternal());
      flagsPatch.put("version", form.flagsVersion());
      backendApiClient.patch("/api/v1/missions/" + id + "/flags", flagsPatch, Void.class);
    }
  }

  /**
   * AJAX twin of {@link #updateMission} (#589): saves the mission core-edit form in place. Routed
   * by the {@code X-Requested-With} header (the classic {@code POST /missions/{id}} stays the no-JS
   * fallback). On a Jakarta {@code @Valid} failure it returns {@code 422} with a {@code {field:
   * message}} JSON map (messages resolved exactly as {@code th:errors} via {@link #messageSource})
   * so the client renders the errors inline without a navigation. On success it runs the three
   * section PATCHes, re-reads the mission to capture the PLANNED → ACTIVE auto-bump, and returns
   * the four fresh versions {@code {version, coreVersion, scheduleVersion, flagsVersion}} so the
   * client writes them back and a second consecutive save does not 409. A backend {@code 409} /
   * domain conflict is propagated as {@code problem+json} via {@link #propagateBackendError}.
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
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> updateMissionAjax(
      @PathVariable @NotNull UUID id,
      @Valid @ModelAttribute("missionForm") MissionForm form,
      BindingResult bindingResult,
      Locale locale) {
    if (bindingResult.hasErrors()) {
      Map<String, String> fieldErrors = new java.util.LinkedHashMap<>();
      for (FieldError fe : bindingResult.getFieldErrors()) {
        // First error per field wins (the form's fields carry one constraint each); the message is
        // resolved exactly as th:errors does so the inline text matches the classic re-render. A
        // constraint without a resolvable message key falls back to its default message rather than
        // crashing the 422 contract to a 500.
        String message;
        try {
          message = messageSource.getMessage(fe, locale);
        } catch (org.springframework.context.NoSuchMessageException ex) {
          message = fe.getDefaultMessage();
        }
        fieldErrors.putIfAbsent(fe.getField(), message);
      }
      return org.springframework.http.ResponseEntity.unprocessableContent().body(fieldErrors);
    }
    try {
      applyMissionUpdate(id, form);
      MissionDto refreshed =
          backendApiClient.get("/api/v1/missions/" + id, MissionDto.class, false);
      Map<String, Object> versions = new java.util.LinkedHashMap<>();
      versions.put("version", refreshed.version());
      versions.put("coreVersion", refreshed.coreVersion());
      versions.put("scheduleVersion", refreshed.scheduleVersion());
      versions.put("flagsVersion", refreshed.flagsVersion());
      return org.springframework.http.ResponseEntity.ok(versions);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update mission (ajax) failed for {}: {}", id, e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update mission (ajax) failed for {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Form-post endpoint that deletes (or cancels) a mission. The backend cascades by detaching
   * inventory/refinery references rather than hard-deleting them, per the CHANGELOG entry for the
   * deleteMission change.
   *
   * @return redirect to {@code /missions}
   */
  @PostMapping("/{id}/delete")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String deleteMission(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/missions/" + id, Void.class);
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
  public org.springframework.http.ResponseEntity<Object> addManager(
      @PathVariable String id, @PathVariable String userId) {
    log.debug("START addManager - id: '{}', userId: '{}'", id, userId);
    try {
      if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
        log.debug("MISSING PARAMETERS - id: '{}', userId: '{}'", id, userId);
        return org.springframework.http.ResponseEntity.badRequest().build();
      }
      java.util.UUID missionUuid;
      java.util.UUID userUuid;
      try {
        missionUuid = java.util.UUID.fromString(id.trim());
      } catch (IllegalArgumentException e) {
        log.debug("INVALID MISSION ID FORMAT - id: '{}', Error: {}", id, e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().build();
      }
      try {
        userUuid = java.util.UUID.fromString(userId.trim());
      } catch (IllegalArgumentException e) {
        log.debug("INVALID USER ID FORMAT - userId: '{}', Error: {}", userId, e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().build();
      }

      log.debug("CALLING BACKEND - Mission: {}, User: {}", missionUuid, userUuid);
      try {
        backendApiClient.post(
            "/api/v1/missions/" + missionUuid + "/managers/" + userUuid + "/slim",
            null,
            String.class,
            false);
        log.debug("SUCCESS - Manager {} added to mission {}", userUuid, missionUuid);
        return org.springframework.http.ResponseEntity.ok().build();
      } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
        log.debug(
            "BACKEND ERROR adding manager for mission {} and user {}: Status={}, Message={},"
                + " Readable={}",
            missionUuid,
            userUuid,
            e.getStatusCode(),
            e.getMessage(),
            e.getReadableErrorMessage());
        return MissionPageController.propagateBackendError(e);
      }
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in addManager: id='{}', userId='{}', error={}",
          id,
          userId,
          e.getMessage(),
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
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
  public org.springframework.http.ResponseEntity<Object> removeManager(
      @PathVariable String id, @PathVariable String userId) {
    log.debug("START removeManager - id: '{}', userId: '{}'", id, userId);
    try {
      if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
        log.debug("MISSING PARAMETERS in removeManager - id: '{}', userId: '{}'", id, userId);
        return org.springframework.http.ResponseEntity.badRequest().build();
      }
      java.util.UUID missionUuid;
      java.util.UUID userUuid;
      try {
        missionUuid = java.util.UUID.fromString(id.trim());
      } catch (IllegalArgumentException e) {
        log.debug(
            "INVALID MISSION ID FORMAT in removeManager - id: '{}', Error: {}", id, e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().build();
      }
      try {
        userUuid = java.util.UUID.fromString(userId.trim());
      } catch (IllegalArgumentException e) {
        log.debug(
            "INVALID USER ID FORMAT in removeManager - userId: '{}', Error: {}",
            userId,
            e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().build();
      }

      log.debug("CALLING BACKEND DELETE - Mission: {}, User: {}", missionUuid, userUuid);
      backendApiClient.delete(
          "/api/v1/missions/" + missionUuid + "/managers/" + userUuid + "/slim",
          Object.class,
          false);
      log.debug("SUCCESS DELETE - Manager {} removed from mission {}", userUuid, missionUuid);
      return org.springframework.http.ResponseEntity.ok().build();
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "BACKEND ERROR removing manager: Status={}, Message={}, Readable={}",
          e.getStatusCode(),
          e.getMessage(),
          e.getReadableErrorMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in removeManager: id='{}', userId='{}', error={}",
          id,
          userId,
          e.getMessage(),
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint that transfers mission ownership to another user. Backend ensures the old owner
   * stays as a co-manager so they don't lose all access in one click.
   *
   * @return 200 on success, propagated backend status on failure
   */
  @PutMapping("/{id}/owner/{userId}")
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> setMissionOwner(
      @PathVariable String id, @PathVariable String userId) {
    log.debug("START setMissionOwner - id: '{}', userId: '{}'", id, userId);
    try {
      if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
        log.debug("MISSING PARAMETERS in setMissionOwner - id: '{}', userId: '{}'", id, userId);
        return org.springframework.http.ResponseEntity.badRequest().build();
      }
      java.util.UUID missionUuid;
      java.util.UUID userUuid;
      try {
        missionUuid = java.util.UUID.fromString(id.trim());
      } catch (IllegalArgumentException e) {
        log.debug(
            "INVALID MISSION ID FORMAT in setMissionOwner - id: '{}', Error: {}",
            id,
            e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().build();
      }
      try {
        userUuid = java.util.UUID.fromString(userId.trim());
      } catch (IllegalArgumentException e) {
        log.debug(
            "INVALID USER ID FORMAT in setMissionOwner - userId: '{}', Error: {}",
            userId,
            e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().build();
      }

      log.debug("CALLING BACKEND PUT - Mission: {}, User: {}", missionUuid, userUuid);
      try {
        backendApiClient.put(
            "/api/v1/missions/" + missionUuid + "/owner/" + userUuid, null, Void.class, false);
        log.debug("SUCCESS - Owner of mission {} changed to user {}", missionUuid, userUuid);
        return org.springframework.http.ResponseEntity.ok().build();
      } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
        log.debug(
            "BACKEND ERROR changing owner: Status={}, Message={}, Readable={}",
            e.getStatusCode(),
            e.getMessage(),
            e.getReadableErrorMessage());
        return MissionPageController.propagateBackendError(e);
      }
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in setMissionOwner: id='{}', userId='{}', error={}",
          id,
          userId,
          e.getMessage(),
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint that reassigns a mission's owning org unit (REQ-ORG-018 / ADR-0050). Forwards the
   * JSON body ({@code owningOrgUnitId} — possibly {@code null} for an ownerless leadership mission
   * — plus the expected {@code version}, i.e. the mission's {@code owningOrgUnitVersion}) to the
   * backend reassignment endpoint and passes the upstream RFC 7807 problem through on a 409 so the
   * shared {@code krtFetch} conflict UX fires. The mission-detail page re-renders the {@code mgmt}
   * fragment in place on success (no full reload).
   *
   * @param id mission id (path)
   * @param body reassignment JSON: {@code owningOrgUnitId} (a UUID string, or blank/absent for
   *     ownerless) plus {@code version}
   * @return {@code 200} on success, or the upstream RFC 7807 error passed through
   */
  @PutMapping(
      value = "/{id}/owning-org-unit/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> setMissionOwningOrgUnit(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    try {
      Map<String, Object> out = new HashMap<>();
      Object owningOrgUnitId = body.get("owningOrgUnitId");
      // Forward an explicit null for the ownerless target; a blank/empty string also means "none".
      out.put(
          "owningOrgUnitId",
          (owningOrgUnitId != null && !String.valueOf(owningOrgUnitId).isBlank())
              ? owningOrgUnitId
              : null);
      out.put("version", body.get("version") != null ? body.get("version") : 0L);
      backendApiClient.put("/api/v1/missions/" + id + "/owning-org-unit", out, Void.class, false);
      MissionDto mission = backendApiClient.get("/api/v1/missions/" + id, MISSION, false);
      return org.springframework.http.ResponseEntity.ok(mission);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Reassign owning org unit (AJAX) failed: status={}", e.getStatusCode());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in setMissionOwningOrgUnit for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Form-post endpoint that creates or updates a frequency entry (radio channel) for the mission.
   * Same endpoint serves both create and update — the form's id field discriminates.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @PostMapping("/{id}/frequencies")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public String addOrUpdateFrequency(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull UUID frequencyTypeId,
      @RequestParam @NotNull java.math.BigDecimal value,
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
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/frequencies/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public org.springframework.http.ResponseEntity<Object> addOrUpdateFrequencyAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/frequencies/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Add/update frequency (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addOrUpdateFrequencyAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3B: deletes a frequency via the Slim backend endpoint and returns the
   * resulting slim list as JSON.
   */
  @DeleteMapping(
      value = "/{id}/frequencies/{frequencyId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public org.springframework.http.ResponseEntity<Object> deleteFrequencyAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID frequencyId) {
    try {
      Object result =
          backendApiClient.delete(
              "/api/v1/missions/" + id + "/frequencies/" + frequencyId + "/slim",
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Delete frequency (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in deleteFrequencyAjax for mission {} freq {}", id, frequencyId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint that adds a custom (mission-specific) frequency (REQ-MISSION-014) via the slim
   * backend endpoint and returns the resulting slim frequency list so the "Weitere Frequenzen"
   * editor and the overview Funk panel refresh in place without a reload.
   *
   * @param id the mission id
   * @param body the JSON payload ({@code name} + {@code value})
   * @return the updated frequency list, or the propagated backend error
   */
  @PostMapping(
      value = "/{id}/frequencies/custom/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public org.springframework.http.ResponseEntity<Object> addCustomFrequencyAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/frequencies/custom/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Add custom frequency (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addCustomFrequencyAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint that updates a custom (mission-specific) frequency (REQ-MISSION-014) via the slim
   * backend endpoint and returns the resulting slim frequency list. Optimistic-locked on the row's
   * version; a stale echo surfaces as HTTP 409 from the backend and is propagated verbatim.
   *
   * @param id the mission id
   * @param frequencyId the custom frequency row id
   * @param body the JSON payload ({@code name} + {@code value} + {@code version})
   * @return the updated frequency list, or the propagated backend error
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/frequencies/custom/{frequencyId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public org.springframework.http.ResponseEntity<Object> updateCustomFrequencyAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID frequencyId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/frequencies/custom/" + frequencyId + "/slim",
              body,
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Update custom frequency (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in updateCustomFrequencyAjax for mission {} freq {}",
          id,
          frequencyId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C: adds a unit via the Slim backend endpoint and returns the resulting
   * slim unit list so the mission detail page can refresh without losing pending input in other
   * sub-panels (Option A).
   */
  @PostMapping(
      value = "/{id}/units/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> addUnitAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/units/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Add unit (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addUnitAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C: updates a unit via the Slim backend endpoint and returns the
   * updated slim unit as JSON.
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/units/{unitId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> updateUnitAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/units/" + unitId + "/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update unit (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in updateUnitAjax for mission {} unit {}", id, unitId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /** AJAX endpoint for Paket 3C: deletes a unit via the Slim backend endpoint. */
  @DeleteMapping(
      value = "/{id}/units/{unitId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> deleteUnitAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID unitId) {
    try {
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/units/" + unitId + "/slim", Void.class, false);
      return org.springframework.http.ResponseEntity.noContent().build();
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Delete unit (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in deleteUnitAjax for mission {} unit {}", id, unitId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  // --- Ablauf steps (procedure timeline) ---

  /**
   * AJAX proxy: appends an Ablauf step via the backend slim endpoint and returns the resulting
   * ordered step list. The page re-renders the editor + overview-checklist fragments in place; the
   * backend's {@code stepsVersion} guard keeps the Ablauf section's optimistic lock narrow.
   *
   * @param id the mission id
   * @param body the step payload (title, optional meta, expected stepsVersion)
   * @return the ordered step list, or the propagated backend error
   */
  @PostMapping(
      value = "/{id}/steps/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> addStepAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/steps/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Add step (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addStepAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: edits an Ablauf step's title / time-place hint and returns the ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param body the step payload (title, optional meta, expected stepsVersion)
   * @return the ordered step list, or the propagated backend error
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/steps/{stepId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> updateStepAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/steps/" + stepId + "/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update step (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in updateStepAjax for mission {} step {}", id, stepId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: removes an Ablauf step and returns the remaining ordered step list. The expected
   * {@code stepsVersion} travels as a query parameter (DELETE carries no body).
   *
   * @param id the mission id
   * @param stepId the step id
   * @param stepsVersion the expected mission steps-section version (optimistic-lock guard)
   * @return the ordered step list, or the propagated backend error
   */
  @DeleteMapping(
      value = "/{id}/steps/{stepId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> deleteStepAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestParam @NotNull Long stepsVersion) {
    try {
      Object result =
          backendApiClient.delete(
              "/api/v1/missions/" + id + "/steps/" + stepId + "/slim?stepsVersion=" + stepsVersion,
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Delete step (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in deleteStepAjax for mission {} step {}", id, stepId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: reorders the mission's Ablauf steps and returns the new ordered step list.
   *
   * @param id the mission id
   * @param body the desired step-id order + expected stepsVersion
   * @return the ordered step list, or the propagated backend error
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/steps/reorder/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> reorderStepsAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/steps/reorder/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Reorder steps (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in reorderStepsAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: toggles an Ablauf step's shared done flag and returns the ordered step list. Used
   * by the overview checklist's click-to-complete control (edit-authorised users only — enforced by
   * the backend's {@code canManageMission} gate).
   *
   * @param id the mission id
   * @param stepId the step id
   * @param body the new done state + expected stepsVersion
   * @return the ordered step list, or the propagated backend error
   */
  @org.springframework.web.bind.annotation.PatchMapping(
      value = "/{id}/steps/{stepId}/done/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> toggleStepDoneAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.patch(
              "/api/v1/missions/" + id + "/steps/" + stepId + "/done/slim", body, Object.class);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Toggle step (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in toggleStepDoneAjax for mission {} step {}", id, stepId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  // --- Mission goals (Ziele) ---

  /**
   * AJAX proxy: appends a goal (Ziel) via the backend slim endpoint and returns the resulting
   * ordered goal list. The page re-renders the editor + overview Ziele fragments in place; the
   * backend's {@code objectivesVersion} guard keeps the goals section's optimistic lock narrow.
   *
   * @param id the mission id
   * @param body the goal payload (title, kind, expected objectivesVersion)
   * @return the ordered goal list, or the propagated backend error
   */
  @PostMapping(
      value = "/{id}/objectives/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> addObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/objectives/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Add objective (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addObjectiveAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: edits a goal's text / classification and returns the ordered goal list.
   *
   * @param id the mission id
   * @param objectiveId the goal id
   * @param body the goal payload (title, kind, expected objectivesVersion)
   * @return the ordered goal list, or the propagated backend error
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/objectives/{objectiveId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> updateObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/objectives/" + objectiveId + "/slim",
              body,
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Update objective (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in updateObjectiveAjax for mission {} objective {}",
          id,
          objectiveId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: removes a goal and returns the remaining ordered goal list. The expected {@code
   * objectivesVersion} travels as a query parameter (DELETE carries no body).
   *
   * @param id the mission id
   * @param objectiveId the goal id
   * @param objectivesVersion the expected mission goals-section version (optimistic-lock guard)
   * @return the ordered goal list, or the propagated backend error
   */
  @DeleteMapping(
      value = "/{id}/objectives/{objectiveId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> deleteObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestParam @NotNull Long objectivesVersion) {
    try {
      Object result =
          backendApiClient.delete(
              "/api/v1/missions/"
                  + id
                  + "/objectives/"
                  + objectiveId
                  + "/slim?objectivesVersion="
                  + objectivesVersion,
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Delete objective (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in deleteObjectiveAjax for mission {} objective {}",
          id,
          objectiveId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX proxy: reorders the mission's goals and returns the new ordered goal list.
   *
   * @param id the mission id
   * @param body the desired goal-id order + expected objectivesVersion
   * @return the ordered goal list, or the propagated backend error
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/objectives/reorder/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> reorderObjectivesAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/objectives/reorder/slim", body, Object.class, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Reorder objectives (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in reorderObjectivesAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): adds a participant via the Slim backend
   * endpoint and returns the resulting slim participant list so the mission detail page can refresh
   * without losing pending input in other sub-panels (Option A: sub-section writes must not bump
   * Mission.version).
   */
  @PostMapping(
      value = "/{id}/participants/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> addParticipantAjax(
      @PathVariable @NotNull UUID id,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      // Mirror the classical /missions/{id}/participant handler: anonymous guests
      // hit the backend's slim endpoint via the public WebClient (no JWT) so the
      // backend can apply its guest-signup branch (jwt == null + guestName).
      // Previously this method was annotated with @PreAuthorize("isAuthenticated()")
      // and always passed isPublic=false, which produced the AccessDeniedException
      // observed in live-log/log.txt for anonymous mission signups.
      boolean isPublic = authHelper.isAnonymous();
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/participants/slim", body, Object.class, isPublic);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Add participant (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addParticipantAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): updates a participant via the Slim
   * backend endpoint and returns the updated slim participant as JSON.
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/participants/{participantId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> updateParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      // Anonymous guests are allowed to edit their own guest participant entries
      // (see backend MissionSecurityService#canAccessParticipant: guest entries
      // with user == null are editable). Route via the public WebClient when no
      // OIDC principal is present, mirroring addParticipantAjax.
      boolean isPublic = authHelper.isAnonymous();
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/participants/" + participantId + "/slim",
              body,
              Object.class,
              isPublic);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Update participant (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in updateParticipantAjax for mission {} participant {}",
          id,
          participantId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): deletes a participant via the Slim
   * backend endpoint.
   */
  @DeleteMapping(
      value = "/{id}/participants/{participantId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> deleteParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/participants/" + participantId + "/slim",
          Void.class,
          isPublic);
      return org.springframework.http.ResponseEntity.noContent().build();
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Delete participant (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in deleteParticipantAjax for mission {} participant {}",
          id,
          participantId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): checks a participant in via the Slim
   * backend endpoint.
   */
  @PostMapping(
      value = "/{id}/participants/{participantId}/check-in/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> checkInParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/participants/" + participantId + "/check-in/slim",
              null,
              Object.class,
              isPublic);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Check-in participant (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in checkInParticipantAjax for mission {} participant {}",
          id,
          participantId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option b - Participants): checks a participant out via the Slim
   * backend endpoint.
   */
  @PostMapping(
      value = "/{id}/participants/{participantId}/check-out/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> checkOutParticipantAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal OidcUser principal) {
    try {
      boolean isPublic = authHelper.isAnonymous();
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/participants/" + participantId + "/check-out/slim",
              null,
              Object.class,
              isPublic);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Check-out participant (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in checkOutParticipantAjax for mission {} participant {}",
          id,
          participantId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option c - Crew): adds a crew member to a unit via the Slim backend
   * endpoint and returns the resulting slim crew list.
   */
  @PostMapping(
      value = "/{id}/units/{unitId}/crew/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> addCrewAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.post(
              "/api/v1/missions/" + id + "/units/" + unitId + "/crew/slim",
              body,
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Add crew (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in addCrewAjax for mission {} unit {}", id, unitId, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option c - Crew): updates a crew member via the Slim backend
   * endpoint and returns the updated slim crew entry.
   */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/units/{unitId}/crew/{crewId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> updateCrewAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    try {
      Object result =
          backendApiClient.put(
              "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/slim",
              body,
              Object.class,
              false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Update crew (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in updateCrewAjax for mission {} unit {} crew {}",
          id,
          unitId,
          crewId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX endpoint for Paket 3C (Option c - Crew): deletes a crew member via the Slim backend
   * endpoint.
   */
  @DeleteMapping(
      value = "/{id}/units/{unitId}/crew/{crewId}/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> deleteCrewAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @PathVariable @NotNull UUID crewId) {
    try {
      backendApiClient.delete(
          "/api/v1/missions/" + id + "/units/" + unitId + "/crew/" + crewId + "/slim",
          Void.class,
          false);
      return org.springframework.http.ResponseEntity.noContent().build();
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug("Delete crew (AJAX) failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return MissionPageController.propagateBackendError(e);
    } catch (Exception e) {
      log.debug(
          "UNEXPECTED ERROR in deleteCrewAjax for mission {} unit {} crew {}",
          id,
          unitId,
          crewId,
          e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Display time zone for the mission schedule fields. The datetime-splitter renders/edits times in
   * the browser's local zone; the server-side format/parse round trip uses this fixed zone for the
   * zoneless local-datetime form the hidden input carries when a field is rendered but never
   * re-edited. Verbatim private mirror of the read-side {@code MissionPageController} constant
   * (#924, L5), kept because {@link #parseToInstant} moved here with the write handlers while
   * {@code formatInstant} stayed read-side.
   */
  private static final java.time.ZoneId MISSION_TIME_ZONE = java.time.ZoneId.of("Europe/Berlin");

  /**
   * Parses a hidden datetime-input value back into an {@link java.time.Instant}, accepting every
   * shape the datetime-splitter or {@link #formatInstant} can produce: a zone-bearing value (the
   * splitter writes a UTC {@code toISOString()} with {@code Z} on edit, an explicit offset is
   * equally absolute), a zoneless local datetime of <em>any</em> fractional-second precision (what
   * {@link #formatInstant} renders for a field that was displayed but never re-edited, e.g. {@code
   * 2026-06-21T11:59:58.222717}), or a bare date. A zoneless value is interpreted in {@link
   * #MISSION_TIME_ZONE}.
   *
   * <p>The earlier fixed-length (16/19) checks rejected the microsecond local form and fell through
   * to {@link java.time.Instant#parse}, which threw — silently nulling {@code
   * plannedStartTime}/{@code meetingTime}/{@code plannedEndTime} on every save that did not
   * re-touch the field (the #589 e2e regression).
   *
   * @param dateTimeStr the hidden input value; {@code null}/blank yields {@code null}
   * @return the parsed instant, or {@code null} if the value is blank or unparseable
   */
  private java.time.Instant parseToInstant(String dateTimeStr) {
    if (dateTimeStr == null || dateTimeStr.isBlank()) {
      return null;
    }
    final String value = dateTimeStr.trim();
    try {
      // A date-only value (the splitter submits a bare date when no time was entered) maps to the
      // start of that day in the display zone.
      if (value.length() == 10) {
        return java.time.LocalDate.parse(value).atStartOfDay(MISSION_TIME_ZONE).toInstant();
      }
      // ISO_DATE_TIME parses both a zone-bearing value (absolute instant) and a zoneless local
      // datetime of any fractional precision; parseBest picks OffsetDateTime when a zone is present
      // and LocalDateTime otherwise.
      java.time.temporal.TemporalAccessor parsed =
          java.time.format.DateTimeFormatter.ISO_DATE_TIME.parseBest(
              value, java.time.OffsetDateTime::from, java.time.LocalDateTime::from);
      return parsed instanceof java.time.OffsetDateTime odt
          ? odt.toInstant()
          : ((java.time.LocalDateTime) parsed).atZone(MISSION_TIME_ZONE).toInstant();
    } catch (Exception e) {
      log.warn("Failed to parse datetime string: {}", value, e);
      return null;
    }
  }
}
