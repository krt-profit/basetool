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

package de.greluc.krt.profit.basetool.backend.scope.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The scope module's part of the Art. 15 / Art. 20 data export: the member's stored choice of the
 * active org unit (REQ-ORG-031, REQ-SEC-058). An empty {@code org_unit} is the choice of all of the
 * member's org units.
 */
@Component
public class ActiveOrgUnitChoiceExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                475,
                "activeOrgUnitChoice",
                ART_15_20,
                "Chosen by the member in the org-unit switcher.",
                """
                SELECT o.name AS org_unit, o.shorthand, o.kind, c.created_at, c.updated_at
                FROM member_active_org_unit c LEFT JOIN org_unit o ON o.id = c.org_unit_id
                WHERE c.user_id = :userId
                """)
            .keeping(
                "org_unit",
                "org_unit.name and org_unit.shorthand: an organisational unit, created by the"
                    + " organisation and not named after a member. Scrubbing it would corrupt the"
                    + " name of the unit the member chose.")
            .keeping("shorthand", "See activeOrgUnitChoice.org_unit."));
  }
}
