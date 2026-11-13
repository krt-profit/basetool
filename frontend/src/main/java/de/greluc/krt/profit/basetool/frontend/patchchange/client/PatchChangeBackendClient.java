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

package de.greluc.krt.profit.basetool.frontend.patchchange.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.TriggerPatchChangeRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the patch-change domain: the admin's history, form defaults, preview,
 * trigger and label correction (REQ-PATCH-005, REQ-PATCH-008), over {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class PatchChangeBackendClient {

  /** The backend's admin patch-change endpoints. */
  private static final String ADMIN = "/api/v1/patch-changes/admin";

  private static final ParameterizedTypeReference<List<PatchChangeDto>> HISTORY =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Lists every patch change, newest live time first.
   *
   * @return the history, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PatchChangeDto> history() {
    return backendApiClient.get(ADMIN, HISTORY);
  }

  /**
   * Reads what a new patch change's form starts from.
   *
   * @return the suggested label, the previous label and live time and the presets, or {@code null}
   *     when the backend sent no body
   */
  @Nullable
  public PatchChangeFormDto form() {
    return backendApiClient.get(ADMIN + "/form", PatchChangeFormDto.class);
  }

  /**
   * Counts what a patch change with the given live time and options would touch now; writes
   * nothing.
   *
   * @param request the live time and options
   * @return the counts of every enabled effect, or {@code null} when the backend sent no body
   */
  @Nullable
  public PatchChangePreviewDto preview(@NotNull PatchChangePreviewRequest request) {
    return backendApiClient.post(ADMIN + "/preview", request, PatchChangePreviewDto.class);
  }

  /**
   * Records a patch change for every org unit and runs its effects, all or nothing.
   *
   * @param request the label, kind, live time, options and the typed confirmation
   * @return the recorded patch change, or {@code null} when the backend sent no body
   */
  @Nullable
  public PatchChangeDto trigger(@NotNull TriggerPatchChangeRequest request) {
    return backendApiClient.post(ADMIN, request, PatchChangeDto.class);
  }

  /**
   * Corrects a triggered patch change's label, carrying the version the admin last saw.
   *
   * @param id the patch change
   * @param request the corrected label and the version
   * @return the corrected patch change, or {@code null} when the backend sent no body
   */
  @Nullable
  public PatchChangeDto correctLabel(
      @NotNull UUID id, @NotNull CorrectPatchChangeLabelRequest request) {
    return backendApiClient.patch(ADMIN + "/{id}/label", request, PatchChangeDto.class, id);
  }
}
