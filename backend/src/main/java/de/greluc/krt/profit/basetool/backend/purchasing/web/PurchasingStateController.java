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

package de.greluc.krt.profit.basetool.backend.purchasing.web;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PausePurchasingRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PauseStateDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingPauseService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingSettingsService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingStateDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingStateService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.ResumePurchasingRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether purchasing is on and what the caller may do (REQ-PUR-001), and the kill switch
 * (REQ-PUR-019). The state answers while the switch is off; every other purchasing read answers 404
 * then.
 */
@RestController
@RequestMapping("/api/v1/purchasing")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class PurchasingStateController {

  private final PurchasingStateService stateService;
  private final PurchasingPauseService pauseService;
  private final PurchasingSettingsService settingsService;

  /**
   * Reads the switch and the caller's capabilities.
   *
   * @return the state for the caller
   */
  @Operation(summary = "Read whether purchasing is on and what the caller may do")
  @GetMapping("/state")
  public PurchasingStateDto state() {
    return stateService.state();
  }

  /**
   * Reads where purchasing is paused.
   *
   * @return the global pause and the paused units
   */
  @Operation(summary = "Read where purchasing is paused")
  @GetMapping("/pause")
  public PauseStateDto pauseState() {
    settingsService.requireEnabled();
    return pauseService.state();
  }

  /**
   * Pauses purchasing for the whole organisation or for one raising unit.
   *
   * @param request the scope and the reason
   * @return the pauses afterwards
   */
  @Operation(summary = "Pause purchasing globally or for one raising unit")
  @PostMapping("/pause")
  public PauseStateDto pause(@NotNull @Valid @RequestBody PausePurchasingRequest request) {
    return pauseService.pause(request);
  }

  /**
   * Lifts a pause of purchasing.
   *
   * @param request the scope to resume
   * @return the pauses afterwards
   */
  @Operation(summary = "Resume purchasing globally or for one raising unit")
  @PostMapping("/pause/resume")
  public PauseStateDto resume(@NotNull @Valid @RequestBody ResumePurchasingRequest request) {
    return pauseService.resume(request);
  }
}
