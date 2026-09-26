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
 * Frontend mirror of a mission participant, with one entry in {@code orgUnits} per org-unit
 * affiliation.
 *
 * <p>A row without {@code user} is an external participant recorded by mission leadership
 * (ADR-0159).
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
