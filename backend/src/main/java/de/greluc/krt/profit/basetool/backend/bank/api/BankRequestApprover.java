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

package de.greluc.krt.profit.basetool.backend.bank.api;

/**
 * The class of approver a flagged booking request needs before a bank employee may confirm it
 * (REQ-BANK-046); set only when the request needs approval, decided by {@link BankAccessPolicy}.
 */
public enum BankRequestApprover {

  /**
   * The account's derived responsible holder (REQ-BANK-034) approves — the behaviour for every
   * request-capable account other than the KRT account: Staffelleiter / SK-Lead for an {@code
   * ORG_UNIT} account, Bereichsleiter for an {@code AREA} account.
   */
  RESPONSIBLE_HOLDER,

  /**
   * The bank management (holders of the {@code BANK_MANAGEMENT} role) approves: the middle band of
   * the KRT-account amount ladder, above {@code T1} and at or below {@code T2} (REQ-BANK-047).
   */
  BANK_MANAGEMENT,

  /**
   * The Organisationsleitung (any {@code OL_MEMBER}) approves — the top band of the KRT-account
   * amount ladder (REQ-BANK-047): an amount above the area-lead ceiling {@code T2}.
   */
  ORGANISATIONSLEITUNG
}
