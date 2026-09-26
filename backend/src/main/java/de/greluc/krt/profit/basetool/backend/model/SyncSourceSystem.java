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
 * External catalogue that produced an {@link ExternalSyncReport} event. Independent of {@link
 * MaterialExternalAliasSource}, which covers only commodity aliases.
 */
public enum SyncSourceSystem {

  /** Event emitted by a UEX sync service. */
  UEX,

  /** Event emitted by an SC Wiki sync service. */
  SCWIKI,

  /**
   * Event emitted by the KRT P4K Reader catalog import (admin upload of {@code Game2.dcb} data).
   */
  P4K
}
