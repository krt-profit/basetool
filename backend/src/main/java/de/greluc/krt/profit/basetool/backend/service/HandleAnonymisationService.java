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

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a member's surviving handle snapshots on a granted Art. 17 request (REQ-SEC-062).
 *
 * <p>Deleting an account removes or reassigns everything it owned (REQ-DATA-008) but deliberately
 * leaves the <b>handle snapshots</b> behind, because they are what keeps a trail readable after the
 * person is gone: the user foreign keys are {@code ON DELETE SET NULL}, so without the snapshot a
 * row would record an action by nobody. The privacy policy says so and rests it on Art. 6(1)(f).
 *
 * <p>That interest is real but not automatically overriding, so the policy also offers the member
 * an Art. 17 request that reaches these snapshots too. This service is that request's execution. It
 * is <b>never automatic</b>: an admin weighs the wish and grants it deliberately, and the procedure
 * for weighing it is in {@code docs/privacy/data-subject-requests.md}.
 *
 * <p><b>Twelve columns in eight statements, one act.</b> Erasing some and leaving others would be
 * worse than not erasing at all, because the result reads as an erasure that has been performed.
 * The list below is the eight <em>statements</em>; two of them cover several columns, and {@link
 * #ANONYMISED_COLUMNS} is the authoritative twelve that {@code HandleErasureCoverageTest} and the
 * receipt's own log line both read. The prose used to say „Eight columns“ over a list naming
 * twelve, which is the same kind of count a reader checks a coverage list against (corrected
 * 2026-09-17):
 *
 * <ol>
 *   <li>{@code audit_event.actor_handle} — the activity trail, matched by id
 *   <li>{@code audit_event.subject_label} — matched where the whole label <em>is</em> the name
 *   <li>{@code bank_audit_event.actor_handle} — the bank trail, matched by id
 *   <li>{@code bank_transaction.counterparty_handle} — the booking history, matched by id
 *   <li>{@code bank_booking_request} — four handle columns, matched by their four id columns in one
 *       statement
 *   <li>{@code bank_holder.handle} — the custodian registry, which outlives the account by design
 *   <li>{@code job_order.handle} — the order's contact person, matched by the whole value
 *   <li>{@code job_order_handover.recipient_handle} and its item sibling — likewise
 * </ol>
 *
 * <p><b>Every statement here is a whole-value comparison or an id match. None is a substring
 * rewrite, and that is a security property rather than a style preference.</b> Three substring
 * {@code REPLACE} statements were removed on 2026-09-17 after review: they rewrote every row of
 * {@code audit_event.details}, {@code bank_audit_event.details} and {@code notification.params}
 * whose text contained the needle, with no owner predicate — and the needle is the departing
 * member's own {@code display_name}, which is self-service free text. A member who called
 * themselves {@code aUEC} could have an admin, acting through the intended and legally obliged
 * workflow, irreversibly rewrite the amount annotation on the entire financial trail. A single
 * common letter would have shredded all three columns.
 *
 * <p>Guarding them — a length floor, an owner predicate, LIKE escaping, longest-first ordering —
 * would have left a narrower version of the same weapon. Not having one is better, and it costs
 * nothing that was real: a name inside a key/value payload is text somebody's code composed around
 * it, which {@link de.greluc.krt.profit.basetool.backend.support.HandleErasureCoverage} already
 * classifies as an administrator's manual step, found through the Personensuche. The spliced
 * sentinel could not have been rendered either — both humanisers compare the whole value, so every
 * viewer would have shown {@code #ANONYMISED#} verbatim in the middle of a sentence.
 *
 * <p>The columns are held to the schema by {@code HandleErasureCoverageTest}, which walks every
 * column {@link de.greluc.krt.profit.basetool.backend.support.PersonSearchTargets} registers as a
 * place a person is named and fails the build unless each one is anonymised here, removed by the
 * account's own deletion, structurally about somebody else, or recorded as a manual step with a
 * reason. The person search got that gate from the start; this set did not, and that asymmetry is
 * why one drifted and the other did not.
 *
 * <p><b>Every spelling, not the effective name only.</b> {@code getEffectiveName()} is {@code
 * displayName ?: username}, so the text-matched columns missed a handover typed with the member's
 * username or their Discord nickname. All three are passed, longest first.
 *
 * <p><b>What it does not change.</b> Not one row is removed and not one fact about what happened is
 * altered: timestamps, event types, amounts, accounts, subjects and counts all stand. Only the name
 * goes. That is the difference between this and a deletion, and it is why it can coexist with an
 * append-only guarantee (ADR-0183) — whereas REQ-AUDIT-006's retention sweep deliberately deletes
 * rather than anonymises, because a blanket scrub would turn every old row into the
 * action-by-nobody the snapshot exists to prevent (ADR-0179).
 *
 * <p><b>It leaves a receipt.</b> Both trails get a {@code HANDLE_SNAPSHOTS_ANONYMISED} marker,
 * written <em>after</em> the updates so the marker itself is not scrubbed, carrying the per-table
 * row counts and never the handle that was removed — writing the value back would undo the erasure
 * in the very row that records it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandleAnonymisationService {

  private final AuditEventRepository auditEventRepository;
  private final BankAuditEventRepository bankAuditEventRepository;
  private final BankTransactionRepository bankTransactionRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BankHolderRepository bankHolderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final JobOrderRepository jobOrderRepository;
  private final AuditService auditService;
  private final BankAuditService bankAuditService;

  /**
   * Exactly the columns this service rewrites, as {@code table.column}.
   *
   * <p>Declared here rather than restated in a test, because a literal list in a test is how the
   * count came apart: it held fourteen entries while the service rewrote fifteen columns, and the
   * only configuration in which everything was green was the one that <em>understated</em> the
   * erasure. {@code HandleErasureCoverageTest} reads this, so the two cannot disagree — and a
   * column added to the service without being added here is a compile-time-visible omission rather
   * than a silently passing test.
   */
  public static final List<String> ANONYMISED_COLUMNS =
      List.of(
          "audit_event.actor_handle",
          "audit_event.subject_label",
          "bank_audit_event.actor_handle",
          "bank_transaction.counterparty_handle",
          "bank_booking_request.requester_handle",
          "bank_booking_request.decider_handle",
          "bank_booking_request.counterparty_handle",
          "bank_booking_request.owner_approval_granted_by_handle",
          "bank_holder.handle",
          "job_order.handle",
          "job_order_handover.recipient_handle",
          "job_order_item_handover.recipient_handle");

  /**
   * The per-table row counts one anonymisation rewrote.
   *
   * @param activityAudit rows rewritten in {@code audit_event}
   * @param bankAudit rows rewritten in {@code bank_audit_event}
   * @param bankTransactions rows rewritten in {@code bank_transaction}
   * @param bookingRequests rows rewritten in {@code bank_booking_request}
   * @param bankHolders rows rewritten in {@code bank_holder}
   * @param jobOrders rows rewritten in {@code job_order}
   * @param materialHandovers rows rewritten in {@code job_order_handover}
   * @param itemHandovers rows rewritten in {@code job_order_item_handover}
   */
  public record AnonymisationResult(
      int activityAudit,
      int bankAudit,
      int bankTransactions,
      int bookingRequests,
      int bankHolders,
      int jobOrders,
      int materialHandovers,
      int itemHandovers) {

    /**
     * The total number of rows rewritten across every column.
     *
     * @return the sum of the per-table counts
     */
    public int total() {
      return activityAudit
          + bankAudit
          + bankTransactions
          + bookingRequests
          + bankHolders
          + jobOrders
          + materialHandovers
          + itemHandovers;
    }
  }

  /**
   * Erases the member's handle snapshots everywhere they survive a deletion, and records the two
   * marker events.
   *
   * <p>Runs in one transaction: a partial anonymisation is the one outcome that must not be
   * possible, because the rows left behind would still name a member the record claims was erased.
   *
   * <p>Must be called <b>before</b> the account is deleted. The id-matched updates only reach rows
   * while the foreign key still points at the account; afterwards the handle text is the only link
   * and the admin Personensuche (REQ-SEC-060) is the route to it.
   *
   * @param userId the member whose snapshots are erased
   * @param spellings every name the member is stored under — username, display name and Discord
   *     guild nickname — used for the text-matched columns. Blanks are dropped; when nothing is
   *     left, those columns are skipped, because matching an empty string would rewrite every row
   *     that has no value there.
   * @return the per-table row counts
   */
  @Transactional
  public @NotNull AnonymisationResult anonymise(
      @NotNull UUID userId, @Nullable Collection<String> spellings) {
    String sentinel = HandleAnonymisation.SENTINEL;
    Set<String> names = usableSpellings(spellings);

    int activityAudit = auditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankAudit = bankAuditEventRepository.anonymiseActorHandle(userId, sentinel);
    int bankTransactions = bankTransactionRepository.anonymiseCounterpartyHandle(userId, sentinel);
    int bookingRequests = bankBookingRequestRepository.anonymiseHandles(userId, sentinel);
    int bankHolders = bankHolderRepository.anonymiseHandle(userId, sentinel);

    int jobOrders = 0;
    int materialHandovers = 0;
    int itemHandovers = 0;
    for (String name : names) {
      activityAudit += auditEventRepository.anonymiseSubjectLabel(name, sentinel);
      jobOrders += jobOrderRepository.anonymiseHandle(name, sentinel);
      materialHandovers += jobOrderHandoverRepository.anonymiseRecipientHandle(name, sentinel);
      itemHandovers += jobOrderItemHandoverRepository.anonymiseRecipientHandle(name, sentinel);
    }

    AnonymisationResult result =
        new AnonymisationResult(
            activityAudit,
            bankAudit,
            bankTransactions,
            bookingRequests,
            bankHolders,
            jobOrders,
            materialHandovers,
            itemHandovers);

    auditService.record(
        AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        userId,
        null,
        userId,
        AuditDetails.of("activityAudit", activityAudit)
            .with("bankAudit", bankAudit)
            .with("bankTransactions", bankTransactions)
            .with("bookingRequests", bookingRequests)
            .with("bankHolders", bankHolders)
            .with("jobOrders", jobOrders)
            .with("materialHandovers", materialHandovers)
            .with("itemHandovers", itemHandovers)
            .with("spellings", names.size()));

    bankAuditService.record(
        BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        null,
        null,
        userId,
        AuditDetails.of("bankAudit", bankAudit)
            .with("bankTransactions", bankTransactions)
            .with("bookingRequests", bookingRequests)
            .with("bankHolders", bankHolders));

    log.info(
        "Anonymised handle snapshots for user {}: {} row(s) across {} column(s), {} spelling(s)",
        userId,
        result.total(),
        ANONYMISED_COLUMNS.size(),
        names.size());
    return result;
  }

  /**
   * The spellings that are safe to match on, longest first.
   *
   * <p>A blank one would match every row whose column is empty and rewrite all of them, so blanks
   * are dropped rather than passed through. Deduplicated because a member whose display name equals
   * their username would otherwise have every statement run twice, inflating the counts in the
   * receipt for no change on disk.
   *
   * <p><b>Longest first</b>, for the same reason {@link
   * de.greluc.krt.profit.basetool.backend.support.HandleScrubber} sorts that way. Every remaining
   * statement is a whole-value comparison, so the order cannot currently change the outcome — but
   * the two components are read side by side and a reader who finds one sorted and the other not
   * has to work out whether the difference is deliberate. It is not, so they match.
   *
   * @param spellings the raw candidates, possibly {@code null} and possibly containing blanks
   * @return the distinct non-blank spellings, trimmed, longest first
   */
  private static @NotNull Set<String> usableSpellings(@Nullable Collection<String> spellings) {
    if (spellings == null) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    spellings.stream()
        .filter(candidate -> candidate != null && !candidate.isBlank())
        .map(String::trim)
        .distinct()
        .sorted((a, b) -> Integer.compare(b.length(), a.length()))
        .forEach(out::add);
    return out;
  }
}
