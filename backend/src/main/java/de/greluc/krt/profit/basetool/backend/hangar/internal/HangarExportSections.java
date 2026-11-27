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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** The hangar's part of the Art. 15 / Art. 20 data export: the member's ships (REQ-SEC-058). */
@Component
public class HangarExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                110,
                "hangar",
                ART_15_20,
                "Entered by the member, by hand or from their own hangar import with its pledge"
                    + " details.",
                """
                SELECT s.name, t.name AS ship_type, s.insurance, s.fitted, l.name AS location,
                       o.name AS org_unit, s.origin, s.origin_locked, s.production_status,
                       s.pledge_id, s.pledge_date, s.pledge_cost, s.pledge_cost_currency,
                       s.meltable, s.giftable, s.created_at, s.updated_at
                FROM ship s
                  LEFT JOIN ship_type t ON t.id = s.ship_type_id
                  LEFT JOIN location l ON l.id = s.location_id
                  LEFT JOIN org_unit o ON o.id = s.owning_org_unit_id
                WHERE s.owner_id = :userId ORDER BY s.created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.ACCOUNT_LIFETIME,
                Source.MEMBER,
                Source.CONNECTED_APPLICATION)
            .scrubbing("name")
            .keeping(
                "ship_type",
                "ship_type.name: a hull from the synced catalogue. Flagged for the same reason as"
                    + " warehouseContributions.material -- the statement also reads ship, whose"
                    + " name column is the member's own and is scrubbed.")
            .keeping("location", "location.name; see warehouseContributions.location.")
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit."));
  }
}
