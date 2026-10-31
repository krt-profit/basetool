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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * The change feed's triggers against Postgres: every write path to a synced table is sequenced,
 * attributed from the transaction variable, a default-set change reaches every owner, and every
 * change of a pool's shared rows reaches the org-stock feed keyed by unit (REQ-XCH-013,
 * REQ-XCH-042, ADR-0224).
 */
@SpringBootTest
@Transactional
class ExchangeChangeFeedTriggerIntegrationTest {

  /** Every table whose writes the feed must see, with the triggers that sequence them. */
  private static final Set<String> SYNCED_TABLES =
      Set.of(
          "personal_blueprint",
          "default_blueprint",
          "inventory_item",
          "inventory_item_job_order_allocation",
          "inventory_item_mission_allocation",
          "ship",
          "org_unit",
          "org_unit_membership",
          "app_user",
          "exchange_name_withholding",
          "mission");

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
        .satisfies(c -> {
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
        .satisfies(c -> {
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
  void rebookingStockBetweenPersonalAndSharedSequencesItsLotUnderTheSameKey() {
    UUID material = material("feed-quantainium");
    UUID location = location("feed-orison");
    String lot = "m:" + material + "|l:" + location + "|q:1|s:1";
    UUID row = stock(alice, material, location, 1, true, true);
    int before = changes(alice).size();

    jdbc.update("UPDATE inventory_item SET personal = false WHERE id = ?", row);
    jdbc.update("UPDATE inventory_item SET personal = true WHERE id = ?", row);

    assertThat(changes(alice).subList(before, changes(alice).size()))
        .extracting(ExchangeChange::getEntityKey)
        .containsExactly(lot, lot);
  }

  @Test
  void movingASharedRowToAnotherPoolSequencesItsLotButRestampingAPersonalRowDoesNot() {
    UUID from = orgUnit("feed-split-from");
    UUID to = orgUnit("feed-split-to");
    UUID material = material("feed-stileron");
    UUID location = location("feed-pyro");
    UUID shared = stock(alice, material, location, 1, false, false, from);
    UUID personal = stock(alice, material, location, 1, true, false, from);
    int before = changes(alice).size();

    jdbc.update("UPDATE inventory_item SET owning_org_unit_id = ? WHERE id = ?", to, shared);
    jdbc.update("UPDATE inventory_item SET owning_org_unit_id = ? WHERE id = ?", to, personal);

    assertThat(changes(alice).subList(before, changes(alice).size()))
        .extracting(ExchangeChange::getEntityKey)
        .containsExactly("m:" + material + "|l:" + location + "|q:1|s:0");
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
        .satisfies(c -> {
          assertThat(c.getEntityKey()).isEqualTo("m:" + material + "|l:" + location + "|q:4|s:0");
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
  void everyChangeOfASharedRowIsSequencedByItsUnitLot() {
    UUID pool = orgUnit("feed-pool");
    UUID material = material("feed-laranite");
    UUID location = location("feed-levski");
    String lot = orgLot(pool, material, location, 2, false);
    source("web");

    UUID row = stock(alice, material, location, 2, false, false, pool);
    jdbc.update("UPDATE inventory_item SET amount = 4 WHERE id = ?", row);
    jdbc.update("UPDATE inventory_item SET user_id = ? WHERE id = ?", bob, row);
    jdbc.update("UPDATE inventory_item SET note = 'for the op' WHERE id = ?", row);
    jdbc.update("UPDATE inventory_item SET version = version + 1 WHERE id = ?", row);
    jdbc.update("DELETE FROM inventory_item WHERE id = ?", row);

    assertThat(orgChanges(pool))
        .extracting(OrgChange::key, OrgChange::channel)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(lot, "web"),
            org.assertj.core.groups.Tuple.tuple(lot, "web"),
            org.assertj.core.groups.Tuple.tuple(lot, "web"),
            org.assertj.core.groups.Tuple.tuple(lot, "web"),
            org.assertj.core.groups.Tuple.tuple(lot, "web"));
  }

  @Test
  void aClientChangeOfTheOrgStockIsAttributedToItsClientAndKey() {
    UUID pool = orgUnit("feed-client-pool");
    source("client|versekit|" + "K".repeat(43));

    stock(alice, material("feed-iron"), location("feed-hurston"), 1, false, false, pool);

    assertThat(
            jdbc.queryForMap(
                "SELECT source_channel, source_client, source_key, user_id FROM exchange_change"
                    + " WHERE org_unit_id = ?",
                pool))
        .containsEntry("source_channel", "client")
        .containsEntry("source_client", "versekit")
        .containsEntry("source_key", "K".repeat(43))
        .containsEntry("user_id", null);
  }

  @Test
  void personalRowsAndOwnerlessSharedRowsNeverReachTheOrgFeed() {
    UUID pool = orgUnit("feed-quiet-pool");
    UUID material = material("feed-copper");
    UUID location = location("feed-microtech");

    UUID personal = stock(alice, material, location, 1, true, false, pool);
    jdbc.update("UPDATE inventory_item SET amount = 3 WHERE id = ?", personal);
    stock(alice, material, location, 1, false, false, null);

    assertThat(orgChanges(pool)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_change WHERE resource = 'ORG_STOCK'"
                    + " AND entity_key LIKE ?",
                Integer.class,
                "%m:" + material + "%"))
        .isZero();
  }

  @Test
  void rebookingBetweenPersonalAndSharedIsAnOrgStockChange() {
    UUID pool = orgUnit("feed-rebook-pool");
    UUID material = material("feed-gold");
    UUID location = location("feed-port-olisar");
    UUID row = stock(alice, material, location, 1, true, false, pool);

    jdbc.update("UPDATE inventory_item SET personal = false WHERE id = ?", row);
    jdbc.update("UPDATE inventory_item SET personal = true WHERE id = ?", row);

    assertThat(orgChanges(pool))
        .extracting(OrgChange::key)
        .containsExactly(
            orgLot(pool, material, location, 1, false), orgLot(pool, material, location, 1, false));
  }

  @Test
  void changingTheUnitSequencesTheLotItLeftAndTheLotItJoined() {
    UUID from = orgUnit("feed-from-pool");
    UUID to = orgUnit("feed-to-pool");
    UUID material = material("feed-beryl");
    UUID location = location("feed-everus");
    UUID row = stock(alice, material, location, 1, false, false, from);

    jdbc.update("UPDATE inventory_item SET owning_org_unit_id = ? WHERE id = ?", to, row);

    assertThat(orgChanges(from))
        .extracting(OrgChange::key)
        .containsExactly(
            orgLot(from, material, location, 1, false), orgLot(from, material, location, 1, false));
    assertThat(orgChanges(to))
        .extracting(OrgChange::key)
        .containsExactly(orgLot(to, material, location, 1, false));
  }

  @Test
  void aMoveOrAStolenMarkSequencesBothLotsOfThePool() {
    UUID pool = orgUnit("feed-move-pool");
    UUID material = material("feed-taranite");
    UUID from = location("feed-a18");
    UUID to = location("feed-nb");
    UUID row = stock(alice, material, from, 1, false, false, pool);

    jdbc.update("UPDATE inventory_item SET location_id = ? WHERE id = ?", to, row);
    jdbc.update("UPDATE inventory_item SET stolen = true WHERE id = ?", row);

    assertThat(orgChanges(pool))
        .extracting(OrgChange::key)
        .containsExactly(
            orgLot(pool, material, from, 1, false),
            orgLot(pool, material, from, 1, false),
            orgLot(pool, material, to, 1, false),
            orgLot(pool, material, to, 1, false),
            orgLot(pool, material, to, 1, true));
  }

  @Test
  void anEarmarkInsertUpdateOrDeleteSequencesTheLotOfItsRow() {
    UUID pool = orgUnit("feed-earmark-pool");
    UUID material = material("feed-corundum");
    UUID location = location("feed-orison-2");
    UUID row = stock(alice, material, location, 1, false, false, pool);
    UUID personal = stock(alice, material, location, 1, true, false, pool);
    int before = orgChanges(pool).size();
    UUID mission = UUID.randomUUID();
    jdbc.update("INSERT INTO mission (id, name, version) VALUES (?, 'Op', 0)", mission);
    UUID shared = UUID.randomUUID();

    jdbc.update(
        """
        INSERT INTO inventory_item_mission_allocation (id, inventory_item_id, mission_id, amount,
                                                       version, created_at, updated_at)
        VALUES (?, ?, ?, 1, 0, now(), now())
        """,
        shared,
        row,
        mission);
    jdbc.update("UPDATE inventory_item_mission_allocation SET amount = 0.5 WHERE id = ?", shared);
    jdbc.update("DELETE FROM inventory_item_mission_allocation WHERE id = ?", shared);
    jdbc.update(
        """
        INSERT INTO inventory_item_mission_allocation (id, inventory_item_id, mission_id, amount,
                                                       version, created_at, updated_at)
        VALUES (?, ?, ?, 1, 0, now(), now())
        """,
        UUID.randomUUID(),
        personal,
        mission);

    assertThat(orgChanges(pool).subList(before, orgChanges(pool).size()))
        .extracting(OrgChange::key)
        .containsExactly(
            orgLot(pool, material, location, 1, false),
            orgLot(pool, material, location, 1, false),
            orgLot(pool, material, location, 1, false));
  }

  @Test
  void aJobOrderEarmarkSequencesTheLotOfItsRow() {
    UUID pool = orgUnit("feed-order-pool");
    UUID material = material("feed-tungsten");
    UUID location = location("feed-lorville-2");
    UUID row = stock(alice, material, location, 1, false, false, pool);
    int before = orgChanges(pool).size();
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        pool,
        pool);

    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, 1, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        row,
        order);

    assertThat(orgChanges(pool).subList(before, orgChanges(pool).size()))
        .extracting(OrgChange::key)
        .containsExactly(orgLot(pool, material, location, 1, false));
  }

  @Test
  void joiningAUnitAnnouncesEveryLotOfItsPoolOnce() {
    UUID pool = orgUnit("feed-join-pool");
    UUID material = material("feed-agricium-2");
    UUID location = location("feed-grim-hex-2");
    stock(bob, material, location, 1, false, false, pool);
    stock(bob, material, location, 1, false, false, pool);
    stock(bob, material, location, 1, true, false, pool);
    int before = orgChanges(pool).size();

    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, 'MEMBER')",
        alice,
        pool);

    assertThat(orgChanges(pool).subList(before, orgChanges(pool).size()))
        .extracting(OrgChange::key)
        .containsExactly(orgLot(pool, material, location, 1, false));
  }

  @Test
  void anAppChangeOfQualityOrMaterialSequencesBothLotsOfThePool() {
    UUID pool = orgUnit("feed-app-pool");
    UUID material = material("feed-hephaestanite");
    UUID other = material("feed-borase");
    UUID location = location("feed-seraphim");
    UUID row = stock(alice, material, location, 1, false, false, pool);
    int before = orgChanges(pool).size();
    source("app");

    jdbc.update("UPDATE inventory_item SET quality = 7 WHERE id = ?", row);
    jdbc.update("UPDATE inventory_item SET material_id = ? WHERE id = ?", other, row);

    assertThat(orgChanges(pool).subList(before, orgChanges(pool).size()))
        .extracting(OrgChange::key, OrgChange::channel)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(orgLot(pool, material, location, 1, false), "app"),
            org.assertj.core.groups.Tuple.tuple(orgLot(pool, material, location, 7, false), "app"),
            org.assertj.core.groups.Tuple.tuple(orgLot(pool, material, location, 7, false), "app"),
            org.assertj.core.groups.Tuple.tuple(orgLot(pool, other, location, 7, false), "app"));
  }

