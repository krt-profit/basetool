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

import java.util.List;

/**
 * Frontend mirror of the Terms-of-Use wording served by the backend (REQ-SEC-028, ADR-0138).
 *
 * <p>The wording used to live in this module's message bundle. It moved to the backend so the web
 * page, the consent gate and the Android app all render one source — a second copy is what lets a
 * gate show different text from the document it claims to reproduce.
 *
 * <p>Deliberately <strong>not</strong> a {@code CachedCatalog} entry: the response varies by {@code
 * Accept-Language}, and that cache is keyed by URI alone and documented as global-only, so caching
 * this would eventually serve one member the other language.
 *
 * @param version content digest of this wording; the value an acceptance is recorded against
 * @param title the document's own heading
 * @param intro the lead paragraph, before the first numbered section
 * @param sections the numbered sections, in document order
 * @param lastUpdated the "Stand ..." line
 */
public record TermsDocumentDto(
    String version,
    String title,
    String intro,
    List<TermsSectionDto> sections,
    String lastUpdated) {}
