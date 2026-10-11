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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The refinery's part of the Art. 15 / Art. 20 data export: the member's refinery orders
 * (REQ-SEC-058).
 */
@Component
public class RefineryExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                280,
                "refineryOrdersOwned",
                ART_15_20,
                "Created by the member.",
                """
                SELECT r.status, r.started_at, r.duration_minutes, r.expenses, r.ore_sales,
                       l.name AS location, r.created_at
                FROM refinery_order r LEFT JOIN location l ON l.id = r.location_id
                WHERE r.owner_id = :userId ORDER BY r.created_at
                """)
            .keeping("location", "location.name; see warehouseContributions.location."));
  }
}
