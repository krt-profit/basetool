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
 * The Terms-of-Use wording in force as structured data rather than HTML (REQ-SEC-028).
 *
 * <p>{@link #version} is the digest an acceptance is recorded against.
 *
 * @param version content digest of this wording; identical to the value the status endpoint reports
 * @param title the document's own heading
 * @param intro the lead paragraph, before the first numbered section
 * @param sections the numbered sections, in document order
 * @param lastUpdated the "Stand ..." line; part of the version digest
 */
public record TermsDocumentDto(
    String version,
    String title,
    String intro,
    List<TermsSectionDto> sections,
    String lastUpdated) {}
