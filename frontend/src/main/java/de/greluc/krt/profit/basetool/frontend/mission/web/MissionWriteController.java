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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionActualTimeUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionForm;
import de.greluc.krt.profit.basetool.frontend.mission.model.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.PatchMissionFlagsRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.PatchMissionScheduleRequest;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationDto;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Handles mission creation, core edits, scheduling and deletion. */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionWriteController {

  private final MissionBackendClient missionClient;
  private final MessageSource messageSource;
  private final MissionPageController missionPageController;
  private final LiveSyncLocalBus liveSyncLocalBus;
  private static final List<String> MISSIONS_LIST_SECTION = List.of("list");
  private final JsonMapper objectMapper =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

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
      MissionDto current = missionClient.reloadMission(id);
      if (current == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      Instant newStart =
          "actualStartTime".equals(request.field()) ? request.value() : current.actualStartTime();
      Instant newEnd =
          "actualEndTime".equals(request.field()) ? request.value() : current.actualEndTime();

      missionClient.patchSchedule(
          id,
          new PatchMissionScheduleRequest(
              current.meetingTime(),
              current.plannedStartTime(),
              current.plannedEndTime(),
              newStart,
              newEnd,
              request.version()));
      MissionDto refreshed = missionClient.reloadMission(id);
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
    }
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

      MissionDto created = missionClient.createMission(createRequest);
      liveSyncLocalBus.publish("missions", MISSIONS_LIST_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
      return "redirect:/missions/" + created.id() + "?tab=verw";
    } catch (BackendServiceException | JacksonException | IllegalArgumentException e) {
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
    } catch (BackendServiceException | IllegalArgumentException e) {
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

      missionClient.patchSchedule(
          id,
          new PatchMissionScheduleRequest(
              meetingTime,
              plannedStartTime,
              plannedEndTime,
              actualStartTime,
              actualEndTime,
              form.scheduleVersion()));
    }

    if (saveCore) {
      UUID operationId =
          (form.operationId() != null && !form.operationId().isBlank())
              ? UUID.fromString(form.operationId())
              : null;
      missionClient.patchCore(
          id,
          new PatchMissionCoreRequest(
              form.name(),
              form.description(),
              form.calendarLink(),
              form.status(),
              operationId,
              form.coreVersion(),
              form.meetingPoint()));
    }

    if (saveFlags) {
      missionClient.patchFlags(
          id,
          new PatchMissionFlagsRequest(
              form.isInternal() != null && form.isInternal(), form.flagsVersion()));
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
        } catch (NoSuchMessageException _) {
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
          MissionDto refreshed = missionClient.reloadMission(id);
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
      missionClient.deleteMission(id);
      liveSyncLocalBus.publish("missions", MISSIONS_LIST_SECTION);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.mission_delete");
    } catch (BackendServiceException e) {
      log.error("Delete mission failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.mission.delete");
      return "redirect:/missions/" + id;
    }
    return "redirect:/missions";
  }
}
