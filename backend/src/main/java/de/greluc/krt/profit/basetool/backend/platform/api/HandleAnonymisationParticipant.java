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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A module's part of the granted Art. 17 handle anonymisation (REQ-SEC-062): it replaces the
 * member's surviving handle snapshots in the tables its module holds with the erasure sentinel,
 * inside the anonymisation's transaction.
 *
 * <p>The orchestrator runs every participant by {@link #position()}, writes the activity trail's
 * marker row from the summed counts, then lets each participant record its own marker.
 * Implementations join the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface HandleAnonymisationParticipant {

  /** The row counts an anonymisation reports, one per table the participants rewrite. */
  enum Rewritten {

    /** Rows rewritten in {@code audit_event}. */
    ACTIVITY_AUDIT,

    /** Rows rewritten in {@code bank_audit_event}. */
    BANK_AUDIT,

    /** Rows rewritten in {@code bank_transaction}. */
    BANK_TRANSACTIONS,

    /** Rows rewritten in {@code bank_booking_request}. */
    BOOKING_REQUESTS,

    /** Rows rewritten in {@code bank_holder}. */
    BANK_HOLDERS,

    /** Rows rewritten in {@code job_order}. */
    JOB_ORDERS,

    /** Rows rewritten in {@code job_order_handover}. */
    MATERIAL_HANDOVERS,

    /** Rows rewritten in {@code job_order_item_handover}. */
    ITEM_HANDOVERS
  }

  /**
   * Returns where the participant runs, ascending; two participants never share a value.
   *
   * @return the position
   */
  int position();

  /**
   * Returns exactly the columns {@link #anonymise} rewrites, as {@code table.column}.
   *
   * @return the qualified column names
   */
  @NotNull
  List<String> columns();

  /**
   * Rewrites the member's handle snapshots of this module to {@code
   * de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation#SENTINEL}.
   *
   * <p>Columns matched by id are rewritten once; columns matched by text once per spelling, whole
   * value and case-insensitively, never by substring.
   *
   * @param userId the member whose snapshots are erased, whose account still exists
   * @param spellings every name the member is stored under, distinct, trimmed and non-blank,
   *     longest first; empty when none is known
   * @return the rows rewritten per table, empty entries omitted
   */
  @NotNull
  Map<Rewritten, Integer> anonymise(@NotNull UUID userId, @NotNull List<String> spellings);

  /**
   * Records this module's own marker row after the orchestrator wrote the activity trail's; a
   * module without one does nothing.
   *
   * @param userId the member whose snapshots were erased
   * @param rewritten the summed row counts of every participant
   */
  void recordAnonymised(@NotNull UUID userId, @NotNull Map<Rewritten, Integer> rewritten);
}
