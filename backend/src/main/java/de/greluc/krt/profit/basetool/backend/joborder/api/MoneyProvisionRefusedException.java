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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import static de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderProblemCode.MONEY_PROVISION_REFUSED;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/** Reports a provision identity that can no longer have a monetary effect. */
public final class MoneyProvisionRefusedException extends DomainProblem {

  /** Creates the problem for an operation that can no longer book. */
  public MoneyProvisionRefusedException() {
    super("problem.money_provision_refused.detail");
  }

  @NotNull
  @Override
  public HttpStatus status() {
    return MONEY_PROVISION_REFUSED.status();
  }

  @NotNull
  @Override
  public String code() {
    return MONEY_PROVISION_REFUSED.code();
  }

  @NotNull
  @Override
  public String logLabel() {
    return "Money provision refused";
  }
}
