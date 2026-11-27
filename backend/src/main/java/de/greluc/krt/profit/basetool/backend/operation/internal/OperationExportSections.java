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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The operation's part of the Art. 15 / Art. 20 data export: the payout records of the member
 * (REQ-SEC-058).
 */
@Component
public class OperationExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                265,
                "operationPayouts",
                ART_15,
                "Whether and when an officer paid out the member's share of an operation, and the"
                    + " settlement fee rate applied; the officer is not named, being a third"
                    + " party.",
                """
                SELECT o.name AS operation, s.paid_out, s.paid_out_at, s.settlement_fee_rate,
                       s.settlement_calculated_at, s.created_at, s.updated_at
                FROM operation_payout_status s JOIN operation o ON o.id = s.operation_id
                WHERE s.participant_key = CAST(:userId AS VARCHAR)
                ORDER BY s.created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.NAMES_REMOVED_AFTER_THREE_YEARS,
                Source.OTHER_MEMBERS)
            .scrubbing("operation"),
        new Section(
                650,
                "operationPayoutsMarked",
                ART_15,
                "Payouts of other members the member marked as an officer; the recipient is not"
                    + " included.",
                """
                SELECT o.name AS operation, s.paid_out, s.paid_out_at
                FROM operation_payout_status s JOIN operation o ON o.id = s.operation_id
                WHERE s.paid_out_by_user_id = :userId ORDER BY s.paid_out_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.UNLINKED_ON_DELETION, Source.MEMBER)
            .scrubbing("operation"));
  }
}
