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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The exchange's part of the Art. 15 / Art. 20 data export: the member's connected external apps
 * and what they changed (REQ-SEC-058).
 */
@Component
public class ExchangeExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                120,
                "connectedApps",
                ART_15_20,
                "Recorded when an approved external client connects on the member's behalf; the"
                    + " label is the client's name for the installation.",
                """
                SELECT c.client_id, c.display_name AS client, i.label, i.first_seen_at,
                       i.last_seen_at, i.revoked_at
                FROM exchange_installation i
                  JOIN exchange_client c ON c.id = i.exchange_client_id
                WHERE i.user_id = :userId ORDER BY i.first_seen_at
                """)
            .scrubbing("label"),
        new Section(
            130,
            "connectedAppRevocations",
            ART_15,
            "Recorded when the member disconnects a whole external client.",
            """
            SELECT c.client_id, c.display_name AS client, r.revoked_at
            FROM exchange_client_revocation r
              JOIN exchange_client c ON c.id = r.exchange_client_id
            WHERE r.user_id = :userId ORDER BY r.revoked_at
            """),
        new Section(
            140,
            "exchangeChanges",
            ART_15,
            "Recorded by the database whenever the member's synced data changes, naming the"
                + " channel and, for an external client, the client that changed it; kept 90"
                + " days.",
            """
            SELECT resource, entity_key, source_channel, source_client, changed_at
            FROM exchange_change WHERE user_id = :userId ORDER BY seq
            """),
        new Section(
                150,
                "exchangeJournal",
                ART_15,
                "Recorded for every write an external client made to the member's synced data, with"
                    + " the entry before and after, so the member can undo it; an org-stock entry"
                    + " without its holder, another member; kept 90 days.",
                """
                SELECT client_id, resource, entity_key, action,
                       CASE WHEN resource = 'ORG_STOCK' THEN (before_state::jsonb - 'holder')::text
                            ELSE before_state END AS before_state,
                       CASE WHEN resource = 'ORG_STOCK' THEN (after_state::jsonb - 'holder')::text
                            ELSE after_state END AS after_state,
                       recorded_at, undone_at
                FROM exchange_journal WHERE user_id = :userId ORDER BY recorded_at
                """)
            .scrubbing("before_state", "after_state"),
        new Section(
            160,
            "exchangeBulkUndoSkips",
            ART_15,
            "Recorded when an admin's undo of an external client's writes for every member left"
                + " one of the member's entries alone or could not process the member; kept 90"
                + " days.",
            """
            SELECT s.run_id, r.client_id, s.resource, s.reason, r.started_at
            FROM exchange_bulk_undo_skip s JOIN exchange_bulk_undo_run r ON r.id = s.run_id
            WHERE s.user_id = :userId ORDER BY r.started_at
            """),
        new Section(
            170,
            "exchangeShipLinks",
            ART_15,
            "Recorded when an installation of an external client links its own id for a ship to"
                + " one of the member's ships.",
            """
            SELECT client_id, external_id, ship_id, created_at
            FROM exchange_ship_link WHERE user_id = :userId ORDER BY created_at
            """),
        new Section(
            175,
            "connectedAppsNameWithholding",
            ART_15_20,
            "Chosen by the member on the profile page.",
            """
            SELECT withhold_name, created_at, updated_at
            FROM exchange_name_withholding WHERE user_id = :userId
            """));
  }
}
