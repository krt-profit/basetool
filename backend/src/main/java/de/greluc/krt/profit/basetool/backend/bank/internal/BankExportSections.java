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
                SELECT handle, active, role_managed, created_at, updated_at
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
                SELECT a.account_no, a.name AS account, g.created_at, g.updated_at
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
                       g.can_transfer, g.created_at, g.updated_at
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
                SELECT a.account_no, a.name AS account, l.limit_amount, l.created_at, l.updated_at
                FROM bank_account_approval_limit l JOIN bank_account a ON a.id = l.account_id
                WHERE l.grantee_user_id = :userId ORDER BY l.created_at
                """)
            .scrubbing("account"),
        bookingsAsCounterparty(),
        requestsRaised(),
        new Section(
                410,
                "bankRequestsAsCounterparty",
                ART_15,
                "Booking requests naming the member as the counterparty. Neither the requesting"
                    + " member nor the deciding bank employee is named, being third parties; the"
                    + " requester's note and justification are scrubbed.",
                """
                SELECT r.type, r.amount, a.account_no, a.name AS account, r.note, r.justification,
                       r.status, r.created_at, r.decided_at
                FROM bank_booking_request r JOIN bank_account a ON a.id = r.account_id
                WHERE r.counterparty_user_id = :userId ORDER BY r.created_at
                """)
            .scrubbing("account", "note", "justification"),
        requestsAsCustodian(),
        new Section(
                415,
                "bankFundsAuthorisations",
                ART_15,
                "A funds authorisation the member performed or held custody for.",
                """
                SELECT a.amount, acc.account_no, acc.name AS account, a.authorised_at,
                       COALESCE(a.authorised_by = :userId, FALSE) AS member_authorised,
                       COALESCE(h.user_id = :userId, FALSE) AS member_is_custodian
                FROM bank_authorisation a
                  JOIN bank_account acc ON acc.id = a.account_id
                  LEFT JOIN bank_holder h ON h.id = a.holder_id
                WHERE a.authorised_by = :userId OR h.user_id = :userId
                ORDER BY a.authorised_at, a.id
                """)
            .scrubbing("account"),
        new Section(
            417,
            "bankCustodianPostings",
            ART_15,
            "The amounts booked onto the org money the member holds in custody, one row per"
                + " booking; who booked it is not named, being a third party.",
            """
            SELECT t.type, hp.amount, hp.created_at, t.effective_at
            FROM bank_holder_posting hp
              JOIN bank_holder h ON h.id = hp.holder_id
              JOIN bank_transaction t ON t.id = hp.transaction_id
            WHERE h.user_id = :userId
            ORDER BY hp.created_at, hp.id
            """),
        new Section(
            440,
            "bankAuditActionsByMember",
            ART_15,
            "Bank actions the member performed, retained under Art. 6(1)(f).",
            """
            SELECT e.occurred_at, e.event_type, a.account_no, e.client_id
            FROM bank_audit_event e LEFT JOIN bank_account a ON a.id = e.account_id
            WHERE e.actor_user_id = :userId ORDER BY e.occurred_at
            """),
        new Section(
            450,
            "bankAuditActionsOnMember",
            ART_15,
            "Bank actions performed on the member by somebody else. The acting person is not"
                + " named, being a third party.",
            """
            SELECT e.occurred_at, e.event_type, a.account_no
            FROM bank_audit_event e LEFT JOIN bank_account a ON a.id = e.account_id
            WHERE e.target_user_id = :userId ORDER BY e.occurred_at
            """));
  }

  private static Section bookingsAsCounterparty() {
    return new Section(
            390,
            "bankBookingsAsCounterparty",
            ART_15,
            "Bookings recording the member as the counterparty, with the amount booked on each"
                + " account. Who booked them is not named, being a third party; the ledger itself"
                + " is retained under Art. 6(1)(f).",
            """
            SELECT t.type, t.note, t.justification, t.transfer_fee, t.counterparty_org_unit_name,
                   t.reversed_transaction_id IS NOT NULL AS reverses_booking, t.created_at,
                   t.effective_at,
                   (SELECT COALESCE(jsonb_agg(jsonb_build_object('accountNo', a.account_no,
                                                                 'amount', p.amount)
                                              ORDER BY a.account_no, p.id), '[]'::jsonb)
                    FROM bank_posting p JOIN bank_account a ON a.id = p.account_id
                    WHERE p.transaction_id = t.id) AS postings
            FROM bank_transaction t WHERE t.counterparty_user_id = :userId
            ORDER BY t.created_at
            """)
        .scrubbing("note", "justification");
  }

  private static Section requestsRaised() {
    return new Section(
            400,
            "bankRequestsRaised",
            ART_15_20,
            "Raised by the member, including their own justification and note, the accounts and"
                + " approval terms that applied and the connected application they raised it"
                + " through. The deciding bank employee is not named.",
            """
            SELECT r.type, r.amount, a.account_no, a.name AS account,
                   ta.account_no AS target_account_no, ta.name AS target_account,
                   r.counterparty_org_unit_name, r.note, r.justification, r.purpose_kind,
                   r.split_enabled, r.split_percent, r.applicable_limit,
                   r.requires_owner_approval, r.required_approver, r.owner_approval_granted,
                   r.owner_approval_granted_at, r.status,
                   r.cancelled_by_patch_change IS NOT NULL AS cancelled_by_patch_change,
                   r.client_id, r.created_at, r.updated_at, r.decided_at
            FROM bank_booking_request r
              JOIN bank_account a ON a.id = r.account_id
              LEFT JOIN bank_account ta ON ta.id = r.target_account_id
            WHERE r.requested_by = :userId ORDER BY r.created_at
            """)
        .scrubbing("account", "target_account", "note", "justification");
  }

  private static Section requestsAsCustodian() {
    return new Section(
        413,
        "bankRequestsAsCustodian",
        ART_15,
        "Linked booking requests that name the member as a custodian.",
        """
        SELECT r.type, r.amount, r.status, r.created_at, r.decided_at,
               (h.id = r.requested_holder_id AND
                r.type IN ('WITHDRAWAL', 'TRANSFER', 'HOLDER_TRANSFER'))
                   AS member_is_source_custodian,
               (r.type = 'DEPOSIT' OR h.id IS NOT DISTINCT FROM r.requested_target_holder_id)
                   AS member_is_destination_custodian,
               (r.type = 'AUTHORISATION') AS member_is_funding_custodian
        FROM bank_booking_request r JOIN bank_holder h
          ON h.id = r.requested_holder_id OR h.id = r.requested_target_holder_id
            OR (r.linked_attempt_id IS NOT NULL AND r.type = 'DEPOSIT' AND h.id = r.holder_id)
        WHERE h.user_id = :userId
        ORDER BY r.created_at, r.id
        """);
  }
}
