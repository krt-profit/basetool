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

package de.greluc.krt.profit.basetool.guardfixture.massassignment;

import java.time.Instant;
import java.util.UUID;

/**
 * Planted dual-use DTO: returned by the fixture controller and bound as its request body, with
 * server-managed components.
 *
 * @param id the row identity
 * @param name a plain client field
 * @param ownerId the owner, stamped from the caller in real code
 * @param createdAt the creation timestamp
 * @param status the lifecycle status
 */
public record FixtureOrderDto(
    UUID id, String name, UUID ownerId, Instant createdAt, String status) {}
