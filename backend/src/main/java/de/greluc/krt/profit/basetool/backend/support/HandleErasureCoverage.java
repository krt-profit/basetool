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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Registers what happens to each place a person is named when that person's Art. 17 erasure is
 * granted (REQ-SEC-062).
 *
 * <p>{@code HandleErasureCoverageTest} requires every {@link PersonSearchTargets} column to have a
 * disposition here, and cross-checks {@link Disposition#ANONYMISED} against {@code
 * HandleAnonymisationService.ANONYMISED_COLUMNS}. Prose columns are {@link
 * Disposition#REVIEWED_BY_HAND}.
 */
public final class HandleErasureCoverage {

  /** Not instantiable. */
  private HandleErasureCoverage() {}

  /** What becomes of one column's contents when an erasure is granted. */
  public enum Disposition {
    /** Rewritten to {@link HandleAnonymisation#SENTINEL} by {@code HandleAnonymisationService}. */
    ANONYMISED,
    /** The row itself goes when the account does — a purge or a cascading foreign key. */
    REMOVED_WITH_THE_ACCOUNT,
    /** The column structurally names somebody or something other than the member. */
    NOT_THE_MEMBER,
    /**
     * Free text that may mention the member. Found by the admin Personensuche and edited by hand,
     * because a name inside a sentence cannot be rewritten mechanically without risking the
     * sentence.
     */
    REVIEWED_BY_HAND
  }

  /**
   * One column's disposition and why.
   *
   * @param disposition what happens to the contents
   * @param reason why, in a sentence somebody reviewing an erasure can act on
   */
  public record Coverage(Disposition disposition, String reason) {}

  private static Map.Entry<String, Coverage> anonymised(String column, String reason) {
    return Map.entry(column, new Coverage(Disposition.ANONYMISED, reason));
  }

  private static Map.Entry<String, Coverage> removed(String column, String reason) {
    return Map.entry(column, new Coverage(Disposition.REMOVED_WITH_THE_ACCOUNT, reason));
  }

  private static Map.Entry<String, Coverage> notTheMember(String column, String reason) {
    return Map.entry(column, new Coverage(Disposition.NOT_THE_MEMBER, reason));
  }

  private static Map.Entry<String, Coverage> byHand(String column, String reason) {
    return Map.entry(column, new Coverage(Disposition.REVIEWED_BY_HAND, reason));
  }

  /** Keyed {@code table.column}, exactly as {@link PersonSearchTargets} spells it. */
  public static final Map<String, Coverage> COVERAGE =
      Map.ofEntries(
          anonymised(
              "audit_event.actor_handle",
              "The activity trail's actor snapshot, matched by actor_user_id while the account"
                  + " still exists."),
          anonymised(
              "audit_event.subject_label",
              "Matched where the whole label IS the member's name. Rows written before 2026-09-17"
                  + " can instead hold a job-order label with the contact's name composed in"
                  + " (#<id> '<handle>'), which whole-value equality cannot reach and a substring"
                  + " rewrite must not: see JobOrderAuditLabel. Those are an administrator's"
                  + " manual step, found through the Personensuche."),
          byHand(
              "audit_event.details",
              "A name concatenated into the payload against REQ-AUDIT-001. The erasure used to"
                  + " rewrite it by substring REPLACE over every row of the table; that was removed"
                  + " on 2026-09-17 because the needle is the departing member's own self-service"
                  + " display name, so a short or common one rewrote unrelated rows irreversibly."
                  + " Found through the Personensuche and edited by an administrator."),
          anonymised(
              "bank_audit_event.actor_handle", "The bank trail's actor snapshot, matched by id."),
          byHand(
              "bank_audit_event.details",
              "Where BankHolderService and BankLedgerService concatenate the handle. Same removal"
                  + " and same reason as audit_event.details -- a member called aUEC would have had"
                  + " the amount annotation rewritten across the whole financial trail."),
          anonymised(
              "bank_transaction.counterparty_handle",
              "The booking history. The single approved mutation of an append-only ledger"
                  + " (ADR-0183); no booking fact changes, only the name."),
          anonymised(
              "bank_booking_request.requester_handle",
              "One of four handle columns on the request, any of which can name the same member."),
          anonymised("bank_booking_request.decider_handle", "See requester_handle."),
          anonymised("bank_booking_request.counterparty_handle", "See requester_handle."),
          anonymised(
              "bank_booking_request.owner_approval_granted_by_handle", "See requester_handle."),
          anonymised(
              "bank_holder.handle",
              "The custodian registry, which outlives the account by design: user_id is ON DELETE"
                  + " SET NULL and the display name falls back to this column the moment it"
                  + " nulls."),
          anonymised(
              "job_order.handle",
              "The order's contact person, typed by hand and therefore matched by text, once per"
                  + " spelling the account carries."),
          anonymised(
              "job_order_handover.recipient_handle",
              "A recipient with no user id beside them; matched by text, case-insensitively."),
          anonymised("job_order_item_handover.recipient_handle", "See job_order_handover."),
          removed("app_user.username", "The account row itself is deleted."),
          removed("app_user.display_name", "The account row itself is deleted."),
          removed("app_user.email", "The account row itself is deleted."),
          removed("app_user.description", "The account row itself is deleted."),
          removed("app_user.discord_guild_nickname", "The account row itself is deleted."),
          removed("app_user.rsi_handle", "The account row itself is deleted."),
          removed(
              "deletion_request.decision_note",
              "deletion_request.user_id is ON DELETE CASCADE, so the request that asked for the"
                  + " erasure goes with the account it was about."),
          removed(
              "user_approval_event.reason",
              "Purged explicitly by UserDeletionService, and separately by the REQ-SEC-057"
                  + " retention sweep 90 days after a refusal."),
          removed(
              "personal_inventory_item.name",
              "One of the five FK-less personal stores UserDeletionService purges by hand;"
                  + " nothing else in the system can remove them."),
          removed("personal_inventory_item.note", "See personal_inventory_item.name."),
          removed("personal_blueprint.note", "See personal_inventory_item.name."),
          removed("ship.name", "The member's hangar is purged with the account."),
          removed(
              "inventory_item.note",
              "The departing member's warehouse rows are purged, with the job-order and mission"
                  + " allocations the database cascades off them."),
          notTheMember(
              "mission.party_lead_guest_name",
              "A party lead with no account -- a third party, whose own request would be served"
                  + " through the Personensuche."),
          notTheMember("mission_participant.guest_name", "An external participant; see above."),
          notTheMember(
              "job_order_handover.recipient_squadron",
              "A squadron, not a person: an organisational unit's name."),
          notTheMember(
              "org_unit.grand_admiral_display_name",
              "The organisation's own office holder, carried as configuration rather than as a"
                  + " membership record."),
          notTheMember("bank_account.name", "An account's name, chosen by the bank."),
          notTheMember("location.name", "A station or outpost from the synced catalogue."),
          notTheMember("location.description", "See location.name."),
          notTheMember("kommando_group.name", "An organisational grouping."),
          notTheMember(
              "org_unit.name",
              "An organisational unit: a squadron or special command, named by the organisation"
                  + " and not after the member."),
          notTheMember("org_unit.shorthand", "An organisational unit's short form."),
          notTheMember("mission_frequency.name", "A radio frequency's label."),
          notTheMember(
              "mission_unit.name",
              "A unit within a mission -- a formation label from the mission plan rather than"
                  + " the name of a participant."),
          notTheMember("promotion_category.name", "A promotion catalogue entry."),
          notTheMember("promotion_topic.name", "A promotion catalogue entry."),
          byHand(
              "announcement.content",
              "An administrator's announcement. It can name a member mid-sentence, and rewriting"
                  + " an announcement mechanically would change what the organisation said."),
          byHand(
              "bank_transaction.note",
              "Prose on a booking. Rewriting it mechanically risks the description of the"
                  + " booking; the Personensuche finds it and an admin edits it."),
          byHand("bank_transaction.justification", "See bank_transaction.note."),
          byHand("bank_transaction.staff_note", "See bank_transaction.note."),
          byHand("bank_booking_request.note", "See bank_transaction.note."),
          byHand("bank_booking_request.justification", "See bank_transaction.note."),
          byHand("bank_booking_request.reject_reason", "See bank_transaction.note."),
          byHand("bank_booking_request.staff_note", "See bank_transaction.note."),
          byHand("job_order.comment", "Prose on an order, which survives the member."),
          byHand(
              "job_order_assignees.note",
              "Prose on an assignment. The assignee link is unlinked on deletion but the note"
                  + " stays on the order, which survives."),
          byHand(
              "mission.name",
              "A mission survives its owner -- it is reassigned, not deleted, because it carries"
                  + " operation history. Somebody may have named it after a person."),
          byHand("mission.description", "See mission.name."),
          byHand("mission.meeting_point", "See mission.name."),
          byHand("mission_objective.title", "Prose on a surviving mission."),
          byHand("mission_step.title", "Prose on a surviving mission."),
          byHand("mission_step.meta", "Prose on a surviving mission."),
          byHand("mission_unit.note", "Prose on a surviving mission."),
          byHand("mission_finance_entry.note", "Prose on a surviving mission's finances."),
          byHand(
              "mission_participant.comment",
              "The participation row survives and renders as the deleted-user placeholder, so its"
                  + " comment survives with it."),
          byHand(
              "material_exchange_offer.remark",
              "A market remark. The owning reference nulls out but the row and its prose stay."),
          byHand("material_exchange_request.remark", "See material_exchange_offer.remark."),
          byHand(
              "notification_rule.description",
              "An administrator's description of a rule, which may describe it by the member it"
                  + " targets. The rule outlives the member."),
          byHand(
              "operation.name",
              "An operation survives its participants and carries payout history."),
          byHand("operation.description", "See operation.name."),
          byHand(
              "org_chart_position.name",
              "An org-chart entry. A placeholder position can carry a person's name where no"
                  + " account backs it."),
          byHand("org_chart_position.display_name", "See org_chart_position.name."),
          byHand("org_unit.description", "An organisational unit's prose."),
          byHand("promotion_category.description", "Promotion catalogue prose."),
          byHand("promotion_topic.description", "Promotion catalogue prose."),
          byHand("promotion_level_content.description", "Promotion catalogue prose."),
          byHand("rank_requirement.description", "Promotion catalogue prose."),
          byHand(
              "blueprint_external_alias.created_by",
              "Who created an alias, stored as a name rather than a reference. Prose in effect,"
                  + " and the alias outlives the member."),
          byHand(
              "blueprint_external_alias.note",
              "Prose on a blueprint alias, which belongs to the catalogue and outlives whoever"
                  + " wrote it."),
          byHand("material_external_alias.created_by", "See blueprint_external_alias.created_by."),
          byHand(
              "material_external_alias.note",
              "Prose on a material alias; see blueprint_external_alias.note, same catalogue"
                  + " lifetime."));

  /**
   * The disposition of one column.
   *
   * @param tableColumn {@code table.column}
   * @return the coverage entry, or {@code null} when the column is not registered
   */
  public static Coverage of(@NotNull String tableColumn) {
    return COVERAGE.get(tableColumn);
  }
}
