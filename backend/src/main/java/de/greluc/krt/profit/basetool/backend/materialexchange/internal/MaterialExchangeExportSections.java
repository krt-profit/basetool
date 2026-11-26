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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The materialexchange's part of the Art. 15 / Art. 20 data export: the member's market offers,
 * requests and interests (REQ-SEC-058).
 */
@Component
public class MaterialExchangeExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                310,
                "marketOffers",
                ART_15_20,
                "Posted by the member, including their own remark.",
                """
                SELECT x.offer_kind, x.item_name, x.item_quantity, x.offered_amount, x.remark,
                       o.name AS org_unit, x.status, x.released_at, x.created_at, x.updated_at
                FROM material_exchange_offer x LEFT JOIN org_unit o ON o.id = x.owning_org_unit_id
                WHERE x.owner_id = :userId ORDER BY x.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER)
            .scrubbing("remark")
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit."),
        new Section(
                320,
                "marketRequests",
                ART_15_20,
                "Posted by the member, including their own remark.",
                """
                SELECT x.request_kind, x.item_name, x.item_quantity, x.requested_amount,
                       x.min_quality, x.remark, o.name AS org_unit, x.status, x.posted_at,
                       x.created_at, x.updated_at
                FROM material_exchange_request x
                  LEFT JOIN org_unit o ON o.id = x.owning_org_unit_id
                WHERE x.owner_id = :userId ORDER BY x.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER)
            .scrubbing("remark")
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit."),
        new Section(
                330,
                "marketInterests",
                ART_15_20,
                "Interest the member registered. The offering member is not named, being the"
                    + " counterparty.",
                """
                SELECT o.item_name, o.offer_kind, i.created_at, i.updated_at
                FROM material_exchange_interest i JOIN material_exchange_offer o ON o.id = i.offer_id
                WHERE i.interested_user_id = :userId ORDER BY i.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER),
        new Section(
                340,
                "marketRequestInterests",
                ART_15_20,
                "Interest the member registered in a request. The requesting member is not named,"
                    + " being the counterparty.",
                """
                SELECT r.request_kind, r.item_name, m.name AS material, i.created_at, i.updated_at
                FROM material_exchange_request_interest i
                  JOIN material_exchange_request r ON r.id = i.request_id
                  LEFT JOIN material m ON m.id = r.requested_material_id
                WHERE i.interested_user_id = :userId ORDER BY i.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER));
  }
}
