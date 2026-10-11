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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The personalinventory's part of the Art. 15 / Art. 20 data export: the member's personal
 * inventory (REQ-SEC-058).
 */
@Component
public class PersonalInventoryExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                180,
                "personalInventory",
                ART_15_20,
                "Entered by the member, including free-text notes.",
                """
                SELECT name, quantity, location_type, location_name_snapshot, note, created_at
                FROM personal_inventory_item WHERE owner_user_id = :userId ORDER BY created_at
                """)
            .scrubbing("name", "note"));
  }
}
