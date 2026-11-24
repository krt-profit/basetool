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

/**
 * The Basetool links an exchange answer carries. The published v1 schemas pin every {@code link} to
 * {@code ^https://profit-base\.online/}, and a client opens one only after checking that host, so
 * the host is part of the contract rather than configuration.
 */
final class ExchangeLinks {

  /** The Basetool's origin every link of an exchange answer starts with. */
  static final String BASETOOL = "https://profit-base.online";

  /** Not instantiable. */
  private ExchangeLinks() {}
}
