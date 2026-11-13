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
import de.greluc.krt.profit.basetool.backend.patchchange.internal.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeHistory;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeLabelCorrection;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangePreviewDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeTrigger;
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
 * The admin's patch-change surface (REQ-PATCH-005, REQ-PATCH-008): the form's defaults, the
 * preview, the trigger, the history and the label correction. Only an admin reaches it, also when
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
}
