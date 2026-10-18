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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the pages of one domain that the quick access loads on demand because deciding whether
 * the caller may open them needs a backend read (REQ-UI-026, ADR-0282).
 */
public interface QuickAccessContributor {

  /**
   * The pages the current caller may open.
   *
   * <p>Fails closed: a failed read yields no entry.
   *
   * @return the entries, possibly empty
   */
  @NotNull
  List<QuickAccessEntry> entries();
}
