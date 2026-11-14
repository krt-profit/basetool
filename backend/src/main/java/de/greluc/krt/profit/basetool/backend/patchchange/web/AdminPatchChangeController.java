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

package de.greluc.krt.profit.basetool.backend.patchchange.web;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPhaseEnd;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPrompts;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.MemberRelocations;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeHistory;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeLabelCorrection;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangePreviewDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeTrigger;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.RelocationChooser;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.RelocationResultDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.TriggerPatchChangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin's patch-change surface (REQ-PATCH-005, REQ-PATCH-008, REQ-PATCH-022, REQ-PATCH-024):
 * the form's defaults, the preview, the trigger, the history, the label correction, the end of a
 * home-location phase and the choice on a member's behalf. Only an admin reaches it, also when
 * pinned to one org unit, because a patch change applies to every org unit.
 */
@RestController
@RequestMapping("/api/v1/patch-changes/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class AdminPatchChangeController {

  private final PatchChangeHistory history;
  private final PatchChangeTrigger trigger;
  private final PatchChangeLabelCorrection labelCorrection;
  private final HomeLocationPhaseEnd phaseEnd;
  private final HomeLocationPrompts prompts;
  private final MemberRelocations relocations;

  /**
   * Lists every patch change, newest live time first.
   *
   * @return the history
   */
  @GetMapping
  @Operation(summary = "List the patch changes, newest first (admin).")
  public List<PatchChangeDto> listPatchChanges() {
    return history.history();
  }

  /**
   * Returns what a new patch change's form starts from.
   *
   * @return the suggested label, the previous label and live time, and the presets per kind
   */
  @GetMapping("/form")
  @Operation(summary = "Read the defaults of a new patch change (admin).")
  public PatchChangeFormDto patchChangeForm() {
    return history.form();
  }

  /**
   * Counts what a patch change with the given live time and options would touch now.
   *
   * @param request the live time and options
   * @return the counts of every enabled effect
   */
  @PostMapping("/preview")
  @Operation(summary = "Preview what a patch change would touch (admin).")
  public PatchChangePreviewDto previewPatchChange(
      @Valid @RequestBody PatchChangePreviewRequest request) {
    return trigger.preview(request);
  }

  /**
   * Records a patch change and runs its effects, all or nothing.
   *
   * @param request the label, kind, live time, options and the typed confirmation
   * @return the recorded patch change
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Trigger a patch change for every org unit (admin).")
  public PatchChangeDto triggerPatchChange(@Valid @RequestBody TriggerPatchChangeRequest request) {
    return trigger.trigger(request);
  }

  /**
   * Corrects a triggered patch change's label.
   *
   * @param id the patch change
   * @param request the corrected label and the version the admin last saw
   * @return the corrected patch change
   */
  @PatchMapping("/{id}/label")
  @Operation(summary = "Correct a patch change's label (admin).")
  public PatchChangeDto correctPatchChangeLabel(
      @PathVariable UUID id, @Valid @RequestBody CorrectPatchChangeLabelRequest request) {
    return labelCorrection.correct(id, request);
  }

  /**
   * Ends a patch change's open home-location phase early.
   *
   * @param id the patch change
   * @return the patch change with its ended phase
   */
  @PostMapping("/{id}/phase-end")
  @Operation(summary = "End a patch change's open home-location phase (admin).")
  public PatchChangeDto endHomeLocationPhase(@PathVariable UUID id) {
    return phaseEnd.end(id);
  }

  /**
   * Reads a member's home-location prompt, for a choice on the member's behalf.
   *
   * @param userId the member
   * @return the member's state, the home locations on offer and what would move
   */
  @GetMapping("/members/{userId}/home-location")
  @Operation(summary = "Read a member's home-location prompt (admin).")
  public HomeLocationPromptDto memberHomeLocationPrompt(@PathVariable UUID userId) {
    return prompts.promptFor(userId);
  }

  /**
   * Chooses a member's new home location on the member's behalf and relocates the member.
   *
   * @param userId the member
   * @param request the chosen home location
   * @return what moved
   */
  @PostMapping("/members/{userId}/home-location")
  @Operation(summary = "Choose a member's new home location on the member's behalf (admin).")
  public RelocationResultDto chooseMemberHomeLocation(
      @PathVariable UUID userId, @Valid @RequestBody ChooseHomeLocationRequest request) {
    return relocations.relocate(userId, request.locationId(), RelocationChooser.ADMIN);
  }
}
