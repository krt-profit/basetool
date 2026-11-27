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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The supply module's part of the Art. 15 / Art. 20 data export: the member's donations
 * (REQ-SUP-020, REQ-SEC-058).
 */
@Component
public class SupplyExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                480,
                "supplyDonations",
                ART_15_20,
                "Entered by the member.",
                """
                SELECT d.created_at, d.updated_at, d.status, COALESCE(m.name, g.name) AS entry,
                       d.quality, d.pledged_amount, d.received_amount, d.received_at,
                       d.withdrawn_by_patch_change IS NOT NULL AS withdrawn_by_patch_change,
                       u.name AS receiving_unit
                FROM supply_donation d
                  LEFT JOIN material m ON m.id = d.material_id
                  LEFT JOIN game_item g ON g.id = d.game_item_id
                  JOIN org_unit u ON u.id = d.receiving_org_unit_id
                WHERE d.donor_user_id = :userId ORDER BY d.created_at
                """)
            .processedAs(
                Activity.OPERATIONS, Retention.DONATION, Source.MEMBER, Source.OTHER_MEMBERS)
            .keeping("entry", "entry: the name of a catalogue material or item, not a person.")
            .keeping("receiving_unit", "receiving_unit: the name of an org unit, not a person."),
        new Section(
                630,
                "supplyDonationsReceived",
                ART_15,
                "Donations of other members the member confirmed as received for their unit; the"
                    + " donor is not included.",
                """
                SELECT d.received_at, COALESCE(m.name, g.name) AS entry, d.quality,
                       d.received_amount, u.name AS receiving_unit
                FROM supply_donation d
                  LEFT JOIN material m ON m.id = d.material_id
                  LEFT JOIN game_item g ON g.id = d.game_item_id
                  JOIN org_unit u ON u.id = d.receiving_org_unit_id
                WHERE d.received_by = :userId ORDER BY d.received_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.UNLINKED_ON_DELETION, Source.MEMBER)
            .keeping("entry", "entry: the name of a catalogue material or item, not a person.")
            .keeping("receiving_unit", "receiving_unit: the name of an org unit, not a person."),
        new Section(
                631,
                "supplyNeedsRaised",
                ART_15,
                "Needs the member raised for a unit as its lead, with their own purchase reason.",
                """
                SELECT u.name AS org_unit, COALESCE(m.name, g.name) AS entry,
                       q.code AS quality_tier, n.amount, n.fulfilled_amount, n.purchased_amount,
                       n.priority, n.purchasable, n.purchase_reason, n.max_unit_price, n.status,
                       n.created_at, n.updated_at
                FROM supply_need n
                  JOIN org_unit u ON u.id = n.org_unit_id
                  LEFT JOIN material m ON m.id = n.material_id
                  LEFT JOIN game_item g ON g.id = n.game_item_id
                  LEFT JOIN quality_tier q ON q.id = n.quality_tier_id
                WHERE n.created_by = :userId ORDER BY n.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.UNLINKED_ON_DELETION, Source.MEMBER)
            .scrubbing("purchase_reason")
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit.")
            .keeping("entry", "entry: the name of a catalogue material or item, not a person."));
  }
}
