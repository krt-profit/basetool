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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;

/**
 * The organizational layer a {@link BankAccount} belongs to (REQ-BANK-001). There is deliberately
 * no per-player account type — players appear only as {@link BankHolder holders} of organizational
 * money (REQ-BANK-003). Persisted as {@code VARCHAR(16)} via {@code @Enumerated(STRING)}; the V150
 * CHECK constraint mirrors this set.
 */
public enum BankAccountType {

  /**
   * Account owned by exactly one {@link OrgUnit} (Staffel or Spezialkommando); at most one per org
   * unit, enforced by the partial unique index {@code uq_bank_account_org_unit} (V150).
   */
  ORG_UNIT,

  /**
   * Account for a Bereich. Bereiche are not entities (see {@code docs/specs/org-chart.md}), so the
   * account carries the free-form area name in {@link BankAccount#getAreaName()}.
   */
  AREA,

  /**
   * The single account of the cartel as a whole; singleton enforced by the partial unique index
   * {@code uq_bank_account_singleton_cartel} (V150).
   */
  CARTEL,

  /**
   * The bank's own operating account; singleton enforced by the partial unique index {@code
   * uq_bank_account_singleton_cartel_bank} (V150).
   */
  CARTEL_BANK,

  /** Dynamically created special-purpose account with a free-form display name (e.g. events). */
  SPECIAL;

  /**
   * Whether a debit (withdrawal or transfer) leaving an account of this type must carry a non-blank
   * justification (REQ-BANK-045). The cartel-wide accounts — the KRT account ({@link #CARTEL}), the
   * bank's own operating account ({@link #CARTEL_BANK}) and every special account ({@link
   * #SPECIAL}) — hold org-wide money whose outflow needs an explicit reason; org-unit and area
   * accounts ({@link #ORG_UNIT}, {@link #AREA}) leave the justification optional.
   *
   * @return {@code true} for {@code CARTEL}, {@code CARTEL_BANK} and {@code SPECIAL}; {@code false}
   *     for {@code ORG_UNIT} and {@code AREA}
   */
  public boolean requiresDebitJustification() {
    return this == CARTEL || this == CARTEL_BANK || this == SPECIAL;
  }
}
