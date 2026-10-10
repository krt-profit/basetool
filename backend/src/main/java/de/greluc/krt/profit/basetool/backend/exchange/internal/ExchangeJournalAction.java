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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** What an exchange write did to one entry, as the journal records it (REQ-XCH-022). */
@Getter
@RequiredArgsConstructor
public enum ExchangeJournalAction {
  BLUEPRINT_ADD(ExchangeResource.BLUEPRINT),
  BLUEPRINT_REMOVE(ExchangeResource.BLUEPRINT),
  STOCK_SET_QUANTITY(ExchangeResource.STOCK),
  SHIP_LINK(ExchangeResource.SHIP),
  SHIP_UPSERT(ExchangeResource.SHIP),
  SHIP_REMOVE(ExchangeResource.SHIP);

  /** The resource the action belongs to. */
  @NotNull private final ExchangeResource resource;
}
