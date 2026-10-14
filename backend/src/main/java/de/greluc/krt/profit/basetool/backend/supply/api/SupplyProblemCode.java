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

package de.greluc.krt.profit.basetool.backend.supply.api;

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/** The problem codes of the supply module (REQ-API-019, ADR-0235). */
@RequiredArgsConstructor
public enum SupplyProblemCode implements ProblemCode {

  /** The supply surface is switched off (REQ-SUP-001). */
  SUPPLY_DISABLED(HttpStatus.NOT_FOUND),

  /** The unit may not raise needs (REQ-SUP-002). */
  SUPPLY_UNIT_CANNOT_RAISE(HttpStatus.CONFLICT),

  /** The unit does not accept donations (REQ-SUP-002). */
  SUPPLY_UNIT_CANNOT_RECEIVE(HttpStatus.CONFLICT),

  /** The need is not open (REQ-SUP-005). */
  SUPPLY_NEED_NOT_OPEN(HttpStatus.CONFLICT),

  /** The need belongs to another unit than the donation's receiving unit (REQ-SUP-007). */
  SUPPLY_NEED_UNIT_MISMATCH(HttpStatus.CONFLICT),

  /** The donation is no longer pledged (REQ-SUP-009). */
  SUPPLY_DONATION_NOT_PLEDGED(HttpStatus.CONFLICT),

  /** The donation does not match the need's catalogue entry or quality tier (REQ-SUP-007). */
  SUPPLY_DONATION_DOES_NOT_FIT_NEED(HttpStatus.CONFLICT),

  /** The pledged row does not belong to the donor (REQ-SUP-007). */
  SUPPLY_ROW_NOT_OWNED(HttpStatus.CONFLICT);

  /** The status the code is answered with. */
  private final HttpStatus status;

  /**
   * Returns the wire value, which is the constant's name.
   *
   * @return the code string
   */
  @NotNull
  @Override
  public String code() {
    return name();
  }

  /**
   * Returns the status the code is answered with.
   *
   * @return the HTTP status
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return status;
  }
}
