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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.function.SingletonSupplier;

/**
 * Undoes a client's writes since a point in time (REQ-XCH-022, REQ-XCH-046) — to the member's own
 * entries and to the org-stock entries the member wrote as a Logistician: each entry goes back to
 * its state before the client's first write in that span, unless something else has changed it
 * since the client's last one; Materialbörse offers a book-out lowered stay lowered. An admin's
 * bulk undo runs the same per member (REQ-XCH-034).
 */
@Service
@RequiredArgsConstructor
public class ExchangeUndoService {

  /** The reason of an entry changed after the client's last write to it. */
  static final String CHANGED_AFTERWARDS = "CHANGED_AFTERWARDS";

  /**
   * The reason of an entry that no longer belongs to the member, or whose catalogue entry is gone.
   */
  static final String GONE = "GONE";

  private static final String RESTORED = "restored";
  private static final String SKIPPED = "skipped";

  private final ExchangeJournalRepository journalRepository;
  private final ExchangeClientRepository clientRepository;
  private final ExchangeBlueprintJournal blueprintJournal;
  private final ExchangeStockJournal stockJournal;
  private final ExchangeShipJournal shipJournal;
  private final ExchangeOrgStockJournal orgStockJournal;
  private final ExchangeOrgUnitService orgUnitService;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final AuditRecorder auditRecorder;
  private final ExchangeLiveSync liveSync;
  private final ExchangeEntryLabels entryLabels;
  private final ExchangeChangeRetentionProperties retention;
  private final MeterRegistry meterRegistry;
  private final Clock clock = Clock.systemUTC();

  /**
   * Undoes a client's writes to the member's entries since a point in time, in one transaction.
   *
   * @param member the member
   * @param clientId the client
   * @param since the point in time; one older than the configured journal and change-log retention
   *     reaches only as far back as that retention
   * @return how many entries were restored, and the ones left alone with the reason
   * @throws NotFoundException when the client is not registered
   */
  @Transactional
  public @NotNull ExchangeUndoResultDto undo(
      @NotNull UUID member, @NotNull String clientId, @NotNull Instant since) {
    final ExchangeClient client =
        Entities.require(
            clientRepository.findWithCapabilitiesByClientId(clientId), () -> "Client not found");
    Outcome outcome =
        perform(member, clientId, since, null, null, orgUnitService.logisticianUnits(member));
    auditRecorder.record(
        AuditEventType.EXCHANGE_CHANGES_UNDONE,
        client.getId(),
        client.getClientId(),
        member,
        AuditDetails.of("restored", outcome.restored()).with("skipped", outcome.skipped().size()));
    Map<UUID, String> labels = entryLabels.label(outcome.skippedEntries());
    List<ExchangeUndoResultDto.Skipped> skipped = new ArrayList<>();
    for (int i = 0; i < outcome.skipped().size(); i++) {
      ExchangeJournalEntry entry = outcome.skippedEntries().get(i);
      skipped.add(
          new ExchangeUndoResultDto.Skipped(
              entry.getResource().name(),
              labels.get(entry.getId()),
              outcome.skipped().get(i).reason()));
    }
    return new ExchangeUndoResultDto(outcome.restored(), List.copyOf(skipped));
  }

