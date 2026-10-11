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

package de.greluc.krt.profit.basetool.backend.orgchart.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The orgchart's part of the Art. 15 / Art. 20 data export: the member's org-chart placements
 * (REQ-SEC-058).
 */
@Component
public class OrgChartExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                60,
                "orgChartPositions",
                ART_15,
                "A placement in the org chart, assigned by the organisation.",
                """
                SELECT p.position_type, p.name, p.display_name, o.name AS org_unit, p.created_at
                FROM org_chart_position p LEFT JOIN org_unit o ON o.id = p.org_unit_id
                WHERE p.user_id = :userId ORDER BY p.created_at
                """)
            .scrubbing("name", "display_name")
            .keeping(
                "org_unit",
                "org_unit.name, the unit the position sits in; see orgUnitMemberships.org_unit."));
  }
}
