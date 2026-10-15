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

package de.greluc.krt.profit.basetool.frontend.exchange.model;

/**
 * Form-binding object of the profile's „Meinen Namen nicht an verbundene Anwendungen weitergeben"
 * toggle (REQ-XCH-044).
 *
 * @param withholdName whether the member withholds their name; the no-JS form posts {@code false}
 *     when the checkbox is unticked
 * @param version the setting's own optimistic-lock version, echoed back so a concurrent edit
 *     surfaces as a 409
 */
public record ProfileNameWithholdingForm(boolean withholdName, Long version) {}
