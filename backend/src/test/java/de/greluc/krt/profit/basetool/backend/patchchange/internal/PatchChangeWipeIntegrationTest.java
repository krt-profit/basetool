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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderResponsibilityGuard;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LivesyncMetricNames;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchKind;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeExceptionMode;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeExceptionTarget;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroup;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyPatchLabels;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wipes stock and in-game ships through a patch change against PostgreSQL (REQ-PATCH-010 …
 * REQ-PATCH-017): only what was created before the live time goes, by group and exception, with its
 * offers, pledges and earmarks, through the ordinary ship deletion, past an order with invalid
 * responsibility, and with one summary event per area.
 *
 * <p>Its live times lie in 1999 to 2001, before every other test's dated rows; a trigger also wipes
 * the undated rows other tests left behind, which this class never counts exactly.
 */
@SpringBootTest
class PatchChangeWipeIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");
  private static final Instant AFTER = Instant.parse("2001-06-01T00:00:00Z");

  @Autowired private PatchChangeTrigger trigger;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JobOrderRepository orderRepository;
  @Autowired private JobOrderResponsibilityGuard responsibilityGuard;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private SupplyPatchLabels supplyPatchLabels;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> units = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> gameItems = new ArrayList<>();
  private final List<UUID> shipTypes = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();

  private UUID member;
  private UUID otherMember;
  private UUID unit;
  private UUID location;
  private UUID shipType;
  private UUID ore;
  private UUID gold;
  private UUID ration;
  private UUID rifle;

  @BeforeEach
  void setUp() {
    UUID admin = user("wipe-admin-");
    member = user("wipe-member-");
    otherMember = user("wipe-other-");
    unit = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible)"
            + " VALUES (?, 'SPECIAL_COMMAND', ?, ?, true, false)",
        unit,
        "Wipe unit " + unit,
        "W" + unit.toString().substring(0, 8));
    units.add(unit);
    location = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, location, "Wipe station " + location);
    shipType = UUID.randomUUID();
    jdbc.update("INSERT INTO ship_type (id, name) VALUES (?, ?)", shipType, "Wiper " + shipType);
    shipTypes.add(shipType);
    ore = material("Wipe ore", "RAW");
    gold = material("Wipe gold", "REFINED");
    ration = gameItem("Wipe ration", "Food", "FOOD");
    rifle = gameItem("Wipe rifle", "WeaponPersonal", "WEAPON");
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(admin.toString())
            .claim("azp", "basetool-frontend")
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM patch_change");
    for (UUID mission : missions) {
      jdbc.update("DELETE FROM mission_unit WHERE mission_id = ?", mission);
      jdbc.update("DELETE FROM mission WHERE id = ?", mission);
    }
    for (UUID user : users) {
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", user);
      jdbc.update("DELETE FROM supply_donation WHERE donor_user_id = ?", user);
      jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", user);
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
    }
    jobOrders.forEach(order -> jdbc.update("DELETE FROM job_order WHERE id = ?", order));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    gameItems.forEach(id -> jdbc.update("DELETE FROM game_item WHERE id = ?", id));
    shipTypes.forEach(id -> jdbc.update("DELETE FROM ship_type WHERE id = ?", id));
    units.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    users.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
  }

  @Test
  void aFullWipeRemovesThePreLiveTimeStockAndShipsWithTheirDependents() {
    UUID oreBefore = materialRow(member, ore, BEFORE, false);
    UUID oreAfter = materialRow(member, ore, AFTER, false);
    UUID goldBefore = materialRow(otherMember, gold, BEFORE, false);
    UUID rationBefore = itemRow(member, ration, BEFORE, true);
    UUID rifleUndated = itemRow(otherMember, rifle, null, true);
    UUID order = invalidJobOrder();
    Set<UUID> validOrders =
        new TransactionTemplate(transactionManager)
            .execute(_ ->
                responsibilityGuard.validOrderIds(orderRepository.findAllById(List.of(order))));
    assertThat(validOrders)
        .as("an ordinary stock write would refuse this order (REQ-ORDERS-055)")
        .isEmpty();
    UUID earmark = earmark(oreBefore, order);
    UUID offer = offer(oreBefore);
    UUID pledge = pledge(oreBefore);
    UUID inGameBefore = ship("IN_GAME", false, BEFORE);
    UUID inGameAfter = ship("IN_GAME", false, AFTER);
    UUID pledgedBefore = ship("PLEDGE", false, BEFORE);
    UUID lockedBefore = ship("IN_GAME", true, BEFORE);
    UUID unknownBefore = ship("UNKNOWN", false, BEFORE);
    UUID crew = missionUnit(inGameBefore);
    WipeException keepGold =
        new WipeException(WipeExceptionTarget.MATERIAL, gold, WipeExceptionMode.KEEP);
    PatchChangeOptions options =
        new PatchChangeOptions(EnumSet.allOf(WipeGroup.class), Set.of(keepGold), true, false, true);
    double lagerFrames = frames("inventory_all");
    double orderFrames = frames("order_detail");
    double queueFrames = frames("orders_queue");

    PatchChangeDto recorded =
        trigger.trigger(
            new TriggerPatchChangeRequest("0.1", PatchKind.FULL_WIPE, LIVE_AT, options, "0.1"));

    assertThat(frames("inventory_all"))
        .as("the Lager room refreshes after the commit (REQ-PATCH-018)")
        .isGreaterThan(lagerFrames);
    assertThat(frames("order_detail"))
        .as("the room of the order whose earmark went refreshes after the commit")
        .isGreaterThan(orderFrames);
    assertThat(frames("orders_queue"))
        .as("the order queue refreshes after the commit")
        .isGreaterThan(queueFrames);
    assertThat(exists("inventory_item", oreBefore)).isFalse();
    assertThat(exists("inventory_item", rationBefore)).isFalse();
    assertThat(exists("inventory_item", rifleUndated)).isFalse();
    assertThat(exists("inventory_item", oreAfter)).isTrue();
    assertThat(exists("inventory_item", goldBefore)).isTrue();
    assertThat(exists("inventory_item_job_order_allocation", earmark)).isFalse();
    assertThat(exists("job_order", order)).isTrue();
    assertThat(exists("material_exchange_offer", offer)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM supply_donation WHERE id = ?", String.class, pledge))
        .isEqualTo("WITHDRAWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT withdrawn_by_patch_change FROM supply_donation WHERE id = ?",
                UUID.class,
                pledge))
        .isEqualTo(recorded.id());
    assertThat(supplyPatchLabels.labels(List.of(recorded.id())))
        .as("the donation lists name the withdrawing patch change by its label (REQ-SUP-010)")
        .containsEntry(recorded.id(), "0.1");
    assertThat(exists("ship", inGameBefore)).isFalse();
    assertThat(exists("ship", inGameAfter)).isTrue();
    assertThat(exists("ship", pledgedBefore)).isTrue();
    assertThat(exists("ship", lockedBefore)).isTrue();
    assertThat(exists("ship", unknownBefore)).isTrue();
    assertThat(
            jdbc.queryForObject("SELECT ship_id FROM mission_unit WHERE id = ?", UUID.class, crew))
        .isNull();
    assertThat(recorded.wipeExceptions())
        .containsExactly(
            new WipeExceptionDto(
                WipeExceptionTarget.MATERIAL, gold, WipeExceptionMode.KEEP, "Wipe gold " + gold));
    assertThat(recorded.counts())
        .extracting(PatchCountDto::kind)
        .contains(
            PatchCount.STOCK_ROWS_WIPED,
            PatchCount.IN_GAME_SHIPS_WIPED,
            PatchCount.STOCK_MEMBERS_AFFECTED,
            PatchCount.JOB_ORDERS_AFFECTED,
            PatchCount.MARKET_OFFERS_ENDED,
            PatchCount.DONATIONS_WITHDRAWN);
    assertThat(auditCount("INVENTORY_WIPED_BY_PATCH", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("HANGAR_SHIPS_WIPED_BY_PATCH", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("DONATIONS_WITHDRAWN_BY_PATCH", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("MARKET_OFFERS_ENDED_BY_PATCH", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("JOB_ORDERS_STOCK_WIPED_BY_PATCH", recorded.id())).isEqualTo(1L);
    assertThat(auditCount("MARKET_OFFER_REMOVED", offer)).isZero();
    assertThat(auditCount("HANGAR_SHIP_DELETED", inGameBefore)).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT entity_key FROM exchange_change WHERE user_id = ? AND resource = 'SHIP'"
                    + " AND source_channel = 'patch' AND source_patch_change = ?",
                String.class,
                member,
                recorded.id()))
        .contains(inGameBefore.toString());
  }

  @Test
  void aRebuildWipesItsGroupsAndAWipingExceptionButKeepsTheRest() {
    UUID rationBefore = itemRow(member, ration, BEFORE, false);
    UUID rationAfter = itemRow(member, ration, AFTER, false);
    UUID rifleBefore = itemRow(member, rifle, BEFORE, false);
    UUID oreBefore = materialRow(member, ore, BEFORE, false);
    UUID inGameBefore = ship("IN_GAME", false, BEFORE);
    WipeException wipeRifle =
        new WipeException(WipeExceptionTarget.GAME_ITEM, rifle, WipeExceptionMode.WIPE);
    PatchChangeOptions preset = PatchChangeOptions.presetFor(PatchKind.REBUILD);
    PatchChangeOptions options =
        new PatchChangeOptions(
            preset.wipeGroups(),
            Set.of(wipeRifle),
            preset.cancelRefineryOrders(),
            preset.currencyWipe(),
            preset.homeLocationPrompt());

    trigger.trigger(
        new TriggerPatchChangeRequest("0.1", PatchKind.REBUILD, LIVE_AT, options, "0.1"));

    assertThat(exists("inventory_item", rationBefore)).isFalse();
    assertThat(exists("inventory_item", rifleBefore)).isFalse();
    assertThat(exists("inventory_item", rationAfter)).isTrue();
    assertThat(exists("inventory_item", oreBefore)).isTrue();
    assertThat(exists("ship", inGameBefore)).isTrue();
  }

  @Test
  void thePreviewCountsEachGroupAndExceptionWithoutWriting() {
    UUID goldBefore = materialRow(member, gold, BEFORE, false);
    materialRow(otherMember, gold, BEFORE, false);
    materialRow(member, gold, AFTER, false);
    UUID rifleBefore = itemRow(member, rifle, BEFORE, false);
    WipeException keepGold =
        new WipeException(WipeExceptionTarget.MATERIAL, gold, WipeExceptionMode.KEEP);
    WipeException wipeRifle =
        new WipeException(WipeExceptionTarget.GAME_ITEM, rifle, WipeExceptionMode.WIPE);

    PatchChangePreviewDto preview =
        trigger.preview(
            new PatchChangePreviewRequest(
                LIVE_AT,
                new PatchChangeOptions(
                    Set.of(WipeGroup.MATERIALS_REFINED),
                    Set.of(keepGold, wipeRifle),
                    false,
                    false,
                    false)));

    assertThat(preview.wipe().exceptions())
        .containsExactly(
            new WipePreviewDto.ExceptionCount(
                WipeExceptionDto.of(keepGold, "Wipe gold " + gold), 2, 2),
            new WipePreviewDto.ExceptionCount(
                WipeExceptionDto.of(wipeRifle, "Wipe rifle " + rifle), 1, 1));
    assertThat(preview.wipe().groups())
        .extracting(WipePreviewDto.GroupCount::group)
        .containsExactly(WipeGroup.values());
    assertThat(preview.counts())
        .extracting(PatchCountDto::kind)
        .contains(PatchCount.STOCK_ROWS_WIPED)
        .doesNotContain(PatchCount.IN_GAME_SHIPS_WIPED, PatchCount.REFINERY_ORDERS_CANCELED);
    assertThat(exists("inventory_item", goldBefore)).isTrue();
    assertThat(exists("inventory_item", rifleBefore)).isTrue();
  }

  @Test
  void anUnknownOrADoubleExceptionIsRefused() {
    WipeException unknown =
        new WipeException(WipeExceptionTarget.GAME_ITEM, UUID.randomUUID(), WipeExceptionMode.KEEP);
    WipeException keepRifle =
        new WipeException(WipeExceptionTarget.GAME_ITEM, rifle, WipeExceptionMode.KEEP);
    WipeException wipeRifle =
        new WipeException(WipeExceptionTarget.GAME_ITEM, rifle, WipeExceptionMode.WIPE);

    assertThatThrownBy(() -> trigger.preview(previewOf(Set.of(unknown))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.wipe_exception_unknown");
    assertThatThrownBy(() -> trigger.preview(previewOf(Set.of(keepRifle, wipeRifle))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.wipe_exception_duplicate");
  }

  /**
   * Builds a preview of the given exceptions and no groups.
   *
   * @param exceptions the exceptions
   * @return the request
   */
  private static PatchChangePreviewRequest previewOf(@NotNull Set<WipeException> exceptions) {
    return new PatchChangePreviewRequest(
        LIVE_AT, new PatchChangeOptions(Set.of(), exceptions, false, false, false));
  }

  private @NotNull UUID user(@NotNull String prefix) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)", id, prefix + id);
    users.add(id);
    return id;
  }

  private @NotNull UUID material(@NotNull String name, @NotNull String type) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, ?, 'SCU', false, false, true, 'UEX_ONLY')",
        id,
        name + " " + id,
        type);
    materials.add(id);
    return id;
  }

  private @NotNull UUID gameItem(@NotNull String name, @NotNull String type, @NotNull String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems, wiki_type)"
            + " VALUES (?, ?, ?, 'UEX_ONLY', ?)",
        id,
        name + " " + id,
        kind,
        type);
    gameItems.add(id);
    return id;
  }

  private @NotNull UUID materialRow(
      @NotNull UUID holder, @NotNull UUID material, @Nullable Instant createdAt, boolean personal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,"
            + " personal, stolen, owning_org_unit_id, version, created_at)"
            + " VALUES (?, ?, ?, ?, 500, 10, ?, false, ?, 0, ?)",
        id,
        holder,
        material,
        location,
        personal,
        unit,
        createdAt == null ? null : Timestamp.from(createdAt));
    return id;
  }

  private @NotNull UUID itemRow(
      @NotNull UUID holder, @NotNull UUID gameItem, @Nullable Instant createdAt, boolean personal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount,"
            + " personal, stolen, owning_org_unit_id, version, created_at)"
            + " VALUES (?, ?, ?, ?, 3, ?, false, ?, 0, ?)",
        id,
        holder,
        gameItem,
        location,
        personal,
        unit,
        createdAt == null ? null : Timestamp.from(createdAt));
    return id;
  }

  private @NotNull UUID invalidJobOrder() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version,"
            + " status, requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        id,
        unit,
        unit);
    jobOrders.add(id);
    return id;
  }

  private @NotNull UUID earmark(@NotNull UUID row, @NotNull UUID order) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,"
            + " amount, delivered, version, created_at, updated_at)"
            + " VALUES (?, ?, ?, 2, false, 0, now(), now())",
        id,
        row,
        order);
    return id;
  }

  private @NotNull UUID offer(@NotNull UUID row) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_offer (id, inventory_item_id, owner_id, status,"
            + " released_at, version, created_at, updated_at, offered_amount, offer_kind)"
            + " VALUES (?, ?, ?, 'ACTIVE', now(), 0, now(), now(), 5, 'MATERIAL')",
        id,
        row,
        member);
    return id;
  }

  private @NotNull UUID pledge(@NotNull UUID row) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO supply_donation (id, donor_user_id, receiving_org_unit_id, material_id,"
            + " quality, pledged_amount, status, source_inventory_item_id)"
            + " VALUES (?, ?, ?, ?, 500, 3, 'PLEDGED', ?)",
        id,
        member,
        unit,
        ore,
        row);
    return id;
  }

  private @NotNull UUID ship(@NotNull String origin, boolean locked, @NotNull Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, name, ship_type_id, owner_id, origin, origin_locked, version,"
            + " created_at) VALUES (?, 'Wiped?', ?, ?, ?, ?, 0, ?)",
        id,
        shipType,
        member,
        origin,
        locked,
        Timestamp.from(createdAt));
    return id;
  }

  private @NotNull UUID missionUnit(@NotNull UUID ship) {
    UUID mission = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", mission, "Op " + mission);
    missions.add(mission);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_unit (id, mission_id, ship_id, name, high_value_unit, version)"
            + " VALUES (?, ?, ?, 'Alpha', false, 0)",
        id,
        mission,
        ship);
    return id;
  }

  private boolean exists(@NotNull String table, @NotNull UUID id) {
    Long count =
        jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
    return count != null && count > 0;
  }

  private long auditCount(@NotNull String type, @NotNull UUID subject) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
            Long.class,
            type,
            subject);
    return count == null ? 0 : count;
  }

  /**
   * Reads how many live-sync frames the backend published for one room class.
   *
   * @param topicClass the room class's metric label
   * @return the count so far
   */
  private double frames(@NotNull String topicClass) {
    Counter counter =
        meterRegistry
            .find(LivesyncMetricNames.LIVESYNC_PUBLISH_ACCEPTED)
            .tag(LivesyncMetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
