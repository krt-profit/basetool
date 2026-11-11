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

  private static final String MONEY_PARTICIPANTS =
      """
      LEFT JOIN job_order_money_holder source ON source.id = p.holder_id
      LEFT JOIN job_order_money_holder holder ON holder.id = COALESCE(source.merged_into_id, source.id)
      LEFT JOIN LATERAL (
        SELECT COALESCE(
          (SELECT effective.amount FROM job_order_money_correction_entry effective
           WHERE effective.order_id = p.order_id AND effective.original_operation_id = p.operation_id
           ORDER BY effective.id DESC LIMIT 1),
          p.amount
        ) > 0 AS paid
      ) participation ON TRUE
      LEFT JOIN job_order_money_recipient original_recipient ON original_recipient.id = p.recipient_id
      LEFT JOIN job_order_money_recipient r ON r.id = COALESCE(original_recipient.corrected_into_id, original_recipient.id)
        AND participation.paid
      LEFT JOIN job_order_money_holder receiving ON receiving.id = p.destination_holder_id
      LEFT JOIN job_order_money_holder destination ON destination.id = COALESCE(receiving.merged_into_id, receiving.id)
      LEFT JOIN job_order_money_holder designated ON designated.id = r.holder_id
      LEFT JOIN job_order_money_holder payee ON payee.id = COALESCE(designated.merged_into_id, designated.id)
      """;

  private static final String MONEY_PERSON =
      """
      (p.actor_id = :userId OR p.selected_user_id = :userId AND p.kind = 'SETTLEMENT' AND participation.paid OR holder.user_id = :userId
       OR r.user_id = :userId OR destination.user_id = :userId OR payee.user_id = :userId)
      """;

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
            "Provisions and movements the member recorded or directly participated in.",
            """
            SELECT o.display_id AS job_order, p.operation_id, p.holder_id, p.kind,
                   CAST(p.amount AS text) AS amount, p.bank_handling, p.recorded_at,
                   CAST(p.fee AS text) AS fee, CAST(p.new_provision AS text) AS new_provision
            FROM job_order_money_operation p
            JOIN job_order o ON o.id = p.order_id
            """
                + MONEY_PARTICIPANTS
                + "WHERE "
                + MONEY_PERSON
                + "ORDER BY p.recorded_at, p.id"),
        new Section(
            307,
            "jobOrderMoneyRecipients",
            ART_15_20,
            "The member's active and historical requester recipient designations.",
            """
            SELECT o.display_id AS job_order, r.id AS recipient_id, r.active,
                   r.corrects_id, r.corrected_at, r.corrected_into_id IS NOT NULL AS superseded
            FROM job_order_money_recipient r JOIN job_order o ON o.id = r.order_id
            LEFT JOIN job_order_money_holder h ON h.id = r.holder_id
            LEFT JOIN job_order_money_holder linked ON linked.id = COALESCE(h.merged_into_id, h.id)
            WHERE r.user_id = :userId OR linked.user_id = :userId OR r.correction_actor_id = :userId
            ORDER BY o.display_id, r.id
            """),
        new Section(
            308,
            "jobOrderMoneyTargetChanges",
            ART_15_20,
            "The member's recorded changes to requested net aUEC amounts.",
            """
            SELECT o.display_id AS job_order, CAST(t.previous_target AS text) AS previous_target,
                   CAST(t.target AS text) AS target, t.recorded_at
            FROM job_order_money_target_change t JOIN job_order o ON o.id = t.order_id
            WHERE t.actor_id = :userId ORDER BY t.recorded_at, t.id
            """),
        new Section(
                309,
                "jobOrderMoneyHolderChanges",
                ART_15_20,
                "Holder changes recorded by or directly concerning the member.",
                """
                SELECT o.display_id AS job_order, c.holder_id, c.target_holder_id, c.kind, c.recorded_at,
                       CASE WHEN linked.user_id = :userId THEN c.previous_name END AS previous_name,
                       CASE WHEN linked.user_id = :userId THEN c.name END AS name
                FROM job_order_money_holder_change c
                JOIN job_order o ON o.id = c.order_id
                JOIN job_order_money_holder h ON h.id = c.holder_id
                JOIN job_order_money_holder linked ON linked.id = COALESCE(h.merged_into_id, h.id)
                WHERE linked.user_id = :userId OR c.actor_id = :userId
                   OR c.previous_user_id = :userId OR c.user_id = :userId
                ORDER BY c.recorded_at, c.id
                """)
            .scrubbing("previous_name", "name"),
        new Section(
            312,
            "jobOrderMoneyCorrections",
            ART_15_20,
            "Effective revisions of money facts the member recorded or participated in.",
            """
            SELECT o.display_id AS job_order, c.correction_operation_id, c.original_operation_id,
                   CAST(c.previous_amount AS text) AS previous_amount, CAST(c.amount AS text) AS amount,
                   CAST(c.previous_provision AS text) AS previous_provision,
                   CAST(c.provision AS text) AS provision, CAST(c.previous_fee AS text) AS previous_fee,
                   CAST(c.fee AS text) AS fee, CAST(c.previous_credit AS text) AS previous_credit,
                   CAST(c.credit AS text) AS credit, correction.recorded_at
            FROM job_order_money_correction_entry c
            JOIN job_order o ON o.id = c.order_id
            JOIN job_order_money_operation correction
              ON correction.order_id = c.order_id AND correction.operation_id = c.correction_operation_id
            JOIN job_order_money_operation p
              ON p.order_id = c.order_id AND p.operation_id = c.original_operation_id
            """
                + MONEY_PARTICIPANTS
                + "WHERE correction.actor_id = :userId OR "
                + MONEY_PERSON
                + "ORDER BY c.id"),
        linkedMoneyAttempts());
  }

  private static Section linkedMoneyAttempts() {
    return new Section(
        313,
        "jobOrderLinkedMoneyAttempts",
        ART_15_20,
        "Bank-linked monetary attempts the member recorded, decided or directly participated in.",
        """
            SELECT o.display_id AS job_order, a.operation_id, a.revision, a.kind, a.state,
                   a.active, CAST(a.amount AS text) AS amount, CAST(a.provision AS text) AS new_provision,
                   CAST(a.actual_fee AS text) AS fee, a.fee_funding, a.actual_payment,
                   a.submitted_at, a.updated_at
            FROM job_order_linked_money_attempt a JOIN job_order o ON o.id = a.order_id
            LEFT JOIN job_order_money_holder h ON h.id = a.holder_id
            LEFT JOIN job_order_money_holder source ON source.id = COALESCE(h.merged_into_id, h.id)
            LEFT JOIN job_order_money_holder d ON d.id = a.destination_holder_id
            LEFT JOIN job_order_money_holder destination ON destination.id = COALESCE(d.merged_into_id, d.id)
            LEFT JOIN job_order_money_recipient original_recipient ON original_recipient.id = a.recipient_id
            LEFT JOIN job_order_money_recipient recipient
              ON recipient.id = COALESCE(original_recipient.corrected_into_id, original_recipient.id)
              AND a.actual_payment
            LEFT JOIN job_order_money_holder r ON r.id = recipient.holder_id
            LEFT JOIN job_order_money_holder payee ON payee.id = COALESCE(r.merged_into_id, r.id)
            WHERE a.actor_id = :userId OR a.decision_actor_id = :userId OR a.counterparty_user_id = :userId
               OR a.selected_user_id = :userId AND a.kind = 'SETTLEMENT' AND a.actual_payment
               OR source.user_id = :userId OR destination.user_id = :userId
               OR recipient.user_id = :userId OR payee.user_id = :userId
            ORDER BY a.submitted_at, a.id
        """);
  }
}
