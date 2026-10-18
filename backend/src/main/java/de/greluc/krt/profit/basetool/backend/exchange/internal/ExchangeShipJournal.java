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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipCommands;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the journal state of a member's ship and of an installation's link to it: what the ship
 * writer records and what an undo reads back to remove, update or recreate the ship and to take
 * back the links (REQ-XCH-017, REQ-XCH-022, ADR-0285).
 */
@Component
@RequiredArgsConstructor
public class ExchangeShipJournal implements ExchangeEntryRestorer {

  private static final String EXTERNAL_ID = "externalId";

  private final ShipRepository shipRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final LocationRepository locationRepository;
  private final ShipCommands shipCommands;
  private final ExchangeShipLinkRepository linkRepository;
  private final ExchangeChangeRepository changeRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock = Clock.systemUTC();

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.SHIP;
  }

  /**
   * Sets the ship back to its state before the client's first write, then takes back the links the
   * client made.
   *
   * @param scope the member, the client and the member's memberships
   * @param group the client's writes to the ship and its links, newest first
   * @return {@code null} when restored, {@code CHANGED_AFTERWARDS} when something else changed the
   *     ship, {@code GONE} when it cannot be set back or its earlier state cannot be read
   */
  @Override
  public @Nullable String restore(
      @NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> group) {
    List<ExchangeJournalEntry> data =
        group.stream().filter(e -> e.getAction() != ExchangeJournalAction.SHIP_LINK).toList();
    List<ExchangeJournalEntry> links =
        group.stream().filter(e -> e.getAction() == ExchangeJournalAction.SHIP_LINK).toList();
    if (!data.isEmpty()) {
      ExchangeJournalEntry newest = data.getFirst();
      if (ExchangeEntryRestorer.changedAfterwards(
          changeRepository, scope.member(), newest, newest.getEntityKey())) {
        return ExchangeUndoService.CHANGED_AFTERWARDS;
      }
      String before = data.getLast().getBeforeState();
      if ((before != null && State.unreadable(objectMapper.readTree(before)))
          || !restoreShip(scope, newest.getEntityKey(), read(before))) {
        return ExchangeUndoService.GONE;
      }
    }
    unlink(scope, links);
    return null;
  }

  /**
   * Reads a journaled ship state.
   *
   * @param json the state as the journal stores it, or {@code null}
   * @return the state, or {@code null} when the ship was absent
   */
  @Nullable
  State read(@Nullable String json) {
    return json == null ? null : State.read(objectMapper.readTree(json));
  }

  /**
   * Captures the installation's id a link points at, for the journal.
   *
   * @param externalId the installation's id
   * @return the link's state
   */
  static @NotNull Map<String, Object> linkState(@NotNull String externalId) {
    return Map.of(EXTERNAL_ID, externalId);
  }

  /**
   * Reads the installation's id of a journaled link state.
   *
   * @param json the state, or {@code null}
   * @return the id, or {@code null} when there was no link
   */
  @Nullable
  String readLink(@Nullable String json) {
    return json == null ? null : ExchangeJournalJson.text(objectMapper.readTree(json), EXTERNAL_ID);
  }

  /**
   * Removes, updates or recreates a ship to match its earlier state, including its earlier org unit
   * while the member still directly belongs to it; a recreated ship gets a new id and does not
   * rejoin mission units, and one whose unit is not restored is stamped like a client's create.
   *
   * @param scope the member and the member's memberships
   * @param rawId the ship's id
   * @param before the state before the client's first write, or {@code null} when it was absent
   * @return whether the ship could be restored
   */
  private boolean restoreShip(
      @NotNull UndoScope scope, @NotNull String rawId, @Nullable State before) {
    UUID member = scope.member();
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
    UUID shipType = before.shipType();
    UUID locationId = before.location();
    if (shipType == null
        || before.insurance() == null
        || !shipTypeRepository.existsById(shipType)
        || (locationId != null && !locationRepository.existsById(locationId))) {
      return false;
    }
    ShipRequestDto dto =
        new ShipRequestDto(
            before.name(),
            shipType,
            before.insurance(),
            locationId,
            before.fitted(),
            ship.map(Ship::getVersion).orElse(null),
            null);
    RecordedOrgUnit unit = before.orgUnit();
    if (unit != null && unit.id() != null && !scope.memberships().get().contains(unit.id())) {
      unit = null;
    }
    if (ship.isEmpty()) {
      if (unit == null) {
        shipCommands.addShipForClient(member, dto);
      } else {
        shipCommands.addShipForClientInOrgUnit(member, dto, unit.id());
      }
      return true;
    }
    Ship current = ship.get();
    UUID currentUnit = orgUnitIdOf(current);
    long unitVersion = current.getOwningOrgUnitVersion();
    shipCommands.updateShip(member, shipId, dto);
    if (unit != null && !Objects.equals(unit.id(), currentUnit)) {
      shipCommands.changeShipOrgUnit(member, shipId, unit.id(), unitVersion);
    }
    return true;
  }

  /**
   * Takes back the links the client made in the span and puts back what they replaced, the latter
   * only while the ship is still the member's.
   *
   * @param scope the member and the client
   * @param links the client's link writes to one ship, newest first
   */
  private void unlink(@NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> links) {
    UUID member = scope.member();
    String clientId = scope.clientId();
    Map<String, List<ExchangeJournalEntry>> byInstallation = new LinkedHashMap<>();
    links.forEach(
        entry ->
            byInstallation
                .computeIfAbsent(entry.getInstallationKey(), _ -> new ArrayList<>())
                .add(entry));
    byInstallation.forEach(
        (installation, entries) -> {
          UUID shipId = UUID.fromString(entries.getFirst().getEntityKey());
          String made = readLink(entries.getFirst().getAfterState());
          String replaced = readLink(entries.getLast().getBeforeState());
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
   * Reads a ship's org unit id without initialising a lazy reference.
   *
   * @param ship the ship
   * @return its org unit's id, or {@code null} for none
   */
  static @Nullable UUID orgUnitIdOf(@NotNull Ship ship) {
    return ship.getOwningOrgUnit() == null ? null : ship.getOwningOrgUnit().getId();
  }

  /**
   * What the journal holds of a ship: its synced fields and its org unit.
   *
   * @param shipType the ship type, or {@code null} when the state names none
   * @param name the name, or {@code null}
   * @param insurance the stored insurance, or {@code null}
   * @param location the location, or {@code null}
   * @param fitted whether it is fitted
   * @param orgUnit the org unit, or {@code null} when the state does not record it
   */
  record State(
      @Nullable UUID shipType,
      @Nullable String name,
      @Nullable String insurance,
      @Nullable UUID location,
      boolean fitted,
      @Nullable RecordedOrgUnit orgUnit) {

    /**
     * Captures a ship's synced fields and its org unit.
     *
     * @param ship the ship
     * @return its state
     */
    static @NotNull State of(@NotNull Ship ship) {
      return new State(
          ship.getShipType().getId(),
          ship.getName(),
          ship.getInsurance(),
          ship.getLocation() == null ? null : ship.getLocation().getId(),
          ship.isFitted(),
          new RecordedOrgUnit(orgUnitIdOf(ship)));
    }

    /**
     * Captures the synced fields an update would write.
     *
     * @param dto the update
     * @return its state, without an org unit
     */
    static @NotNull State of(@NotNull ShipRequestDto dto) {
      return new State(
          dto.shipTypeId(), dto.name(), dto.insurance(), dto.locationId(), dto.fitted(), null);
    }

    /**
     * Returns the synced fields alone, for the no-change check.
     *
     * @return the state without its org unit
     */
    @NotNull
    State synced() {
      return new State(shipType, name, insurance, location, fitted, null);
    }

    /**
     * Turns the state into the journal's JSON shape: the five synced fields always, {@code orgUnit}
     * when the state records it.
     *
     * @return the state as the journal stores it
     */
    @NotNull
    Map<String, Object> toJournal() {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("shipType", shipType == null ? null : shipType.toString());
      state.put("name", name);
      state.put("insurance", insurance);
      state.put("location", location == null ? null : location.toString());
      state.put("fitted", fitted);
      if (orgUnit != null) {
        state.put("orgUnit", orgUnit.id() == null ? null : orgUnit.id().toString());
      }
      return state;
    }

    /**
     * Whether a stored state names a ship type or a location that is no id, or no insurance, so
     * that restoring it would lose data.
     *
     * @param node the state
     * @return {@code true} when the state cannot be restored as it was
     */
    static boolean unreadable(@NotNull JsonNode node) {
      return ExchangeJournalJson.uuid(node, "shipType") == null
          || ExchangeJournalJson.text(node, "insurance") == null
          || (ExchangeJournalJson.text(node, "location") != null
              && ExchangeJournalJson.uuid(node, "location") == null);
    }

    /**
     * Reads a state the journal stores, from any version of the writer; an older state without
     * {@code orgUnit}, or one whose unit is no unit id, records no unit.
     *
     * @param node the state
     * @return the state
     */
    static @NotNull State read(@NotNull JsonNode node) {
      RecordedOrgUnit unit = null;
      if (node.has("orgUnit")) {
        String raw = ExchangeJournalJson.text(node, "orgUnit");
        UUID id = ExchangeJournalJson.uuid(node, "orgUnit");
        if (raw == null || id != null) {
          unit = new RecordedOrgUnit(id);
        }
      }
      JsonNode fitted = node.get("fitted");
      return new State(
          ExchangeJournalJson.uuid(node, "shipType"),
          ExchangeJournalJson.text(node, "name"),
          ExchangeJournalJson.text(node, "insurance"),
          ExchangeJournalJson.uuid(node, "location"),
          fitted != null && fitted.asBoolean(),
          unit);
    }
  }

  /**
   * The org unit a ship state records; a state that records none has no such value at all.
   *
   * @param id the unit, or {@code null} for a ship in no unit
   */
  record RecordedOrgUnit(@Nullable UUID id) {}
}
