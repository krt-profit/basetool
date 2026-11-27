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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The purchasing module's part of the Art. 15 data export that records the member's own acts on the
 * organisation's purchasing records and other members' deals (REQ-PUR-028, REQ-SEC-058, ADR-0415).
 * The other member and the trade partner's name and notes are not part of it.
 */
@Component
public class PurchasingActExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                620,
                "purchasingAppointmentsMade",
                ART_15,
                "Appointments and revocations of trade delegates the member decided as a leader;"
                    + " the delegate and the terms of their appointment are not included.",
                """
                SELECT p.appointed_at, p.revoked_at,
                       p.appointed_by IS NOT DISTINCT FROM :userId AS appointed_by_member,
                       p.revoked_by IS NOT DISTINCT FROM :userId AS revoked_by_member
                FROM purchasing_delegate p
                WHERE p.appointed_by = :userId OR p.revoked_by = :userId ORDER BY p.appointed_at
                """)
            .processedAs(Activity.PURCHASING, Retention.UNLINKED_ON_DELETION, Source.MEMBER),
        new Section(
                621,
                "purchasingPartnersEntered",
                ART_15,
                "Trade partners the member entered as a delegate; the partner's name, tag and notes"
                    + " are a third party's data and not included.",
                """
                SELECT kind, channel, contact_via, status, erased, created_at
                FROM purchasing_partner WHERE created_by = :userId ORDER BY created_at
                """)
            .processedAs(Activity.PURCHASING, Retention.UNLINKED_ON_DELETION, Source.MEMBER),
        new Section(
                622,
                "purchasingPausesSet",
                ART_15,
                "Pauses of purchasing the member set for the organisation or a unit, with their own"
                    + " reason.",
                """
                SELECT 'ORGANISATION' AS scope, CAST(NULL AS VARCHAR) AS org_unit,
                       s.pause_reason AS reason, s.paused, s.paused_at
                FROM purchasing_setting s WHERE s.paused_by = :userId
                UNION ALL
                SELECT 'UNIT', o.name, p.reason, TRUE, p.paused_at
                FROM purchasing_unit_pause p JOIN org_unit o ON o.id = p.org_unit_id
                WHERE p.paused_by = :userId
                ORDER BY paused_at
                """)
            .processedAs(Activity.PURCHASING, Retention.UNLINKED_ON_DELETION, Source.MEMBER)
            .scrubbing("reason"),
        new Section(
                623,
                "purchasingDeliveriesHandled",
                ART_15,
                "Deliveries on other delegates' deals the member accepted or recorded; the delegate"
                    + " is not included.",
                """
                SELECT d.deal_no, v.amount, v.status, v.accepted_amount, v.rejected_amount,
                       v.reject_reason, v.measured_quality, v.accepted_at,
                       v.recorded_by IS NOT DISTINCT FROM :userId AS recorded_by_member,
                       v.accepted_by IS NOT DISTINCT FROM :userId AS accepted_by_member, v.created_at
                FROM purchasing_deal_delivery v JOIN purchasing_deal d ON d.id = v.deal_id
                WHERE v.accepted_by = :userId
                   OR v.recorded_by = :userId AND d.delegate_user_id IS DISTINCT FROM :userId
                ORDER BY v.created_at, v.id
                """)
            .processedAs(Activity.PURCHASING, Retention.UNLINKED_ON_DELETION, Source.MEMBER),
        new Section(
                624,
                "purchasingSettlementsRecorded",
                ART_15,
                "Settlements of other delegates' deals the member recorded; the delegate is not"
                    + " included.",
                """
                SELECT d.deal_no, s.money_received, s.money_paid, s.remainder, s.loss_amount,
                       s.loss_reason, s.settled_at, s.created_at
                FROM purchasing_deal_settlement s JOIN purchasing_deal d ON d.id = s.deal_id
                WHERE s.recorded_by = :userId AND d.delegate_user_id IS DISTINCT FROM :userId
                ORDER BY s.created_at, s.id
                """)
            .processedAs(Activity.PURCHASING, Retention.UNLINKED_ON_DELETION, Source.MEMBER));
  }
}
