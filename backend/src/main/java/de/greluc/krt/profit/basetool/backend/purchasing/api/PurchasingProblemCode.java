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

package de.greluc.krt.profit.basetool.backend.purchasing.api;

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/** The problem codes of the purchasing module (REQ-API-019, ADR-0235). */
@RequiredArgsConstructor
public enum PurchasingProblemCode implements ProblemCode {

  /** The purchasing surface is switched off (REQ-PUR-001). */
  PURCHASING_DISABLED(HttpStatus.NOT_FOUND),

  /** Purchasing is paused for the whole organisation or for the need's unit (REQ-PUR-019). */
  PURCHASING_PAUSED(HttpStatus.CONFLICT),

  /** The need is not released for purchase (REQ-PUR-010). */
  PURCHASING_NEED_NOT_PURCHASABLE(HttpStatus.CONFLICT),

  /** The need is not open (REQ-PUR-010). */
  PURCHASING_NEED_NOT_OPEN(HttpStatus.CONFLICT),

  /** The unit price is above the need's maximum unit price (REQ-PUR-008). */
  PURCHASING_PRICE_ABOVE_MAXIMUM(HttpStatus.CONFLICT),

  /** The deals of the need together exceed what the need still asks for (REQ-PUR-009). */
  PURCHASING_AMOUNT_EXCEEDS_NEED(HttpStatus.CONFLICT),

  /** The counterparty's handle belongs to a member (REQ-PUR-005). */
  PURCHASING_COUNTERPARTY_IS_MEMBER(HttpStatus.CONFLICT),

  /** The partner is blocked (REQ-PUR-004). */
  PURCHASING_PARTNER_BLOCKED(HttpStatus.CONFLICT),

  /** The risk class requires a partner with a settled deal (REQ-PUR-011). */
  PURCHASING_PARTNER_NOT_KNOWN(HttpStatus.CONFLICT),

  /** The need's quality tier is not allowed for purchasing (REQ-PUR-002). */
  PURCHASING_TIER_NOT_ALLOWED(HttpStatus.CONFLICT),

  /** The deal's total is above the highest risk class's maximum (REQ-PUR-011). */
  PURCHASING_TOTAL_ABOVE_CLASS_MAXIMUM(HttpStatus.CONFLICT),

  /** The advance would exceed the share the deal's risk class allows (REQ-PUR-011). */
  PURCHASING_ADVANCE_ABOVE_CLASS_SHARE(HttpStatus.CONFLICT),

  /** The delegate's monthly limit would be exceeded (REQ-PUR-013). */
  PURCHASING_LIMIT_EXCEEDED(HttpStatus.CONFLICT),

  /** The budget cap of the period would be exceeded (REQ-PUR-013). */
  PURCHASING_BUDGET_EXCEEDED(HttpStatus.CONFLICT),

  /** No usable funding account is configured (REQ-PUR-002). */
  PURCHASING_FUNDING_ACCOUNT_MISSING(HttpStatus.CONFLICT),

  /** The deal is not in a state that allows the action. */
  PURCHASING_DEAL_STATE(HttpStatus.CONFLICT),

  /** The tranche would pay more than the deal's total (REQ-PUR-012). */
  PURCHASING_TRANCHE_EXCEEDS_TOTAL(HttpStatus.CONFLICT),

  /** The delivery exceeds what is still undelivered (REQ-PUR-015). */
  PURCHASING_DELIVERY_EXCEEDS_UNDELIVERED(HttpStatus.CONFLICT),

  /** The measured quality does not reach the need's tier (REQ-PUR-016). */
  PURCHASING_QUALITY_BELOW_TIER(HttpStatus.CONFLICT),

  /** The delegate may not accept their own delivery (REQ-PUR-016). */
  PURCHASING_SELF_ACCEPTANCE(HttpStatus.CONFLICT),

  /** The settlement figures are inconsistent (REQ-PUR-017). */
  PURCHASING_SETTLEMENT_INVALID(HttpStatus.CONFLICT),

  /** The member already holds an active appointment (REQ-PUR-003). */
  PURCHASING_DELEGATE_ACTIVE(HttpStatus.CONFLICT);

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
