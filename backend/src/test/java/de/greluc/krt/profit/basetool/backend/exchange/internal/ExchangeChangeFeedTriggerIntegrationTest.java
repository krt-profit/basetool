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

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The change feed's triggers against Postgres: every write path to a synced table is sequenced,
 * attributed from the transaction variable, and a default-set change reaches every owner
 * (REQ-XCH-013, ADR-0224).
 */
@SpringBootTest
@Transactional
class ExchangeChangeFeedTriggerIntegrationTest {

  /** Every table whose writes the feed must see, with the triggers that sequence them. */
  private static final Set<String> SYNCED_TABLES =
      Set.of("personal_blueprint", "default_blueprint", "inventory_item", "ship");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeChangeRepository changeRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;

  private UUID alice;
  private UUID bob;

  @BeforeEach
  void setUp() {
    alice = user("feed-alice");
    bob = user("feed-bob");
  }

  @Test
  void everySyncedTableCarriesAnExchangeTrigger() {
    List<String> triggered =
        jdbc.queryForList(
            """
            SELECT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
            WHERE NOT t.tgisinternal AND t.tgname LIKE 'trg_%_exchange_change'
            """,
            String.class);

    assertThat(triggered).containsExactlyInAnyOrderElementsOf(SYNCED_TABLES);
  }

