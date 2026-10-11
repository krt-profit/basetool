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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The joborder's part of the Art. 15 / Art. 20 data export: the member's job-order assignments and
 * material claims (REQ-SEC-058).
 */
@Component
public class JobOrderExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                290,
                "jobOrderAssignments",
                ART_15_20,
                "The member's own assignment note.",
                """
                SELECT o.display_id, o.type, o.status, a.note, a.created_at
                FROM job_order_assignees a JOIN job_order o ON o.id = a.job_order_id
                WHERE a.user_id = :userId ORDER BY a.created_at
                """)
            .scrubbing("note"),
        new Section(
                300,
                "materialClaims",
                ART_15_20,
                "Signed up for by the member.",
                """
                SELECT o.display_id AS job_order, m.name AS material, c.amount,
                       t.code AS quality_requirement, c.created_at
                FROM material_claim c
                  LEFT JOIN job_order o ON o.id = c.job_order_id
                  LEFT JOIN material m ON m.id = c.material_id
                  LEFT JOIN quality_tier t ON t.id = c.quality_tier_id
                WHERE c.claimed_by_user_id = :userId ORDER BY c.created_at
                """)
            .keeping(
                "job_order",
                "job_order.display_id, a number rather than a name. The order's handle column is"
                    + " deliberately not selected by any section, being an external contact's name"
                    + " the scrubber cannot see."));
  }
}
