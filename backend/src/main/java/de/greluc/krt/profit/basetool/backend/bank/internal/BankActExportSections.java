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

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The bank's part of the Art. 15 data export that records the member's own acts on other members'
 * bank records: bookings made, requests decided, approvals and rights given (REQ-SEC-058,
 * ADR-0415). The other member, their name snapshot and their notes are not part of it.
 */
@Component
public class BankActExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                610,
                "bankBookingsMade",
                ART_15,
                "Bookings the member made as a bank employee, with the amount per account; the"
                    + " counterparty, its name snapshot and the notes are not included.",
                """
                SELECT t.type, t.transfer_fee, t.created_at, t.effective_at,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object('accountNo', a.account_no,
                                                                     'amount', p.amount)
                                                  ORDER BY a.account_no, p.id), '[]'::jsonb)
                        FROM bank_posting p JOIN bank_account a ON a.id = p.account_id
                        WHERE p.transaction_id = t.id) AS postings
                FROM bank_transaction t WHERE t.initiated_by = :userId
                ORDER BY t.created_at
                """)
            .processedAs(Activity.BANKING, Retention.UNLINKED_ON_DELETION, Source.MEMBER),
        new Section(
                611,
                "bankRequestsDecided",
                ART_15,
                "Booking requests of other members the member decided as a bank employee; the"
                    + " requester, the counterparty, their notes and the reject reason are not"
                    + " included.",
                """
                SELECT r.type, r.amount, a.account_no, ta.account_no AS target_account_no,
                       r.status, r.decided_at
                FROM bank_booking_request r
                  JOIN bank_account a ON a.id = r.account_id
                  LEFT JOIN bank_account ta ON ta.id = r.target_account_id
                WHERE r.decided_by = :userId ORDER BY r.decided_at
                """)
            .processedAs(Activity.BANKING, Retention.UNLINKED_ON_DELETION, Source.MEMBER),
        new Section(
                612,
                "bankOwnerApprovalsGiven",
                ART_15,
                "Approvals the member gave as the owner of an account to other members' booking"
                    + " requests; the requester is not included.",
                """
                SELECT r.type, r.amount, a.account_no, r.owner_approval_granted,
                       r.owner_approval_granted_at
                FROM bank_booking_request r JOIN bank_account a ON a.id = r.account_id
                WHERE r.owner_approval_granted_by = :userId ORDER BY r.owner_approval_granted_at
                """)
            .processedAs(Activity.BANKING, Retention.UNLINKED_ON_DELETION, Source.MEMBER),
        new Section(
                613,
                "bankAccountGrantsGiven",
                ART_15,
                "Booking rights on an account the member granted to another member as a bank"
                    + " manager; the grantee is not included.",
                """
                SELECT a.account_no, g.can_deposit, g.can_withdraw, g.can_transfer, g.created_at
                FROM bank_account_grant g JOIN bank_account a ON a.id = g.account_id
                WHERE g.granted_by = :userId ORDER BY g.created_at
                """)
            .processedAs(Activity.BANKING, Retention.UNLINKED_ON_DELETION, Source.MEMBER));
  }
}
