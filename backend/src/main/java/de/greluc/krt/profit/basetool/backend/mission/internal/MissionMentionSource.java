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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The mission's part of the person search: the columns of missions and everything they carry that
 * can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class MissionMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("MISSION", "mission", "description", "id", LinkKind.MISSION),
        new Target("MISSION", "mission", "meeting_point", "id", LinkKind.MISSION),
        new Target("MISSION", "mission", "name", "id", LinkKind.MISSION),
        new Target("MISSION", "mission", "party_lead_guest_name", "id", LinkKind.MISSION),
        new Target("MISSION", "mission_finance_entry", "note", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_frequency", "name", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_objective", "title", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_participant", "comment", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_participant", "guest_name", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_step", "meta", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_step", "title", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_unit", "name", "mission_id", LinkKind.MISSION),
        new Target("MISSION", "mission_unit", "note", "mission_id", LinkKind.MISSION));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "mission.calendar_link",
        "mission.status",
        "mission_finance_entry.type",
        "mission_frequency.created_by",
        "mission_frequency.updated_by",
        "mission_objective.created_by",
        "mission_objective.kind",
        "mission_objective.updated_by",
        "mission_participant.payout_preference",
        "mission_step.created_by",
        "mission_step.updated_by");
  }
}
