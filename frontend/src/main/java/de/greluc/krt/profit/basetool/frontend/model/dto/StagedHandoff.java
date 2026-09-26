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

package de.greluc.krt.profit.basetool.frontend.model.dto;

/**
 * Mirror of the ingest gateway's staged-handoff value: the draft kind plus the backend import draft
 * as JSON, stored in Redis under {@code ingest:handoff:<sub>:<handoffId>} and read once by the
 * frontend to pre-fill the review page.
 *
 * @param kind which draft this is ({@code REFINERY} / {@code BLUEPRINT})
 * @param draftJson the backend draft response, stored verbatim as JSON text
 */
public record StagedHandoff(HandoffKind kind, String draftJson) {}
