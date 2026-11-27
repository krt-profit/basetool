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
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The purchasing module's part of the Art. 15 / Art. 20 data export: the member's deals as a trade
 * delegate with their deliveries, settlements and tranches, and their appointments (REQ-PUR-028,
 * REQ-SEC-058). The partner of a deal is a third party's data and is not part of it.
 */
@Component
public class PurchasingExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                490,
                "purchasingDeals",
                ART_15_20,
                "Entered by the member.",
                """
                SELECT d.deal_no, d.created_at, d.updated_at, d.status,
                       COALESCE(m.name, g.name) AS entry, q.code AS quality_tier, d.amount,
                       d.unit_price, d.total, d.payment_model, d.risk_class, d.escort_wish,
                       u.name AS raising_unit, d.delivery_closed, d.settlement_due_at,
                       d.overdue_notified_at
                FROM purchasing_deal d
                  LEFT JOIN material m ON m.id = d.material_id
                  LEFT JOIN game_item g ON g.id = d.game_item_id
                  LEFT JOIN quality_tier q ON q.id = d.quality_tier_id
                  LEFT JOIN org_unit u ON u.id = d.raising_org_unit_id
                WHERE d.delegate_user_id = :userId ORDER BY d.created_at
                """)
            .processedAs(Activity.PURCHASING, Retention.PURCHASING_PERMANENT, Source.MEMBER)
            .keeping("entry", "entry: the name of a catalogue material or item, not a person.")
            .keeping("raising_unit", "org_unit.name; see orgUnitMemberships.org_unit."),
        new Section(
                491,
                "purchasingAppointments",
                ART_15,
                "Decided by the governing leadership about the member.",
                """
                SELECT p.appointed_at, p.valid_until, p.monthly_limit, p.revoked_at, p.created_at,
                       p.updated_at
                FROM purchasing_delegate p
                WHERE p.user_id = :userId ORDER BY p.appointed_at
                """)
            .processedAs(Activity.PURCHASING, Retention.PURCHASING_PERMANENT, Source.OTHER_MEMBERS),
        new Section(
                492,
                "purchasingDealDeliveries",
                ART_15,
                "Deliveries on the member's deals as delegate, recorded by the member or a lead and"
                    + " assessed by the accepting member, who is not named, being a third party.",
                """
                SELECT d.deal_no, v.amount, l.name AS location, v.status, v.accepted_amount,
                       v.rejected_amount, v.reject_reason, v.measured_quality, v.accepted_at,
                       v.recorded_by IS NOT DISTINCT FROM :userId AS recorded_by_member,
                       v.created_at, v.updated_at
                FROM purchasing_deal_delivery v
                  JOIN purchasing_deal d ON d.id = v.deal_id
                  LEFT JOIN location l ON l.id = v.location_id
                WHERE d.delegate_user_id = :userId ORDER BY v.created_at, v.id
                """)
            .processedAs(
                Activity.PURCHASING,
                Retention.PURCHASING_PERMANENT,
                Source.MEMBER,
                Source.OTHER_MEMBERS)
            .keeping("location", "location.name; see warehouseContributions.location."),
        new Section(
                493,
                "purchasingDealSettlements",
                ART_15,
                "The settlement of the member's deals as delegate: money received and paid, the"
                    + " remainder and any loss with its coded reason.",
                """
                SELECT d.deal_no, s.money_received, s.money_paid, s.remainder, s.loss_amount,
                       s.loss_reason, s.settled_at,
                       s.recorded_by IS NOT DISTINCT FROM :userId AS recorded_by_member,
                       s.created_at, s.updated_at
                FROM purchasing_deal_settlement s JOIN purchasing_deal d ON d.id = s.deal_id
                WHERE d.delegate_user_id = :userId ORDER BY s.created_at, s.id
                """)
            .processedAs(
                Activity.PURCHASING,
                Retention.PURCHASING_PERMANENT,
                Source.MEMBER,
                Source.OTHER_MEMBERS),
        new Section(
                494,
                "purchasingDealTranches",
                ART_15,
                "The payment tranches of the member's deals as delegate and the state the bank"
                    + " decided for each.",
                """
                SELECT d.deal_no, t.kind, t.amount, t.state, t.decided_at, t.created_at, t.updated_at
                FROM purchasing_deal_tranche t JOIN purchasing_deal d ON d.id = t.deal_id
                WHERE d.delegate_user_id = :userId ORDER BY t.created_at, t.id
                """)
            .processedAs(
                Activity.PURCHASING, Retention.PURCHASING_PERMANENT, Source.OTHER_MEMBERS));
  }
}
