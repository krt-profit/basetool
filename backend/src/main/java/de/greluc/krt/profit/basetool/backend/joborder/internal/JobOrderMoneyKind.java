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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

/** Identifies the purpose recorded with an effective money fact. */
public enum JobOrderMoneyKind {
  /** Records newly captured order funds. */
  PROVISION,
  /** Moves captured funds to another procurement holder. */
  COLLECTION,
  /** Records an actual transfer to a designated requester recipient. */
  HANDOVER,
  /** Changes money held by the same person from procurement to requester purpose. */
  PURPOSE_HANDOVER;

  boolean delivers() {
    return this == HANDOVER || this == PURPOSE_HANDOVER;
  }
}
