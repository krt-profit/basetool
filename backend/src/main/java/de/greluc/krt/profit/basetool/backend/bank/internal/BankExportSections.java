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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The bank's part of the Art. 15 / Art. 20 data export: the bank's records of the member and the
 * bank audit trail (REQ-SEC-058).
 */
@Component
public class BankExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                350,
                "bankHolderRegistration",
                ART_15,
                "The bank's record of the member as a custodian of org money.",
                """
                SELECT handle, active, role_managed, created_at
                FROM bank_holder WHERE user_id = :userId ORDER BY created_at
                """)
            .keeping(
                "handle",
                "The subject's own custodian handle -- the registration is theirs, and the column"
                    + " is a snapshot of their own name."),
        new Section(
                360,
                "bankAccountGrants",
                ART_15,
                "Per-account viewing rights granted to the member by the bank.",
                """
                SELECT a.account_no, a.name AS account, g.created_at
                FROM bank_account_view_grant g JOIN bank_account a ON a.id = g.account_id
                WHERE g.grantee_user_id = :userId ORDER BY g.created_at
                """)
            .scrubbing("account"),
        new Section(
                370,
                "bankAccountBookingGrants",
                ART_15,
                "Per-account booking rights granted to the member as a bank employee. The granting"
                    + " manager is not named, being a third party.",
                """
                SELECT a.account_no, a.name AS account, g.can_deposit, g.can_withdraw,
                       g.can_transfer, g.created_at
                FROM bank_account_grant g JOIN bank_account a ON a.id = g.account_id
                WHERE g.user_id = :userId ORDER BY g.created_at
                """)
            .scrubbing("account"),
        new Section(
                380,
                "bankApprovalLimits",
                ART_15,
                "Personal approval ceilings the bank set for the member's booking requests on an"
                    + " account.",
                """
                SELECT a.account_no, a.name AS account, l.limit_amount, l.created_at
                FROM bank_account_approval_limit l JOIN bank_account a ON a.id = l.account_id
                WHERE l.grantee_user_id = :userId ORDER BY l.created_at
                """)
            .scrubbing("account"),
        new Section(
                390,
                "bankBookingsAsCounterparty",
                ART_15,
                "Bookings recording the member as the counterparty. Who booked them is not named,"
                    + " being a third party; the ledger itself is retained under Art. 6(1)(f).",
                """
                SELECT type, note, justification, transfer_fee, created_at
                FROM bank_transaction WHERE counterparty_user_id = :userId ORDER BY created_at
                """)
            .scrubbing("note", "justification"),
        new Section(
                400,
                "bankRequestsRaised",
                ART_15_20,
                "Raised by the member, including their own justification and note. The deciding"
                    + " bank employee is not named.",
                """
                SELECT type, amount, note, justification, status, created_at, decided_at
                FROM bank_booking_request WHERE requested_by = :userId ORDER BY created_at
                """)
            .scrubbing("note", "justification"),
        new Section(
                410,
                "bankRequestsAsCounterparty",
                ART_15,
                "Booking requests naming the member as the counterparty. Neither the requesting"
                    + " member nor the deciding bank employee is named, being third parties; the"
                    + " requester's note and justification are scrubbed.",
                """
                SELECT type, amount, note, justification, status, created_at, decided_at
                FROM bank_booking_request WHERE counterparty_user_id = :userId ORDER BY created_at
                """)
            .scrubbing("note", "justification"),
        new Section(
            440,
            "bankAuditActionsByMember",
            ART_15,
            "Bank actions the member performed, retained under Art. 6(1)(f).",
            """
            SELECT occurred_at, event_type, client_id
            FROM bank_audit_event WHERE actor_user_id = :userId ORDER BY occurred_at
            """),
        new Section(
            450,
            "bankAuditActionsOnMember",
            ART_15,
            "Bank actions performed on the member by somebody else. The acting person is not"
                + " named, being a third party.",
            """
            SELECT occurred_at, event_type
            FROM bank_audit_event WHERE target_user_id = :userId ORDER BY occurred_at
            """));
  }
}
