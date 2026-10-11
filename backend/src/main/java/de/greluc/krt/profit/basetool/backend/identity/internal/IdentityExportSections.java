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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The identity's part of the Art. 15 / Art. 20 data export: the member's account, consents,
 * registration decisions, erasure requests and realm roles (REQ-SEC-058).
 */
@Component
public class IdentityExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                10,
                "account",
                ART_15_20,
                "Provided at registration and on the profile page.",
                """
                SELECT id, username, display_name, email, description, user_rank, join_date,
                       discord_user_id, discord_guild_nickname, rsi_handle, default_payout_preference,
                       share_blueprints_globally, approval_status, approved_at, created_at, updated_at
                FROM app_user WHERE id = :userId
                """)
            .scrubbing("description")
            .keeping(
                "username",
                "The subject's own handle. Their own name is what the export is about, and"
                    + " substituting another member's handle inside it corrupts an identity field.")
            .keeping("display_name", "The subject's own name; see account.username.")
            .keeping(
                "email",
                "A structured identifier, and the subject's own. A three-character handle occurring"
                    + " inside a local part would have made the address unusable.")
            .keeping("discord_guild_nickname", "The subject's own nickname; see account.username.")
            .keeping("rsi_handle", "The subject's own RSI handle; see account.username."),
        new Section(
            20,
            "termsAcceptances",
            ART_15,
            "A record of consent, not data the member supplied.",
            """
            SELECT terms_version, accepted_at
            FROM terms_acceptance WHERE user_id = :userId ORDER BY accepted_at
            """),
        new Section(
                30,
                "registrationDecisions",
                ART_15,
                "An assessment of the member by an administrator; the deciding administrator is not"
                    + " named, being a third party.",
                """
                SELECT decision, reason, created_at
                FROM user_approval_event WHERE user_id = :userId ORDER BY created_at
                """)
            .scrubbing("reason"),
        new Section(
                40,
                "deletionRequests",
                ART_15,
                "The member's own erasure requests and their outcome.",
                """
                SELECT status, erase_history_requested, decision_note, created_at, decided_at
                FROM deletion_request WHERE user_id = :userId ORDER BY created_at
                """)
            .scrubbing("decision_note"),
        new Section(
            70,
            "realmRoles",
            ART_15,
            "The roles the organisation assigned to the member, which decide what they may do.",
            """
            SELECT r.code, r.name AS role
            FROM user_roles ur JOIN role r ON r.id = ur.role_id
            WHERE ur.user_id = :userId ORDER BY r.name
            """));
  }
}
