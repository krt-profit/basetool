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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * Discriminator of the bulk rebooking (Massen-Umbuchen, REQ-INV-036): which kind of move the whole
 * marked selection performs.
 *
 * <p>Unlike the single-row Umbuchen modal — which infers the personal direction from the source
 * row's own {@code personal} flag — a bulk selection can mix personal and shared rows, so the two
 * personal directions are named <em>explicitly</em> as separate modes. That makes the outcome
 * independent of what happens to be selected: {@link #PERSONALIZE} always ends with personal stock,
 * {@link #DEPERSONALIZE} always with shared stock, and rows that already sit in the requested
 * target state are skipped rather than flipped back and forth.
 */
public enum BulkRebookMode {

  /**
   * Move every marked row to a target user / location / owning org-unit pool — the bulk counterpart
   * of the single row's {@code TRANSFER} book-out. Rows already sitting at the requested target
   * (same user <em>and</em> same location) are skipped.
   */
  LOCATION,

  /**
   * Mark every marked row as the owner's personal stock. Rows that are already personal are
   * skipped; a row earmarked for a job order or mission aborts the whole action, because personal
   * stock may never carry either association and silently dropping the link would lose the
   * assignment.
   */
  PERSONALIZE,

  /**
   * Move every marked row into the shared squadron pool. Rows that are already shared are skipped.
   * The new shared rows are stamped onto the picked org-unit pool (validated against the owner's
   * memberships), or onto the row's current pool when no pick is made.
   */
  DEPERSONALIZE
}
