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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The supply module's part of the Art. 15 / Art. 20 data export: the member's donations
 * (REQ-SUP-020, REQ-SEC-058).
 */
@Component
public class SupplyExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                480,
                "supplyDonations",
                ART_15_20,
                "Entered by the member.",
                """
                SELECT d.created_at, d.status, COALESCE(m.name, g.name) AS entry, d.quality,
                       d.pledged_amount, d.received_amount, u.name AS receiving_unit
                FROM supply_donation d
                  LEFT JOIN material m ON m.id = d.material_id
                  LEFT JOIN game_item g ON g.id = d.game_item_id
                  JOIN org_unit u ON u.id = d.receiving_org_unit_id
                WHERE d.donor_user_id = :userId ORDER BY d.created_at
                """)
            .keeping("entry", "entry: the name of a catalogue material or item, not a person.")
            .keeping("receiving_unit", "receiving_unit: the name of an org unit, not a person."));
  }
}
