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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The purchasing module's part of the person search: the handle and the notes of a partner can name
 * a person, every other text column is a code (REQ-SEC-060, REQ-PUR-028).
 */
@Component
public class PurchasingMentionSource implements PersonMentionSource {

  private static final String AREA = "PURCHASING";

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target(AREA, "purchasing_partner", "name", "id", null),
        new Target(AREA, "purchasing_partner", "org_tag", "id", null),
        new Target(AREA, "purchasing_partner", "conditions_note", "id", null),
        new Target(AREA, "purchasing_partner", "reputation_note", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "purchasing_partner.kind",
        "purchasing_partner.channel",
        "purchasing_partner.contact_via",
        "purchasing_partner.status",
        "purchasing_setting.budget_period",
        "purchasing_setting.pause_reason",
        "purchasing_unit_pause.reason",
        "purchasing_risk_class.code",
        "purchasing_deal.payment_model",
        "purchasing_deal.risk_class",
        "purchasing_deal.status",
        "purchasing_deal_tranche.kind",
        "purchasing_deal_tranche.state",
        "purchasing_deal_delivery.status",
        "purchasing_deal_delivery.reject_reason",
        "purchasing_deal_settlement.loss_reason");
  }
}