  @Test
  void aMembershipMovedToAnotherUnitAnnouncesThatPool() {
    UUID from = orgUnit("feed-membership-from");
    UUID to = orgUnit("feed-membership-to");
    UUID material = material("feed-aluminium");
    UUID location = location("feed-tressler");
    stock(bob, material, location, 1, false, false, to);
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, 'MEMBER')",
        alice,
        from);
    int before = orgChanges(to).size();

    jdbc.update(
        "UPDATE org_unit_membership SET org_unit_id = ? WHERE user_id = ? AND org_unit_id = ?",
        to,
        alice,
        from);

    assertThat(orgChanges(to).subList(before, orgChanges(to).size()))
        .extracting(OrgChange::key)
        .containsExactly(orgLot(to, material, location, 1, false));
  }

  @Test
  void renamingAUnitAnnouncesEveryLotOfItsPool() {
    UUID pool = orgUnit("feed-rename-pool");
    UUID material = material("feed-bexalite");
    UUID location = location("feed-area-18-3");
    stock(bob, material, location, 1, false, false, pool);
    int before = orgChanges(pool).size();

    jdbc.update("UPDATE org_unit SET name = 'feed-renamed-pool' WHERE id = ?", pool);

    assertThat(orgChanges(pool).subList(before, orgChanges(pool).size()))
        .extracting(OrgChange::key)
        .containsExactly(orgLot(pool, material, location, 1, false));
  }

  @Test
  void theMigrationAnnouncesEachSharedLotOfEveryPoolOnce() throws Exception {
    UUID pool = orgUnit("feed-backfill-pool");
    UUID material = material("feed-diamond");
    UUID location = location("feed-crusader");
    stock(alice, material, location, 4, false, false, pool);
    stock(bob, material, location, 4, false, false, pool);
    stock(alice, material, location, 4, true, false, pool);
    jdbc.update("DELETE FROM exchange_change WHERE org_unit_id = ?", pool);
    String migration =
        new String(
            Objects.requireNonNull(
                    getClass()
                        .getResourceAsStream("/db/migration/V296__exchange_org_stock_feed.sql"))
                .readAllBytes(),
            StandardCharsets.UTF_8);

    jdbc.execute(migration.substring(migration.lastIndexOf("INSERT INTO exchange_change")));

    assertThat(orgChanges(pool))
        .extracting(OrgChange::key, OrgChange::channel)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                orgLot(pool, material, location, 4, false), "system"));
  }

  @Test
  void deletingAUnitDropsItsOrgFeedWithoutFailing() {
    UUID pool = orgUnit("feed-gone-pool");
    stock(alice, material("feed-silicon"), location("feed-arccorp"), 1, false, false, pool);
    jdbc.update("DELETE FROM inventory_item WHERE owning_org_unit_id = ?", pool);

    jdbc.update("DELETE FROM org_unit WHERE id = ?", pool);

    assertThat(orgChanges(pool)).isEmpty();
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
    return stock(owner, material, location, quality, personal, stolen, null);
  }

  /**
   * Inserts a stock row of an org unit's pool directly, as a bulk path would.
   *
   * @param owner the holder
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param personal whether it is the holder's personal stock
   * @param stolen whether it is stolen
   * @param pool the owning org unit, or {@code null}
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      boolean personal,
      boolean stolen,
      @Nullable UUID pool) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id)
        VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
        """,
        id,
        owner,
        material,
        location,
        quality,
        personal,
        stolen,
        pool);
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
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, FALSE)",
        id,
        name,
        "F" + id.toString().substring(0, 8));
    return id;
  }

  /**
   * Builds the org-stock key of a material lot in a pool.
   *
   * @param pool the owning org unit
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param stolen whether it is stolen
   * @return the key
   */
  private static @NotNull String orgLot(
      @NotNull UUID pool,
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      boolean stolen) {
    return "u:"
        + pool
        + "|m:"
        + material
        + "|l:"
        + location
        + "|q:"
        + quality
        + "|s:"
        + (stolen ? "1" : "0");
  }

  /**
   * Lists a pool's org-stock feed entries in sequence order.
   *
   * @param pool the org unit
   * @return the entries' keys and channels
   */
  private @NotNull List<OrgChange> orgChanges(@NotNull UUID pool) {
    return jdbc.query(
        "SELECT entity_key, source_channel FROM exchange_change"
            + " WHERE org_unit_id = ? AND resource = 'ORG_STOCK' ORDER BY seq",
        (rs, _) -> new OrgChange(rs.getString(1), rs.getString(2)),
        pool);
  }

  /**
   * One org-stock feed entry.
   *
   * @param key the org-stock lot key
   * @param channel the writing channel
   */
  private record OrgChange(@NotNull String key, @NotNull String channel) {}

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
