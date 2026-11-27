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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The patch change's part of the Art. 15 data export: the member's home-location choices and what
 * each relocation moved (REQ-SEC-058, REQ-PATCH-022).
 */
@Component
public class PatchChangeExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                115,
                "homeLocationChoices",
                ART_15,
                "Chosen by the member or by an admin on the member's behalf.",
                """
                SELECT p.label AS patch, l.name AS home_location, r.chosen_by, r.relocated_at,
                       r.rows_moved, r.ships_moved
                FROM patch_change_relocation r
                  JOIN patch_change p ON p.id = r.patch_change_id
                  LEFT JOIN location l ON l.id = r.home_location_id
                WHERE r.user_id = :userId ORDER BY r.relocated_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.ACCOUNT_LIFETIME,
                Source.MEMBER,
                Source.OTHER_MEMBERS,
                Source.CONNECTED_APPLICATION)
            .keeping("home_location", "location.name; see warehouseContributions.location."),
        new Section(
                680,
                "patchChangesTriggered",
                ART_15,
                "Patch changes the member triggered or whose home-location phase they ended as an"
                    + " administrator, kept as evidence of an act on every org unit's data.",
                """
                SELECT label, kind, live_at, triggered_at, phase_ended_at, cancel_refinery_orders,
                       currency_wipe, home_location_prompt,
                       triggered_by IS NOT DISTINCT FROM :userId AS triggered_by_member,
                       phase_ended_by IS NOT DISTINCT FROM :userId AS phase_ended_by_member
                FROM patch_change
                WHERE triggered_by = :userId OR phase_ended_by = :userId ORDER BY triggered_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ADMIN_ACT_PERMANENT, Source.MEMBER));
  }
}
