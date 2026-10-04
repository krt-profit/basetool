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

package de.greluc.krt.profit.basetool.backend.notification.api.events;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.util.UUID;

/**
 * Immutable reference to an org unit carried by a {@link NotificationEvent}. Only scalars are
 * passed across the transaction/thread boundary, never a managed entity.
 *
 * @param id the org unit id
 * @param kind the org unit kind (squadron or special command)
 */
public record OrgUnitRef(UUID id, OrgUnitKind kind) {}
