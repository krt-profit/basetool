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

package de.greluc.krt.profit.basetool.frontend.identity.model;

/**
 * Form-binding object for the profile-level "share my blueprints globally" toggle (REQ-INV-018).
 *
 * @param shareBlueprintsGlobally whether the user opts into having their owned blueprints counted
 *     in the leadership availability overview and the item-order blueprint-coverage view across
 *     every org unit; the no-JS form posts {@code false} when the checkbox is unticked.
 * @param version the optimistic-lock version of the user row, echoed back so a concurrent edit
 *     surfaces as a 409 rather than a silent overwrite.
 */
public record ProfileBlueprintSharingForm(boolean shareBlueprintsGlobally, Long version) {}
