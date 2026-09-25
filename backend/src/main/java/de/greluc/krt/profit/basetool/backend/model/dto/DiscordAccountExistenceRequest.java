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

/**
 * Candidate identity of a Discord first-broker-login, sent by the Keycloak SPI to the
 * account-existence precheck (REQ-SEC-022).
 *
 * <p>Every field is optional; a {@code null} or blank field is not matched. Values are never
 * logged.
 *
 * @param username the Discord username, matched against usernames and display names
 * @param email the Discord e-mail address, if shared
 * @param serverNickname the per-guild server nickname, matched like {@code username}
 */
public record DiscordAccountExistenceRequest(
    String username, String email, String serverNickname) {}
