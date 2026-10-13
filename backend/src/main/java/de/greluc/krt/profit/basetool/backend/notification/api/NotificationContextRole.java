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
 * Names which org unit carried by an event an {@link SelectorKind#ORG_RELATIVE_ROLE} selector
 * resolves against. For a job order, {@link #RESPONSIBLE} is the processing unit and {@link
 * #REQUESTING} is the customer unit.
 */
public enum NotificationContextRole {

  /** The org unit responsible for processing the originating aggregate. */
  RESPONSIBLE,

  /** The org unit that requested / placed the originating aggregate. */
  REQUESTING
}
