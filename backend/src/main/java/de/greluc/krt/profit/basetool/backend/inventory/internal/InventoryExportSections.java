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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The inventory's part of the Art. 15 / Art. 20 data export: the member's warehouse contributions
 * (REQ-SEC-058).
 */
@Component
public class InventoryExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                100,
                "warehouseContributions",
                ART_15_20,
                "Entered by the member; the note is their own free text.",
                """
                SELECT i.amount, i.quality, i.personal, i.stolen, i.note, m.name AS material,
                       l.name AS location, i.created_at
                FROM inventory_item i
                  LEFT JOIN material m ON m.id = i.material_id
                  LEFT JOIN location l ON l.id = i.location_id
                WHERE i.user_id = :userId ORDER BY i.created_at
                """)
            .scrubbing("note")
            .keeping(
                "location",
                "location.name: a station or outpost from the synced catalogue, not a person.")
            .keeping(
                "material",
                "material.name: a commodity from the synced catalogue. Flagged only because the"
                    + " statement also reads location, whose name column is a person-name surface;"
                    + " the coverage check matches column names across a statement's tables on"
                    + " purpose, so an over-flag is answered here rather than by narrowing the"
                    + " check."));
  }
}
