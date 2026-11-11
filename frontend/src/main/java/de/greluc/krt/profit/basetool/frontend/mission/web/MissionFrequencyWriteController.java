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
import de.greluc.krt.profit.basetool.frontend.mission.model.AddCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.AddFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateCustomFrequencyRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Handles mission radio frequencies. */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionFrequencyWriteController {

  private final MissionBackendClient missionClient;

  /**
   * Form-post endpoint that creates or updates a frequency entry (radio channel) for the mission.
   * Same endpoint serves both create and update — the form's id field discriminates.
   *
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/frequencies")
  @PreAuthorize("@missionManagerGate.isGranted()")
  public String addOrUpdateFrequency(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull UUID frequencyTypeId,
      @RequestParam @NotNull BigDecimal value,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.putFrequency(id, new AddFrequencyRequest(frequencyTypeId, value));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
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
  @PreAuthorize("@missionManagerGate.isGranted()")
  public String deleteFrequency(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID frequencyId,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.deleteFrequency(id, frequencyId);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
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
  @PreAuthorize("@missionManagerGate.isGranted()")
  public ResponseEntity<Object> addOrUpdateFrequencyAjax(
      @PathVariable @NotNull UUID id, @RequestBody AddFrequencyRequest body) {
    return relay(
        log,
        "add or update frequency (ajax) for mission " + id,
        () -> {
          List<MissionFrequencyDto> result = missionClient.putFrequencyJson(id, body);
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
  @PreAuthorize("@missionManagerGate.isGranted()")
  public ResponseEntity<Object> deleteFrequencyAjax(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID frequencyId) {
    return relay(
        log,
        "delete frequency (ajax) for mission " + id + " freq " + frequencyId,
        () -> {
          missionClient.deleteFrequency(id, frequencyId);
          return ResponseEntity.ok().build();
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
  @PreAuthorize("@missionManagerGate.isGranted()")
  public ResponseEntity<Object> addCustomFrequencyAjax(
      @PathVariable @NotNull UUID id, @RequestBody AddCustomFrequencyRequest body) {
    return relay(
        log,
        "add custom frequency (ajax) for mission " + id,
        () -> {
          List<MissionFrequencyDto> result = missionClient.addCustomFrequency(id, body);
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
  @PreAuthorize("@missionManagerGate.isGranted()")
  public ResponseEntity<Object> updateCustomFrequencyAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID frequencyId,
      @RequestBody UpdateCustomFrequencyRequest body) {
    return relay(
        log,
        "update custom frequency (ajax) for mission " + id + " freq " + frequencyId,
        () -> {
          List<MissionFrequencyDto> result =
              missionClient.updateCustomFrequency(id, frequencyId, body);
          return ResponseEntity.ok(result);
        });
  }
}
