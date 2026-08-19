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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.List;

/**
 * The Terms-of-Use wording in force, structured rather than pre-rendered (REQ-SEC-028).
 *
 * <p><strong>Structure, not HTML.</strong> The two clients that render this are a Thymeleaf page
 * and a Jetpack Compose screen; neither can share a markup blob, and shipping HTML to the app would
 * force it to parse and sanitise a document it is meant to display. Sections and paragraphs travel
 * as data so each client applies its own typography.
 *
 * <p>{@link #version} is the same digest {@code TermsStatusDto.currentVersion} reports and the
 * value an acceptance is recorded against. It travels with the text so a client can tell, from one
 * response, that what it is showing is what consent will be recorded for — the mismatch that would
 * otherwise be invisible is a member reading one wording and accepting another.
 *
 * @param version content digest of this wording; identical to the value the status endpoint reports
 * @param title the document's own heading
 * @param intro the lead paragraph, before the first numbered section
 * @param sections the numbered sections, in document order
 * @param lastUpdated the "Stand ..." line; part of the document, and inside the version digest
 */
public record TermsDocumentDto(
    String version,
    String title,
    String intro,
    List<TermsSectionDto> sections,
    String lastUpdated) {}
