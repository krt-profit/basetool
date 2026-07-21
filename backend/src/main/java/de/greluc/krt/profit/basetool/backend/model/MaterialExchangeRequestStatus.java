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
 * Lifecycle state of a {@link MaterialExchangeRequest} (Gesuch) on the Materialbörse trade board —
 * the request-side sibling of {@link MaterialExchangeOfferStatus} (ADR-0116, REQ-MARKET-016).
 *
 * <p>A request is {@link #ACTIVE} while it is publicly listed and {@link #DEACTIVATED} once the
 * requester withdraws it. Unlike an offer there is no one-active-per-Lager-row constraint — a
 * member may post several requests for the same material or item. The {@code MARKET} request
 * business-metric gauge counts {@link #ACTIVE} rows only.
 */
public enum MaterialExchangeRequestStatus {

  /** Publicly listed on the board and visible to every member. */
  ACTIVE,

  /** Withdrawn by the requester; retained for the audit trail but never listed. */
  DEACTIVATED
}
