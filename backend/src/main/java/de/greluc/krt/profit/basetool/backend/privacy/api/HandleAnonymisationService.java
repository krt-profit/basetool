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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant.Rewritten;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a member's surviving handle snapshots on a granted Art. 17 request (REQ-SEC-062); never
 * automatic.
 *
 * <p>Runs the registered {@link HandleAnonymisationParticipant}s, each rewriting the columns of its
 * module in one act, each by id match or whole-value comparison against every spelling of the
 * member's name, never by substring. No row is removed and no other fact changes. Both audit trails
 * receive a {@code HANDLE_SNAPSHOTS_ANONYMISED} marker with per-table row counts, never the handle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandleAnonymisationService {

  private final List<HandleAnonymisationParticipant> participants;
  private final AuditRecorder auditRecorder;

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
   * Erases the member's handle snapshots everywhere they survive a deletion and records the marker
   * events, in one transaction.
   *
   * <p>Must be called before the account is deleted, while the id-matched foreign keys still point
   * at it.
   *
   * @param userId the member whose snapshots are erased
   * @param spellings every name the member is stored under, for the text-matched columns; blanks
   *     are dropped and those columns are skipped when none remain
   * @return the per-table row counts
   */
  @Transactional
  public @NotNull AnonymisationResult anonymise(
      @NotNull UUID userId, @Nullable Collection<String> spellings) {
    List<String> names = List.copyOf(usableSpellings(spellings));
    List<HandleAnonymisationParticipant> ordered = inRunOrder();

    Map<Rewritten, Integer> rewritten = new EnumMap<>(Rewritten.class);
    for (HandleAnonymisationParticipant participant : ordered) {
      participant
          .anonymise(userId, names)
          .forEach((table, rows) -> rewritten.merge(table, rows, Integer::sum));
    }

    AnonymisationResult result =
        new AnonymisationResult(
            rewritten.getOrDefault(Rewritten.ACTIVITY_AUDIT, 0),
            rewritten.getOrDefault(Rewritten.BANK_AUDIT, 0),
            rewritten.getOrDefault(Rewritten.BANK_TRANSACTIONS, 0),
            rewritten.getOrDefault(Rewritten.BOOKING_REQUESTS, 0),
            rewritten.getOrDefault(Rewritten.BANK_HOLDERS, 0),
            rewritten.getOrDefault(Rewritten.JOB_ORDERS, 0),
            rewritten.getOrDefault(Rewritten.MATERIAL_HANDOVERS, 0),
            rewritten.getOrDefault(Rewritten.ITEM_HANDOVERS, 0));

    auditRecorder.record(
        AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED,
        userId,
        null,
        userId,
        AuditDetails.of("activityAudit", result.activityAudit())
            .with("bankAudit", result.bankAudit())
            .with("bankTransactions", result.bankTransactions())
            .with("bookingRequests", result.bookingRequests())
            .with("bankHolders", result.bankHolders())
            .with("jobOrders", result.jobOrders())
            .with("materialHandovers", result.materialHandovers())
            .with("itemHandovers", result.itemHandovers())
            .with("spellings", names.size()));

    for (HandleAnonymisationParticipant participant : ordered) {
      participant.recordAnonymised(userId, rewritten);
    }

    log.info(
        "Anonymised handle snapshots for user {}: {} row(s) across {} column(s), {} spelling(s)",
        userId,
        result.total(),
        ordered.stream().mapToInt(participant -> participant.columns().size()).sum(),
        names.size());
    return result;
  }

  /**
   * Returns the participants in the order they run, by position.
   *
   * @return the participants, earliest first
   */
  public @NotNull List<HandleAnonymisationParticipant> inRunOrder() {
    return participants.stream()
        .sorted(Comparator.comparingInt(HandleAnonymisationParticipant::position))
        .toList();
  }

  /**
   * Returns the distinct, trimmed, non-blank spellings, longest first.
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
