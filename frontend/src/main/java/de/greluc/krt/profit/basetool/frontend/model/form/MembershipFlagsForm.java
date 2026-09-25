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

package de.greluc.krt.profit.basetool.frontend.model.form;

/**
 * Form-binding object for the per-membership Logistician / Mission Manager flag flip on the admin
 * Spezialkommando detail page.
 *
 * <p>A record so the component names {@code isLogistician} / {@code isMissionManager} bind as the
 * form's field names; an unticked checkbox arrives as {@code false} via Spring's {@code _field}
 * marker.
 *
 * @param isLogistician new value of the Logistician flag
 * @param isMissionManager new value of the Mission Manager flag
 * @param version optimistic-lock counter; required for the backend's version check
 */
public record MembershipFlagsForm(boolean isLogistician, boolean isMissionManager, Long version) {}
