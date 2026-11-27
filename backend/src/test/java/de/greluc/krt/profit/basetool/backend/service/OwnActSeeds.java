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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the rows that record the export subject's own acts on another member's or the
 * organisation's records (ADR-0415). Every row names the other member, by id, handle or free text,
 * as the counterpart the export must leave out.
 */
final class OwnActSeeds {

  private final @NotNull JdbcTemplate jdbc;
  private final @NotNull UUID subject;
  private final @NotNull String subjectHandle;
  private final @NotNull UUID other;
  private final @NotNull String otherHandle;
  private final @NotNull String counterpartText;

  /**
   * Binds the seeds to the two members.
   *
   * @param jdbc writes the rows
   * @param subject the member whose export is taken; every seeded act is theirs
   * @param other the member the acts concern
   */
  OwnActSeeds(@NotNull JdbcTemplate jdbc, @NotNull User subject, @NotNull User other) {
    this.jdbc = jdbc;
    this.subject = subject.getId();
    this.subjectHandle = subject.getUsername();
    this.other = other.getId();
    this.otherHandle = other.getUsername();
    this.counterpartText = "Notiz zu " + other.getUsername();
  }

  /** The subject approves the other member's registration and declines their erasure request. */
  void identity() {
    jdbc.update(
        "INSERT INTO user_approval_event (id, user_id, decision, reason, decided_by_id)"
            + " VALUES (?, ?, 'APPROVED', ?, ?)",
        UUID.randomUUID(),
        other,
        counterpartText,
        subject);
    jdbc.update(
        "INSERT INTO deletion_request (id, user_id, status, decision_note, decided_by_id,"
            + " decided_at) VALUES (?, ?, 'DECLINED', ?, ?, now())",
        UUID.randomUUID(),
        other,
        counterpartText,
        subject);
  }

  /**
   * The subject books a deposit for the other member, rejects and owner-approves their request and
   * grants them booking rights.
   *
   * @param account the bank account the acts concern
   */
  void bank(@NotNull UUID account) {
    UUID transaction = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_transaction (id, type, initiated_by, note, counterparty_user_id,"
            + " counterparty_handle, created_at) VALUES (?, 'DEPOSIT', ?, ?, ?, ?, now())",
        transaction,
        subject,
        counterpartText,
        other,
        otherHandle);
    jdbc.update(
        "INSERT INTO bank_posting (id, transaction_id, account_id, amount, created_at)"
            + " VALUES (?, ?, ?, 700, now())",
        UUID.randomUUID(),
        transaction,
        account);
    jdbc.update(
        "INSERT INTO bank_booking_request (id, account_id, type, amount, note, requested_by,"
            + " requester_handle, status, decided_by, decided_at, requires_owner_approval,"
            + " owner_approval_granted, owner_approval_granted_by, owner_approval_granted_at)"
            + " VALUES (?, ?, 'DEPOSIT', 900, ?, ?, ?, 'REJECTED', ?, now(), TRUE, TRUE, ?,"
            + " now())",
        UUID.randomUUID(),
        account,
        counterpartText,
        other,
        otherHandle,
        subject,
        subject);
    jdbc.update(
        "INSERT INTO bank_account_grant (user_id, account_id, can_deposit, granted_by)"
            + " VALUES (?, ?, TRUE, ?)",
        other,
        account,
        subject);
  }

  /**
   * The subject pauses purchasing for the organisation and for a unit, and raises a need for it.
   *
   * @param unit the unit the pause and the need concern
   * @param material the material the need asks for
   */
  void purchasingAndSupply(@NotNull UUID unit, @NotNull UUID material) {
    jdbc.update(
        "UPDATE purchasing_setting SET paused = TRUE, pause_reason = ?, paused_by = ?,"
            + " paused_at = now()",
        counterpartText,
        subject);
    jdbc.update(
        "INSERT INTO purchasing_unit_pause (id, org_unit_id, reason, paused_by) VALUES (?, ?, ?,"
            + " ?)",
        UUID.randomUUID(),
        unit,
        counterpartText,
        subject);
    jdbc.update(
        "INSERT INTO supply_need (id, org_unit_id, material_id, amount, priority, purchasable,"
            + " purchase_reason, max_unit_price, status, created_by)"
            + " VALUES (?, ?, ?, 6, 'HIGH', TRUE, ?, 120, 'OPEN', ?)",
        UUID.randomUUID(),
        unit,
        material,
        counterpartText,
        subject);
  }

  /**
   * The subject executes an order handover the other member hands over, and hands over an item the
   * other member executes the handover of.
   *
   * @param jobOrder the order both handovers belong to
   */
  void handovers(@NotNull UUID jobOrder) {
    jdbc.update(
        "INSERT INTO job_order_handover (id, job_order_id, handover_time, recipient_handle,"
            + " executing_user_id, handing_over_user_id, handing_over_handle, created_at,"
            + " updated_at, version) VALUES (?, ?, now(), ?, ?, ?, ?, now(), now(), 0)",
        UUID.randomUUID(),
        jobOrder,
        otherHandle,
        subject,
        other,
        otherHandle);
    jdbc.update(
        "INSERT INTO job_order_item_handover (id, job_order_id, handover_time, recipient_handle,"
            + " executing_user_id, handing_over_user_id, handing_over_handle, created_at,"
            + " updated_at, version) VALUES (?, ?, now() + interval '1 second', ?, ?, ?, ?,"
            + " now(), now(), 0)",
        UUID.randomUUID(),
        jobOrder,
        otherHandle,
        other,
        subject,
        subjectHandle);
  }

  /**
   * The subject starts and applies a catalogue import, resolves a review case of it and adds a
   * default blueprint.
   */
  void catalogue() {
    jdbc.update(
        "INSERT INTO p4k_import_job (id, kind, status, source_filename, created_by, started_at,"
            + " finished_at) VALUES (?, 'APPLY', 'SUCCEEDED', ?, ?, now(), now())",
        UUID.randomUUID(),
        otherHandle + ".json",
        subject);
    UUID run = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO p4k_import_run (id, channel, build_branch, build_version, change_number,"
            + " build_id, created_by, applied_at) VALUES (?, 'LIVE', 'sc-alpha-4.4', '4.4.0', 1,"
            + " 'zzz-build', ?, now())",
        run,
        subject);
    jdbc.update(
        "INSERT INTO p4k_review_case (id, kind, guid, reason, status, resolution, resolved_by,"
            + " resolved_at, first_run_id, last_run_id) VALUES (?, 'ITEM', ?,"
            + " 'UNCLEAR_PLAYABILITY', 'REVIEWED', 'KEEP', ?, now(), ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        subject,
        run,
        run);
    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name, created_by)"
            + " VALUES (?, 'zzz-export-default', 'Zzz Export Default', ?)",
        UUID.randomUUID(),
        subject.toString());
  }
}
