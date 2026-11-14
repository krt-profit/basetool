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

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.RelocationResultDto;
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
 * trigger, label correction and phase end (REQ-PATCH-005, REQ-PATCH-008, REQ-PATCH-024), and the
 * home-location prompt and choice of the caller and, for an admin, of a member (REQ-PATCH-022),
 * over {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class PatchChangeBackendClient {

  /** The backend's admin patch-change endpoints. */
  private static final String ADMIN = "/api/v1/patch-changes/admin";

  /** The caller's own home-location prompt and choice. */
  private static final String MY_HOME_LOCATION = "/api/v1/patch-changes/me/home-location";

  /** A member's home-location prompt and choice, for an admin. */
  private static final String MEMBER_HOME_LOCATION = ADMIN + "/members/{userId}/home-location";

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

  /**
   * Ends a patch change's open home-location phase early.
   *
   * @param id the patch change
   * @return the patch change with its ended phase, or {@code null} when the backend sent no body
   */
  @Nullable
  public PatchChangeDto endPhase(@NotNull UUID id) {
    return backendApiClient.post(ADMIN + "/{id}/phase-end", null, PatchChangeDto.class, id);
  }

  /**
   * Reads the caller's home-location prompt.
   *
   * @return the caller's state, the home locations on offer and what would move, or {@code null}
   *     when the backend sent no body
   */
  @Nullable
  public HomeLocationPromptDto myHomeLocationPrompt() {
    return backendApiClient.get(MY_HOME_LOCATION, HomeLocationPromptDto.class);
  }

  /**
   * Reads whether the caller must choose a new home location before using the platform, the cheap
   * check the home-location gate asks (REQ-PATCH-020).
   *
   * @return the status, or {@code null} when the backend sent no body
   */
  @Nullable
  public HomeLocationStatusDto myHomeLocationStatus() {
    return backendApiClient.get(MY_HOME_LOCATION + "/status", HomeLocationStatusDto.class);
  }

  /**
   * Stores the caller's choice and relocates the caller, once per phase.
   *
   * @param request the chosen home location
   * @return what moved, or {@code null} when the backend sent no body
   */
  @Nullable
  public RelocationResultDto chooseMyHomeLocation(@NotNull ChooseHomeLocationRequest request) {
    return backendApiClient.post(MY_HOME_LOCATION, request, RelocationResultDto.class);
  }

  /**
   * Reads a member's home-location prompt, for a choice on the member's behalf.
   *
   * @param userId the member
   * @return the member's state, the home locations on offer and what would move, or {@code null}
   *     when the backend sent no body
   */
  @Nullable
  public HomeLocationPromptDto memberHomeLocationPrompt(@NotNull UUID userId) {
    return backendApiClient.get(MEMBER_HOME_LOCATION, HomeLocationPromptDto.class, userId);
  }

  /**
   * Chooses a member's new home location on the member's behalf and relocates the member.
   *
   * @param userId the member
   * @param request the chosen home location
   * @return what moved, or {@code null} when the backend sent no body
   */
  @Nullable
  public RelocationResultDto chooseMemberHomeLocation(
      @NotNull UUID userId, @NotNull ChooseHomeLocationRequest request) {
    return backendApiClient.post(MEMBER_HOME_LOCATION, request, RelocationResultDto.class, userId);
  }

  /**
   * Reads a member's user record, for the name the admin's member page shows.
   *
   * @param userId the member
   * @return the record, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto member(@NotNull UUID userId) {
    return backendApiClient.get("/api/v1/users/{id}", UserDto.class, userId);
  }
}