  /**
   * Undoes a client's writes to one member's entries within an admin's bulk undo, in the caller's
   * transaction, and audits it for that member with the run (REQ-XCH-034).
   *
   * @param member the member
   * @param client the client
   * @param since the start of the span, already clamped to the retention
   * @param installationKey the one installation to undo, or {@code null} for all
   * @param resource the one resource to undo, or {@code null} for all
   * @param runId the bulk undo run
   * @return how many entries were restored, and the ones left alone
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Outcome undoWithinRun(
      @NotNull UUID member,
      @NotNull ExchangeClient client,
      @NotNull Instant since,
      @Nullable String installationKey,
      @Nullable ExchangeResource resource,
      @NotNull UUID runId) {
    Outcome outcome = perform(member, client.getClientId(), since, installationKey, resource, null);
    auditRecorder.record(
        AuditEventType.EXCHANGE_CHANGES_UNDONE,
        client.getId(),
        client.getClientId(),
        member,
        AuditDetails.of("restored", outcome.restored())
            .with("skipped", outcome.skipped().size())
            .with("run", runId));
    return outcome;
  }

  /**
   * Restores every entry in scope and refreshes the member's pages for what changed.
   *
   * @param member the member
   * @param clientId the client
   * @param since the start of the span; clamped to the retention
   * @param installationKey the one installation to undo, or {@code null} for all
   * @param resource the one resource to undo, or {@code null} for all
   * @param pools the units the member is a Logistician and direct member of now, whose org-stock
   *     entries alone the member may set back; {@code null} for an admin's run, which sets back
   *     every one
   * @return the outcome
   */
  private @NotNull Outcome perform(
      @NotNull UUID member,
      @NotNull String clientId,
      @NotNull Instant since,
      @Nullable String installationKey,
      @Nullable ExchangeResource resource,
      @Nullable Set<UUID> pools) {
    Instant floor = clock.instant().minus(retention.maxAge());
    Instant from = since.isBefore(floor) ? floor : since;
    Map<String, List<ExchangeJournalEntry>> groups = new TreeMap<>();
    for (ExchangeJournalEntry entry : journalRepository.findUndoable(member, clientId, from)) {
      if ((installationKey != null && !installationKey.equals(entry.getInstallationKey()))
          || (resource != null && resource != entry.getResource())) {
        continue;
      }
      groups
          .computeIfAbsent(
              entry.getResource().name() + ':' + entry.getEntityKey(), _ -> new ArrayList<>())
          .add(entry);
    }
    List<String> lots =
        groups.values().stream()
            .map(List::getFirst)
            .filter(entry -> entry.getResource() == ExchangeResource.STOCK)
            .map(ExchangeJournalEntry::getEntityKey)
            .toList();
    List<List<ExchangeJournalEntry>> orgGroups =
        groups.values().stream()
            .filter(group -> group.getFirst().getResource() == ExchangeResource.ORG_STOCK)
            .toList();
    Map<List<ExchangeJournalEntry>, String> orgStock =
        orgStockJournal.lockAndRestore(member, lots, orgGroups, pools);
    int restored = 0;
    List<ExchangeJournalEntry> skippedEntries = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    Set<ExchangeResource> touched = EnumSet.noneOf(ExchangeResource.class);
    List<UUID> undone = new ArrayList<>();
    ExchangeEntryRestorer.UndoScope scope =
        new ExchangeEntryRestorer.UndoScope(
            member,
            clientId,
            SingletonSupplier.of(
                () -> membershipQueryService.findDirectMembershipOrgUnitIds(member)));
    for (List<ExchangeJournalEntry> group : groups.values()) {
      ExchangeJournalEntry newest = group.getFirst();
      ExchangeResource entryResource = newest.getResource();
      String reason =
          entryResource == ExchangeResource.ORG_STOCK
              ? orgStock.get(group)
              : restorer(entryResource).restore(scope, group);
      if (reason != null) {
        skippedEntries.add(newest);
        skipped.add(new Skipped(newest.getId(), entryResource, reason));
        counter(clientId, entryResource, SKIPPED).increment();
        continue;
      }
      group.forEach(entry -> undone.add(entry.getId()));
      restored++;
      touched.add(entryResource);
      counter(clientId, entryResource, RESTORED).increment();
    }
    if (!undone.isEmpty()) {
      journalRepository.markUndone(undone, clock.instant());
    }
    touched.forEach(entryResource -> refresh(member, entryResource));
    return new Outcome(restored, List.copyOf(skipped), List.copyOf(skippedEntries));
  }

  /**
   * Returns the restorer of a resource whose entries are set back one by one.
   *
   * @param resource the resource
   * @return its restorer
   * @throws IllegalStateException for the org stock, whose entries are restored together
   */
  private @NotNull ExchangeEntryRestorer restorer(@NotNull ExchangeResource resource) {
    return switch (resource) {
      case BLUEPRINT -> blueprintJournal;
      case STOCK -> stockJournal;
      case SHIP -> shipJournal;
      case ORG_STOCK -> throw new IllegalStateException("Org-stock entries are restored together");
    };
  }

  /**
   * Refreshes the member's open pages for a resource once the undo has committed.
   *
   * @param member the member
   * @param resource the resource
   */
  private void refresh(@NotNull UUID member, @NotNull ExchangeResource resource) {
    switch (resource) {
      case BLUEPRINT -> liveSync.blueprintsChanged(member);
      case STOCK, ORG_STOCK -> liveSync.stockChanged(false);
      case SHIP -> liveSync.hangarChanged(member);
      case null -> throw new NullPointerException("resource");
    }
  }

  /**
   * Returns the undo counter of one client, resource and outcome.
   *
   * @param clientId the client, a registered one
   * @param resource the resource
   * @param outcome {@code restored} or {@code skipped}
   * @return the counter
   */
  private @NotNull Counter counter(
      @NotNull String clientId, @NotNull ExchangeResource resource, @NotNull String outcome) {
    return meterRegistry.counter(
        MetricNames.EXCHANGE_UNDO,
        MetricNames.TAG_CLIENT_ID,
        clientId,
        MetricNames.TAG_RESOURCE,
        resource.name().toLowerCase(Locale.ROOT),
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  /**
   * What an undo did for one member.
   *
   * @param restored how many entries were restored
   * @param skipped the entries left alone, in the order they were met
   * @param skippedEntries the newest journal entry of each skipped entry, in the same order
   */
  public record Outcome(
      int restored,
      @NotNull @Unmodifiable List<Skipped> skipped,
      @NotNull @Unmodifiable List<ExchangeJournalEntry> skippedEntries) {}

  /**
   * One entry an undo left alone.
   *
   * @param journalEntryId the entry's newest journal entry
   * @param resource the entry's resource
   * @param reason {@code CHANGED_AFTERWARDS} or {@code GONE}
   */
  public record Skipped(
      @NotNull UUID journalEntryId, @NotNull ExchangeResource resource, @NotNull String reason) {}
}
