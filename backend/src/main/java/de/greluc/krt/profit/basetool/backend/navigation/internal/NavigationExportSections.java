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

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The navigation module's part of the Art. 15 / Art. 20 data export: the member's navigation
 * preferences in one section (REQ-SEC-058).
 */
@Component
public class NavigationExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
            500,
            "navigationPreferences",
            ART_15_20,
            "Chosen by the member: entry keys of pages, never free text.",
            """
            SELECT 'favourites' AS preference_section, f.order_index AS position,
                   f.entry_key AS entry_key
            FROM navigation_favourite f
            JOIN navigation_preferences np ON np.user_id = f.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'hidden' AS preference_section, 0 AS position, h.entry_key AS entry_key
            FROM navigation_hidden h
            JOIN navigation_preferences np ON np.user_id = h.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'order.' || o.mode || '.' || o.scope AS preference_section,
                   o.order_index AS position, o.entry_key AS entry_key
            FROM navigation_order o
            JOIN navigation_preferences np ON np.user_id = o.preferences_id
            WHERE np.user_id = :userId
            ORDER BY preference_section, position, entry_key
            """));
  }
}
