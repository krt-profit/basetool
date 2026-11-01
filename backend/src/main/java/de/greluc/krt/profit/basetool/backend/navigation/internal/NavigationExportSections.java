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
            "Chosen by the member: entry keys of pages and widget choices, never free text.",
            """
            SELECT 'favourites' AS preference_section, f.order_index AS position,
                   f.entry_key AS entry_key, CAST(NULL AS VARCHAR) AS dashboard_mode,
                   CAST(NULL AS VARCHAR) AS widget_type, CAST(NULL AS VARCHAR) AS column_side,
                   CAST(NULL AS UUID) AS org_unit_id, CAST(NULL AS INTEGER) AS range_days,
                   CAST(NULL AS INTEGER) AS row_count, CAST(NULL AS VARCHAR) AS status_filter
            FROM navigation_favourite f
            JOIN navigation_preferences np ON np.user_id = f.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'hidden', 0, h.entry_key, CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
                   CAST(NULL AS VARCHAR), CAST(NULL AS UUID), CAST(NULL AS INTEGER),
                   CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR)
            FROM navigation_hidden h
            JOIN navigation_preferences np ON np.user_id = h.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'tabbar-' || lower(t.mode), t.slot_index, t.entry_key, CAST(NULL AS VARCHAR),
                   CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS UUID),
                   CAST(NULL AS INTEGER), CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR)
            FROM navigation_tab_bar t
            JOIN navigation_preferences np ON np.user_id = t.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'desktopMode', 0, a.desktop_mode, CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
                   CAST(NULL AS VARCHAR), CAST(NULL AS UUID), CAST(NULL AS INTEGER),
                   CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR)
            FROM navigation_appearance a
            JOIN navigation_preferences np ON np.user_id = a.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'shortcutsEnabled', 0, CAST(a.shortcuts_enabled AS VARCHAR), CAST(NULL AS VARCHAR),
                   CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS UUID),
                   CAST(NULL AS INTEGER), CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR)
            FROM navigation_appearance a
            JOIN navigation_preferences np ON np.user_id = a.preferences_id
            WHERE np.user_id = :userId
            UNION ALL
            SELECT 'dashboard', w.position, CAST(NULL AS VARCHAR), l.mode, w.widget_type,
                   w.column_side, w.org_unit_id, w.range_days, w.row_count, w.status_filter
            FROM navigation_dashboard_widget w
            JOIN navigation_dashboard_layout l ON l.id = w.layout_id
            WHERE l.preferences_id = :userId
            ORDER BY preference_section, dashboard_mode, position, entry_key
            """));
  }
}
