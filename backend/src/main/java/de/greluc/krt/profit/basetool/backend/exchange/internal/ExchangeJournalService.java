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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Journals every exchange write with the entry's state before and after, for the mass-change
 * guard's count and the member's undo (REQ-XCH-021, REQ-XCH-022, ADR-0218).
 */
@Service
@RequiredArgsConstructor
public class ExchangeJournalService {

  private final ExchangeJournalRepository journalRepository;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Records one written entry in the write's own transaction, so the journal and the data commit or
   * roll back together.
   *
   * @param caller who wrote it
   * @param batchId the change set it belongs to
   * @param action what the write did
   * @param entityKey the entry's key, as the change feed records it
   * @param removal whether it counts as a removal for the mass-change guard
   * @param before the entry's state before, or {@code null} when it did not exist
   * @param after the entry's state after, or {@code null} when it was removed
   * @return the recorded entry
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull ExchangeJournalEntry record(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batchId,
      @NotNull ExchangeJournalAction action,
      @NotNull String entityKey,
      boolean removal,
      @Nullable Object before,
      @Nullable Object after) {
    if (removal) {
      countAfterCommit(caller.clientId(), action.getResource());
    }
    return journalRepository.save(
        ExchangeJournalEntry.builder()
            .id(UUID.randomUUID())
            .userId(caller.member())
            .clientId(caller.clientId())
            .installationKey(caller.installationKey())
            .batchId(batchId)
            .resource(action.getResource())
            .entityKey(entityKey)
            .action(action)
            .removal(removal)
            .beforeState(before == null ? null : objectMapper.writeValueAsString(before))
            .afterState(after == null ? null : objectMapper.writeValueAsString(after))
            .build());
  }

  /**
   * Counts one removal once the write has committed, for the per-client removal alert.
   *
   * @param clientId the client, a registered one
   * @param resource the resource
   */
  private void countAfterCommit(@NotNull String clientId, @NotNull ExchangeResource resource) {
    Counter counter =
        meterRegistry.counter(
            MetricNames.EXCHANGE_REMOVALS,
            MetricNames.TAG_CLIENT_ID,
            clientId,
            MetricNames.TAG_RESOURCE,
            resource.name().toLowerCase(Locale.ROOT));
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      counter.increment();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            counter.increment();
          }
        });
  }

  /**
   * Counts a client's removals of a member's entries of one resource within a window.
   *
   * @param caller the client and member
   * @param resource the resource
   * @param since the start of the window
   * @return the removals not undone
   */
  @Transactional(readOnly = true)
  public long removalsSince(
      @NotNull ExchangeCaller caller, @NotNull ExchangeResource resource, @NotNull Instant since) {
    return journalRepository.countRemovals(caller.member(), caller.clientId(), resource, since);
  }

  /**
   * Deletes the entries past their retention.
   *
   * @param cutoff the oldest write still kept
   * @return the number of entries deleted
   */
  @Transactional
  public int purgeRecordedBefore(@NotNull Instant cutoff) {
    return journalRepository.deleteRecordedBefore(cutoff);
  }
}
