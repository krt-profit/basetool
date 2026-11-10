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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.AddCrewRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.AddUnitRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.CrewForm;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionCrewDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionUnitDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateCrewRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateUnitRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Handles mission units and their crews. */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionUnitWriteController {

  private final MissionBackendClient missionClient;
  private final MissionPageController missionPageController;

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
      missionClient.addUnit(
          id,
          new AddUnitRequest(
              name, shipTypeId, shipId, highValueUnit, frequency, responsibleUserId, note));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
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
      missionClient.updateUnit(
          id,
          unitId,
          new UpdateUnitRequest(
              name, shipTypeId, shipId, highValueUnit, frequency, responsibleUserId, note, null));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
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
      missionClient.deleteUnit(id, unitId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
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
      missionClient.addCrew(
          id,
          unitId,
          new AddCrewRequest(
              form.participantId(),
              form.jobTypeIds() != null && !form.jobTypeIds().isEmpty()
                  ? form.jobTypeIds()
                  : null));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
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
      missionClient.updateCrew(
          id,
          unitId,
          crewId,
          new UpdateCrewRequest(
              form.jobTypeIds() != null && !form.jobTypeIds().isEmpty()
                  ? form.jobTypeIds()
                  : List.of(),
              null));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
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
      missionClient.deleteCrew(id, unitId, crewId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      log.error("Delete crew failed", e);
      return "redirect:/missions/" + id + "?error=error.mission.crew.delete";
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX endpoint for Paket 3C: adds a unit via the Slim backend endpoint and returns the resulting
   * slim unit list so the mission detail page can refresh without losing pending input in other
   * sub-panels (Option A).
   */
  @PostMapping(value = "/{id}/units/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addUnitAjax(
      @PathVariable @NotNull UUID id, @RequestBody AddUnitRequest body) {
    return relay(
        log,
        "add unit (ajax) for mission " + id,
        () -> {
          List<MissionUnitDto> result = missionClient.addUnitJson(id, body);
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
      @RequestBody UpdateUnitRequest body) {
    return relay(
        log,
        "update unit (ajax) for mission " + id + " unit " + unitId,
        () -> {
          MissionUnitDto result = missionClient.updateUnitJson(id, unitId, body);
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
          missionClient.deleteUnit(id, unitId);
          return ResponseEntity.noContent().build();
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
      @RequestBody AddCrewRequest body) {
    return relay(
        log,
        "add crew (ajax) for mission " + id + " unit " + unitId,
        () -> {
          List<MissionCrewDto> result = missionClient.addCrewJson(id, unitId, body);
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
      @RequestBody UpdateCrewRequest body) {
    return relay(
        log,
        "update crew (ajax) for mission " + id + " unit " + unitId + " crew " + crewId,
        () -> {
          MissionCrewDto result = missionClient.updateCrewJson(id, unitId, crewId, body);
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
          missionClient.deleteCrew(id, unitId, crewId);
          return ResponseEntity.noContent().build();
        });
  }
}
