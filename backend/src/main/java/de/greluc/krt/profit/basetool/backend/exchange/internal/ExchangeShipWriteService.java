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

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.AMBIGUOUS;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.REJECTED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.UNCHANGED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.UNMATCHED;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeShipJournal.State;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitChoice;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeShipChangeSet;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipCommands;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a client's ship changes to the member's own ships (REQ-XCH-017, REQ-XCH-021,
 * REQ-XCH-022): installation links first, then creates, updates and removals through the Hangar's
 * own owner path, every entry journaled (ADR-0285).
 */
@Service
@RequiredArgsConstructor
public class ExchangeShipWriteService
    implements ExchangeChangeSetAdapter<ExchangeShipChangeSet, ExchangeShipWriteService.Change> {

  private static final String CLIENT_CHANNEL = "client";
  private static final String LTI = "LTI";

  /** The ship an id points at while an earlier op of the batch still creates it. */
  private static final UUID PENDING = new UUID(0, 0);

  /** The reason of a ship the member does not own or the server does not know. */
  static final String UNMATCHED_REASON = "UNMATCHED";

  /** The reason of a ship type that matches several catalogue entries. */
  static final String AMBIGUOUS_REASON = "AMBIGUOUS";

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = "LOCATION_UNKNOWN";

  /** The refusal of a version the ship no longer has, or of a linked id sent without its ship. */
  static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** The refusal of linking a ship the installation already links to another id. */
  static final String LINK_TARGET_TAKEN = "LINK_TARGET_TAKEN";

  /** The refusal of bringing back a ship removed by another channel or installation. */
  static final String REMOVED_ELSEWHERE = "REMOVED_ELSEWHERE";

  /** The refusal of an org unit that is not one of the member's direct memberships. */
  static final String ORG_UNIT_NOT_ALLOWED = "ORG_UNIT_NOT_ALLOWED";

  /** The refusal of a unit counter the ship no longer has. */
  static final String ORG_UNIT_VERSION_CONFLICT = "ORG_UNIT_VERSION_CONFLICT";

  private final ExchangeResolveService resolveService;
  private final ExchangeLocationResolver locationResolver;
  private final ShipRepository shipRepository;
  private final ExchangeShipLinkRepository linkRepository;
  private final ShipCommands shipCommands;
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeJournalService journalService;
  private final ExchangeMassChangeGuard guard;
  private final ExchangeChangeSetRunner runner;
  private final ExchangeLiveSync liveSync;
  private final OrgUnitMembershipQueryService membershipQueryService;

  /**
   * Plans and applies one change set in one transaction.
   *
   * @param caller the client, installation and member
   * @param changeSet the changes
   * @return the counts, the mission units the removals detached, and the detail of every op that
   *     was not applied
   * @throws BadRequestException when an op lacks a field its kind requires
   * @throws ExchangeProblemException {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} when the batch
   *     removes too much; nothing is written then
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto apply(
      @NotNull ExchangeCaller caller, @NotNull ExchangeShipChangeSet changeSet) {
    return runner.run(caller, this, changeSet, true);
  }

  /**
   * Applies a change set the member confirmed after the mass-change guard held it back, without
   * asking the guard again (REQ-XCH-021).
   *
   * @param caller the client, installation and member the change set was staged for
   * @param changeSet the changes
   * @return the counts and the detail of every op that was not applied
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto applyConfirmed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeShipChangeSet changeSet) {
    return runner.run(caller, this, changeSet, false);
  }

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.SHIP;
  }

  @Override
  public boolean dryRun(@NotNull ExchangeShipChangeSet changeSet) {
    return changeSet.dryRun();
  }

  @Override
  public @Nullable String opId(@NotNull ExchangeShipChangeSet changeSet, int index) {
    return changeSet.ops().get(index).opId();
  }

  /**
   * Counts the batch's removals, and its updates that change both name and type, against the
   * member's ships.
   *
   * @param caller the caller
   * @param changeSet the changes
   * @param plan the plan
   * @return {@code true} when the member must confirm the batch
   */
  @Override
  public boolean holdsBack(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeShipChangeSet changeSet,
      @NotNull List<Planned<Change>> plan) {
    long removals =
        plan.stream()
            .filter(p -> p instanceof Write<Change>(Change change) && isRemoval(change))
            .count();
    return guard.requiresConfirmation(
        caller, ExchangeResource.SHIP, shipRepository.countOwnedBy(caller.member()), removals);
  }

  /**
   * Writes the planned links, creates, updates and removals, counting the mission units the
   * removals detach, and refreshes the member's hangar afterwards.
   *
   * @param caller the caller
   * @param batch the change set's id
   * @param plan the plan
   * @param dryRun whether nothing is written
   * @return the execution
   */
  @Override
  public @NotNull Execution<Change> start(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull List<Planned<Change>> plan,
      boolean dryRun) {
    return new Execution<>() {
      private int detached;

      @Override
      public @NotNull Outcome run(@NotNull Change change) {
        if (!dryRun) {
          detached += execute(caller, batch, change);
        }
        return Outcome.APPLIED;
      }

      @Override
      public @NotNull Effects effects() {
        return new Effects(null, null, detached);
      }

      @Override
      public void announce() {
        liveSync.hangarChanged(caller.member());
      }
    };
  }

  /**
   * Decides every op in order, locking each ship it touches, as if the earlier ops had run.
   *
   * @param caller the caller
   * @param changeSet the changes
   * @return one plan per op
   * @throws BadRequestException when an op lacks a field its kind requires
   */
  @Override
  public @NotNull List<Planned<Change>> plan(
      @NotNull ExchangeCaller caller, @NotNull ExchangeShipChangeSet changeSet) {
    List<ExchangeShipChangeSet.Op> ops = changeSet.ops();
    Map<Integer, ShipTypeMatch> types = resolveTypes(ops);
    Links links = new Links(caller);
    Set<UUID> touched = new HashSet<>();
    Set<UUID> memberships =
        ops.stream().anyMatch(op -> op.orgUnit() != null)
            ? membershipQueryService.findDirectMembershipOrgUnitIds(caller.member())
            : Set.of();
    List<Planned<Change>> plan = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      ExchangeShipChangeSet.Op op = ops.get(i);
      Planned<Change> planned =
          switch (op.op()) {
            case ExchangeShipChangeSet.LINK -> planLink(caller, op, links, touched);
            case ExchangeShipChangeSet.UPSERT ->
                planUpsert(
                    caller, op, Objects.requireNonNull(types.get(i)), links, touched, memberships);
            case ExchangeShipChangeSet.REMOVE -> planRemove(caller, op, touched);
            default -> throw new IllegalArgumentException("Unknown ship operation: " + op.op());
          };
      plan.add(planned);
    }
    return plan;
  }

  /**
   * Plans a link of the installation's id to a server ship.
   *
   * @param caller the caller
   * @param op the op
   * @param links the installation's links as the earlier ops leave them
   * @param touched the ships the earlier ops change
   * @return the plan
   */
  private @NotNull Planned<Change> planLink(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeShipChangeSet.Op op,
      @NotNull Links links,
      @NotNull Set<UUID> touched) {
    String externalId = require(op.externalId(), "externalId");
    Optional<Ship> ship = ownShip(caller, require(op.shipId(), "shipId"), touched);
    if (ship.isEmpty()) {
      return ExchangeChangeSetAdapter.skip(UNMATCHED, UNMATCHED_REASON);
    }
    UUID shipId = ship.get().getId();
    String linked = links.externalOf(shipId);
    if (externalId.equals(linked)) {
      return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
    }
    if (linked != null) {
      return ExchangeChangeSetAdapter.skip(REJECTED, LINK_TARGET_TAKEN);
    }
    links.link(externalId, shipId);
    return ExchangeChangeSetAdapter.write(new Link(externalId, shipId));
  }

  /**
   * Plans a create or an update.
   *
   * @param caller the caller
   * @param op the op
   * @param type the resolved ship type, or the result that ends the op
   * @param links the installation's links as the earlier ops leave them
   * @param touched the ships the earlier ops change
   * @param memberships the member's direct org-unit memberships, empty when no op names a unit
   * @return the plan
   */
  private @NotNull Planned<Change> planUpsert(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeShipChangeSet.Op op,
      @NotNull ShipTypeMatch type,
      @NotNull Links links,
      @NotNull Set<UUID> touched,
      @NotNull Set<UUID> memberships) {
    String externalId = require(op.externalId(), "externalId");
    String insurance = insurance(require(op.insurance(), "insurance"));
    if (type.result() != null) {
      return ExchangeChangeSetAdapter.skip(type.result(), type.reason());
    }
    UUID locationId = null;
    if (op.location() != null) {
      Optional<Location> location = locationResolver.resolve(op.location());
      if (location.isEmpty()) {
        return ExchangeChangeSetAdapter.skip(REJECTED, LOCATION_UNKNOWN);
      }
      locationId = location.get().getId();
    }
    UnitTarget unit = unitTarget(op);
    if (op.shipId() == null) {
      if (!unitAllowed(unit, memberships)) {
        return ExchangeChangeSetAdapter.skip(REJECTED, ORG_UNIT_NOT_ALLOWED);
      }
      if (links.shipOf(externalId) != null) {
        return ExchangeChangeSetAdapter.skip(REJECTED, VERSION_CONFLICT);
      }
      links.claim(externalId);
      return ExchangeChangeSetAdapter.write(
          new Create(
              externalId,
              new ShipRequestDto(
                  op.name(),
                  Objects.requireNonNull(type.shipTypeId()),
                  insurance,
                  locationId,
                  Boolean.TRUE.equals(op.fitted()),
                  null,
                  null),
              unit));
    }
    UUID shipId = parse(op.shipId());
    Optional<Ship> locked = lockOwn(caller, shipId);
    if (locked.isEmpty()) {
      return shipId != null && shipRepository.existsById(shipId)
          ? ExchangeChangeSetAdapter.skip(UNMATCHED, UNMATCHED_REASON)
          : planReturn(
              caller, op, externalId, shipId, type, insurance, locationId, links, memberships);
    }
    if (unit != null && !unit.valid()) {
      return ExchangeChangeSetAdapter.skip(REJECTED, ORG_UNIT_NOT_ALLOWED);
    }
    Ship ship = locked.get();
    UUID ownedId = Objects.requireNonNull(shipId);
    if (touched.contains(ownedId)) {
      return ExchangeChangeSetAdapter.skip(REJECTED, VERSION_CONFLICT);
    }
    if (op.version() == null || !op.version().equals(ship.getVersion())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, VERSION_CONFLICT);
    }
    String linked = links.externalOf(ownedId);
    if (linked != null && !linked.equals(externalId)) {
      return ExchangeChangeSetAdapter.skip(REJECTED, LINK_TARGET_TAKEN);
    }
    UnitMove move =
        unit == null || Objects.equals(unit.orgUnitId(), ExchangeShipJournal.orgUnitIdOf(ship))
            ? null
            : new UnitMove(unit.orgUnitId(), ship.getOwningOrgUnitVersion());
    if (move != null && !unitAllowed(unit, memberships)) {
      return ExchangeChangeSetAdapter.skip(REJECTED, ORG_UNIT_NOT_ALLOWED);
    }
    if (move != null && !Objects.equals(op.orgUnitVersion(), move.expectedVersion())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, ORG_UNIT_VERSION_CONFLICT);
    }
    ShipRequestDto dto =
        new ShipRequestDto(
            op.name(),
            Objects.requireNonNull(type.shipTypeId()),
            insurance,
            locationId,
            op.fitted() == null ? ship.isFitted() : op.fitted(),
            ship.getVersion(),
            null);
    boolean changes = !State.of(ship).synced().equals(State.of(dto));
    if (!changes && move == null && linked != null) {
      return ExchangeChangeSetAdapter.skip(UNCHANGED, null);
    }
    touched.add(ownedId);
    links.link(externalId, ownedId);
    boolean removal =
        !Objects.equals(ship.getName(), dto.name())
            && !Objects.equals(ship.getShipType().getId(), dto.shipTypeId());
    return ExchangeChangeSetAdapter.write(
        new Update(externalId, ownedId, State.of(ship), dto, changes, removal, move));
  }

  /**
   * Plans an upsert that names a ship the server no longer has: brought back when this installation
   * removed it or the member agreed, refused when it was removed elsewhere.
   *
   * @param caller the caller
   * @param op the op
   * @param externalId the installation's id
   * @param shipId the named ship, or {@code null} when the id is no ship id
   * @param type the resolved ship type
   * @param insurance the stored insurance
   * @param locationId the location, or {@code null}
   * @param links the installation's links as the earlier ops leave them
   * @param memberships the member's direct org-unit memberships, empty when no op names a unit
   * @return the plan
   */
  private @NotNull Planned<Change> planReturn(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeShipChangeSet.Op op,
      @NotNull String externalId,
      @Nullable UUID shipId,
      @NotNull ShipTypeMatch type,
      @NotNull String insurance,
      @Nullable UUID locationId,
      @NotNull Links links,
      @NotNull Set<UUID> memberships) {
    Optional<ExchangeChange> tombstone =
        shipId == null
            ? Optional.empty()
            : changeRepository.findLatestForKey(
                caller.member(), ExchangeResource.SHIP.name(), shipId.toString());
    if (tombstone.isEmpty()) {
      return ExchangeChangeSetAdapter.skip(UNMATCHED, UNMATCHED_REASON);
    }
    if (!Boolean.TRUE.equals(op.override()) && !writtenBy(caller, tombstone.get())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, REMOVED_ELSEWHERE);
    }
    UnitTarget unit = unitTarget(op);
    if (!unitAllowed(unit, memberships)) {
      return ExchangeChangeSetAdapter.skip(REJECTED, ORG_UNIT_NOT_ALLOWED);
    }
    UUID current = links.shipOf(externalId);
    if (current != null) {
      return ExchangeChangeSetAdapter.skip(REJECTED, VERSION_CONFLICT);
    }
    links.claim(externalId);
    return ExchangeChangeSetAdapter.write(
        new Create(
            externalId,
            new ShipRequestDto(
                op.name(),
                Objects.requireNonNull(type.shipTypeId()),
                insurance,
                locationId,
                Boolean.TRUE.equals(op.fitted()),
                null,
                null),
            unit));
  }

  /**
   * Plans a removal.
   *
   * @param caller the caller
   * @param op the op
   * @param touched the ships the earlier ops change
   * @return the plan
   */
  private @NotNull Planned<Change> planRemove(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeShipChangeSet.Op op,
      @NotNull Set<UUID> touched) {
    String rawId = require(op.shipId(), "shipId");
    Long version = require(op.version(), "version");
    UUID shipId = parse(rawId);
    Optional<Ship> locked = lockOwn(caller, shipId);
    if (locked.isEmpty()) {
      boolean known =
          shipId != null
              && !shipRepository.existsById(shipId)
              && changeRepository
                  .findLatestForKey(caller.member(), ExchangeResource.SHIP.name(), rawId)
                  .isPresent();
      return known
          ? ExchangeChangeSetAdapter.skip(UNCHANGED, null)
          : ExchangeChangeSetAdapter.skip(UNMATCHED, UNMATCHED_REASON);
    }
    Ship ship = locked.get();
    UUID ownedId = Objects.requireNonNull(shipId);
    if (touched.contains(ownedId) || !version.equals(ship.getVersion())) {
      return ExchangeChangeSetAdapter.skip(REJECTED, VERSION_CONFLICT);
    }
    touched.add(ownedId);
    return ExchangeChangeSetAdapter.write(new Remove(ownedId, State.of(ship)));
  }

  /**
   * Writes one planned change and journals it.
   *
   * @param caller the caller
   * @param batch the change set's id
   * @param planned the change
   * @return the mission units a removal detached, otherwise 0
   */
  private int execute(
      @NotNull ExchangeCaller caller, @NotNull UUID batch, @NotNull Change planned) {
    return switch (planned) {
      case Link link -> {
        String before = relink(caller, link.externalId(), link.shipId());
        journalService.record(
            caller,
            batch,
            ExchangeJournalAction.SHIP_LINK,
            link.shipId().toString(),
            false,
            before == null ? null : ExchangeShipJournal.linkState(before),
            ExchangeShipJournal.linkState(link.externalId()));
        yield 0;
      }
      case Create create -> {
        Ship saved =
            create.unit() == null
                ? shipCommands.addShipForClient(caller.member(), create.dto())
                : shipCommands.addShipForClientInOrgUnit(
                    caller.member(), create.dto(), create.unit().orgUnitId());
        relink(caller, create.externalId(), saved.getId());
        journalService.record(
            caller,
            batch,
            ExchangeJournalAction.SHIP_UPSERT,
            saved.getId().toString(),
            false,
            null,
            State.of(saved).toJournal());
        yield 0;
      }
      case Update update -> {
        State after = update.before();
        if (update.changes()) {
          after = State.of(shipCommands.updateShip(caller.member(), update.shipId(), update.dto()));
        }
        if (update.move() != null) {
          after =
              State.of(
                  shipCommands.changeShipOrgUnit(
                      caller.member(),
                      update.shipId(),
                      update.move().orgUnitId(),
                      update.move().expectedVersion()));
        }
        relink(caller, update.externalId(), update.shipId());
        journalService.record(
            caller,
            batch,
            ExchangeJournalAction.SHIP_UPSERT,
            update.shipId().toString(),
            update.removal(),
            update.before().toJournal(),
            after.toJournal());
        yield 0;
      }
      case Remove remove -> {
        int detached = shipCommands.deleteShip(caller.member(), remove.shipId());
        journalService.record(
            caller,
            batch,
            ExchangeJournalAction.SHIP_REMOVE,
            remove.shipId().toString(),
            true,
            remove.before().toJournal(),
            null);
        yield detached;
      }
    };
  }

  /**
   * Points the installation's id at a ship, moving it off any other ship.
   *
   * @param caller the caller
   * @param externalId the installation's id
   * @param shipId the ship
   * @return the id the ship was linked to before, or {@code null}
   */
  private @Nullable String relink(
      @NotNull ExchangeCaller caller, @NotNull String externalId, @NotNull UUID shipId) {
    Optional<ExchangeShipLink> ofShip =
        linkRepository.findByUserIdAndClientIdAndInstallationKeyAndShipId(
            caller.member(), caller.clientId(), caller.installationKey(), shipId);
    if (ofShip.filter(ship -> ship.getExternalId().equals(externalId)).isPresent()) {
      return externalId;
    }
    ofShip.ifPresent(linkRepository::delete);
    linkRepository
        .findByUserIdAndClientIdAndInstallationKeyAndExternalId(
            caller.member(), caller.clientId(), caller.installationKey(), externalId)
        .ifPresent(linkRepository::delete);
    linkRepository.flush();
    linkRepository.save(
        ExchangeShipLink.builder()
            .id(UUID.randomUUID())
            .userId(caller.member())
            .clientId(caller.clientId())
            .installationKey(caller.installationKey())
            .externalId(externalId)
            .shipId(shipId)
            .createdAt(Instant.now())
            .build());
    return ofShip.map(ExchangeShipLink::getExternalId).orElse(null);
  }

  /**
   * Resolves the ship type of every upsert in one resolver call.
   *
   * @param ops the ops
   * @return the ship type, or the result that ends the op, by op index
   */
  private @NotNull Map<Integer, ShipTypeMatch> resolveTypes(
      @NotNull List<ExchangeShipChangeSet.Op> ops) {
    List<Integer> indexes = new ArrayList<>();
    List<ExchangeItemRef> refs = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      if (ExchangeShipChangeSet.UPSERT.equals(ops.get(i).op())) {
        indexes.add(i);
        refs.add(require(ops.get(i).shipType(), "shipType"));
      }
    }
    Map<Integer, ShipTypeMatch> byIndex = new HashMap<>();
    if (refs.isEmpty()) {
      return byIndex;
    }
    ExchangeResolveResponse response =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.SHIP_TYPE, refs));
    for (ExchangeResolveResponse.Result result : response.results()) {
      int index = indexes.get(result.index());
      byIndex.put(
          index,
          switch (result.status()) {
            case RESOLVED ->
                new ShipTypeMatch(
                    UUID.fromString(Objects.requireNonNull(result.ref()).bt()), null, null);
            case AMBIGUOUS -> new ShipTypeMatch(null, AMBIGUOUS, AMBIGUOUS_REASON);
            case UNMATCHED -> new ShipTypeMatch(null, UNMATCHED, UNMATCHED_REASON);
          });
    }
    return byIndex;
  }

  /**
   * Finds and locks one of the member's ships that no earlier op of the batch changes.
   *
   * @param caller the caller
   * @param rawId the client's ship id
   * @param touched the ships the earlier ops change
   * @return the ship, or empty when it is unknown, another member's or already changed
   */
  private @NotNull Optional<Ship> ownShip(
      @NotNull ExchangeCaller caller, @NotNull String rawId, @NotNull Set<UUID> touched) {
    UUID shipId = parse(rawId);
    if (shipId == null || touched.contains(shipId)) {
      return Optional.empty();
    }
    return lockOwn(caller, shipId);
  }

  /**
   * Locks a ship only when it is the member's, so another member's ship named in a batch stays
   * unlocked.
   *
   * @param caller the caller
   * @param shipId the ship's id, or {@code null} when the client's id is none
   * @return the ship, locked for this transaction, or empty when it is unknown or another member's
   */
  private @NotNull Optional<Ship> lockOwn(@NotNull ExchangeCaller caller, @Nullable UUID shipId) {
    return shipId == null
        ? Optional.empty()
        : shipRepository.lockOwnedById(shipId, caller.member());
  }

  /**
   * Whether the latest change of an entry came from this installation.
   *
   * @param caller the caller
   * @param change the latest change
   * @return whether the caller's installation wrote it
   */
  private static boolean writtenBy(@NotNull ExchangeCaller caller, @NotNull ExchangeChange change) {
    return CLIENT_CHANNEL.equals(change.getSourceChannel())
        && caller.clientId().equals(change.getSourceClient())
        && caller.installationKey().equals(change.getSourceKey());
  }

  /**
   * Whether a planned change counts against the mass-change guard.
   *
   * @param planned the change
   * @return {@code true} for a removal and for an update that changes both name and type
   */
  private static boolean isRemoval(@NotNull Change planned) {
    return planned instanceof Remove || (planned instanceof Update update && update.removal());
  }

  /**
   * Reads a client's ship id.
   *
   * @param raw the id as sent
   * @return the id, or {@code null} when it is no ship id
   */
  private static @Nullable UUID parse(@NotNull String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * Stores an insurance the way the Hangar does.
   *
   * @param insurance the client's insurance
   * @return {@code LTI} or the months as digits
   * @throws BadRequestException when {@code MONTHS} comes without months
   */
  private static @NotNull String insurance(@NotNull ExchangeShipChangeSet.Insurance insurance) {
    if (LTI.equals(insurance.kind())) {
      return LTI;
    }
    return String.valueOf(require(insurance.months(), "insurance.months"));
  }

  /**
   * Requires a field an op's kind needs.
   *
   * @param value the field
   * @param name the field's name
   * @param <T> the field's type
   * @return the field
   * @throws BadRequestException when it is missing
   */
  private static <T> @NotNull T require(@Nullable T value, @NotNull String name) {
    if (value == null) {
      throw new BadRequestException("Missing " + name);
    }
    return value;
  }

  /**
   * Reads the org unit an upsert names.
   *
   * @param op the upsert
   * @return the target, or {@code null} when the op leaves the unit out
   */
  private static @Nullable UnitTarget unitTarget(@NotNull ExchangeShipChangeSet.Op op) {
    ExchangeOrgUnitChoice orgUnit = op.orgUnit();
    if (orgUnit == null) {
      return null;
    }
    String raw = orgUnit.orgUnitId();
    if (raw == null) {
      return new UnitTarget(null, true);
    }
    UUID orgUnitId = parse(raw);
    return new UnitTarget(orgUnitId, orgUnitId != null);
  }

  /**
   * Whether an upsert's org unit may own the member's ship.
   *
   * @param unit the unit the op names, or {@code null} when it names none
   * @param memberships the member's direct org-unit memberships
   * @return {@code true} when the op names no unit, names none, or names one of the memberships
   */
  private static boolean unitAllowed(@Nullable UnitTarget unit, @NotNull Set<UUID> memberships) {
    if (unit == null) {
      return true;
    }
    UUID orgUnitId = unit.orgUnitId();
    return unit.valid() && (orgUnitId == null || memberships.contains(orgUnitId));
  }

  /** The installation's links as the planned ops leave them. */
  private final class Links {

    /** The caller. */
    private final ExchangeCaller caller;

    /** The ship each id points at after the earlier ops, {@code null} for an id they freed. */
    private final Map<String, UUID> shipByExternal = new HashMap<>();

    /** The id each ship is linked to after the earlier ops, {@code null} for a ship they freed. */
    private final Map<UUID, String> externalByShip = new HashMap<>();

    /** The ids an earlier op creates a ship for. */
    private final Set<String> claimed = new HashSet<>();

    /**
     * Starts from the stored links.
     *
     * @param caller the caller
     */
    private Links(@NotNull ExchangeCaller caller) {
      this.caller = caller;
    }

    /**
     * Returns the ship an id points at.
     *
     * @param externalId the installation's id
     * @return the ship, or {@code null} when the id is free
     */
    private @Nullable UUID shipOf(@NotNull String externalId) {
      if (claimed.contains(externalId)) {
        return PENDING;
      }
      if (shipByExternal.containsKey(externalId)) {
        return shipByExternal.get(externalId);
      }
      return linkRepository
          .findByUserIdAndClientIdAndInstallationKeyAndExternalId(
              caller.member(), caller.clientId(), caller.installationKey(), externalId)
          .map(ExchangeShipLink::getShipId)
          .orElse(null);
    }

    /**
     * Returns the id a ship is linked to.
     *
     * @param shipId the ship
     * @return the id, or {@code null} when the ship is not linked
     */
    private @Nullable String externalOf(@NotNull UUID shipId) {
      if (externalByShip.containsKey(shipId)) {
        return externalByShip.get(shipId);
      }
      return linkRepository
          .findByUserIdAndClientIdAndInstallationKeyAndShipId(
              caller.member(), caller.clientId(), caller.installationKey(), shipId)
          .map(ExchangeShipLink::getExternalId)
          .orElse(null);
    }

    /**
     * Records a link an earlier op makes, freeing what it replaces.
     *
     * @param externalId the installation's id
     * @param shipId the ship
     */
    private void link(@NotNull String externalId, @NotNull UUID shipId) {
      UUID previousShip = shipOf(externalId);
      if (previousShip != null) {
        externalByShip.put(previousShip, null);
      }
      String previousId = externalOf(shipId);
      if (previousId != null) {
        shipByExternal.put(previousId, null);
      }
      shipByExternal.put(externalId, shipId);
      externalByShip.put(shipId, externalId);
    }

    /**
     * Records an id an earlier op creates a ship for.
     *
     * @param externalId the installation's id
     */
    private void claim(@NotNull String externalId) {
      claimed.add(externalId);
    }
  }

  /** One change a planned op writes. */
  sealed interface Change permits Link, Create, Update, Remove {}

  /**
   * Links the installation's id to a ship.
   *
   * @param externalId the installation's id
   * @param shipId the ship
   */
  private record Link(@NotNull String externalId, @NotNull UUID shipId) implements Change {}

  /**
   * Creates a ship and links it.
   *
   * @param externalId the installation's id
   * @param dto the ship
   * @param unit the org unit the op names, or {@code null} to stamp the member's only one
   */
  private record Create(
      @NotNull String externalId, @NotNull ShipRequestDto dto, @Nullable UnitTarget unit)
      implements Change {}

  /**
   * Updates a ship and makes sure it is linked.
   *
   * @param externalId the installation's id
   * @param shipId the ship
   * @param before the ship's state before
   * @param dto the update
   * @param changes whether any synced field changes
   * @param removal whether it counts against the mass-change guard
   * @param move the change of the ship's org unit, or {@code null} when it keeps its unit
   */
  private record Update(
      @NotNull String externalId,
      @NotNull UUID shipId,
      @NotNull State before,
      @NotNull ShipRequestDto dto,
      boolean changes,
      boolean removal,
      @Nullable UnitMove move)
      implements Change {}

  /**
   * The org unit an upsert names.
   *
   * @param orgUnitId the unit, or {@code null} for none or for an id that is no unit id
   * @param valid whether the client's id is a unit id or {@code null}
   */
  private record UnitTarget(@Nullable UUID orgUnitId, boolean valid) {}

  /**
   * A change of a ship's org unit behind its unit counter.
   *
   * @param orgUnitId the new unit, or {@code null} for none
   * @param expectedVersion the unit counter the ship has
   */
  private record UnitMove(@Nullable UUID orgUnitId, long expectedVersion) {}

  /**
   * Removes a ship.
   *
   * @param shipId the ship
   * @param before the ship's state before
   */
  private record Remove(@NotNull UUID shipId, @NotNull State before) implements Change {}

  /**
   * A resolved ship type, or the result that ends the op.
   *
   * @param shipTypeId the ship type, or {@code null}
   * @param result the result that ends the op, or {@code null}
   * @param reason the reason of that result, or {@code null}
   */
  private record ShipTypeMatch(
      @Nullable UUID shipTypeId, @Nullable String result, @Nullable String reason) {}
}
