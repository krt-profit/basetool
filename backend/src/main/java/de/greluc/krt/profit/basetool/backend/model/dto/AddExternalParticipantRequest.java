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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Inbound request payload for the add-participant operations — by {@code userId} for a registered
 * member, or by free-text {@code guestName} for an <b>external</b> participant, a named person
 * without an account (ADR-0159, decision D4).
 *
 * <p>Named {@code AddParticipantPublicRequest} until ADR-0159, after the endpoint that was {@code
 * permitAll}. The {@code @Size} caps were written for that audience: without them an
 * unauthenticated caller could spam multi-megabyte {@code guestName} / {@code comment} payloads
 * until the {@code mission_participant} table was full (audit finding H-2). They stay — a member
 * can fill a table too, and a bound on a free-text column is cheap.
 *
 * <p>{@code orgUnitIds} is honoured only for an external entry (and only when the caller may label
 * those org units — see {@code MissionParticipantService.resolveSubmittedOrgUnits}); for a
 * registered participant the affiliations are auto-derived server-side from the user's memberships
 * and any submitted list is ignored.
 *
 * <p>{@code payoutPreference} optionally fixes the per-mission payout choice at sign-up time (the
 * "Auszahlungsart" select in the sign-up modal). When {@code null}, the registered user's profile
 * default (REQ-MISSION-002) respectively the entity default ({@code PAYOUT}) applies.
 */
public record AddExternalParticipantRequest(
    UUID userId,
    @Size(max = 100) String guestName,
    UUID desiredJobTypeId,
    @Size(max = 1000) String comment,
    List<UUID> orgUnitIds,
    PayoutPreference payoutPreference) {}
