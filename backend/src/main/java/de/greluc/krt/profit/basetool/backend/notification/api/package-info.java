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

/**
 * The notification module's SPIs: the recipient directories through which rule selectors resolve
 * their recipients in the modules that own the data (plan §5.3). {@link
 * de.greluc.krt.profit.basetool.backend.notification.api.RoleRecipientDirectory} is implemented by
 * identity, {@link
 * de.greluc.krt.profit.basetool.backend.notification.api.OrgUnitRecipientDirectory} by orgunit and
 * {@link de.greluc.krt.profit.basetool.backend.notification.api.AccountRecipientDirectory} by bank.
 */
package de.greluc.krt.profit.basetool.backend.notification.api;
