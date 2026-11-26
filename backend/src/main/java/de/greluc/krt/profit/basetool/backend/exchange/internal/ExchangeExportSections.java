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
                SELECT c.client_id, c.display_name AS client, i.label, i.key_thumbprint,
                       i.first_seen_at, i.last_seen_at, i.revoked_at, i.created_at, i.updated_at
                FROM exchange_installation i
                  JOIN exchange_client c ON c.id = i.exchange_client_id
                WHERE i.user_id = :userId ORDER BY i.first_seen_at
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS,
                Retention.CONNECTION,
                Source.CONNECTED_APPLICATION,
                Source.SYSTEM)
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
                """)
            .processedAs(Activity.CONNECTED_APPLICATIONS, Retention.CONNECTION, Source.MEMBER),
        new Section(
                140,
                "exchangeChanges",
                ART_15,
                "Recorded by the database whenever the member's synced data changes, naming the"
                    + " channel and, for an external client, the client and installation that"
                    + " changed it; kept 90 days.",
                """
                SELECT c.resource, c.entity_key, o.name AS org_unit, c.source_channel,
                       c.source_client, c.source_key AS source_installation,
                       c.source_patch_change IS NOT NULL AS source_patch_change, c.changed_at
                FROM exchange_change c LEFT JOIN org_unit o ON o.id = c.org_unit_id
                WHERE c.user_id = :userId ORDER BY c.seq
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS, Retention.EXCHANGE_NINETY_DAYS, Source.SYSTEM)
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit."),
        new Section(
                150,
                "exchangeJournal",
                ART_15,
                "Recorded for every write an external client made to the member's synced data, with"
                    + " the entry before and after, so the member can undo it; an org-stock entry"
                    + " without its holder, another member; kept 90 days.",
                """
                SELECT client_id, installation_key, resource, entity_key, action, removal,
                       CASE WHEN resource = 'ORG_STOCK' THEN (before_state::jsonb - 'holder')::text
                            ELSE before_state END AS before_state,
                       CASE WHEN resource = 'ORG_STOCK' THEN (after_state::jsonb - 'holder')::text
                            ELSE after_state END AS after_state,
                       recorded_at, undone_at
                FROM exchange_journal WHERE user_id = :userId ORDER BY recorded_at
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS,
                Retention.EXCHANGE_NINETY_DAYS,
                Source.CONNECTED_APPLICATION,
                Source.SYSTEM)
            .scrubbing("before_state", "after_state"),
        new Section(
                160,
                "exchangeBulkUndoSkips",
                ART_15,
                "Recorded when an admin's undo of an external client's writes for every member left"
                    + " one of the member's entries alone or could not process the member; kept 90"
                    + " days.",
                """
                SELECT s.run_id, r.client_id, r.installation_key, r.resource AS run_resource,
                       r.since, r.status AS run_status, s.resource, s.reason, r.started_at,
                       r.finished_at
                FROM exchange_bulk_undo_skip s JOIN exchange_bulk_undo_run r ON r.id = s.run_id
                WHERE s.user_id = :userId ORDER BY r.started_at
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS,
                Retention.EXCHANGE_NINETY_DAYS,
                Source.OTHER_MEMBERS,
                Source.SYSTEM),
        new Section(
                170,
                "exchangeShipLinks",
                ART_15,
                "Recorded when an installation of an external client links its own id for a ship to"
                    + " one of the member's ships.",
                """
                SELECT client_id, installation_key, external_id, ship_id, created_at
                FROM exchange_ship_link WHERE user_id = :userId ORDER BY created_at
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS, Retention.SHIP_LINK, Source.CONNECTED_APPLICATION),
        new Section(
                172,
                "exchangeRefineryOrderKeys",
                ART_15,
                "Recorded when an external client creates one of the member's refinery orders under"
                    + " its own key.",
                """
                SELECT client_id, client_key, refinery_order_id, created_at, retired_at
                FROM exchange_refinery_order_link WHERE user_id = :userId ORDER BY created_at
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS,
                Retention.CLIENT_ORDER_KEY,
                Source.CONNECTED_APPLICATION),
        new Section(
                175,
                "connectedAppsNameWithholding",
                ART_15_20,
                "Chosen by the member on the profile page.",
                """
                SELECT withhold_name, created_at, updated_at
                FROM exchange_name_withholding WHERE user_id = :userId
                """)
            .processedAs(
                Activity.CONNECTED_APPLICATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER));
  }
}
