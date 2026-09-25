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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Keycloak user payload for the scheduled admin-API user sync.
 *
 * <p>{@code id}, {@code username}, {@code email} and {@code enabled} come from the Admin {@code GET
 * /users} response; {@code roles} is joined in per realm role. {@code discordUserId} is enriched
 * only for users the sync back-fills, so {@code null} means "leave the existing link alone" in
 * {@link de.greluc.krt.profit.basetool.backend.service.UserService#syncUser(KeycloakUserDto)}
 * (REQ-DATA-006).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakUserDto(
    UUID id,
    String username,
    String email,
    Boolean enabled,
    Set<String> roles,
    @Nullable String discordUserId) {}
