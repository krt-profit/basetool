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

package de.greluc.krt.profit.basetool.backend.audit.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The audit's part of the Art. 15 / Art. 20 data export: the activity trail of the member
 * (REQ-SEC-058).
 */
@Component
public class AuditExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
            420,
            "auditActionsByMember",
            ART_15,
            "Actions the member performed, retained under Art. 6(1)(f) for up to 24 months"
                + " (REQ-AUDIT-006). What each action was about is not named: that label can"
                + " itself be a person.",
            """
            SELECT occurred_at, domain, event_type, client_id
            FROM audit_event WHERE actor_user_id = :userId ORDER BY occurred_at
            """),
        new Section(
            430,
            "auditActionsOnMember",
            ART_15,
            "Actions performed on the member by somebody else. Neither the acting person nor"
                + " what the action was about is named, both being third parties.",
            """
            SELECT occurred_at, domain, event_type
            FROM audit_event WHERE target_user_id = :userId ORDER BY occurred_at
            """));
  }
}
