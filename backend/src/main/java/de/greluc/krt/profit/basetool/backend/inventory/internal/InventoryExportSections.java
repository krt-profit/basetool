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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The inventory's part of the Art. 15 / Art. 20 data export: the member's warehouse contributions,
 * their reservations and the "Mein Lager" price basis (REQ-SEC-058).
 */
@Component
public class InventoryExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                100,
                "warehouseContributions",
                ART_15_20,
                "Entered by the member; the note is their own free text.",
                """
                SELECT i.amount, i.quality, i.personal, i.stolen, i.note, m.name AS material,
                       g.name AS item, l.name AS location, o.name AS org_unit, i.created_at,
                       i.updated_at
                FROM inventory_item i
                  LEFT JOIN material m ON m.id = i.material_id
                  LEFT JOIN game_item g ON g.id = i.game_item_id
                  LEFT JOIN location l ON l.id = i.location_id
                  LEFT JOIN org_unit o ON o.id = i.owning_org_unit_id
                WHERE i.user_id = :userId ORDER BY i.created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.ACCOUNT_LIFETIME,
                Source.MEMBER,
                Source.OTHER_MEMBERS,
                Source.CONNECTED_APPLICATION)
            .scrubbing("note")
            .keeping("item", "game_item.name: an item from the synced catalogue, not a person.")
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit.")
            .keeping(
                "location",
                "location.name: a station or outpost from the synced catalogue, not a person.")
            .keeping(
                "material",
                "material.name: a commodity from the synced catalogue. Flagged only because the"
                    + " statement also reads location, whose name column is a person-name surface;"
                    + " the coverage check matches column names across a statement's tables on"
                    + " purpose, so an over-flag is answered here rather than by narrowing the"
                    + " check."),
        new Section(
                102,
                "warehouseReservations",
                ART_15,
                "Parts of the member's warehouse entries reserved for an order, a mission or one"
                    + " of their own donations; a reservation is recorded by whoever plans the"
                    + " order or mission.",
                """
                SELECT 'JOB_ORDER' AS reservation_kind, m.name AS material, g.name AS item,
                       o.display_id AS job_order, CAST(NULL AS VARCHAR) AS mission,
                       CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS donation_created_at,
                       a.amount, a.delivered, a.created_at, a.updated_at
                FROM inventory_item_job_order_allocation a
                  JOIN inventory_item i ON i.id = a.inventory_item_id
                  JOIN job_order o ON o.id = a.job_order_id
                  LEFT JOIN material m ON m.id = i.material_id
                  LEFT JOIN game_item g ON g.id = i.game_item_id
                WHERE i.user_id = :userId
                UNION ALL
                SELECT 'MISSION', m.name, g.name, CAST(NULL AS INTEGER), ms.name,
                       CAST(NULL AS TIMESTAMP WITH TIME ZONE), a.amount, CAST(NULL AS BOOLEAN),
                       a.created_at, a.updated_at
                FROM inventory_item_mission_allocation a
                  JOIN inventory_item i ON i.id = a.inventory_item_id
                  JOIN mission ms ON ms.id = a.mission_id
                  LEFT JOIN material m ON m.id = i.material_id
                  LEFT JOIN game_item g ON g.id = i.game_item_id
                WHERE i.user_id = :userId
                UNION ALL
                SELECT 'DONATION', m.name, g.name, CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR),
                       d.created_at, a.amount, CAST(NULL AS BOOLEAN), a.created_at, a.updated_at
                FROM inventory_item_donation_allocation a
                  JOIN inventory_item i ON i.id = a.inventory_item_id
                  JOIN supply_donation d ON d.id = a.donation_id
                  LEFT JOIN material m ON m.id = i.material_id
                  LEFT JOIN game_item g ON g.id = i.game_item_id
                WHERE i.user_id = :userId
                ORDER BY created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.ACCOUNT_LIFETIME,
                Source.MEMBER,
                Source.OTHER_MEMBERS)
            .scrubbing("mission")
            .keeping("material", "material.name; see warehouseContributions.material.")
            .keeping("item", "game_item.name; see warehouseContributions.item."),
        new Section(
                105,
                "salePriceBasis",
                ART_15_20,
                "Chosen by the member on the profile page.",
                """
                SELECT sale_price_basis, created_at, updated_at
                FROM member_sale_value_setting WHERE user_id = :userId
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER));
  }
}
