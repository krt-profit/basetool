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

import de.greluc.krt.profit.basetool.backend.patchchange.api.HomeLocationGate;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPhases;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPrompts;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.MemberRelocations;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.RelocationChooser;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.RelocationResultDto;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own home-location prompt (REQ-PATCH-020, REQ-PATCH-022): the gate's status, the
 * prompt with the curated home locations and the preview, and the choice that runs the relocation.
 * The home-location gate leaves these endpoints open to a pending member.
 */
@RestController
@RequestMapping(HomeLocationGate.PROMPT_PATH)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HomeLocationController {

  private final HomeLocationPhases phases;
  private final HomeLocationPrompts prompts;
  private final MemberRelocations relocations;
  private final AuthHelperService authHelper;

  /**
   * Tells whether the caller must choose a new home location before using the platform.
   *
   * @return the gate's status
   */
  @GetMapping("/status")
  @Operation(summary = "Tell whether the caller must choose a new home location.")
  public HomeLocationStatusDto homeLocationStatus() {
    return new HomeLocationStatusDto(phases.mustChoose(caller()));
  }

  /**
   * Reads the caller's prompt.
   *
   * @return the caller's state, the home locations on offer and what would move
   */
  @GetMapping
  @Operation(summary = "Read the caller's home-location prompt.")
  public HomeLocationPromptDto homeLocationPrompt() {
    return prompts.promptFor(caller());
  }

  /**
   * Stores the caller's choice and relocates the caller, once per phase.
   *
   * @param request the chosen home location
   * @return what moved
   */
  @PostMapping
  @Operation(summary = "Choose the caller's new home location and relocate.")
  public RelocationResultDto chooseHomeLocation(
      @Valid @RequestBody ChooseHomeLocationRequest request) {
    return relocations.relocate(caller(), request.locationId(), RelocationChooser.SELF);
  }

  /**
   * Returns the caller's member id.
   *
   * @return the id
   * @throws AccessDeniedException when the caller's subject is no member id
   */
  private UUID caller() {
    return authHelper
        .currentUserId()
        .orElseThrow(() -> new AccessDeniedException("No member behind the request."));
  }
}
