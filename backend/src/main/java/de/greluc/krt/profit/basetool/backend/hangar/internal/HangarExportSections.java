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

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

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
                "Entered by the member.",
                """
                SELECT s.name, t.name AS ship_type, s.insurance, s.fitted, l.name AS location,
                       s.created_at
                FROM ship s
                  LEFT JOIN ship_type t ON t.id = s.ship_type_id
                  LEFT JOIN location l ON l.id = s.location_id
                WHERE s.owner_id = :userId ORDER BY s.created_at
                """)
            .scrubbing("name")
            .keeping(
                "ship_type",
                "ship_type.name: a hull from the synced catalogue. Flagged for the same reason as"
                    + " warehouseContributions.material -- the statement also reads ship, whose"
                    + " name column is the member's own and is scrubbed.")
            .keeping("location", "location.name; see warehouseContributions.location."));
  }
}
