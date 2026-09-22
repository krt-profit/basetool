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

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body of the manager-only add-by-id endpoint ({@code POST
 * /api/v1/missions/{id}/participants/by-id/slim}, REQ-MISSION-020): the registered member to put on
 * the roster, and nothing else.
 *
 * <p>Deliberately narrower than {@link AddExternalParticipantRequest}: no free-text name, no
 * org-unit affiliations, no comment, no sign-up answers. The endpoint exists for the Android app's
 * manager action "Teilnehmer hinzufügen" on the public API vhost, where ADR-0170 keeps the
 * add-anybody endpoints off the edge; a body that can only name one member by id is what makes this
 * one admissible there.
 *
 * @param userId the {@code app_user} id of the member to add; required
 */
public record AddParticipantByIdRequest(@NotNull UUID userId) {}
