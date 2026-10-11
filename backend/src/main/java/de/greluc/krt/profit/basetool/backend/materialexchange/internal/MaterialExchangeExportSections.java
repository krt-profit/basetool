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

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

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
                SELECT offer_kind, item_name, offered_amount, remark, status, released_at, created_at
                FROM material_exchange_offer WHERE owner_id = :userId ORDER BY created_at
                """)
            .scrubbing("remark"),
        new Section(
                320,
                "marketRequests",
                ART_15_20,
                "Posted by the member, including their own remark.",
                """
                SELECT request_kind, item_name, requested_amount, min_quality, remark, status,
                       posted_at, created_at
                FROM material_exchange_request WHERE owner_id = :userId ORDER BY created_at
                """)
            .scrubbing("remark"),
        new Section(
            330,
            "marketInterests",
            ART_15_20,
            "Interest the member registered. The offering member is not named, being the"
                + " counterparty.",
            """
            SELECT o.item_name, o.offer_kind, i.created_at
            FROM material_exchange_interest i JOIN material_exchange_offer o ON o.id = i.offer_id
            WHERE i.interested_user_id = :userId ORDER BY i.created_at
            """),
        new Section(
            340,
            "marketRequestInterests",
            ART_15_20,
            "Interest the member registered in a request. The requesting member is not named,"
                + " being the counterparty.",
            """
            SELECT r.request_kind, r.item_name, m.name AS material, i.created_at
            FROM material_exchange_request_interest i
              JOIN material_exchange_request r ON r.id = i.request_id
              LEFT JOIN material m ON m.id = r.requested_material_id
            WHERE i.interested_user_id = :userId ORDER BY i.created_at
            """));
  }
}
