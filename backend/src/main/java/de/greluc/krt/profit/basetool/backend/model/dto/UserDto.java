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

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Wire shape of a user.
 *
 * <p>{@code squadron} is the user's primary Staffel (first by name) and {@code squadrons} the
 * complete membership set (REQ-ORG-017); both are {@code null} / empty without a membership. {@code
 * discordLinked} tells whether a Discord account is federated, never the Discord id itself, and is
 * {@code null} in peer and guest redactions (REQ-SEC-019).
 */
public record UserDto(
    UUID id,
    String username,
    String displayName,
    String effectiveName,
    String email,
    Integer rank,
    String description,
    Set<String> roles,
    Set<String> permissions,
    UUID lastReadAnnouncementId,
    Boolean isLogistician,
    Boolean isMissionManager,
    Boolean inKeycloak,
    @Nullable SquadronReferenceDto squadron,
    @Nullable List<SquadronReferenceDto> squadrons,
    Long version,
    @Nullable LocalDate joinDate,
    Boolean discordLinked) {}
