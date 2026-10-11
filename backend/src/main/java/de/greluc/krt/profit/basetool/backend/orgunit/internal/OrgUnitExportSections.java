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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The orgunit's part of the Art. 15 / Art. 20 data export: the member's org-unit memberships and
 * grand-admiral appointments (REQ-SEC-058).
 */
@Component
public class OrgUnitExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                50,
                "orgUnitMemberships",
                ART_15,
                "Assigned by the organisation, not provided by the member.",
                """
                SELECT o.name AS org_unit, o.shorthand, m.kind, m.role, m.is_logistician,
                       m.is_mission_manager, m.joined_at
                FROM org_unit_membership m JOIN org_unit o ON o.id = m.org_unit_id
                WHERE m.user_id = :userId ORDER BY m.joined_at
                """)
            .keeping(
                "org_unit",
                "org_unit.name and org_unit.shorthand: an organisational unit, created by the"
                    + " organisation and not named after a member. Scrubbing it would corrupt the"
                    + " name of the unit the membership is in.")
            .keeping("shorthand", "See orgUnitMemberships.org_unit."),
        new Section(
                80,
                "orgUnitsAsGrandAdmiral",
                ART_15,
                "An appointment as the grand admiral of an organisational unit, made by the"
                    + " organisation.",
                """
                SELECT name AS org_unit, shorthand, kind
                FROM org_unit WHERE grand_admiral_user_id = :userId ORDER BY name
                """)
            .keeping(
                "org_unit",
                "org_unit.name, the unit the member is grand admiral of; see"
                    + " orgUnitMemberships.org_unit.")
            .keeping(
                "shorthand", "org_unit.shorthand of that unit; see orgUnitMemberships.org_unit."));
  }
}
