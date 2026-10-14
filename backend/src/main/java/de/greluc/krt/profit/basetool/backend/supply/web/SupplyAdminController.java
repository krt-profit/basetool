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

package de.greluc.krt.profit.basetool.backend.supply.web;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplySettingsDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplySettingsService;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitSettingService;
import de.greluc.krt.profit.basetool.backend.supply.internal.UpdateSupplySettingsRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.UpdateSupplyUnitRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The admin side of the supply module: the units that take part and the runtime switch. */
@RestController
@RequestMapping("/api/v1/supply/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class SupplyAdminController {

  private final SupplyUnitSettingService unitSettingService;
  private final SupplySettingsService settingsService;

  /**
   * Lists every active org unit with its supply setting.
   *
   * @return all units
   */
  @Operation(summary = "List every org unit with its supply setting")
  @GetMapping("/units")
  public List<SupplyUnitDto> units() {
    return unitSettingService.listAll();
  }

  /**
   * Sets what a unit does in the supply module.
   *
   * @param orgUnitId the unit
   * @param request the flags and the echoed version
   * @return the unit with its new setting
   */
  @Operation(summary = "Set what an org unit does in the supply module")
  @PutMapping("/units/{orgUnitId}")
  public SupplyUnitDto updateUnit(
      @PathVariable UUID orgUnitId, @NotNull @Valid @RequestBody UpdateSupplyUnitRequest request) {
    return unitSettingService.update(orgUnitId, request);
  }

  /**
   * Reads the runtime settings.
   *
   * @return the switch and the governing Bereich
   */
  @Operation(summary = "Read the supply runtime settings")
  @GetMapping("/settings")
  public SupplySettingsDto settings() {
    return settingsService.read();
  }

  /**
   * Stores the runtime settings.
   *
   * @param request the switch and the governing Bereich
   * @return the stored settings
   */
  @Operation(summary = "Store the supply runtime settings")
  @PutMapping("/settings")
  public SupplySettingsDto updateSettings(
      @NotNull @Valid @RequestBody UpdateSupplySettingsRequest request) {
    return settingsService.update(request.enabled(), request.governingAreaId());
  }
}
