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
import org.jetbrains.annotations.Nullable;

/**
 * Body of a consolidate-account request (REQ-SEC-055, #1828): fold the duplicate account named in
 * the path into the account the member keeps.
 *
 * <p>The path carries the account that is <em>dissolved</em> and this body the one that
 * <em>survives</em> — the opposite way round from {@code POST /admin/registrations/{id}/merge},
 * deliberately: the admin acts on the duplicate's row in the member list, so the row they clicked
 * is the one the URL names.
 *
 * @param targetUserId the account the member keeps and everything moves onto; required
 * @param version the duplicate's optimistic-lock version the admin last read; {@code null} bypasses
 *     the check
 */
public record ConsolidateAccountRequest(@NotNull UUID targetUserId, @Nullable Long version) {}
