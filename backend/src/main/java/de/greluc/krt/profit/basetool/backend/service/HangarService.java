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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronShipDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import jakarta.persistence.EntityManager;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the personal hangar (ships per user) and the squadron-wide ship overview.
 *
 * <p>Update and delete require the caller to own the ship. Deleting a ship first detaches it from
 * mission units, which keep their name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HangarService {

  private final ShipRepository shipRepository;
  private final UserRepository userRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final LocationRepository locationRepository;
  private final MissionUnitRepository missionUnitRepository;
  private final ShipMapper shipMapper;
  private final EntityManager entityManager;
  private final OwnerScopeService ownerScopeService;

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
    Ship ship = new Ship();
    ship.setName(dto.name());
    ship.setInsurance(dto.insurance());
    ship.setFitted(dto.fitted());
    ship.setOwner(user);
    ship.setOwningOrgUnit(
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, dto.owningOrgUnitId()));
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
    return shipRepository.save(ship);
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
   * ship-type or manufacturer name (REQ-HANGAR-002). Ordering and filtering run in the repository
   * across the whole fleet.
   *
   * @param userId owner id; only this user's ships are returned
   * @param search optional ship-type/manufacturer name filter; {@code null}/blank means no filter
   * @param pageable page request, unsorted (the query defines the order)
   * @return one ordered, optionally filtered page of the user's ships
   */
  public Page<Ship> getMyShipsFiltered(
      @NotNull UUID userId, String search, @NotNull Pageable pageable) {
    String normalizedSearch =
        search == null || search.isBlank() ? null : LikePatterns.escape(search.trim());
    return shipRepository.findByOwnerIdFiltered(userId, normalizedSearch, pageable);
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
    String normalizedQuery = StringNormalization.trimToNull(query);
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

    return p.map(
        obj -> {
          ShipType type = (ShipType) obj[0];
          List<SquadronShipDetailDto> details = null;
          if (includeOwnerDetails) {
            details =
                shipsByType.getOrDefault(type.getId(), List.of()).stream()
                    .map(
                        s ->
                            new SquadronShipDetailDto(
                                s.getOwner() != null ? s.getOwner().getEffectiveName() : "Unknown",
                                s.getLocation() != null ? s.getLocation().getName() : null,
                                s.isFitted()))
                    .toList();
          }

          return new SquadronShipOverviewDto(
              shipMapper.shipTypeToDto(type),
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

    if (ship.getOwner() == null
        || ship.getOwner().getId() == null
        || !ship.getOwner().getId().equals(userId)) {
      throw new AccessDeniedException("Access denied: You do not own this ship");
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

    return shipRepository.save(ship);
  }

  /**
   * Deletes a ship owned by {@code userId} after detaching it from any mission units.
   *
   * @param userId calling user's id
   * @param shipId ship primary key
   * @throws NotFoundException when the ship does not exist
   * @throws AccessDeniedException when the calling user is not the owner
   */
  @Transactional
  public void deleteShip(@NotNull UUID userId, @NotNull UUID shipId) {
    Ship ship = Entities.require(shipRepository.findById(shipId), "Ship not found");

    if (ship.getOwner() == null
        || ship.getOwner().getId() == null
        || !ship.getOwner().getId().equals(userId)) {
      throw new AccessDeniedException("Access denied: You do not own this ship");
    }

    missionUnitRepository
        .findByShipId(shipId)
        .forEach(
            unit -> {
              unit.setShip(null);
              missionUnitRepository.save(unit);
            });

    entityManager.flush();
    shipRepository.delete(ship);
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
    for (Ship ship : ships) {
      missionUnitRepository
          .findByShipId(ship.getId())
          .forEach(
              unit -> {
                unit.setShip(null);
                missionUnitRepository.save(unit);
              });
    }
    entityManager.flush();
    shipRepository.deleteAll(ships);
    log.info("deleteAllShipsForUser: deleted {} ships for user {}", ships.size(), userId);
  }

  /**
   * Clears the {@code fitted} flag on every ship in the caller's squadron scope; in the admin
   * all-squadrons mode on every ship.
   */
  @Transactional
  public void resetAllFittedStatus() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    shipRepository.resetAllFittedScoped(
        scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
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
    return shipRepository.setLocationForOwner(userId, location);
  }
}
