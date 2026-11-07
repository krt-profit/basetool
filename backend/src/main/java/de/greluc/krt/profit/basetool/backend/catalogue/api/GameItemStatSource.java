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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

/**
 * The source of a stored blueprint stat base value (REQ-P4K-030, ADR-0342); a P4K value wins over
 * an SC Wiki value of the same stat.
 */
public enum GameItemStatSource {
  /** The P4K catalogue's {@code stats} of the item. */
  P4K,
  /** The SC Wiki item payload, written only where no P4K value exists. */
  SCWIKI
}
