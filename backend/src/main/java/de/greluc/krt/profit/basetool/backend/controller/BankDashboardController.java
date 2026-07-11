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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.BankDashboardService;
import de.greluc.krt.profit.basetool.backend.service.BankSecurityService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the bank dashboard (epic #556, REQ-BANK-016): balances, 30-day deltas and
 * sparkline series of every visible account in one grouped payload — employees get their granted
 * accounts, management/admin get all accounts plus the totals strip (REQ-BANK-010).
 */
@RestController
@RequestMapping("/api/v1/bank/dashboard")
@RequiredArgsConstructor
public class BankDashboardController {

  private final BankDashboardService bankDashboardService;
  private final BankSecurityService bankSecurityService;
  private final AuthHelperService authHelperService;

  /**
   * Assembles the dashboard for the calling user.
   *
   * @return the dashboard payload
   */
  @Operation(summary = "Read the bank dashboard for the caller")
  @GetMapping
  @PreAuthorize(Roles.HAS_ROLE_BANK_EMPLOYEE)
  @Transactional(readOnly = true)
  public BankDashboardDto getDashboard() {
    UUID userId =
        authHelperService
            .currentUserId()
            .orElseThrow(() -> new AccessDeniedException("Authentication required"));
    return bankDashboardService.getDashboard(bankSecurityService.isManagement(), userId);
  }
}
