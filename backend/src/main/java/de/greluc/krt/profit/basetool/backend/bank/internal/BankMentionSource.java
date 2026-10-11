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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The bank's part of the person search: the columns of bank accounts, holders, bookings and
 * requests that can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class BankMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("BANK", "bank_account", "name", "id", LinkKind.BANK),
        new Target("AUDIT", "bank_audit_event", "actor_handle", "id", LinkKind.AUDIT),
        new Target("AUDIT", "bank_audit_event", "details", "id", LinkKind.AUDIT),
        new Target("BANK", "bank_booking_request", "counterparty_handle", "id", LinkKind.BANK),
        new Target("BANK", "bank_booking_request", "decider_handle", "id", LinkKind.BANK),
        new Target("BANK", "bank_booking_request", "justification", "id", LinkKind.BANK),
        new Target("BANK", "bank_booking_request", "note", "id", LinkKind.BANK),
        new Target(
            "BANK",
            "bank_booking_request",
            "owner_approval_granted_by_handle",
            "id",
            LinkKind.BANK),
        new Target("BANK", "bank_booking_request", "reject_reason", "id", LinkKind.BANK),
        new Target("BANK", "bank_booking_request", "requester_handle", "id", LinkKind.BANK),
        new Target("BANK", "bank_booking_request", "staff_note", "id", LinkKind.BANK),
        new Target("BANK", "bank_holder", "handle", "id", LinkKind.BANK),
        new Target("BANK", "bank_transaction", "counterparty_handle", "id", LinkKind.BANK),
        new Target("BANK", "bank_transaction", "justification", "id", LinkKind.BANK),
        new Target("BANK", "bank_transaction", "note", "id", LinkKind.BANK),
        new Target("BANK", "bank_transaction", "staff_note", "id", LinkKind.BANK));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "bank_account.account_no",
        "bank_account.area_name",
        "bank_account.status",
        "bank_account.type",
        "bank_account_approval_limit.grantee_kind",
        "bank_account_approval_limit.role_code",
        "bank_account_view_grant.grantee_kind",
        "bank_account_view_grant.role_code",
        "bank_audit_event.client_id",
        "bank_audit_event.event_type",
        "bank_booking_request.counterparty_org_unit_name",
        "bank_booking_request.required_approver",
        "bank_booking_request.status",
        "bank_booking_request.type",
        "bank_transaction.counterparty_org_unit_name",
        "bank_transaction.type");
  }
}
