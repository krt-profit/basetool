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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** Exports the member's job-order assignments, material claims and directly related money facts. */
@Component
public class JobOrderExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                290,
                "jobOrderAssignments",
                ART_15_20,
                "The member's own assignment note.",
                """
                SELECT o.display_id, o.type, o.status, a.note, a.created_at
                FROM job_order_assignees a JOIN job_order o ON o.id = a.job_order_id
                WHERE a.user_id = :userId ORDER BY a.created_at
                """)
            .scrubbing("note"),
        new Section(
                300,
                "materialClaims",
                ART_15_20,
                "Promises last recorded by the member, with directly linked contribution facts.",
                """
                SELECT o.display_id AS job_order, m.name AS material, c.amount,
                       t.code AS quality_requirement, c.created_at, c.ended_at,
                       c.credited_amount, c.changes,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                         'id', p.id, 'creditedAmount', p.amount, 'evidence', p.evidence,
                         'quantityAmount', CASE WHEN p.evidence = 'OBSERVED'
                           THEN p.quantity_amount END,
                         'suppliedQuality', CASE WHEN p.supplied_quality_known
                           THEN p.supplied_quality END,
                         'minimumSuppliedQuality', p.supplied_quality,
                         'factChanges', p.fact_history,
                         'createdAt', p.created_at) ORDER BY p.created_at, p.id)
                         FROM material_provision_credit p
                         WHERE p.job_order_id = c.job_order_id AND p.claim_id = c.id),
                         '[]'::jsonb) AS provisions
                FROM material_claim c
                  LEFT JOIN job_order o ON o.id = c.job_order_id
                  LEFT JOIN material m ON m.id = c.material_id
                  LEFT JOIN quality_tier t ON t.id = c.quality_tier_id
                WHERE c.claimed_by_user_id = :userId ORDER BY c.created_at
                """)
            .keeping(
                "job_order",
                "job_order.display_id, a number rather than a name. The order's handle column is"
                    + " deliberately not selected by any section, being an external contact's name"
                    + " the scrubber cannot see."),
        new Section(
            305,
            "jobOrderMoneyHolders",
            ART_15_20,
            "The member's current monetary holder links within individual orders.",
            """
            SELECT o.display_id AS job_order, h.id AS holder_id, h.revision
            FROM job_order_money_holder h JOIN job_order o ON o.id = h.order_id
            WHERE h.user_id = :userId ORDER BY o.display_id, h.id
            """),
        new Section(
            306,
            "jobOrderMoneyOperations",
            ART_15_20,
            "Provisions the member recorded, received, or was explicitly selected for.",
            """
            SELECT o.display_id AS job_order, p.operation_id, p.holder_id,
                   CAST(p.amount AS text) AS amount, p.bank_handling, p.recorded_at
            FROM job_order_money_operation p
            JOIN job_order o ON o.id = p.order_id
            JOIN job_order_money_holder h ON h.id = p.holder_id
            JOIN job_order_money_holder linked ON linked.id = COALESCE(h.merged_into_id, h.id)
            WHERE linked.user_id = :userId OR p.actor_id = :userId OR p.selected_user_id = :userId
            ORDER BY p.recorded_at, p.id
            """));
  }
}
