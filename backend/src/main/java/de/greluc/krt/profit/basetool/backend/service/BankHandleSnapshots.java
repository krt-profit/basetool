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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The bank's part of the GDPR handle anonymisation (REQ-SEC-057, plan §7.6): erases the member's
 * handle snapshots in the bank tables and records the bank's own audit marker.
 */
public interface BankHandleSnapshots {

  /**
   * Row counts of one anonymisation in the bank tables.
   *
   * @param bankAudit rows rewritten in {@code bank_audit_event}
   * @param bankTransactions rows rewritten in {@code bank_transaction}
   * @param bookingRequests rows rewritten in {@code bank_booking_request}
   * @param bankHolders rows rewritten in {@code bank_holder}
   */
  record Counts(int bankAudit, int bankTransactions, int bookingRequests, int bankHolders) {}

  /**
   * Replaces the member's handle snapshots in the bank tables with the sentinel, inside the
   * caller's transaction.
   *
   * @param userId the member whose snapshots are erased
   * @param sentinel the replacement handle
   * @return the per-table row counts
   */
  @NotNull
  Counts anonymiseHandles(@NotNull UUID userId, @NotNull String sentinel);

  /**
   * Records the bank's {@code HANDLE_SNAPSHOTS_ANONYMISED} audit row for an anonymisation.
   *
   * @param userId the member whose snapshots were erased
   * @param counts the per-table row counts of that anonymisation
   */
  void recordAnonymised(@NotNull UUID userId, @NotNull Counts counts);
}
