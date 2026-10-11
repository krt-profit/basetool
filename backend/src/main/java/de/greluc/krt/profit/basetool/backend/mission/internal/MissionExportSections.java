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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The mission's part of the Art. 15 / Art. 20 data export: the missions the member owns, joined,
 * manages, leads or is responsible in (REQ-SEC-058).
 */
@Component
public class MissionExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                230,
                "missionsOwned",
                ART_15_20,
                "Created by the member; the description is their own free text.",
                """
                SELECT name, description, status, meeting_point, planned_start_time,
                       planned_end_time, created_at
                FROM mission WHERE owner_id = :userId ORDER BY created_at
                """)
            .scrubbing("name", "description", "meeting_point"),
        new Section(
                240,
                "missionParticipations",
                ART_15_20,
                "The member's own sign-up and comment. Other participants of the same mission are"
                    + " not included: they are other people.",
                """
                SELECT m.name AS mission, p.comment, p.payout_preference,
                       p.is_mission_lead_participant, p.start_time, p.end_time, p.created_at
                FROM mission_participant p JOIN mission m ON m.id = p.mission_id
                WHERE p.user_id = :userId ORDER BY p.created_at
                """)
            .scrubbing("mission", "comment"),
        new Section(
                250,
                "missionsManaged",
                ART_15,
                "Assigned by the organisation.",
                """
                SELECT m.name AS mission, m.status
                FROM mission_managers mm JOIN mission m ON m.id = mm.mission_id
                WHERE mm.user_id = :userId ORDER BY m.name
                """)
            .scrubbing("mission"),
        new Section(
                260,
                "missionsAsPartyLead",
                ART_15,
                "A designation as a mission's party lead, made by the mission's organisers.",
                """
                SELECT name AS mission, status, planned_start_time, planned_end_time
                FROM mission WHERE party_lead_user_id = :userId ORDER BY planned_start_time
                """)
            .scrubbing("mission"),
        new Section(
                270,
                "missionUnitResponsibilities",
                ART_15,
                "A responsibility for a unit of a mission, assigned by the mission's organisers."
                    + " The unit's note and ship are not included: they are the organisers' text"
                    + " and possibly another member's ship.",
                """
                SELECT m.name AS mission, u.name AS unit, t.name AS ship_type, u.high_value_unit,
                       u.created_at
                FROM mission_unit u
                  JOIN mission m ON m.id = u.mission_id
                  LEFT JOIN ship_type t ON t.id = u.ship_type_id
                WHERE u.responsible_user_id = :userId ORDER BY u.created_at
                """)
            .scrubbing("mission", "unit")
            .keeping(
                "ship_type",
                "ship_type.name: a hull from the synced catalogue. Flagged because the statement"
                    + " also reads mission and mission_unit, whose name columns are scrubbed."));
  }
}
