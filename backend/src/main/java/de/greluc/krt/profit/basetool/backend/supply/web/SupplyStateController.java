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
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplySettingsService;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitSettingService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the supply surface is on and which units take part in it (REQ-SUP-001, REQ-SUP-002). The
 * state answers while the switch is off; every other supply read answers 404 then.
 */
@RestController
@RequestMapping("/api/v1/supply")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class SupplyStateController {

  private final SupplySettingsService settingsService;
  private final SupplyUnitSettingService unitSettingService;

  /**
   * Tells whether the supply surface is switched on.
   *
   * @return the switch
   */
  @Operation(summary = "Read whether the supply surface is switched on")
  @GetMapping("/state")
  public SupplyStateDto state() {
    return new SupplyStateDto(settingsService.isEnabled());
  }

  /**
   * Lists the units that raise needs or accept donations.
   *
   * @return the configured units
   */
  @Operation(summary = "List the units that raise needs or accept donations")
  @GetMapping("/units")
  public List<SupplyUnitDto> units() {
    return unitSettingService.listConfigured();
  }

  /**
   * The switch of the supply surface.
   *
   * @param enabled whether the supply surface is switched on
   */
  public record SupplyStateDto(boolean enabled) {}
}
