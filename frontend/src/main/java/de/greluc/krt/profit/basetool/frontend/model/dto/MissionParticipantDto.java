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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Data transfer record carrying Mission Participant payload. The {@code orgUnits} list carries the
 * participant's affiliations (zero, one, or several org units — a Staffel and/or Spezialkommandos),
 * mirroring the backend DTO so the roster renders an org-unit badge per affiliation.
 *
 * <p>A row with no {@code user} is an <em>external</em> participant — a named person without an
 * account, recorded by the mission leadership (ADR-0159, decision D4). It used to carry a {@code
 * guestEditToken}: a per-row capability token the anonymous creator kept and replayed to edit their
 * own sign-up without a login. There is no anonymous sign-up left to mint one for, and backend
 * migration V239 dropped the column that stored its hash.
 */
public record MissionParticipantDto(
    UUID id,
    UserDto user,
    String guestName,
    List<OrgUnitReferenceDto> orgUnits,
    JobTypeDto desiredMissionJobType,
    JobTypeDto plannedMissionJobType,
    String comment,
    Instant startTime,
    Instant endTime,
    PayoutPreference payoutPreference,
    Long version) {
  public String getStartTimeFormatted() {
    return formatInstant(startTime);
  }

  public String getEndTimeFormatted() {
    return formatInstant(endTime);
  }

  private String formatInstant(Instant instant) {
    if (instant == null) {
      return "";
    }
    return instant.atZone(ZoneId.of("Europe/Berlin")).toLocalDateTime().toString();
  }
}
