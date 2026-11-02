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

package de.greluc.krt.profit.basetool.backend.navigation.web;

import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardMode;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Reads the lower-case mode segment of a dashboard path, e.g. {@code member}. */
@Component
public class DashboardModeConverter implements Converter<String, DashboardMode> {

  @Override
  public DashboardMode convert(@NotNull String source) {
    return DashboardMode.valueOf(source.toUpperCase(Locale.ROOT));
  }
}
