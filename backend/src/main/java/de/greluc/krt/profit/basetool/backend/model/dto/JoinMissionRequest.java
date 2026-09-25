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
 * Optional body of {@code POST /api/v1/missions/{id}/join} carrying the caller's sign-up answers.
 *
 * <p>The body and each field are optional (REQ-API-009); {@code null} means "no answer given",
 * never "clear it". A {@code null} {@code payoutPreference} falls back to the profile default
 * (REQ-MISSION-002), then to {@code PAYOUT}.
 *
 * @param desiredJobTypeId the Funktion the caller asks to fill, or {@code null} for no preference
 * @param payoutPreference the per-mission payout choice, or {@code null} to keep the default chain
 */
public record JoinMissionRequest(UUID desiredJobTypeId, PayoutPreference payoutPreference) {}
