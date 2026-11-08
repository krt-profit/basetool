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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

/**
 * The column names several kinds write into the change report's {@code field_name} (REQ-P4K-014),
 * so a kind's report rows and the report filters name a column the same way.
 */
final class P4kReportColumns {

  /** The German name the P4K writes (REQ-P4K-023). */
  static final String NAME_DE = "name_de";

  /** The game class name. */
  static final String CLASS_NAME = "class_name";

  /** The parent link of a resource group or a starmap object. */
  static final String PARENT_ID = "parent_id";

  /** Non-instantiable holder of constants. */
  private P4kReportColumns() {}
}
