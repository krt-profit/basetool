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
 * crews, manages, leads or is responsible in, and the finance entries of their participations
 * (REQ-SEC-058).
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
            .processedAs(
                Activity.OPERATIONS,
                Retention.REASSIGNED_ON_DELETION,
                Source.MEMBER,
                Source.OTHER_MEMBERS)
            .scrubbing("name", "description", "meeting_point"),
        participations(),
        new Section(
                242,
                "missionCrewAssignments",
                ART_15,
                "A place in the crew of a mission unit and the crew functions, assigned by the"
                    + " mission's organisers. The unit's note and ship are not included; see"
                    + " missionUnitResponsibilities.",
                """
                SELECT m.name AS mission, u.name AS unit, t.name AS ship_type, jobs.job_types,
                       c.created_at, c.updated_at
                FROM mission_crew c
                  JOIN mission_participant p ON p.id = c.mission_participant_id
                  JOIN mission_unit u ON u.id = c.mission_ship_id
                  JOIN mission m ON m.id = p.mission_id
                  LEFT JOIN ship_type t ON t.id = u.ship_type_id
                  LEFT JOIN LATERAL (
                    SELECT string_agg(j.name, ', ' ORDER BY j.name) AS job_types
                    FROM mission_crew_job_types cj JOIN job_type j ON j.id = cj.job_type_id
                    WHERE cj.mission_crew_id = c.id
                  ) jobs ON TRUE
                WHERE p.user_id = :userId ORDER BY c.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.UNLINKED_ON_DELETION, Source.OTHER_MEMBERS)
            .scrubbing("mission", "unit")
            .keeping("ship_type", "ship_type.name; see missionUnitResponsibilities.ship_type."),
        new Section(
                244,
                "missionFinanceEntries",
                ART_15,
                "Income and expenses booked to the member's participation, by the member or by an"
                    + " officer of the mission's unit; the note is scrubbed like other free text.",
                """
                SELECT m.name AS mission, e.type, e.amount, e.note, e.created_at, e.updated_at
                FROM mission_finance_entry e
                  JOIN mission_participant p ON p.id = e.mission_participant_id
                  JOIN mission m ON m.id = e.mission_id
                WHERE p.user_id = :userId ORDER BY e.created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.UNLINKED_ON_DELETION,
                Source.MEMBER,
                Source.OTHER_MEMBERS)
            .scrubbing("mission", "note"),
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
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.OTHER_MEMBERS)
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
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.OTHER_MEMBERS)
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
                       u.frequency, u.created_at, u.updated_at
                FROM mission_unit u
                  JOIN mission m ON m.id = u.mission_id
                  LEFT JOIN ship_type t ON t.id = u.ship_type_id
                WHERE u.responsible_user_id = :userId ORDER BY u.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.OTHER_MEMBERS)
            .scrubbing("mission", "unit")
            .keeping(
                "ship_type",
                "ship_type.name: a hull from the synced catalogue. Flagged because the statement"
                    + " also reads mission and mission_unit, whose name columns are scrubbed."),
        new Section(
                460,
                "payoutPreference",
                ART_15_20,
                "Chosen by the member on the profile page.",
                """
                SELECT default_payout_preference, created_at, updated_at
                FROM member_payout_setting WHERE user_id = :userId
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER));
  }

  private static Section participations() {
    return new Section(
            240,
            "missionParticipations",
            ART_15_20,
            "The member's own sign-up and comment, with the functions and units it was made for."
                + " Other participants of the same mission are not included: they are other"
                + " people.",
            """
            SELECT m.name AS mission, p.comment, p.payout_preference,
                   p.is_mission_lead_participant, dj.name AS desired_job_type,
                   pj.name AS planned_job_type, sq.name AS squadron, units.org_units,
                   p.start_time, p.end_time, p.created_at, p.updated_at
            FROM mission_participant p
              JOIN mission m ON m.id = p.mission_id
              LEFT JOIN job_type dj ON dj.id = p.desired_mission_job_type_id
              LEFT JOIN job_type pj ON pj.id = p.planned_task_job_type_id
              LEFT JOIN org_unit sq ON sq.id = p.squadron_id
              LEFT JOIN LATERAL (
                SELECT string_agg(o.name, ', ' ORDER BY o.name) AS org_units
                FROM mission_participant_org_unit po JOIN org_unit o ON o.id = po.org_unit_id
                WHERE po.mission_participant_id = p.id
              ) units ON TRUE
            WHERE p.user_id = :userId ORDER BY p.created_at
            """)
        .processedAs(
            Activity.OPERATIONS,
            Retention.UNLINKED_ON_DELETION,
            Source.MEMBER,
            Source.OTHER_MEMBERS)
        .scrubbing("mission", "comment")
        .keeping(
            "desired_job_type",
            "job_type.name: a mission function from the organisation's catalogue. Flagged because"
                + " the statement also reads mission, whose name column is scrubbed.")
        .keeping("planned_job_type", "job_type.name; see missionParticipations.desired_job_type.")
        .keeping("squadron", "org_unit.name; see orgUnitMemberships.org_unit.");
  }
}
