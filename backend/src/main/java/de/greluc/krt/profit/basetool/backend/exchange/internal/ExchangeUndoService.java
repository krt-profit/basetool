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
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCommands;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipCommands;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.function.SingletonSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeClientRepository clientRepository;
  private final PersonalBlueprintRepository blueprintRepository;
  private final PersonalBlueprintCommands blueprintService;
  private final ExchangeStockWriteService stockWriteService;
  private final StockCommands stockCommands;
  private final PoolEntryCommands poolEntryCommands;
  private final ExchangeOrgUnitService orgUnitService;
  private final ShipRepository shipRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final LocationRepository locationRepository;
  private final ShipCommands shipCommands;
  private final OrgUnitMembershipQueryService membershipQueryService;
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
    Map<List<ExchangeJournalEntry>, String> orgStock;
    if (orgGroups.isEmpty()) {
      stockCommands.lockLots(member, lots);
      orgStock = Map.of();
    } else {
      orgStock = restoreOrgStock(member, lots, orgGroups, pools);
    }
    int restored = 0;
    List<ExchangeJournalEntry> skippedEntries = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    Set<ExchangeResource> touched = EnumSet.noneOf(ExchangeResource.class);
    List<UUID> undone = new ArrayList<>();
    Supplier<Set<UUID>> memberships =
        SingletonSupplier.of(() -> membershipQueryService.findDirectMembershipOrgUnitIds(member));
    for (List<ExchangeJournalEntry> group : groups.values()) {
      ExchangeJournalEntry newest = group.getFirst();
      ExchangeResource entryResource = newest.getResource();
      String reason =
          entryResource == ExchangeResource.ORG_STOCK
              ? orgStock.get(group)
              : restore(member, clientId, group, memberships);
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
   * Restores one entry to its state before the client's first write in the span; an entry whose
   * latest change-log entry is missing counts as changed afterwards, because nothing proves it was
   * not.
   *
   * @param member the member
   * @param clientId the client
   * @param group the client's writes to the entry, newest first
   * @param memberships the member's direct org-unit memberships, loaded on first use
   * @return {@code null} when restored, else the reason it was left alone
   */
  private @Nullable String restore(
      @NotNull UUID member,
      @NotNull String clientId,
      @NotNull List<ExchangeJournalEntry> group,
      @NotNull Supplier<Set<UUID>> memberships) {
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
            case SHIP -> restoreShip(member, newest.getEntityKey(), before, memberships);
            case ORG_STOCK ->
                throw new IllegalStateException("Org-stock entries are restored together");
          };
      if (!done) {
        return GONE;
      }
    }
    unlink(member, clientId, links);
    return null;
  }

  /**
   * Sets every org-stock entry in scope back to its state before the client's first write in the
   * span, in one call that first locks them all together with the member's own lots the undo sets
   * back afterwards; an entry of a pool the member may no longer write is left alone as {@code
   * GONE}.
   *
   * @param member the member whose writes are undone
   * @param lots the member's own lots the undo sets back afterwards
   * @param groups the client's writes to each entry, newest first
   * @param pools the units the member is a Logistician and direct member of now, or {@code null}
   *     when every pool may be set back
   * @return the reason each group was left alone, or {@code null} for a restored one, by group
   */
  private @NotNull Map<List<ExchangeJournalEntry>, String> restoreOrgStock(
      @NotNull UUID member,
      @NotNull List<String> lots,
      @NotNull List<List<ExchangeJournalEntry>> groups,
      @Nullable Set<UUID> pools) {
    IdentityHashMap<List<ExchangeJournalEntry>, String> reasons = new IdentityHashMap<>();
    List<PoolEntryCommands.PoolRestore> restores = new ArrayList<>();
    List<List<ExchangeJournalEntry>> restoring = new ArrayList<>();
    for (List<ExchangeJournalEntry> group : groups) {
      IdentityHashMap<ExchangeJournalEntry, WriteOrder> places = new IdentityHashMap<>();
      group.forEach(entry -> places.put(entry, writeOrder(entry)));
      List<ExchangeJournalEntry> ordered =
          group.stream().sorted(Comparator.comparing(places::get)).toList();
      PoolEntryCommands.PoolEntryState target = poolState(ordered.getFirst().getBeforeState());
      PoolEntryCommands.PoolEntryState expected = poolState(ordered.getLast().getAfterState());
      if (pools != null && !(inPools(target, pools) && inPools(expected, pools))) {
        reasons.put(group, GONE);
        continue;
      }
      restores.add(
          new PoolEntryCommands.PoolRestore(
              UUID.fromString(group.getFirst().getEntityKey()), expected, target));
      restoring.add(group);
    }
    List<PoolEntryCommands.PoolRestoreResult> results =
        poolEntryCommands.restore(Map.of(member, lots), restores);
    for (int i = 0; i < results.size(); i++) {
      reasons.put(
          restoring.get(i),
          switch (results.get(i)) {
            case RESTORED -> null;
            case CHANGED_AFTERWARDS -> CHANGED_AFTERWARDS;
            case GONE -> GONE;
          });
    }
    return reasons;
  }

  /**
   * Orders an entry's journaled writes: by transaction, and within one by the version the entry had
   * before the write, since a change set's rows share their recording time.
   *
   * @param entry a journaled write
   * @return its place, the earliest first
   */
  private @NotNull WriteOrder writeOrder(@NotNull ExchangeJournalEntry entry) {
    PoolEntryCommands.PoolEntryState before = poolState(entry.getBeforeState());
    return new WriteOrder(entry.getTx(), before == null ? Long.MIN_VALUE : before.version());
  }

  /**
   * Whether a journaled state lies in one of the given pools.
   *
   * @param state the state, or {@code null} for an absent entry
   * @param pools the pools
   * @return {@code true} for an absent entry or one in those pools
   */
  private static boolean inPools(
      @Nullable PoolEntryCommands.PoolEntryState state, @NotNull Set<UUID> pools) {
    return state == null || pools.contains(state.poolUnitId());
  }

  /**
   * Reads a journaled org-stock state.
   *
   * @param json the state, or {@code null}
   * @return the state, or {@code null} for none
   */
  private @Nullable PoolEntryCommands.PoolEntryState poolState(@Nullable String json) {
    return json == null
        ? null
        : objectMapper.readValue(json, PoolEntryCommands.PoolEntryState.class);
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
    String unit = text(before, "unit");
    if (before == null || before.get("quantity") == null || unit == null) {
      return false;
    }
    BigDecimal quantity = before.get("quantity").decimalValue();
    return stockWriteService.restoreLot(member, lotKey, quantity, unit);
  }

  /**
   * Removes, updates or recreates a ship to match its earlier state, including its earlier org unit
   * while the member still directly belongs to it; a recreated ship gets a new id and does not
   * rejoin mission units, and one whose unit is not restored is stamped like a client's create.
   *
   * @param member the member
   * @param rawId the ship's id
   * @param before the state before the client's first write, or {@code null} when it was absent
   * @param memberships the member's direct org-unit memberships, loaded on first use
   * @return whether the ship could be restored
   */
  private boolean restoreShip(
      @NotNull UUID member,
      @NotNull String rawId,
      @Nullable JsonNode before,
      @NotNull Supplier<Set<UUID>> memberships) {
    UUID shipId = UUID.fromString(rawId);
    Optional<Ship> ship = shipRepository.lockOwnedById(shipId, member);
    if (ship.isEmpty() && shipRepository.existsById(shipId)) {
      return false;
    }
    if (before == null) {
      if (ship.isPresent()) {
        shipCommands.deleteShip(member, shipId);
      }
      return true;
    }
    UUID shipType = UUID.fromString(text(before, "shipType"));
    String location = text(before, "location");
    UUID locationId = location == null ? null : UUID.fromString(location);
    if (!shipTypeRepository.existsById(shipType)
        || (locationId != null && !locationRepository.existsById(locationId))) {
      return false;
    }
    JsonNode fitted = before.get("fitted");
    ShipRequestDto dto =
        new ShipRequestDto(
            text(before, "name"),
            shipType,
            Objects.requireNonNull(text(before, "insurance")),
            locationId,
            fitted != null && fitted.asBoolean(),
            ship.map(Ship::getVersion).orElse(null),
            null);
    EarlierUnit unit = earlierUnit(before, memberships);
    if (ship.isEmpty()) {
      if (unit == null) {
        shipCommands.addShipForClient(member, dto);
      } else {
        shipCommands.addShipForClientInOrgUnit(member, dto, unit.orgUnitId());
      }
      return true;
    }
    Ship current = ship.get();
    UUID currentUnit =
        current.getOwningOrgUnit() == null ? null : current.getOwningOrgUnit().getId();
    long unitVersion = current.getOwningOrgUnitVersion();
    shipCommands.updateShip(member, shipId, dto);
    if (unit != null && !Objects.equals(unit.orgUnitId(), currentUnit)) {
      shipCommands.changeShipOrgUnit(member, shipId, unit.orgUnitId(), unitVersion);
    }
    return true;
  }

  /**
   * Reads the org unit a ship had before the client's first write, when it may be restored.
   *
   * @param before the ship's earlier state
   * @param memberships the member's direct org-unit memberships, loaded on first use
   * @return the earlier unit, whose id is {@code null} when the ship had none; {@code null} when
   *     the state has no {@code orgUnit} key, names no valid unit id, or names a unit the member no
   *     longer directly belongs to
   */
  private static @Nullable EarlierUnit earlierUnit(
      @NotNull JsonNode before, @NotNull Supplier<Set<UUID>> memberships) {
    if (!before.has("orgUnit")) {
      return null;
    }
    String raw = text(before, "orgUnit");
    if (raw == null) {
      return new EarlierUnit(null);
    }
    UUID orgUnitId;
    try {
      orgUnitId = UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      return null;
    }
    return memberships.get().contains(orgUnitId) ? new EarlierUnit(orgUnitId) : null;
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
                .computeIfAbsent(entry.getInstallationKey(), _ -> new ArrayList<>())
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
      case STOCK, ORG_STOCK -> liveSync.stockChanged(false);
      case SHIP -> liveSync.hangarChanged(member);
      case null -> throw new NullPointerException("resource");
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

  /**
   * The org unit an undo puts a ship back in.
   *
   * @param orgUnitId the unit, or {@code null} for none
   */
  private record EarlierUnit(@Nullable UUID orgUnitId) {}

  /**
   * The place of a journaled org-stock write among the writes to the same entry.
   *
   * @param tx the writing transaction
   * @param versionBefore the entry's version before the write, the lowest for a creation
   */
  private record WriteOrder(long tx, long versionBefore) implements Comparable<WriteOrder> {

    @Override
    public int compareTo(@NotNull WriteOrder other) {
      int byTx = Long.compare(tx, other.tx);
      return byTx != 0 ? byTx : Long.compare(versionBefore, other.versionBefore);
    }
  }
}
