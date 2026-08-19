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
 * One numbered section of the Terms of Use (REQ-SEC-028).
 *
 * <p>The heading carries its own number ("4. Pflichten der Nutzer") because the numbering is part
 * of the legal text and is cited as such — a client that renumbered from an ordered list would
 * silently renumber the document whenever a section was added.
 *
 * @param heading the section heading, including its number
 * @param clauses the section's paragraphs, in document order
 */
public record TermsSectionDto(String heading, List<TermsClauseDto> clauses) {}