  @Test
  void anInsertOutsideAnyRequestIsSequencedAsSystem() {
    blueprint(alice, "laser rifle");

    assertThat(changes(alice))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getResource()).isEqualTo(ExchangeResource.BLUEPRINT);
              assertThat(c.getEntityKey()).isEqualTo("laser rifle");
              assertThat(c.getSourceChannel()).isEqualTo("system");
              assertThat(c.getSourceClient()).isNull();
            });
  }

  @Test
  void aClientWriteIsAttributedToItsClientAndKey() {
    source("client|versekit|" + "K".repeat(43));
    blueprint(alice, "laser rifle");

    assertThat(changes(alice))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getSourceChannel()).isEqualTo("client");
              assertThat(c.getSourceClient()).isEqualTo("versekit");
              assertThat(c.getSourceKey()).isEqualTo("K".repeat(43));
            });
  }

  @Test
  void anUnknownChannelFallsBackToSystem() {
    source("forged|x|y");
    blueprint(alice, "laser rifle");

    assertThat(changes(alice))
        .singleElement()
        .satisfies(c -> assertThat(c.getSourceChannel()).isEqualTo("system"));
  }

  @Test
  void aBulkDeleteSequencesEveryRow() {
    blueprint(alice, "a");
    blueprint(alice, "b");
    source("web");

    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", alice);

    assertThat(changes(alice))
        .filteredOn(c -> "web".equals(c.getSourceChannel()))
        .extracting(ExchangeChange::getEntityKey)
        .containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void anOwnerReassignmentRemovesFromOneAndAddsToTheOther() {
    blueprint(alice, "a");
    int before = changes(bob).size();

    jdbc.update(
        "UPDATE personal_blueprint SET owner_user_id = ? WHERE owner_user_id = ?", bob, alice);

    assertThat(changes(alice)).extracting(ExchangeChange::getEntityKey).containsExactly("a", "a");
    assertThat(changes(bob)).hasSize(before + 1);
  }

  @Test
  void aNoteEditIsOneEntry() {
    blueprint(alice, "a");

    jdbc.update("UPDATE personal_blueprint SET note = 'mine' WHERE owner_user_id = ?", alice);

    assertThat(changes(alice)).hasSize(2);
  }

  @Test
  void aDefaultSetChangeReachesEveryOwnerOfTheProduct() {
    blueprint(alice, "shared");
    blueprint(bob, "shared");
    blueprint(bob, "other");
    int aliceBefore = changes(alice).size();
    int bobBefore = changes(bob).size();

    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name) VALUES (?, 'shared',"
            + " 'Shared')",
        UUID.randomUUID());
    jdbc.update("DELETE FROM default_blueprint WHERE product_key = 'shared'");

    assertThat(changes(alice)).hasSize(aliceBefore + 2);
    assertThat(changes(bob).subList(bobBefore, changes(bob).size()))
        .extracting(ExchangeChange::getEntityKey)
        .containsOnly("shared");
  }

  @Test
  void personalAndSharedStockAreSequencedByLot() {
    UUID material = material("feed-titanium");
    UUID location = location("feed-area18");
    String lot = "m:" + material + "|l:" + location + "|q:3|s:0";

    UUID personal = stock(alice, material, location, 3, true, false);
    UUID shared = stock(alice, material, location, 3, false, false);
    jdbc.update("UPDATE inventory_item SET amount = 5 WHERE id = ?", personal);
    jdbc.update("UPDATE inventory_item SET amount = 6 WHERE id = ?", shared);

    assertThat(changes(alice))
        .extracting(ExchangeChange::getResource, ExchangeChange::getEntityKey)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(ExchangeResource.STOCK, lot),
            org.assertj.core.groups.Tuple.tuple(ExchangeResource.STOCK, lot),
            org.assertj.core.groups.Tuple.tuple(ExchangeResource.STOCK, lot),
            org.assertj.core.groups.Tuple.tuple(ExchangeResource.STOCK, lot));
  }

  @Test
  void rebookingStockBetweenPersonalAndSharedLeavesTheLotUnchanged() {
    UUID material = material("feed-quantainium");
    UUID location = location("feed-orison");
    UUID row = stock(alice, material, location, 1, true, true);
    int before = changes(alice).size();

    jdbc.update("UPDATE inventory_item SET personal = false WHERE id = ?", row);
    jdbc.update("UPDATE inventory_item SET personal = true WHERE id = ?", row);

    assertThat(changes(alice)).hasSize(before);
  }

  @Test
  void theMigrationAnnouncesEachLotHoldingSharedRowsOnce() throws Exception {
    UUID material = material("feed-hadanite");
    UUID location = location("feed-grim-hex");
    stock(alice, material, location, 4, false, false);
    stock(alice, material, location, 4, false, false);
    stock(alice, material, location, 4, true, false);
    stock(alice, material, location, 5, true, false);
    jdbc.update("DELETE FROM exchange_change WHERE user_id = ?", alice);
    String migration =
        new String(
            Objects.requireNonNull(
                    getClass()
                        .getResourceAsStream(
                            "/db/migration/V260__exchange_stock_lots_span_shared_rows.sql"))
                .readAllBytes(),
            StandardCharsets.UTF_8);

    jdbc.execute(migration.substring(migration.indexOf("INSERT INTO exchange_change")));

    assertThat(changes(alice))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getEntityKey())
                  .isEqualTo("m:" + material + "|l:" + location + "|q:4|s:0");
              assertThat(c.getSourceChannel()).isEqualTo("system");
            });
  }

  @Test
  void movingStockToAnotherPlaceSequencesTheLotItLeftAndTheLotItJoined() {
    UUID material = material("feed-agricium");
    UUID from = location("feed-lorville");
    UUID to = location("feed-new-babbage");
    UUID row = stock(alice, material, from, 2, false, false);
    int before = changes(alice).size();

    jdbc.update("UPDATE inventory_item SET location_id = ? WHERE id = ?", to, row);

    assertThat(changes(alice).subList(before, changes(alice).size()))
        .extracting(ExchangeChange::getEntityKey)
        .containsExactly(
            "m:" + material + "|l:" + from + "|q:2|s:0", "m:" + material + "|l:" + to + "|q:2|s:0");
  }

  @Test
  void aShipIsSequencedByItsIdAndFollowsItsOwner() {
    UUID type = shipType("feed-cutter");
    UUID ship = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, name, ship_type_id, owner_id) VALUES (?, 'Nomad', ?, ?)",
        ship,
        type,
        alice);

    jdbc.update("UPDATE ship SET owner_id = ? WHERE id = ?", bob, ship);

    assertThat(changes(alice))
        .extracting(ExchangeChange::getResource, ExchangeChange::getEntityKey)
        .containsOnly(org.assertj.core.groups.Tuple.tuple(ExchangeResource.SHIP, ship.toString()))
        .hasSize(2);
    assertThat(changes(bob)).extracting(ExchangeChange::getEntityKey).contains(ship.toString());
  }

  @Test
  void deletingAMemberDropsTheirFeedWithoutFailing() {
    blueprint(alice, "a");

    jdbc.update("DELETE FROM app_user WHERE id = ?", alice);

    assertThat(changes(alice)).isEmpty();
  }

  /**
   * Sets the transaction variable the triggers read.
   *
   * @param source the source
   */
  private void source(@NotNull String source) {
    jdbc.queryForObject(
        "SELECT set_config('basetool.change_source', ?, true)", String.class, source);
  }

  /**
   * Inserts a personal blueprint directly, as a bulk path would.
   *
   * @param owner the owner
   * @param productKey the product key
   */
  private void blueprint(@NotNull UUID owner, @NotNull String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        owner,
        productKey,
        productKey);
  }

  /**
   * Inserts a stock row directly, as a bulk path would.
   *
   * @param owner the owner
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param personal whether it is the owner's personal stock
   * @param stolen whether it is stolen
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      boolean personal,
      boolean stolen) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen)
        VALUES (?, ?, ?, ?, ?, 1, ?, ?)
        """,
        id,
        owner,
        material,
        location,
        quality,
        personal,
        stolen);
    return id;
  }

  /**
   * Seeds a material.
   *
   * @param name the unique name
   * @return its id
   */
  private @NotNull UUID material(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        name);
    return id;
  }

  /**
   * Seeds a location.
   *
   * @param name the unique name
   * @return its id
   */
  private @NotNull UUID location(@NotNull String name) {
    Location location = new Location();
    location.setName(name);
    return locationRepository.saveAndFlush(location).getId();
  }

  /**
   * Seeds a ship type.
   *
   * @param name the unique name
   * @return its id
   */
  private @NotNull UUID shipType(@NotNull String name) {
    ShipType type = new ShipType();
    type.setName(name);
    return shipTypeRepository.saveAndFlush(type).getId();
  }

  /**
   * Lists a member's feed entries in sequence order.
   *
   * @param member the member
   * @return the entries
   */
  private @NotNull List<ExchangeChange> changes(@NotNull UUID member) {
    return changeRepository.findAllByUserIdOrderBySeqAsc(member);
  }

  /**
   * Seeds a member.
   *
   * @param username the username
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }
}
