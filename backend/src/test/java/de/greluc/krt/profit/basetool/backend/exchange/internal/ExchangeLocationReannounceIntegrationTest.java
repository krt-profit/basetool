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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A change of what a place's location reference serialises to — its name, its UEX link, its starmap
 * link or the GUID its starmap object gives — re-announces exactly the ships, stock lots and
 * org-stock lots at that place, once per entry and transaction (REQ-XCH-018, V359).
 *
 * <p>Each test runs in one transaction, so {@link #forgetSetUp()} drops the entries the set-up
 * wrote before the change under test, as a later transaction would not see them.
 */
@SpringBootTest
@Transactional
class ExchangeLocationReannounceIntegrationTest {

  /** The first UEX city id the tests use; far above the seeded ids. */
  private static final int UEX_BASE = 991_000;

  /** The places of the APPLY-sized test. */
  private static final int APPLY_PLACES = 200;

  /** The entries per place: one ship, two stock lots and one org-stock lot. */
  private static final int ENTRIES_PER_PLACE = 4;

  /** The longest an APPLY-sized relink may take with its re-announcements. */
  private static final Duration APPLY_BUDGET = Duration.ofSeconds(20);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;

  private UUID alice;
  private UUID bob;
  private UUID pool;
  private UUID material;
  private UUID shipType;
  private int nextUex = UEX_BASE;

  @BeforeEach
  void setUp() {
    alice = member("reannounce-alice");
    bob = member("reannounce-bob");
    pool = orgUnit("Reannounce Pool " + UUID.randomUUID());
    material = material();
    ShipType type = new ShipType();
    type.setName("reannounce-type-" + UUID.randomUUID());
    shipType = shipTypeRepository.saveAndFlush(type).getId();
  }

  @Test
  void aRenameReannouncesEveryEntryAtThePlaceAndNothingElse() {
    Place renamed = stockedPlace();
    Place other = stockedPlace();
    forgetSetUp();

    jdbc.update("UPDATE location SET name = ? WHERE id = ?", "Renamed " + renamed.id, renamed.id);

    assertThat(announced()).containsExactlyInAnyOrderElementsOf(renamed.entries);
    assertThat(announced()).doesNotContainAnyElementsOf(other.entries);
  }

  @Test
  void aUexRelinkReannouncesTheEntriesAtThePlacesOnTheCity() {
    Place onCity = stockedPlace();
    Place moved = stockedPlace();
    stockedPlace();
    forgetSetUp();

    jdbc.update("UPDATE city SET id_city = ? WHERE id = ?", nextUex++, onCity.city);
    jdbc.update("UPDATE location SET city_id = ? WHERE id = ?", city(), moved.id);

    assertThat(announced())
        .containsExactlyInAnyOrderElementsOf(concat(onCity.entries, moved.entries));
  }

  @Test
  void aStarmapRelinkOfTheCityOrThePlaceReannouncesItsEntries() {
    Place viaCity = stockedPlace();
    Place viaNode = stockedPlace();
    stockedPlace();
    UUID cityObject = starmapObject();
    UUID nodeObject = starmapObject();
    forgetSetUp();

    relinkCities(List.of(viaCity.city), List.of(cityObject));
    jdbc.update("UPDATE location SET starmap_object_id = ? WHERE id = ?", nodeObject, viaNode.id);

    assertThat(announced())
        .containsExactlyInAnyOrderElementsOf(concat(viaCity.entries, viaNode.entries));
  }

  @Test
  void aSweptOrHiddenStarmapObjectReannouncesTheEntriesItGaveItsGuid() {
    Place hidden = stockedPlace();
    Place swept = stockedPlace();
    UUID hiddenObject = starmapObject();
    UUID sweptObject = starmapObject();
    relinkCities(List.of(hidden.city, swept.city), List.of(hiddenObject, sweptObject));
    forgetSetUp();

    jdbc.update("UPDATE starmap_object SET p4k_hidden_at = now() WHERE id = ?", hiddenObject);

    assertThat(announced()).containsExactlyInAnyOrderElementsOf(hidden.entries);

    forgetSetUp();
    jdbc.update("UPDATE starmap_object SET p4k_deleted_at = now() WHERE id = ?", sweptObject);

    assertThat(announced()).containsExactlyInAnyOrderElementsOf(swept.entries);
  }

  @Test
  void aChangeThatLeavesTheReferenceAsItWasAnnouncesNothing() {
    Place place = stockedPlace();
    UUID ownNode = starmapObject();
    UUID cityObject = starmapObject();
    jdbc.update("UPDATE location SET starmap_object_id = ? WHERE id = ?", ownNode, place.id);
    UUID sameUexCity = city();
    jdbc.update("UPDATE city SET id_city = NULL WHERE id = ?", sameUexCity);
    jdbc.update("UPDATE city SET id_city = NULL WHERE id = ?", place.city);
    forgetSetUp();

    jdbc.update("UPDATE city SET name = 'Reannounce renamed city' WHERE id = ?", place.city);
    jdbc.update(
        "UPDATE starmap_object SET name = 'Game text', p4k_synced_at = now() WHERE id = ?",
        ownNode);
    jdbc.update("UPDATE location SET hidden = TRUE, description = 'x' WHERE id = ?", place.id);
    relinkCities(List.of(place.city), List.of(cityObject));
    jdbc.update("UPDATE location SET city_id = ? WHERE id = ?", sameUexCity, place.id);
    jdbc.update("UPDATE starmap_object SET p4k_hidden_at = now() WHERE id = ?", cityObject);

    assertThat(announced()).isEmpty();
  }

  @Test
  void anApplyThatRelinksHidesAndRenamesManyPlacesAnnouncesEachEntryOnce() {
    List<Place> places = new ArrayList<>();
    List<UUID> cities = new ArrayList<>();
    List<UUID> objects = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < APPLY_PLACES; i++) {
      Place place = stockedPlace();
      places.add(place);
      cities.add(place.city);
      objects.add(starmapObject());
      expected.addAll(place.entries);
    }
    forgetSetUp();

    long start = System.nanoTime();
    relinkCities(cities, objects);
    jdbc.update(
        "UPDATE starmap_object SET p4k_hidden_at = now()"
            + " WHERE id = ANY (CAST(string_to_array(?, ',') AS UUID[]))",
        join(objects));
    for (Place place : places) {
      jdbc.update("UPDATE location SET name = ? WHERE id = ?", "Applied " + place.id, place.id);
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(announced())
        .hasSize(APPLY_PLACES * ENTRIES_PER_PLACE)
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(elapsed).isLessThan(APPLY_BUDGET);
  }

  /**
   * Drops the entries this transaction wrote so far, so the next assertion sees only what the
   * change under test announces.
   */
  private void forgetSetUp() {
    jdbc.update("DELETE FROM exchange_change WHERE tx = (pg_current_xact_id()::text)::bigint");
  }

  /**
   * Lists the entries this transaction wrote, as {@code owner|resource|key}.
   *
   * @return the entries in sequence order
   */
  private @NotNull List<String> announced() {
    return jdbc.queryForList(
        "SELECT COALESCE(user_id, org_unit_id) || '|' || resource || '|' || entity_key"
            + " FROM exchange_change WHERE tx = (pg_current_xact_id()::text)::bigint ORDER BY seq",
        String.class);
  }

  /**
   * Seeds a place on a UEX city of its own holding a ship and a personal stock row of Alice and a
   * row Bob shares in the pool.
   *
   * @return the place with the entries that carry it
   */
  private @NotNull Place stockedPlace() {
    UUID city = city();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden, city_id) VALUES (?, ?, false, ?)",
        id,
        "Reannounce place " + id,
        city);
    UUID ship = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, name, ship_type_id, owner_id, location_id) VALUES (?, 'S', ?, ?, ?)",
        ship,
        shipType,
        alice,
        id);
    stock(alice, id, true, null);
    stock(bob, id, false, pool);
    String lot = "m:" + material + "|l:" + id + "|q:0|s:0";
    return new Place(
        id,
        city,
        List.of(
            alice + "|SHIP|" + ship,
            alice + "|STOCK|" + lot,
            bob + "|STOCK|" + lot,
            pool + "|ORG_STOCK|u:" + pool + "|" + lot));
  }

  /**
   * Links cities to starmap objects in one statement, the way an APPLY links a table.
   *
   * @param cities the cities
   * @param objects the object of each city, in the same order
   */
  private void relinkCities(@NotNull List<UUID> cities, @NotNull List<UUID> objects) {
    jdbc.update(
        "UPDATE city t SET starmap_object_id = v.object_id"
            + " FROM unnest(CAST(string_to_array(?, ',') AS UUID[]),"
            + " CAST(string_to_array(?, ',') AS UUID[])) AS v(id, object_id)"
            + " WHERE t.id = v.id",
        join(cities),
        join(objects));
  }

  /**
   * Seeds a city with a fresh UEX id.
   *
   * @return its id
   */
  private @NotNull UUID city() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO city (id, version, created_at, updated_at, name, id_city)"
            + " VALUES (?, 0, now(), now(), ?, ?)",
        id,
        "Reannounce city " + id,
        nextUex++);
    return id;
  }

  /**
   * Seeds a visible starmap object.
   *
   * @return its id
   */
  private @NotNull UUID starmapObject() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO starmap_object (id, p4k_uuid, version) VALUES (?, ?, 0)",
        id,
        UUID.randomUUID());
    return id;
  }

  /**
   * Seeds one stock row of the material at a place.
   *
   * @param holder the holder
   * @param location the place
   * @param personal whether it is the holder's personal stock
   * @param owningUnit the pool of a shared row, or {@code null}
   */
  private void stock(
      @NotNull UUID holder, @NotNull UUID location, boolean personal, @Nullable UUID owningUnit) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id)
        VALUES (?, ?, ?, ?, 0, 1, ?, false, ?)
        """,
        UUID.randomUUID(),
        holder,
        material,
        location,
        personal,
        owningUnit);
  }

  /**
   * Seeds an SCU material.
   *
   * @return its id
   */
  private @NotNull UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        "reannounce-" + id);
    return id;
  }

  /**
   * Seeds an active squadron.
   *
   * @param name the unique name
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible)"
            + " VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE)",
        id,
        name,
        "R" + id.toString().substring(0, 8));
    return id;
  }

  /**
   * Seeds an approved member.
   *
   * @param prefix the username prefix
   * @return the member's id
   */
  private @NotNull UUID member(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * Joins ids for {@code string_to_array}.
   *
   * @param ids the ids
   * @return the comma-separated ids
   */
  private static @NotNull String join(@NotNull List<UUID> ids) {
    return String.join(",", ids.stream().map(UUID::toString).toList());
  }

  /**
   * Concatenates two entry lists.
   *
   * @param first the first entries
   * @param second the second entries
   * @return both, in order
   */
  private static @NotNull List<String> concat(
      @NotNull List<String> first, @NotNull List<String> second) {
    List<String> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  /**
   * A seeded place.
   *
   * @param id the location id
   * @param city the location's city
   * @param entries the feed entries that carry the place, as {@code owner|resource|key}
   */
  private record Place(@NotNull UUID id, @NotNull UUID city, @NotNull List<String> entries) {}
}
