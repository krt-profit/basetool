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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The purchasing module's part of the Art. 15 / Art. 20 data export: the member's deals as a trade
 * delegate and their appointments (REQ-PUR-028, REQ-SEC-058). The partner of a deal is a third
 * party's data and is not part of it.
 */
@Component
public class PurchasingExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                490,
                "purchasingDeals",
                ART_15_20,
                "Entered by the member.",
                """
                SELECT d.created_at, d.status, COALESCE(m.name, g.name) AS entry,
                       d.amount, d.unit_price, d.total, d.payment_model, d.risk_class
                FROM purchasing_deal d
                  LEFT JOIN material m ON m.id = d.material_id
                  LEFT JOIN game_item g ON g.id = d.game_item_id
                WHERE d.delegate_user_id = :userId ORDER BY d.created_at
                """)
            .keeping("entry", "entry: the name of a catalogue material or item, not a person."),
        new Section(
            491,
            "purchasingAppointments",
            ART_15_20,
            "Decided by the governing leadership about the member.",
            """
            SELECT p.appointed_at, p.valid_until, p.monthly_limit, p.revoked_at
            FROM purchasing_delegate p
            WHERE p.user_id = :userId ORDER BY p.appointed_at
            """));
  }
}
