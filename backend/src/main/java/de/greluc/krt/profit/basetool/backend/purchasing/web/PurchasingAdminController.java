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
import de.greluc.krt.profit.basetool.backend.purchasing.internal.FundingAccountOptionDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingSettingsDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingSettingsService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.RiskClassDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.UpdatePurchasingSettingsRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.UpdateRiskClassesRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin side of purchasing: the switch, the settings and the risk-class table (REQ-PUR-002).
 */
@RestController
@RequestMapping("/api/v1/purchasing/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class PurchasingAdminController {

  private final PurchasingSettingsService settingsService;

  /**
   * Reads the switch and the settings.
   *
   * @return the settings
   */
  @Operation(summary = "Read the purchasing settings")
  @GetMapping("/settings")
  public PurchasingSettingsDto settings() {
    return settingsService.read();
  }

  /**
   * Stores the switch and the settings.
   *
   * @param request the new values and the echoed version
   * @return the stored settings
   */
  @Operation(summary = "Set the purchasing switch and settings")
  @PutMapping("/settings")
  public PurchasingSettingsDto updateSettings(
      @NotNull @Valid @RequestBody UpdatePurchasingSettingsRequest request) {
    return settingsService.update(request);
  }

  /**
   * Lists the bank accounts that can be chosen as the funding account.
   *
   * @return the candidates by account number
   */
  @Operation(summary = "List the bank accounts that can fund purchasing")
  @GetMapping("/funding-accounts")
  public List<FundingAccountOptionDto> fundingAccounts() {
    return settingsService.fundingAccountOptions();
  }

  /**
   * Reads the risk-class table.
   *
   * @return the three classes
   */
  @Operation(summary = "Read the risk-class table")
  @GetMapping("/risk-classes")
  public List<RiskClassDto> riskClasses() {
    return settingsService.riskClasses();
  }

  /**
   * Stores changes of the risk-class table.
   *
   * @param request the classes to change, each with its echoed version
   * @return the whole table afterwards
   */
  @Operation(summary = "Set the risk-class table")
  @PutMapping("/risk-classes")
  public List<RiskClassDto> updateRiskClasses(
      @NotNull @Valid @RequestBody UpdateRiskClassesRequest request) {
    return settingsService.updateRiskClasses(request);
  }
}
