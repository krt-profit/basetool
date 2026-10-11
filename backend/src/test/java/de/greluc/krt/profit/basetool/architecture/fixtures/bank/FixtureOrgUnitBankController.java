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

package de.greluc.krt.profit.basetool.architecture.fixtures.bank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planted violations: an unregistered controller under the org-unit bank prefix that skips the seam
 * and reads a bank-domain service.
 */
@RestController
@RequestMapping("/api/v1/bank/org-units")
public class FixtureOrgUnitBankController {

  private FixtureStaffService staff;

  /**
   * Reads past the seam.
   *
   * @return the unscoped answer
   */
  @GetMapping("/balances")
  public String balances() {
    return staff.balances();
  }
}
