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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.HangarService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Undoes a client's writes to the member's entries since a point in time (REQ-XCH-022): each entry
 * goes back to its state before the client's first write in that span, unless something else has
 * changed it since the client's last one; Materialbörse offers a book-out lowered stay lowered. An
 * admin's bulk undo runs the same per member (REQ-XCH-034).
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
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeClientRepository clientRepository;
  private final PersonalBlueprintRepository blueprintRepository;
  private final PersonalBlueprintService blueprintService;
  private final ExchangeStockWriteService stockWriteService;
  private final ShipRepository shipRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final LocationRepository locationRepository;
  private final HangarService hangarService;
  private final ExchangeShipLinkRepository linkRepository;
  private final AuditRecorder auditRecorder;
  private final ExchangeLiveSync liveSync;
  private final ExchangeEntryLabels entryLabels;
  private final ExchangeChangeRetentionProperties retention;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
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
    Outcome outcome = perform(member, clientId, since, null, null);
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
    Outcome outcome = perform(member, client.getClientId(), since, installationKey, resource);
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
   * @return the outcome
   */
  private @NotNull Outcome perform(
      @NotNull UUID member,
      @NotNull String clientId,
      @NotNull Instant since,
      @Nullable String installationKey,
      @Nullable ExchangeResource resource) {
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
              entry.getResource().name() + ':' + entry.getEntityKey(), k -> new ArrayList<>())
          .add(entry);
    }
    stockWriteService.lockLots(
        member,
        groups.values().stream()
            .map(List::getFirst)
            .filter(entry -> entry.getResource() == ExchangeResource.STOCK)
            .map(ExchangeJournalEntry::getEntityKey)
            .toList());
    int restored = 0;
    List<ExchangeJournalEntry> skippedEntries = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    Set<ExchangeResource> touched = EnumSet.noneOf(ExchangeResource.class);
    Instant now = clock.instant();
    for (List<ExchangeJournalEntry> group : groups.values()) {
      ExchangeJournalEntry newest = group.getFirst();
      ExchangeResource entryResource = newest.getResource();
      String reason = restore(member, clientId, group);
      if (reason != null) {
        skippedEntries.add(newest);
        skipped.add(new Skipped(newest.getId(), entryResource, reason));
        counter(clientId, entryResource, SKIPPED).increment();
        continue;
      }
      group.forEach(entry -> entry.setUndoneAt(now));
      restored++;
      touched.add(entryResource);
      counter(clientId, entryResource, RESTORED).increment();
    }
    touched.forEach(entryResource -> refresh(member, entryResource));
    return new Outcome(restored, List.copyOf(skipped), List.copyOf(skippedEntries));
  }

  /**
   * Restores one entry to its state before the client's first write in the span; an entry whose
   * latest change-log entry is missing counts as changed afterwards, because nothing proves it was
   * not.
   *
   * @param member the member
   * @param clientId the client
   * @param group the client's writes to the entry, newest first
   * @return {@code null} when restored, else the reason it was left alone
   */
  private @Nullable String restore(
      @NotNull UUID member, @NotNull String clientId, @NotNull List<ExchangeJournalEntry> group) {
    List<ExchangeJournalEntry> data =
        group.stream().filter(e -> e.getAction() != ExchangeJournalAction.SHIP_LINK).toList();
    List<ExchangeJournalEntry> links =
        group.stream().filter(e -> e.getAction() == ExchangeJournalAction.SHIP_LINK).toList();
    if (!data.isEmpty()) {
      ExchangeJournalEntry newest = data.getFirst();
      ExchangeResource resource = newest.getResource();
      String feedKey =
          resource == ExchangeResource.BLUEPRINT
              ? ExchangeBlueprintFeedService.keyOf(newest.getEntityKey())
              : newest.getEntityKey();
      Optional<ExchangeChange> latest =
          changeRepository.findLatestForKey(member, resource.name(), feedKey);
      if (latest.isEmpty() || latest.get().getTx() != newest.getTx()) {
        return CHANGED_AFTERWARDS;
      }
      JsonNode before = parse(data.getLast().getBeforeState());
      boolean done =
          switch (resource) {
            case BLUEPRINT -> restoreBlueprint(member, newest.getEntityKey(), before);
            case STOCK -> restoreLot(member, newest.getEntityKey(), before);
            case SHIP -> restoreShip(member, newest.getEntityKey(), before);
          };
      if (!done) {
        return GONE;
      }
    }
    unlink(member, clientId, links);
    return null;
  }

  /**
   * Adds or removes a blueprint to match its earlier state.
   *
   * @param member the member
   * @param productKey the product
   * @param before the state before the client's first write, or {@code null} when it was absent
   * @return always {@code true}
   */
  private boolean restoreBlueprint(
      @NotNull UUID member, @NotNull String productKey, @Nullable JsonNode before) {
    Optional<PersonalBlueprint> owned =
        blueprintRepository.findByOwnerUserIdAndProductKey(member, productKey);
    if (before == null) {
      owned.ifPresent(blueprint -> blueprintService.delete(member, blueprint.getId()));
    } else if (owned.isEmpty()) {
      String acquiredAt = text(before, "acquiredAt");
      blueprintService.add(
          member,
          new PersonalBlueprintCreateRequest(
              productKey,
              acquiredAt == null ? null : Instant.parse(acquiredAt),
              text(before, "note")));
    }
    return true;
  }

  /**
   * Sets a lot back to its earlier quantity.
   *
   * @param member the member
   * @param lotKey the lot
   * @param before the state before the client's first write
   * @return whether the lot could be restored
   */
  private boolean restoreLot(
      @NotNull UUID member, @NotNull String lotKey, @Nullable JsonNode before) {
    if (before == null || before.get("quantity") == null || text(before, "unit") == null) {
      return false;
    }
    BigDecimal quantity = before.get("quantity").decimalValue();
    return stockWriteService.restoreLot(member, lotKey, quantity, text(before, "unit"));
  }

  /**
   * Removes, updates or recreates a ship to match its earlier state; a recreated ship gets a new
   * id, does not rejoin mission units and is stamped like a client's create, so a member of several
   * org units gets it without a unit.
   *
   * @param member the member
   * @param rawId the ship's id
   * @param before the state before the client's first write, or {@code null} when it was absent
   * @return whether the ship could be restored
   */
  private boolean restoreShip(
      @NotNull UUID member, @NotNull String rawId, @Nullable JsonNode before) {
    UUID shipId = UUID.fromString(rawId);
    Optional<Ship> ship = shipRepository.lockOwnedById(shipId, member);
    if (ship.isEmpty() && shipRepository.existsById(shipId)) {
      return false;
    }
    if (before == null) {
      if (ship.isPresent()) {
        hangarService.deleteShip(member, shipId);
      }
      return true;
    }
    UUID shipType = UUID.fromString(text(before, "shipType"));
    String location = text(before, "location");
    UUID locationId = location == null ? null : UUID.fromString(location);
    if (!shipTypeRepository.existsById(shipType)
        || locationId != null && !locationRepository.existsById(locationId)) {
      return false;
    }
    JsonNode fitted = before.get("fitted");
    ShipRequestDto dto =
        new ShipRequestDto(
            text(before, "name"),
            shipType,
            text(before, "insurance"),
            locationId,
            fitted != null && fitted.asBoolean(),
            ship.map(Ship::getVersion).orElse(null),
            null);
    if (ship.isPresent()) {
      hangarService.updateShip(member, shipId, dto);
    } else {
      hangarService.addShipForClient(member, dto);
    }
    return true;
  }

  /**
   * Takes back the links the client made in the span and puts back what they replaced, the latter
   * only while the ship is still the member's.
   *
   * @param member the member
   * @param clientId the client
   * @param links the client's link writes to one ship, newest first
   */
  private void unlink(
      @NotNull UUID member, @NotNull String clientId, @NotNull List<ExchangeJournalEntry> links) {
    Map<String, List<ExchangeJournalEntry>> byInstallation = new LinkedHashMap<>();
    links.forEach(
        entry ->
            byInstallation
                .computeIfAbsent(entry.getInstallationKey(), k -> new ArrayList<>())
                .add(entry));
    byInstallation.forEach(
        (installation, entries) -> {
          UUID shipId = UUID.fromString(entries.getFirst().getEntityKey());
          String made = text(parse(entries.getFirst().getAfterState()), "externalId");
          String replaced = text(parse(entries.getLast().getBeforeState()), "externalId");
          linkRepository
              .findByUserIdAndClientIdAndInstallationKeyAndShipId(
                  member, clientId, installation, shipId)
              .filter(link -> link.getExternalId().equals(made))
              .ifPresent(linkRepository::delete);
          linkRepository.flush();
          if (replaced != null
              && shipRepository.existsByIdAndOwnerId(shipId, member)
              && linkRepository
                  .findByUserIdAndClientIdAndInstallationKeyAndExternalId(
                      member, clientId, installation, replaced)
                  .isEmpty()) {
            linkRepository.save(
                ExchangeShipLink.builder()
                    .id(UUID.randomUUID())
                    .userId(member)
                    .clientId(clientId)
                    .installationKey(installation)
                    .externalId(replaced)
                    .shipId(shipId)
                    .createdAt(clock.instant())
                    .build());
          }
        });
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
      case STOCK -> liveSync.stockChanged(false);
      default -> liveSync.hangarChanged(member);
    }
  }

  /**
   * Reads a journaled state.
   *
   * @param json the state, or {@code null}
   * @return the state, or {@code null} for none
   */
  private @Nullable JsonNode parse(@Nullable String json) {
    return json == null ? null : objectMapper.readTree(json);
  }

  /**
   * Reads a text field of a state.
   *
   * @param state the state, or {@code null}
   * @param field the field
   * @return its text, or {@code null} when absent or null
   */
  private static @Nullable String text(@Nullable JsonNode state, @NotNull String field) {
    if (state == null) {
      return null;
    }
    JsonNode value = state.get(field);
    return value == null || value.isNull() ? null : value.asString();
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
