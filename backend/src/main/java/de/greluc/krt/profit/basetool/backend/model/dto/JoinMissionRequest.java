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
import java.util.UUID;

/**
 * Optional body of {@code POST /api/v1/missions/{id}/join} — the two answers a sign-up sheet
 * collects beyond the bare fact of signing up.
 *
 * <p><b>The whole body is optional and so is every field in it.</b> {@code POST …/join} carried no
 * body at all until 2026-09-02 and must keep working without one: a shipped client that sends
 * nothing is not a client to break (REQ-API-009 freezes this operation, and a new <em>required</em>
 * request field would be exactly the break that freeze forbids). Both fields therefore mean "no
 * answer given", never "clear it":
 *
 * <ul>
 *   <li>{@code desiredJobTypeId} — the Funktion the member would like to fill. {@code null} leaves
 *       the participant without a desired function, which is what a bodyless join has always done.
 *   <li>{@code payoutPreference} — fixes the per-mission payout choice at sign-up time. {@code
 *       null} keeps the existing default chain: the registered user's profile default
 *       (REQ-MISSION-002), falling back to the entity default {@code PAYOUT}. A non-null value wins
 *       over the profile default, matching {@code AddExternalParticipantRequest}.
 * </ul>
 *
 * <p>Deliberately <b>not</b> a copy of {@code AddExternalParticipantRequest}: that record can name
 * somebody else ({@code userId}, {@code guestName}, {@code orgUnitIds}) and needs a self-vs-manager
 * check to be safe. {@code join} derives the member from the JWT and can only ever enrol the
 * caller, so the narrower body is the point rather than an omission.
 *
 * @param desiredJobTypeId the Funktion the caller asks to fill, or {@code null} for no preference
 * @param payoutPreference the per-mission payout choice, or {@code null} to keep the default chain
 */
public record JoinMissionRequest(UUID desiredJobTypeId, PayoutPreference payoutPreference) {}
