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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The navigation module's part of the person search: it holds no free text, only entry keys of
 * pages, so it searches no column (REQ-SEC-060).
 */
@Component
public class NavigationMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of();
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "navigation_favourite.entry_key",
        "navigation_hidden.entry_key",
        "navigation_tab_bar.entry_key");
  }
}
