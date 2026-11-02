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

package de.greluc.krt.profit.basetool.frontend.dashboard.model;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** The synchronisations and imports the sync status widget reports on (REQ-UI-034). */
public enum SyncSource {

  /** The UEX catalogue sync. */
  UEX,

  /** The SC Wiki catalogue sync. */
  SCWIKI,

  /** The P4K catalogue import. */
  P4K;

  /**
   * The message key of the source's name.
   *
   * @return the bundle key
   */
  public @NotNull String labelKey() {
    return "dashboard.widget.sync-status.source." + name().toLowerCase(Locale.ROOT);
  }
}
