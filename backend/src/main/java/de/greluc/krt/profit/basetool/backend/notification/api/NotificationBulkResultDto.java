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

package de.greluc.krt.profit.basetool.backend.notification.api;

/**
 * Result of a bulk inbox mutation (mark-all-read / clear-read), reporting how many rows were
 * affected and the recipient's resulting unread count so the frontend can patch the badge without a
 * second round-trip.
 *
 * @param affected number of notifications changed by the operation
 * @param unreadCount the recipient's unread count after the operation
 */
public record NotificationBulkResultDto(int affected, long unreadCount) {}
