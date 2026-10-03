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

import de.greluc.krt.profit.basetool.architecture.fixtures.profit.MissionLedgerFixture;
import de.greluc.krt.profit.basetool.architecture.fixtures.scope.OrgScopeFixture;

/** Planted violations: a bank class coupled to a profit flow and to the org-unit scope. */
public class LedgerFixtureService {

  private MissionLedgerFixture mission;
  private OrgScopeFixture scope;

  /**
   * Books with an org-unit check.
   *
   * @return the scope's answer
   */
  public boolean book() {
    return scope.canSee();
  }
}
