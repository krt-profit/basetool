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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres characterisation of the whole account merge ({@link UserAccountMergeService#merge},
 * REQ-SEC-045): which table of which module is re-pointed, which duplicate the target already holds
 * is dropped, which row of a bystander and which row recording an act stays, and the {@code
 * USER_MERGED} audit row with its exact details and key order.
 *
 * <p>The source account owns rows in every one of the 29 followed columns, the target holds the
 * same key in every table with a unique constraint, and a bystander owns a row of each kind. The
 * expectation list is spelled out here, independent of the code under test, so the steps can move
 * into the owning modules.
 */
@SpringBootTest
@Transactional
class UserAccountMergeEndStateTest {

  /** The followed columns in the order the merge reports them, with the rows each moves. */
  private static final Map<String, Integer> EXPECTED_MOVED = new LinkedHashMap<>();

  static {
    EXPECTED_MOVED.put("inventory_item.user_id", 2);
    EXPECTED_MOVED.put("ship.owner_id", 2);
    EXPECTED_MOVED.put("refinery_order.owner_id", 2);
    EXPECTED_MOVED.put("personal_inventory_item.owner_user_id", 1);
    EXPECTED_MOVED.put("personal_blueprint.owner_user_id", 1);
    EXPECTED_MOVED.put("org_unit_membership.user_id", 1);
    EXPECTED_MOVED.put("org_chart_position.user_id", 1);
    EXPECTED_MOVED.put("org_unit.grand_admiral_user_id", 1);
    EXPECTED_MOVED.put("mission.owner_id", 1);
    EXPECTED_MOVED.put("mission_ownership.owner_id", 1);
    EXPECTED_MOVED.put("mission.party_lead_user_id", 1);
    EXPECTED_MOVED.put("mission_unit.responsible_user_id", 1);
    EXPECTED_MOVED.put("mission_managers.user_id", 1);
    EXPECTED_MOVED.put("mission_participant.user_id", 1);
    EXPECTED_MOVED.put("job_order_assignees.user_id", 1);
    EXPECTED_MOVED.put("material_exchange_offer.owner_id", 1);
    EXPECTED_MOVED.put("material_exchange_request.owner_id", 1);
    EXPECTED_MOVED.put("material_exchange_interest.interested_user_id", 1);
    EXPECTED_MOVED.put("material_exchange_request_interest.interested_user_id", 1);
    EXPECTED_MOVED.put("bank_account_grant.user_id", 1);
    EXPECTED_MOVED.put("bank_account_view_grant.grantee_user_id", 1);
    EXPECTED_MOVED.put("bank_account_approval_limit.grantee_user_id", 1);
    EXPECTED_MOVED.put("bank_holder.user_id", 1);
    EXPECTED_MOVED.put("notification.recipient_user_id", 2);
    EXPECTED_MOVED.put("notification_rule_selector.user_id", 1);
    EXPECTED_MOVED.put("notification_mute.user_id", 1);
    EXPECTED_MOVED.put("member_evaluation.user_id", 1);
    EXPECTED_MOVED.put("exchange_installation.user_id", 1);
    EXPECTED_MOVED.put("exchange_ship_link.user_id", 1);
  }

  @Autowired private UserAccountMergeService mergeService;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  @MockitoBean private KeycloakService keycloakService;

  private String tag;
  private UUID location;
  private UUID material;
  private UUID shipType;
  private UUID skUnit;
  private UUID olUnit;
  private UUID rule;
  private UUID categoryOne;
  private UUID categoryTwo;
  private UUID exchangeClient;

  @Test
  void merging_movesEveryBelongingOfEveryModule_dropsTheTargetsDuplicates_andLeavesTheRest() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    UUID source = member("merge-src-" + tag);
    UUID target = member("merge-tgt-" + tag);
    UUID bystander = member("merge-by-" + tag);
    UUID admin = member("merge-adm-" + tag);
    fixtures();

    seedSource(source, target, bystander);
    seedTarget(target);
    seedBystander(bystander);
    UUID stayingAudit = auditEventBy(source);
    UUID stayingTransaction = bankTransactionBy(source);
    jdbc.update("UPDATE app_user SET approved_by_id = ? WHERE id = ?", source, bystander);
    entityManager.flush();

    Map<String, Integer> targetBefore = counts(target);
    Map<String, Integer> bystanderBefore = counts(bystander);
    assertThat(counts(source)).as("the source owns rows in every followed column").hasSize(29);
    assertThat(counts(source).values()).doesNotContain(0);

    mergeService.merge(source, target, admin);
    entityManager.flush();
    entityManager.clear();

    Map<String, Integer> targetAfter = counts(target);
    for (Map.Entry<String, Integer> moved : EXPECTED_MOVED.entrySet()) {
      String column = moved.getKey();
      assertThat(count(column, source)).as("source rows left in %s", column).isZero();
      assertThat(targetAfter.get(column))
          .as("target rows in %s", column)
          .isEqualTo(targetBefore.get(column) + moved.getValue());
    }
    assertThat(counts(bystander)).as("the bystander's rows").isEqualTo(bystanderBefore);

    assertThat(count("audit_event.actor_user_id", source)).isGreaterThanOrEqualTo(1);
    assertThat(text("audit_event", "actor_user_id", stayingAudit)).isEqualTo(source.toString());
    assertThat(text("bank_transaction", "initiated_by", stayingTransaction))
        .isEqualTo(source.toString());
    assertThat(text("app_user", "approved_by_id", bystander)).isEqualTo(source.toString());

    assertThat(
            jdbc.queryForList(
                "SELECT details FROM audit_event WHERE event_type = 'USER_MERGED'"
                    + " AND target_user_id = ?",
                String.class,
                target))
        .containsExactly(expectedDetails(source));
  }

  @Test
  void merging_anAccountThatOwnsNothing_writesTheAuditRowWithZeroRows() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    UUID source = member("merge-src-" + tag);
    UUID target = member("merge-tgt-" + tag);
    UUID admin = member("merge-adm-" + tag);
    entityManager.flush();

    mergeService.merge(source, target, admin);
    entityManager.flush();

    assertThat(
            jdbc.queryForList(
                "SELECT details FROM audit_event WHERE event_type = 'USER_MERGED'"
                    + " AND target_user_id = ?",
                String.class,
                target))
        .containsExactly("fromUser=" + source + " rows=0");
  }

  @Test
  void merging_twoLedgers_isRefusedBeforeAnythingMoves() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    UUID source = member("merge-src-" + tag);
    UUID target = member("merge-tgt-" + tag);
    UUID admin = member("merge-adm-" + tag);
    jdbc.update(
        "INSERT INTO bank_holder (id, user_id, handle) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        source,
        "holder-s-" + tag);
    jdbc.update(
        "INSERT INTO bank_holder (id, user_id, handle) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        target,
        "holder-t-" + tag);
    jdbc.update(
        "INSERT INTO notification (id, recipient_user_id, type) VALUES (?, ?, 'JOB_ORDER_CREATED')",
        UUID.randomUUID(),
        source);
    entityManager.flush();

    assertThatThrownBy(() -> mergeService.merge(source, target, admin))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessageContaining("bank ledger");

    assertThat(count("notification.recipient_user_id", source)).isEqualTo(1);
    assertThat(count("notification.recipient_user_id", target)).isZero();
    assertThat(count("bank_holder.user_id", source)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE event_type = 'USER_MERGED'"
                    + " AND target_user_id = ?",
                Integer.class,
                target))
        .isZero();
  }

  private String expectedDetails(UUID source) {
    StringBuilder details = new StringBuilder("fromUser=" + source + " rows=33");
    EXPECTED_MOVED.forEach(
        (column, rows) -> details.append(' ').append(column).append('=').append(rows));
    return details.toString();
  }

  private Map<String, Integer> counts(UUID user) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String column : EXPECTED_MOVED.keySet()) {
      counts.put(column, count(column, user));
    }
    return counts;
  }

  private int count(String tableColumn, UUID user) {
    int dot = tableColumn.indexOf('.');
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM "
                + tableColumn.substring(0, dot)
                + " WHERE "
                + tableColumn.substring(dot + 1)
                + " = ?",
            Integer.class,
            user);
    return rows == null ? 0 : rows;
  }

  private String text(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT " + column + "::text FROM " + table + " WHERE id = ?", String.class, id);
  }

  private UUID member(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private void fixtures() {
    Location place = new Location();
    place.setName("Merge Hub " + tag);
    location = locationRepository.save(place).getId();
    Material ore = new Material();
    ore.setName("Merge Ore " + tag);
    ore.setType(MaterialType.RAW);
    ore.setQuantityType(QuantityType.SCU);
    material = materialRepository.save(ore).getId();
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Merge Works " + tag);
    manufacturer.setAbbreviation("MW" + tag.substring(0, 3));
    manufacturerRepository.save(manufacturer);
    ShipType hull = new ShipType();
    hull.setName("Merge Hull " + tag);
    hull.setManufacturer(manufacturer);
    shipType = shipTypeRepository.save(hull).getId();
    SpecialCommand sk = new SpecialCommand();
    sk.setName("Merge-SK-" + tag);
    sk.setShorthand("M" + tag);
    sk.setProfitEligible(true);
    skUnit = specialCommandRepository.save(sk).getId();
    olUnit = orgUnitWithGrandAdmiral("Merge-OL-" + tag, null);
    rule = jdbc.queryForObject("SELECT id FROM notification_rule LIMIT 1", UUID.class);
    UUID topic = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO promotion_topic (id, name, sort_order, owning_squadron_id)"
            + " VALUES (?, ?, 0, ?)",
        topic,
        "Merge Topic " + tag,
        Squadron.IRIDIUM_ID);
    categoryOne = category(topic, "One");
    categoryTwo = category(topic, "Two");
    exchangeClient = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO exchange_client (id, client_id, display_name, status, created_at)"
            + " VALUES (?, ?, ?, 'ACTIVE', now())",
        exchangeClient,
        "merge-" + tag,
        "Merge Client " + tag);
    entityManager.flush();
  }

  private UUID category(UUID topic, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO promotion_category (id, topic_id, name, sort_order) VALUES (?, ?, ?, 0)",
        id,
        topic,
        "Merge " + name + " " + tag);
    return id;
  }

  private UUID orgUnitWithGrandAdmiral(String name, UUID admiral) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, grand_admiral_user_id)"
            + " VALUES (?, 'ORGANISATIONSLEITUNG', ?, ?, TRUE, FALSE, FALSE, ?)",
        id,
        name,
        "G" + id.toString().substring(0, 6),
        admiral);
    return id;
  }

  private void seedSource(UUID source, UUID target, UUID bystander) {
    orgUnitWithGrandAdmiral("Merge-OL2-" + tag, source);

    inventoryItem(source);
    inventoryItem(source);
    ship(source, "S1");
    ship(source, "S2");
    refineryOrder(source);
    refineryOrder(source);
    personalItem(source);
    blueprint(source, "shared-" + tag);
    blueprint(source, "own-" + tag);
    membership(source, Squadron.IRIDIUM_ID, "SQUADRON");
    membership(source, skUnit, "SPECIAL_COMMAND");
    chartPosition("OL_MEMBER", olUnit, source);
    chartPosition("AREA_COORDINATOR", null, source);

    UUID m1 = mission("M1", source, null);
    UUID m2 = mission("M2", bystander, source);
    ownership(m1, source);
    unit(m1, source);
    manager(m1, source);
    manager(m1, target);
    manager(m2, source);
    participant(m1, source);
    participant(m1, target);
    participant(m2, source);

    UUID j1 = jobOrder();
    UUID j2 = jobOrder();
    assignee(j1, source);
    assignee(j1, target);
    assignee(j2, source);

    offer(source);
    request(source);
    UUID o1 = offer(bystander);
    UUID o2 = offer(bystander);
    interest("material_exchange_interest", "offer_id", o1, source);
    interest("material_exchange_interest", "offer_id", o1, target);
    interest("material_exchange_interest", "offer_id", o2, source);
    UUID r1 = request(bystander);
    UUID r2 = request(bystander);
    interest("material_exchange_request_interest", "request_id", r1, source);
    interest("material_exchange_request_interest", "request_id", r1, target);
    interest("material_exchange_request_interest", "request_id", r2, source);

    UUID a1 = bankAccount("A1");
    UUID a2 = bankAccount("A2");
    accountGrant(source, a1, bystander);
    accountGrant(target, a1, bystander);
    accountGrant(source, a2, bystander);
    viewGrant(a1, source);
    viewGrant(a1, target);
    viewGrant(a2, source);
    approvalLimit(a1, source);
    approvalLimit(a1, target);
    approvalLimit(a2, source);
    bankHolder(source);

    notification(source);
    notification(source);
    selector(source);
    mute(source, "TYPE_X");
    mute(source, "TYPE_Y");
    mute(target, "TYPE_X");
    evaluation(source, categoryOne);
    evaluation(source, categoryTwo);
    evaluation(target, categoryOne);

    installation(source, "a");
    installation(source, "b");
    shipLink(source, "ext1", shipOf(source, "S1"));
    shipLink(source, "ext2", shipOf(source, "S2"));
  }

  private void seedTarget(UUID target) {
    blueprint(target, "shared-" + tag);
    membership(target, Squadron.IRIDIUM_ID, "SQUADRON");
    chartPosition("AREA_OPERATOR", null, target);
    installation(target, "a");
    shipLink(target, "ext1", ship(target, "T1"));
  }

  private void seedBystander(UUID by) {
    inventoryItem(by);
    ship(by, "B1");
    refineryOrder(by);
    personalItem(by);
    blueprint(by, "shared-" + tag);
    membership(by, Squadron.IRIDIUM_ID, "SQUADRON");
    chartPosition("OL_MEMBER", olUnit, by);
    orgUnitWithGrandAdmiral("Merge-OL3-" + tag, by);
    UUID m3 = mission("M3", by, by);
    ownership(m3, by);
    unit(m3, by);
    manager(m3, by);
    participant(m3, by);
    assignee(jobOrder(), by);
    offer(by);
    request(by);
    bankHolder(by);
    UUID a3 = bankAccount("A3");
    accountGrant(by, a3, by);
    viewGrant(a3, by);
    approvalLimit(a3, by);
    notification(by);
    selector(by);
    mute(by, "TYPE_X");
    evaluation(by, categoryOne);
    installation(by, "a");
  }

  private void inventoryItem(UUID user) {
    jdbc.update(
        "INSERT INTO inventory_item (id, version, created_at, updated_at, user_id, material_id,"
            + " location_id, quality, amount) VALUES (?, 0, now(), now(), ?, ?, ?, 500, 10)",
        UUID.randomUUID(),
        user,
        material,
        location);
  }

  private UUID ship(UUID owner, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, version, created_at, updated_at, name, ship_type_id, insurance,"
            + " owner_id) VALUES (?, 0, now(), now(), ?, ?, 'LTI', ?)",
        id,
        name + "-" + tag,
        shipType,
        owner);
    return id;
  }

  private UUID shipOf(UUID owner, String name) {
    return jdbc.queryForObject(
        "SELECT id FROM ship WHERE owner_id = ? AND name = ?", UUID.class, owner, name + "-" + tag);
  }

  private void refineryOrder(UUID owner) {
    jdbc.update(
        "INSERT INTO refinery_order (id, version, created_at, updated_at, owner_id, location_id)"
            + " VALUES (?, 0, now(), now(), ?, ?)",
        UUID.randomUUID(),
        owner,
        location);
  }

  private void personalItem(UUID owner) {
    jdbc.update(
        "INSERT INTO personal_inventory_item (id, version, created_at, updated_at, owner_user_id,"
            + " name, location_uex_id, location_type, location_name_snapshot, quantity)"
            + " VALUES (?, 0, now(), now(), ?, ?, 1, 'CITY', 'Area18', 1)",
        UUID.randomUUID(),
        owner,
        "Item " + tag);
  }

  private void blueprint(UUID owner, String productKey) {
    jdbc.update(
        "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        owner,
        productKey,
        productKey);
  }

  private void membership(UUID user, UUID orgUnit, String kind) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, kind) VALUES (?, ?, ?)",
        user,
        orgUnit,
        kind);
  }

  private void chartPosition(String type, UUID orgUnit, UUID user) {
    jdbc.update(
        "INSERT INTO org_chart_position (id, position_type, org_unit_id, user_id)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        type,
        orgUnit,
        user);
  }

  private UUID mission(String name, UUID owner, UUID partyLead) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version, owner_id, party_lead_user_id)"
            + " VALUES (?, ?, 0, ?, ?)",
        id,
        name + "-" + tag,
        owner,
        partyLead);
    return id;
  }

  private void ownership(UUID mission, UUID owner) {
    jdbc.update(
        "INSERT INTO mission_ownership (id, version, mission_id, owner_id) VALUES (?, 0, ?, ?)",
        UUID.randomUUID(),
        mission,
        owner);
  }

  private void unit(UUID mission, UUID responsible) {
    jdbc.update(
        "INSERT INTO mission_unit (id, version, mission_id, name, responsible_user_id)"
            + " VALUES (?, 0, ?, ?, ?)",
        UUID.randomUUID(),
        mission,
        "Unit " + tag,
        responsible);
  }

  private void manager(UUID mission, UUID user) {
    jdbc.update("INSERT INTO mission_managers (mission_id, user_id) VALUES (?, ?)", mission, user);
  }

  private void participant(UUID mission, UUID user) {
    jdbc.update(
        "INSERT INTO mission_participant (id, version, mission_id, user_id) VALUES (?, 0, ?, ?)",
        UUID.randomUUID(),
        mission,
        user);
  }

  private UUID jobOrder() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, created_at, updated_at, version, requesting_org_unit_id,"
            + " responsible_org_unit_id) VALUES (?, now(), now(), 0, ?, ?)",
        id,
        Squadron.IRIDIUM_ID,
        Squadron.IRIDIUM_ID);
    return id;
  }

  private void assignee(UUID order, UUID user) {
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        order,
        user);
  }

  private UUID offer(UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_offer (id, owner_id, status, released_at, created_at,"
            + " updated_at, offer_kind, item_product_key, item_name, item_quantity)"
            + " VALUES (?, ?, 'ACTIVE', now(), now(), now(), 'ITEM', ?, 'Probe', 1)",
        id,
        owner,
        "k-" + id);
    return id;
  }

  private UUID request(UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_request (id, request_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, status, posted_at, version, created_at, updated_at)"
            + " VALUES (?, 'ITEM', ?, 'Probe', 2, ?, 'ACTIVE', now(), 0, now(), now())",
        id,
        "k-" + id,
        owner);
    return id;
  }

  private void interest(String table, String parentColumn, UUID parent, UUID user) {
    jdbc.update(
        "INSERT INTO "
            + table
            + " (id, "
            + parentColumn
            + ", interested_user_id, version, created_at, updated_at)"
            + " VALUES (?, ?, ?, 0, now(), now())",
        UUID.randomUUID(),
        parent,
        user);
  }

  private UUID bankAccount(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, ?, 'SPECIAL')",
        id,
        "M" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999),
        name + " " + tag);
    return id;
  }

  private void accountGrant(UUID user, UUID account, UUID grantedBy) {
    jdbc.update(
        "INSERT INTO bank_account_grant (user_id, account_id, granted_by) VALUES (?, ?, ?)",
        user,
        account,
        grantedBy);
  }

  private void viewGrant(UUID account, UUID user) {
    jdbc.update(
        "INSERT INTO bank_account_view_grant (id, account_id, grantee_kind, grantee_user_id)"
            + " VALUES (?, ?, 'USER', ?)",
        UUID.randomUUID(),
        account,
        user);
  }

  private void approvalLimit(UUID account, UUID user) {
    jdbc.update(
        "INSERT INTO bank_account_approval_limit (id, account_id, grantee_kind, grantee_user_id,"
            + " limit_amount) VALUES (?, ?, 'USER', ?, 100)",
        UUID.randomUUID(),
        account,
        user);
  }

  private void bankHolder(UUID user) {
    jdbc.update(
        "INSERT INTO bank_holder (id, user_id, handle) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        user,
        "holder-" + user);
  }

  private void notification(UUID user) {
    jdbc.update(
        "INSERT INTO notification (id, recipient_user_id, type) VALUES (?, ?, 'JOB_ORDER_CREATED')",
        UUID.randomUUID(),
        user);
  }

  private void selector(UUID user) {
    jdbc.update(
        "INSERT INTO notification_rule_selector (id, rule_id, kind, user_id)"
            + " VALUES (?, ?, 'SPECIFIC_USER', ?)",
        UUID.randomUUID(),
        rule,
        user);
  }

  private void mute(UUID user, String type) {
    jdbc.update(
        "INSERT INTO notification_mute (id, user_id, notification_type) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        user,
        type);
  }

  private void evaluation(UUID user, UUID category) {
    jdbc.update(
        "INSERT INTO member_evaluation (id, user_id, category_id, assigned_level)"
            + " VALUES (?, ?, ?, 'LEVEL_A')",
        UUID.randomUUID(),
        user,
        category);
  }

  private void installation(UUID user, String key) {
    jdbc.update(
        "INSERT INTO exchange_installation (id, exchange_client_id, user_id, key_thumbprint,"
            + " first_seen_at, last_seen_at, created_at) VALUES (?, ?, ?, ?, now(), now(), now())",
        UUID.randomUUID(),
        exchangeClient,
        user,
        key.repeat(43));
  }

  private void shipLink(UUID user, String external, UUID ship) {
    jdbc.update(
        "INSERT INTO exchange_ship_link (id, user_id, client_id, installation_key, external_id,"
            + " ship_id) VALUES (?, ?, 'merge-client', 'inst', ?, ?)",
        UUID.randomUUID(),
        user,
        external,
        ship);
  }

  private UUID auditEventBy(UUID actor) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO audit_event (id, occurred_at, domain, event_type, actor_user_id,"
            + " actor_handle) VALUES (?, now(), 'INVENTORY', 'INVENTORY_ADDED', ?, 'Probe')",
        id,
        actor);
    return id;
  }

  private UUID bankTransactionBy(UUID initiator) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_transaction (id, type, initiated_by, created_at)"
            + " VALUES (?, 'DEPOSIT', ?, now())",
        id,
        initiator);
    return id;
  }
}
