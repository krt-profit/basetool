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

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockLotDto;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A storage place linked to a P4K starmap object carries the object's GUID in the exchange, and a
 * write's GUID wins over its UEX link and name (REQ-P4K-037, REQ-XCH-016, REQ-XCH-018).
 */
@SpringBootTest
@Transactional
class ExchangeLocationGuidIntegrationTest {

  private static final int LINKED_CITY = 990_451;
  private static final int OTHER_CITY = 990_452;
  private static final UUID CITY_GUID = UUID.fromString("5b0a2c7e-1f3d-4e8a-9c6b-2d4f8a1e7c30");
  private static final UUID NODE_GUID = UUID.fromString("0d6e9a41-7c2b-4f15-8e3a-9b1c4d7e2f60");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private CityRepository cityRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeLocationResolver resolver;
  @Autowired private ExchangeStockFeedService stockFeedService;

  private Location onLinkedCity;
  private Location onOtherCity;
  private UUID nodeObject;

  @BeforeEach
  void setUp() {
    City linked = city(LINKED_CITY, "Xch Guid Linked City");
    City other = city(OTHER_CITY, "Xch Guid Other City");
    jdbc.update(
        "UPDATE city SET starmap_object_id = ? WHERE id = ?",
        starmapObject(CITY_GUID, "Xch Guid Starmap Text"),
        linked.getId());
    nodeObject = starmapObject(NODE_GUID, "Xch Guid Node Text");
    onLinkedCity = location("Xch Guid Linked Place", linked);
    onOtherCity = location("Xch Guid Other Place", other);
  }

  @Test
  void theGuidWinsOverTheUexLinkAndTheName() {
    ExchangeLocationRef ref =
        new ExchangeLocationRef(
            onOtherCity.getName(), new ExchangeLocationRef.Uex("CITY", OTHER_CITY), CITY_GUID);

    assertThat(resolver.resolve(ref)).contains(onLinkedCity);
  }

  @Test
  void anUnknownGuidFallsBackToTheUexLinkThenTheName() {
    UUID unknown = UUID.randomUUID();

    assertThat(
            resolver.resolve(
                new ExchangeLocationRef(
                    null, new ExchangeLocationRef.Uex("CITY", OTHER_CITY), unknown)))
        .contains(onOtherCity);
    assertThat(resolver.resolve(new ExchangeLocationRef(onLinkedCity.getName(), null, unknown)))
        .contains(onLinkedCity);
    assertThat(resolver.resolve(new ExchangeLocationRef(null, null, unknown))).isEmpty();
  }

  @Test
  void aPlaceOnTheStarmapObjectItselfWinsOverOneOnItsCity() {
    Location onNode = new Location();
    onNode.setName("Xch Guid Node Place");
    onNode.setStarmapObjectId(nodeObject);
    locationRepository.saveAndFlush(onNode);
    jdbc.update("UPDATE city SET starmap_object_id = ? WHERE id_city = ?", nodeObject, OTHER_CITY);

    assertThat(resolver.resolve(new ExchangeLocationRef(null, null, NODE_GUID))).contains(onNode);
  }

  @Test
  void aSweptOrHiddenStarmapObjectGivesThePlaceNoGuid() {
    jdbc.update("UPDATE starmap_object SET p4k_deleted_at = now() WHERE p4k_uuid = ?", CITY_GUID);

    assertThat(resolver.resolve(new ExchangeLocationRef(null, null, CITY_GUID))).isEmpty();
    assertThat(locationRepository.findExchangeGuids(List.of(onLinkedCity.getId()))).isEmpty();

    jdbc.update(
        "UPDATE starmap_object SET p4k_deleted_at = NULL, p4k_hidden_at = now() WHERE p4k_uuid = ?",
        CITY_GUID);

    assertThat(resolver.resolve(new ExchangeLocationRef(null, null, CITY_GUID))).isEmpty();
    assertThat(locationRepository.findExchangeGuids(List.of(onLinkedCity.getId()))).isEmpty();
  }

  @Test
  void aHiddenPlaceIsNotFoundByItsGuid() {
    onLinkedCity.setHidden(true);
    locationRepository.saveAndFlush(onLinkedCity);

    assertThat(resolver.resolve(new ExchangeLocationRef(null, null, CITY_GUID))).isEmpty();
  }

  @Test
  void theStockFeedCarriesTheGuidOfAPlaceOnALinkedCityBesideItsUexLinkAndCuratedName() {
    UUID member = member();
    UUID material = material();
    stock(member, material, onLinkedCity);
    stock(member, material, onOtherCity);

    Map<String, ExchangeLocationDto> byName =
        stockFeedService.page(member, null, 100).items().stream()
            .map(ExchangeStockLotDto::location)
            .collect(Collectors.toMap(ExchangeLocationDto::name, Function.identity()));

    assertThat(byName.get(onLinkedCity.getName()))
        .isEqualTo(
            new ExchangeLocationDto(
                onLinkedCity.getName(),
                new ExchangeLocationDto.UexRef("CITY", LINKED_CITY),
                CITY_GUID));
    assertThat(byName.get(onOtherCity.getName()).guid()).isNull();
    assertThat(byName).doesNotContainKey("Xch Guid Starmap Text");
  }

  @Test
  void theStockFeedCarriesNoGuidOfAHiddenStarmapObject() {
    UUID member = member();
    stock(member, material(), onLinkedCity);
    jdbc.update("UPDATE starmap_object SET p4k_hidden_at = now() WHERE p4k_uuid = ?", CITY_GUID);

    assertThat(stockFeedService.page(member, null, 100).items())
        .singleElement()
        .satisfies(lot -> assertThat(lot.location().guid()).isNull());
  }

  /**
   * Stores a UEX city.
   *
   * @param idCity the UEX id
   * @param name the name
   * @return the stored city
   */
  private @NotNull City city(int idCity, @NotNull String name) {
    City city = new City();
    city.setIdCity(idCity);
    city.setName(name);
    city.setHasRefineryTerminal(false);
    return cityRepository.saveAndFlush(city);
  }

  /**
   * Stores a storage place on a city.
   *
   * @param name the curated name
   * @param city the city
   * @return the stored place
   */
  private @NotNull Location location(@NotNull String name, @NotNull City city) {
    Location location = new Location();
    location.setName(name);
    location.setCity(city);
    return locationRepository.saveAndFlush(location);
  }

  /**
   * Stores a P4K starmap object.
   *
   * @param guid its GUID
   * @param name its game-text name, which must never reach the exchange
   * @return the row id
   */
  private @NotNull UUID starmapObject(@NotNull UUID guid, @Nullable String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO starmap_object (id, p4k_uuid, name, version) VALUES (?, ?, ?, 0)",
        id,
        guid,
        name);
    return id;
  }

  /**
   * Stores an approved member.
   *
   * @return the member's id
   */
  private @NotNull UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("xch-guid-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * Stores an SCU material.
   *
   * @return the material's id
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
        "xch-guid-" + id);
    return id;
  }

  /**
   * Stores one personal row of a material at a place.
   *
   * @param member the holder
   * @param material the material
   * @param location the place
   */
  private void stock(@NotNull UUID member, @NotNull UUID material, @NotNull Location location) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen)
        VALUES (?, ?, ?, ?, 0, 5, true, false)
        """,
        UUID.randomUUID(),
        member,
        material,
        location.getId());
  }
}
