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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.events.HangarNotices;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDeletionObserver;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the personal hangar (ships per user) and the squadron-wide ship overview.
 *
 * <p>Update and delete require the caller to own the ship. Deleting a ship first detaches it from
 * mission units, which keep their name. Every mutation writes one {@code HANGAR} audit event, and
 * each detached mission unit a {@code MISSION_UNIT_UPDATED} event (REQ-AUDIT-001).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HangarService {

  /** The {@code changeCode} of an admin's notice that a ship moved to another org unit. */
  private static final String ORG_UNIT_CHANGED = "ORG_UNIT_CHANGED";

  private final ShipRepository shipRepository;
  private final UserRepository userRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final LocationRepository locationRepository;
  private final ShipDeletionObserver shipDeletionObserver;
  private final ShipTypeMapper shipTypeMapper;
  private final EntityManager entityManager;
  private final OwnerScopeService ownerScopeService;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final UserService userService;
  private final HangarUnitLiveSync unitLiveSync;

  /**
   * Returns the paged ship list in the caller's squadron scope; an admin without an active squadron
   * pin sees every ship.
   *
   * @param pageable page request
   * @return paged list of ships in the caller's squadron context
   */
  public Page<Ship> getAllShips(@NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return shipRepository.findAllScoped(
        scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable);
  }

  /**
   * Adds a ship to a user's hangar, stamping the owning org unit from the user's membership or the
   * picker output at creation; later membership changes do not move it. A user without membership
   * gets an ownerless personal ship visible only to them.
   *
   * @param userId owning user's id
   * @param dto ship payload (name, type, insurance, fitted, location)
   * @return the persisted ship
   * @throws NotFoundException when the user id does not resolve
   * @throws BadRequestException when the ship type or location id is missing/invalid, or the picker
   *     output references an org unit the user does not belong to
   */
  @Transactional
  public Ship addShip(@NotNull UUID userId, @NotNull ShipRequestDto dto) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return create(
        user,
        dto,
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, dto.owningOrgUnitId()));
  }

  /**
   * Adds a ship a connected application creates for the member without naming a unit (REQ-XCH-017):
   * stamped with the member's only org unit, or none when the member has none or several; otherwise
   * as {@link #addShip(UUID, ShipRequestDto)}.
   *
   * @param userId owning user's id
   * @param dto ship payload (name, type, insurance, fitted, location); its org unit is ignored
   * @return the persisted ship
   * @throws NotFoundException when the user id does not resolve
   * @throws BadRequestException when the ship type or location id is missing or invalid
   */
  @Transactional
  public Ship addShipForClient(@NotNull UUID userId, @NotNull ShipRequestDto dto) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return create(user, dto, ownerScopeService.resolveOrgUnitForClientCreate(user));
  }

  /**
   * Adds a ship a connected application creates for the member in the org unit it names
   * (REQ-XCH-017); otherwise as {@link #addShip(UUID, ShipRequestDto)}.
   *
   * @param userId owning user's id
   * @param dto ship payload (name, type, insurance, fitted, location); its org unit is ignored
   * @param orgUnitId one of the user's direct memberships, or {@code null} for none
   * @return the persisted ship
   * @throws NotFoundException when the user id does not resolve
   * @throws BadRequestException when the unit is not a direct membership of the user, or the ship
   *     type or location id is missing or invalid
   */
  @Transactional
  public Ship addShipForClientInOrgUnit(
      @NotNull UUID userId, @NotNull ShipRequestDto dto, @Nullable UUID orgUnitId) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return create(user, dto, ownerScopeService.resolveOwnerMembershipTarget(user, orgUnitId));
  }

  /**
   * Creates the ship with the given owning org unit and audits it.
   *
   * @param user the owner
   * @param dto ship payload (name, type, insurance, fitted, location)
   * @param owningOrgUnit the stamped org unit, or {@code null} for an ownerless personal ship
   * @return the persisted ship
   * @throws BadRequestException when the ship type or location id is missing or invalid
   */
  private @NotNull Ship create(
      @NotNull User user, @NotNull ShipRequestDto dto, @Nullable OrgUnit owningOrgUnit) {
    Ship ship = new Ship();
    ship.setName(dto.name());
    ship.setInsurance(dto.insurance());
    ship.setFitted(dto.fitted());
    ship.setOwner(user);
    ship.setOwningOrgUnit(owningOrgUnit);
    ship.setShipType(
        shipTypeRepository
            .findById(dto.shipTypeId())
            .orElseThrow(() -> new BadRequestException("ShipType not found")));
    if (dto.locationId() != null) {
      ship.setLocation(
          locationRepository
              .findById(dto.locationId())
              .orElseThrow(() -> new BadRequestException("Location not found")));
    }
    Ship saved = shipRepository.save(ship);
    auditRecorder.record(
        AuditEventType.HANGAR_SHIP_CREATED,
        saved.getId(),
        saved.getShipType().getName(),
        user.getId(),
        AuditDetails.of("shipType", saved.getShipType().getId()));
    unitLiveSync.shipsChanged(Arrays.asList(owningOrgUnit));
    return saved;
  }

  /**
   * Returns paged ship list owned by the user.
   *
   * @param userId owner id
   * @param pageable page request
   * @return paged ship list owned by the user
   */
  public Page<Ship> getMyShips(@NotNull UUID userId, @NotNull Pageable pageable) {
    return shipRepository.findByOwnerId(userId, pageable);
  }

  /**
   * Returns one page of the user's own ships in the personal-hangar order, optionally filtered by
   * ship-type or manufacturer name and by the fitted flag (REQ-HANGAR-002). Ordering and filtering
   * run in the repository across the whole fleet.
   *
   * @param userId owner id; only this user's ships are returned
   * @param search optional ship-type/manufacturer name filter; {@code null}/blank means no filter
   * @param fitted optional fitted filter; {@code null} means fitted and unfitted ships
   * @param pageable page request, unsorted (the query defines the order)
   * @return one ordered, optionally filtered page of the user's ships
   */
  public Page<Ship> getMyShipsFiltered(
      @NotNull UUID userId,
      @Nullable String search,
      @Nullable Boolean fitted,
      @NotNull Pageable pageable) {
    String normalizedSearch =
        search == null || search.isBlank() ? null : LikePatterns.escape(search.trim());
    return shipRepository.findByOwnerIdFiltered(userId, normalizedSearch, fitted, pageable);
  }

  /**
   * Returns the per-ship-type unit overview in the caller's unit-overview scope (REQ-HANGAR-003),
   * optionally with the per-ship owner, location and fitted breakdown. Counts and breakdown use the
   * same {@link ScopePredicate}, so no ship outside the caller's scope is surfaced; whether the
   * caller may see owner details is decided by the controller.
   *
   * @param pageable page request (sortable by {@code shipType.name})
   * @param includeOwnerDetails whether to load the per-ship owner/location/fitted breakdown
   * @param query optional ship-type/manufacturer name filter; {@code null} or blank means no filter
   * @return one page of per-ship-type aggregates
   */
  public Page<SquadronShipOverviewDto> getSquadronOverview(
      Pageable pageable, boolean includeOwnerDetails, String query) {
    ScopePredicate scope = ownerScopeService.currentUnitOverviewScope();
    String normalizedQuery = LikePatterns.escapeNullable(StringNormalization.trimToNull(query));
    Page<Object[]> p =
        shipRepository.countShipsByType(
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            normalizedQuery,
            pageable);

    List<ShipType> types =
        includeOwnerDetails
            ? p.getContent().stream().map(obj -> (ShipType) obj[0]).toList()
            : Collections.emptyList();

    List<Ship> ships =
        includeOwnerDetails && !types.isEmpty()
            ? shipRepository.findByShipTypeInScoped(
                types, scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds())
            : Collections.emptyList();

    Map<UUID, List<Ship>> shipsByType =
        ships.stream().collect(Collectors.groupingBy(s -> s.getShipType().getId()));

    return p.map(obj -> {
      ShipType type = (ShipType) obj[0];
      List<SquadronShipDetailDto> details = null;
      if (includeOwnerDetails) {
        details =
            shipsByType.getOrDefault(type.getId(), List.of()).stream()
                .map(s ->
                    new SquadronShipDetailDto(
                        s.getOwner() != null ? s.getOwner().getEffectiveName() : "Unknown",
                        s.getLocation() != null ? s.getLocation().getName() : null,
                        s.isFitted()))
                .toList();
      }

      return new SquadronShipOverviewDto(
          shipTypeMapper.toDto(type),
          ((Number) obj[1]).longValue(),
          obj[2] != null ? ((Number) obj[2]).longValue() : 0L,
          details);
    });
  }

  /**
   * Updates a ship owned by {@code userId}, checking the optimistic-lock version when {@code
   * dto.version()} is non-null.
   *
   * @param userId calling user's id
   * @param shipId ship primary key
   * @param dto update payload
   * @return the persisted ship
   * @throws NotFoundException when the ship does not exist
   * @throws AccessDeniedException when the calling user is not the owner
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale
   * @throws BadRequestException when the ship type or location id is missing/invalid
   */
  @Transactional
  public Ship updateShip(@NotNull UUID userId, @NotNull UUID shipId, @NotNull ShipRequestDto dto) {
    Ship ship = Entities.require(shipRepository.findById(shipId), "Ship not found");

    OptimisticLock.checkOptionalClient(ship.getVersion(), dto.version(), Ship.class, shipId);
    requireOwner(ship, userId);

    List<String> changed = new ArrayList<>();
    if (!Objects.equals(ship.getName(), dto.name())) {
      changed.add("name");
    }
    if (!Objects.equals(ship.getInsurance(), dto.insurance())) {
      changed.add("insurance");
    }
    if (ship.isFitted() != dto.fitted()) {
      changed.add("fitted");
    }
    UUID oldShipTypeId = ship.getShipType() == null ? null : ship.getShipType().getId();
    if (!Objects.equals(oldShipTypeId, dto.shipTypeId())) {
      changed.add("shipType");
    }
    UUID oldLocationId = ship.getLocation() == null ? null : ship.getLocation().getId();
    if (!Objects.equals(oldLocationId, dto.locationId())) {
      changed.add("location");
    }

    ship.setName(dto.name());
    ship.setInsurance(dto.insurance());
    ship.setFitted(dto.fitted());

    ship.setShipType(
        shipTypeRepository
            .findById(dto.shipTypeId())
            .orElseThrow(() -> new BadRequestException("ShipType not found")));

    if (dto.locationId() != null) {
      ship.setLocation(
          locationRepository
              .findById(dto.locationId())
              .orElseThrow(() -> new BadRequestException("Location not found")));
    } else {
      ship.setLocation(null);
    }

    Ship saved = shipRepository.save(ship);
    if (!changed.isEmpty()) {
      auditRecorder.record(
          AuditEventType.HANGAR_SHIP_UPDATED,
          saved.getId(),
          saved.getShipType().getName(),
          userId,
          AuditDetails.of("changed", String.join(",", changed)));
      unitLiveSync.shipsChanged(Arrays.asList(saved.getOwningOrgUnit()));
    }
    return saved;
  }

  /**
   * Changes the owning org unit of a ship owned by {@code userId} to one of the owner's direct
   * memberships or none (REQ-HANGAR-009). Guarded by the ship's unit counter alone, so it never
   * conflicts with an edit of the other fields (ADR-0258). An unchanged unit writes and records
   * nothing.
   *
   * @param userId calling user's id
   * @param shipId ship primary key
   * @param orgUnitId the new org unit, or {@code null} for none
   * @param owningOrgUnitVersion the unit counter the caller last saw
   * @return the ship as it is after the change
   * @throws NotFoundException when the ship does not exist
   * @throws AccessDeniedException when the calling user is not the owner
   * @throws BadRequestException when the target is not a direct membership of the owner
   * @throws ObjectOptimisticLockingFailureException when the unit counter is stale
   */
  @Transactional
  public Ship changeShipOrgUnit(
      @NotNull UUID userId,
      @NotNull UUID shipId,
      @Nullable UUID orgUnitId,
      long owningOrgUnitVersion) {
    return moveShip(userId, shipId, orgUnitId, owningOrgUnitVersion).ship();
  }

  /**
   * Changes the owning org unit of a member's ship on an admin's behalf to one of the member's
   * direct memberships or none, and tells the member when it moved (REQ-HANGAR-012). Same rules as
   * {@link #changeShipOrgUnit}.
   *
   * @param memberId the ship's owner
   * @param shipId ship primary key
   * @param orgUnitId the new org unit, or {@code null} for none
   * @param owningOrgUnitVersion the unit counter the admin last saw
   * @return the ship as it is after the change
   * @throws NotFoundException when the ship does not exist
   * @throws AccessDeniedException when the ship belongs to another member
   * @throws BadRequestException when the target is not a direct membership of the member
   * @throws ObjectOptimisticLockingFailureException when the unit counter is stale
   */
  @Transactional
  public Ship changeShipOrgUnitByAdmin(
      @NotNull UUID memberId,
      @NotNull UUID shipId,
      @Nullable UUID orgUnitId,
      long owningOrgUnitVersion) {
    ShipMove move = moveShip(memberId, shipId, orgUnitId, owningOrgUnitVersion);
    if (move.moved()) {
      announceByAdmin(memberId, ORG_UNIT_CHANGED, move.ship());
    }
    return move.ship();
  }

  /**
   * Moves one ship of {@code userId} to the target unit behind its unit counter and audits it.
   *
   * @param userId the ship's owner
   * @param shipId ship primary key
   * @param orgUnitId the new org unit, or {@code null} for none
   * @param owningOrgUnitVersion the unit counter the caller last saw
   * @return the ship after the change and whether it moved
   */
  private @NotNull ShipMove moveShip(
      @NotNull UUID userId,
      @NotNull UUID shipId,
      @Nullable UUID orgUnitId,
      long owningOrgUnitVersion) {
    Ship ship = Entities.require(shipRepository.findById(shipId), "Ship not found");
    requireOwner(ship, userId);
    OrgUnit current = ship.getOwningOrgUnit();
    if (Objects.equals(current == null ? null : current.getId(), orgUnitId)) {
      return new ShipMove(ship, false);
    }
    OrgUnit target = ownerScopeService.resolveOwnerMembershipTarget(ship.getOwner(), orgUnitId);
    String from = orgUnitRef(current);
    String shipTypeName = ship.getShipType().getName();
    if (shipRepository.changeOwningOrgUnitIfVersionMatches(shipId, target, owningOrgUnitVersion)
        == 0) {
      throw new ObjectOptimisticLockingFailureException(Ship.class, shipId);
    }
    auditRecorder.record(
        AuditEventType.HANGAR_SHIP_ORG_UNIT_CHANGED,
        shipId,
        shipTypeName,
        userId,
        AuditDetails.of("from", from).with("to", orgUnitRef(target)));
    unitLiveSync.shipsChanged(Arrays.asList(current, target));
    return new ShipMove(Entities.require(shipRepository.findById(shipId), "Ship not found"), true);
  }

  /**
   * Changes the owning org unit of a selection of ships owned by {@code userId} to one of the
   * owner's direct memberships or none (REQ-HANGAR-010). The ships are locked in ascending id order
   * and checked before the first write, so a foreign or unknown ship refuses the whole selection;
   * ships already in the target are skipped. Each moved ship's unit counter goes up, and one
   * summary event is recorded when anything moved.
   *
   * @param userId calling user's id
   * @param shipIds the selected ships
   * @param orgUnitId the new org unit, or {@code null} for none
   * @return how many ships moved and how many were skipped
   * @throws NotFoundException when a selected ship does not exist
   * @throws AccessDeniedException when a selected ship belongs to another member
   * @throws BadRequestException when the target is not a direct membership of the owner
   */
  @Transactional
  public @NotNull ShipBulkOrgUnitResultDto bulkChangeShipOrgUnit(
      @NotNull UUID userId, @NotNull List<UUID> shipIds, @Nullable UUID orgUnitId) {
    return moveShips(userId, shipIds, orgUnitId).result();
  }

  /**
   * Changes the owning org unit of a selection of a member's ships on an admin's behalf to one of
   * the member's direct memberships or none, with the rules of {@link #bulkChangeShipOrgUnit}
   * (REQ-HANGAR-012). The member hears one notice: about the ship when one moved, a summary with
   * the count when several did, nothing when none did.
   *
   * @param memberId the ships' owner
   * @param shipIds the selected ships
   * @param orgUnitId the new org unit, or {@code null} for none
   * @return how many ships moved and how many were skipped
   * @throws NotFoundException when a selected ship does not exist
   * @throws AccessDeniedException when a selected ship belongs to another member
   * @throws BadRequestException when the target is not a direct membership of the member
   */
  @Transactional
  public @NotNull ShipBulkOrgUnitResultDto bulkChangeShipOrgUnitByAdmin(
      @NotNull UUID memberId, @NotNull List<UUID> shipIds, @Nullable UUID orgUnitId) {
    BulkMove move = moveShips(memberId, shipIds, orgUnitId);
    int moved = move.movedShips().size();
    if (moved == 1) {
      announceByAdmin(memberId, ORG_UNIT_CHANGED, move.movedShips().getFirst());
    } else if (moved > 1) {
      tellMember(memberId, actor -> HangarNotices.shipsOrgUnitChanged(memberId, moved, actor));
    }
    return move.result();
  }

  /**
   * Moves the selected ships of {@code userId} to the target unit under sorted row locks and
   * records one summary event when anything moved.
   *
   * @param userId the ships' owner
   * @param shipIds the selected ships
   * @param orgUnitId the new org unit, or {@code null} for none
   * @return the ships that moved and the counts
   */
  private @NotNull BulkMove moveShips(
      @NotNull UUID userId, @NotNull List<UUID> shipIds, @Nullable UUID orgUnitId) {
    List<UUID> distinctIds = shipIds.stream().distinct().toList();
    List<Ship> ships = shipRepository.lockAllByIdOrderById(distinctIds);
    if (ships.size() != distinctIds.size()) {
      throw new NotFoundException("Ship not found");
    }
    ships.forEach(ship -> requireOwner(ship, userId));
    List<Ship> moving =
        ships.stream()
            .filter(ship -> !Objects.equals(orgUnitIdOf(ship.getOwningOrgUnit()), orgUnitId))
            .toList();
    int skipped = ships.size() - moving.size();
    if (moving.isEmpty()) {
      return new BulkMove(List.of(), new ShipBulkOrgUnitResultDto(0, skipped));
    }
    OrgUnit target =
        ownerScopeService.resolveOwnerMembershipTarget(moving.getFirst().getOwner(), orgUnitId);
    List<OrgUnit> touched = new ArrayList<>();
    touched.add(target);
    moving.forEach(ship -> touched.add(ship.getOwningOrgUnit()));
    String from =
        moving.stream()
            .map(ship -> orgUnitRef(ship.getOwningOrgUnit()))
            .distinct()
            .sorted()
            .collect(Collectors.joining(","));
    for (Ship ship : moving) {
      ship.setOwningOrgUnit(target);
      ship.setOwningOrgUnitVersion(ship.getOwningOrgUnitVersion() + 1);
    }
    shipRepository.flush();
    auditRecorder.record(
        AuditEventType.HANGAR_SHIPS_BULK_ORG_UNIT_CHANGED,
        null,
        null,
        userId,
        AuditDetails.of("changed", moving.size())
            .with("skipped", skipped)
            .with("from", from)
            .with("to", orgUnitRef(target)));
    unitLiveSync.shipsChanged(touched);
    return new BulkMove(moving, new ShipBulkOrgUnitResultDto(moving.size(), skipped));
  }

  /**
   * The outcome of a single unit change.
   *
   * @param ship the ship as it is after the change
   * @param moved whether its unit changed
   */
  private record ShipMove(@NotNull Ship ship, boolean moved) {}

  /**
   * The outcome of a bulk unit change.
   *
   * @param movedShips the ships whose unit changed
   * @param result the changed and skipped counts
   */
  private record BulkMove(
      @NotNull List<Ship> movedShips, @NotNull ShipBulkOrgUnitResultDto result) {}

  /**
   * Reads an org unit's id without initialising a lazy reference.
   *
   * @param orgUnit the org unit, or {@code null}
   * @return its id, or {@code null} for none
   */
  private static @Nullable UUID orgUnitIdOf(@Nullable OrgUnit orgUnit) {
    return orgUnit == null ? null : orgUnit.getId();
  }

  /**
   * Renders an org unit for an audit detail as {@code kind:id}, or {@code none}.
   *
   * @param orgUnit the org unit, or {@code null}
   * @return the reference, free of names
   */
  private static @NotNull String orgUnitRef(@Nullable OrgUnit orgUnit) {
    return orgUnit == null ? "none" : orgUnit.getKind().name() + ":" + orgUnit.getId();
  }

  /**
   * Refuses a ship the calling user does not own.
   *
   * @param ship the ship
   * @param userId calling user's id
   * @throws AccessDeniedException when the calling user is not the owner
   */
  private static void requireOwner(@NotNull Ship ship, @NotNull UUID userId) {
    if (ship.getOwner() == null
        || ship.getOwner().getId() == null
        || !ship.getOwner().getId().equals(userId)) {
      throw new AccessDeniedException("Access denied: You do not own this ship");
    }
  }

  /**
   * Deletes a ship owned by {@code userId} after detaching it from any mission units.
   *
   * @param userId calling user's id
   * @param shipId ship primary key
   * @return how many mission units the ship was detached from
   * @throws NotFoundException when the ship does not exist
   * @throws AccessDeniedException when the calling user is not the owner
   */
  @Transactional
  public int deleteShip(@NotNull UUID userId, @NotNull UUID shipId) {
    Ship ship = Entities.require(shipRepository.findById(shipId), "Ship not found");
    requireOwner(ship, userId);

    int detached = shipDeletionObserver.beforeShipDelete(shipId);

    entityManager.flush();
    shipRepository.delete(ship);
    auditRecorder.record(
        AuditEventType.HANGAR_SHIP_DELETED,
        shipId,
        ship.getShipType().getName(),
        userId,
        AuditDetails.of("detachedUnits", detached));
    unitLiveSync.shipsChanged(Arrays.asList(ship.getOwningOrgUnit()));
    return detached;
  }

  /**
   * Removes every ship owned by a user — used when an admin deletes the user account so no orphan
   * ship rows remain. Same unit-detach-then-delete sequence as {@link #deleteShip}.
   *
   * @param userId owning user's id
   */
  @Transactional
  public void deleteAllShipsForUser(@NotNull UUID userId) {
    List<Ship> ships = shipRepository.findByOwnerId(userId);
    if (ships.isEmpty()) {
      log.debug("deleteAllShipsForUser: no ships found for user {}", userId);
      return;
    }
    log.info("deleteAllShipsForUser: unlinking {} ships for user {}", ships.size(), userId);
    int detached = 0;
    for (Ship ship : ships) {
      detached += shipDeletionObserver.beforeShipDelete(ship.getId());
    }
    entityManager.flush();
    shipRepository.deleteAll(ships);
    auditRecorder.record(
        AuditEventType.HANGAR_EMPTIED,
        null,
        null,
        userId,
        AuditDetails.of("ships", ships.size()).with("detachedUnits", detached));
    unitLiveSync.shipsChanged(ships.stream().map(Ship::getOwningOrgUnit).toList());
    log.info("deleteAllShipsForUser: deleted {} ships for user {}", ships.size(), userId);
  }

  /**
   * Clears the {@code fitted} flag on every ship in the caller's squadron scope; in the admin
   * all-squadrons mode on every ship.
   */
  @Transactional
  public void resetAllFittedStatus() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<ShipRepository.OwnerShipCount> perOwner =
        shipRepository.countFittedByOwnerScoped(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
    List<UUID> units =
        shipRepository.findFittedOrgUnitIdsScoped(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
    int reset =
        shipRepository.resetAllFittedScoped(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
    if (reset > 0) {
      auditRecorder.record(
          AuditEventType.HANGAR_FITTED_RESET,
          null,
          null,
          null,
          AuditDetails.of("ships", reset).with("allUnits", scope.adminAllScope()));
      ActorRef actor = userService.currentActor();
      for (ShipRepository.OwnerShipCount owner : perOwner) {
        eventPublisher.publishEvent(
            HangarNotices.fittedReset(owner.getOwnerId(), owner.getShipCount(), actor));
      }
      unitLiveSync.unitsChanged(units);
    }
  }

  /**
   * Adds a ship to a member's hangar on an admin's behalf and tells the member (REQ-HANGAR-008).
   *
   * @param memberId the hangar's owner
   * @param dto the new ship
   * @return the saved ship
   */
  @Transactional
  public Ship addShipByAdmin(@NotNull UUID memberId, @NotNull ShipRequestDto dto) {
    Ship ship = addShip(memberId, dto);
    announceByAdmin(memberId, "ADDED", ship);
    return ship;
  }

  /**
   * Changes a ship of a member's hangar on an admin's behalf and tells the member (REQ-HANGAR-008).
   *
   * @param memberId the hangar's owner
   * @param shipId the ship
   * @param dto the new values
   * @return the saved ship
   */
  @Transactional
  public Ship updateShipByAdmin(
      @NotNull UUID memberId, @NotNull UUID shipId, @NotNull ShipRequestDto dto) {
    Ship ship = updateShip(memberId, shipId, dto);
    announceByAdmin(memberId, "UPDATED", ship);
    return ship;
  }

  /**
   * Deletes a ship of a member's hangar on an admin's behalf and tells the member (REQ-HANGAR-008).
   *
   * @param memberId the hangar's owner
   * @param shipId the ship
   * @return how many mission units the ship was detached from
   */
  @Transactional
  public int deleteShipByAdmin(@NotNull UUID memberId, @NotNull UUID shipId) {
    Ship ship = shipRepository.findById(shipId).orElse(null);
    int detached = deleteShip(memberId, shipId);
    if (ship != null) {
      announceByAdmin(memberId, "DELETED", ship);
    }
    return detached;
  }

  private void announceByAdmin(UUID memberId, String changeCode, Ship ship) {
    tellMember(
        memberId,
        actor ->
            HangarNotices.hangarChanged(
                memberId,
                changeCode,
                ship.getShipType() == null ? ActorRef.UNKNOWN_NAME : ship.getShipType().getName(),
                actor));
  }

  /**
   * Publishes a notice to a member about an admin's change, unless the admin is that member.
   *
   * @param memberId the member the change concerns
   * @param notice builds the notice from the acting admin
   */
  private void tellMember(@NotNull UUID memberId, @NotNull Function<ActorRef, NoticeEvent> notice) {
    ActorRef actor = userService.currentActor();
    if (!memberId.equals(actor.id())) {
      eventPublisher.publishEvent(notice.apply(actor));
    }
  }

  /**
   * Sets the location of every ship owned by {@code userId}, across all org units, to the chosen
   * home location.
   *
   * @param userId the calling user's id; only their ships are updated
   * @param locationId the curated home location to assign to every owned ship
   * @return the number of ships updated
   * @throws BadRequestException when the location does not exist or is not a selectable home
   *     location
   */
  @Transactional
  public int setHomeLocationForMyShips(@NotNull UUID userId, @NotNull UUID locationId) {
    Location location =
        locationRepository
            .findById(locationId)
            .orElseThrow(() -> new BadRequestException("Location not found"));
    if (!Boolean.TRUE.equals(location.getHomeLocation())
        || Boolean.TRUE.equals(location.getHidden())) {
      throw new BadRequestException("Location is not a selectable home location");
    }
    int updated = shipRepository.setLocationForOwner(userId, location);
    if (updated > 0) {
      auditRecorder.record(
          AuditEventType.HANGAR_HOME_LOCATION_SET,
          location.getId(),
          location.getName(),
          userId,
          AuditDetails.of("ships", updated));
      unitLiveSync.unitsChanged(shipRepository.findOrgUnitIdsByOwnerId(userId));
    }
    return updated;
  }
}
