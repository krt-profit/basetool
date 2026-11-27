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

package de.greluc.krt.profit.basetool.backend.identity.api.events;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * An account was deleted and entered into the erasure register (REQ-DATA-048); published inside the
 * deletion's transaction, so listeners act after it commits.
 *
 * @param userId the deleted account's former {@code app_user.id}
 */
public record AccountErasedEvent(@NotNull UUID userId) {}
