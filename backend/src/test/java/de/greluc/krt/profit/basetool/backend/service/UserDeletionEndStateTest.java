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

import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres characterisation of the whole account deletion ({@link
 * UserDeletionService#deleteUser(UUID)}, REQ-DATA-008): what happens to every row kind a departing
 * member owns, which rows of a surviving member stay untouched, and which audit rows the deletion
 * writes, in which order and with which details.
 *
 * <p>The member owns rows in every store the deletion handles; a second member owns one row of each
 * and must come out unchanged. The test is the safety net for moving the deletion's steps into the
 * owning modules: it states the observable result, not the way it is reached.
 */
@SpringBootTest
@Transactional
class UserDeletionEndStateTest {

  @Autowired private UserDeletionService userDeletionService;
  @Autowired private MissionService missionService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private MaterialClaimRepository materialClaimRepository;
  @Autowired private PersonalBlueprintRepository personalBlueprintRepository;
  @Autowired private PersonalInventoryItemRepository personalInventoryItemRepository;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  @MockitoBean private KeycloakService keycloakService;

  private Location location;
  private Material material;
  private ShipType shipType;
  private Squadron squadron;
  private UUID ruleId;
  private UUID categoryId;

  @Test
  void deletingAMember_purgesReassignsAndUnlinksEveryStore_andLeavesAnotherMemberUntouched() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    admin(tag);
    User leaver = member("leaver-" + tag);
    User stayer = member("stayer-" + tag);
    fixtures(tag);

    Seeded leaverRows = seed(leaver, tag + "-l", 2, 3, 2, 3, 2);
    Seeded stayerRows = seed(stayer, tag + "-s", 1, 1, 1, 1, 1);
    UUID missionId = mission(leaver, stayer, tag);
    UUID claimId = claim(leaver, tag);
    entityManager.flush();
    entityManager.clear();

    assertThat(count("inventory_item", "user_id", leaver.getId())).isEqualTo(2);
    assertThat(count("notification", "recipient_user_id", leaver.getId())).isEqualTo(3);

    Instant before = Instant.now();
    userDeletionService.deleteUser(leaver.getId());
    entityManager.flush();
    entityManager.clear();

    UUID leaverId = leaver.getId();
    assertThat(userRepository.findById(leaverId)).isEmpty();
    assertThat(count("inventory_item", "user_id", leaverId)).isZero();
    assertThat(count("ship", "owner_id", leaverId)).isZero();
    assertThat(count("personal_inventory_item", "owner_user_id", leaverId)).isZero();
    assertThat(count("personal_blueprint", "owner_user_id", leaverId)).isZero();
    assertThat(count("notification", "recipient_user_id", leaverId)).isZero();
    assertThat(count("notification_rule_selector", "user_id", leaverId)).isZero();
    assertThat(count("member_evaluation", "user_id", leaverId)).isZero();
    assertThat(count("material_exchange_offer", "owner_id", leaverId)).isZero();
    assertThat(count("job_order_assignees", "user_id", leaverId)).isZero();
    assertThat(count("mission_managers", "user_id", leaverId)).isZero();
    assertThat(count("mission_participant", "user_id", leaverId)).isZero();

    UUID newOwner =
        jdbc.queryForObject("SELECT owner_id FROM mission WHERE id = ?", UUID.class, missionId);
    assertThat(newOwner).isNotNull().isNotEqualTo(leaverId);
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_id FROM mission_ownership WHERE mission_id = ?",
                UUID.class,
                missionId))
        .isEqualTo(newOwner);
    assertThat(
            jdbc.queryForList(
                "SELECT owner_id FROM refinery_order WHERE id IN (?, ?)",
                UUID.class,
                leaverRows.refineryOrders.get(0),
                leaverRows.refineryOrders.get(1)))
        .containsOnly(newOwner);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mission_participant WHERE mission_id = ?",
                Integer.class,
                missionId))
        .as("the participant row survives without its personal link")
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT claimed_by_user_id FROM material_claim WHERE id = ?", UUID.class, claimId))
        .isNull();

    UUID stayerId = stayer.getId();
    assertThat(count("inventory_item", "user_id", stayerId)).isEqualTo(1);
    assertThat(count("ship", "owner_id", stayerId)).isEqualTo(1);
    assertThat(count("personal_inventory_item", "owner_user_id", stayerId)).isEqualTo(1);
    assertThat(count("personal_blueprint", "owner_user_id", stayerId)).isEqualTo(1);
    assertThat(count("notification", "recipient_user_id", stayerId)).isEqualTo(1);
    assertThat(count("notification_rule_selector", "user_id", stayerId)).isEqualTo(1);
    assertThat(count("member_evaluation", "user_id", stayerId)).isEqualTo(1);
    assertThat(count("material_exchange_offer", "owner_id", stayerId)).isEqualTo(1);
    assertThat(count("job_order_assignees", "user_id", stayerId)).isEqualTo(1);
    assertThat(count("mission_managers", "user_id", stayerId)).isEqualTo(1);
    assertThat(count("mission_participant", "user_id", stayerId)).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT owner_id FROM refinery_order WHERE id = ?",
                UUID.class,
                stayerRows.refineryOrders.get(0)))
        .containsExactly(stayerId);

    assertAuditRowsInOrder(
        leaverId,
        before,
        List.of(
            "MARKET_OFFER_REMOVED|kind=MATERIAL reason=user-deletion",
            "INVENTORY_PURGED_ON_USER_DELETION|reason=user-deletion inventoryRows=2 ships=3"
                + " fromUser="
                + leaverId,
            "PERSONAL_DATA_PURGED_ON_USER_DELETION|reason=user-deletion personalInventory=1"
                + " blueprints=2 notifications=3 ruleSelectors=1 evaluations=1",
            "REFINERY_ORDERS_REASSIGNED|reason=user-deletion rows=2 fromUser="
                + leaverId
                + " toAdmin="
                + newOwner,
            "USER_DELETED|inventoryRows=2 ships=3 personalInventory=1 blueprints=2 notifications=3"
                + " ruleSelectors=1 evaluations=1 refineryOrdersReassignedTo="
                + newOwner));
  }

  @Test
  void deletingAMemberWhoOwnsNothing_writesOnlyTheUserDeletedRow() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    admin(tag);
    User leaver = member("empty-" + tag);
    entityManager.flush();
    entityManager.clear();

    Instant before = Instant.now();
    userDeletionService.deleteUser(leaver.getId());
    entityManager.flush();

    UUID adminId = lastAdminTarget(leaver.getId());
    assertAuditRowsInOrder(
        leaver.getId(),
        before,
        List.of(
            "USER_DELETED|inventoryRows=0 ships=0 personalInventory=0 blueprints=0"
                + " notifications=0 ruleSelectors=0 evaluations=0 refineryOrdersReassignedTo="
                + adminId));
  }

  private UUID lastAdminTarget(UUID deleted) {
    String details =
        jdbc.queryForObject(
            "SELECT details FROM audit_event WHERE event_type = 'USER_DELETED'"
                + " AND target_user_id = ?",
            String.class,
            deleted);
    return UUID.fromString(details.substring(details.lastIndexOf('=') + 1));
  }

  private int count(String table, String column, UUID userId) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, userId);
    return rows == null ? 0 : rows;
  }

  /**
   * Asserts that the target's audit rows are exactly the expected ones and that no row precedes one
   * the expected order puts first. Rows of one transaction can share a microsecond, and their
   * relative order is then the random id's, so only rows with different instants are ordered.
   */
  private void assertAuditRowsInOrder(UUID target, Instant since, List<String> expected) {
    List<AuditRow> rows =
        jdbc.query(
            """
            SELECT occurred_at, event_type || '|' || coalesce(details, '') AS text
            FROM audit_event
            WHERE target_user_id = ? AND occurred_at >= to_timestamp(? / 1000.0)
            ORDER BY occurred_at, id
            """,
            (rs, _) ->
                new AuditRow(rs.getTimestamp("occurred_at").toInstant(), rs.getString("text")),
            target,
            since.toEpochMilli());
    assertThat(rows.stream().map(AuditRow::text).toList())
        .containsExactlyInAnyOrderElementsOf(expected);
    for (int earlier = 0; earlier < rows.size(); earlier++) {
      for (int later = earlier + 1; later < rows.size(); later++) {
        if (rows.get(earlier).at().isBefore(rows.get(later).at())) {
          assertThat(expected.indexOf(rows.get(earlier).text()))
              .as("%s was recorded before %s", rows.get(earlier).text(), rows.get(later).text())
              .isLessThan(expected.indexOf(rows.get(later).text()));
        }
      }
    }
  }

  private record AuditRow(Instant at, String text) {}

  private User admin(String tag) {
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(
                () -> {
                  Role fresh = new Role();
                  fresh.setName("ADMIN");
                  fresh.setCode("ADMIN");
                  return roleRepository.save(fresh);
                });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("end-admin-" + tag);
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    return userRepository.save(admin);
  }

  private User member(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.save(user);
  }

  private void fixtures(String tag) {
    squadron = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    location = new Location();
    location.setName("End Hub " + tag);
    locationRepository.save(location);
    material = new Material();
    material.setName("End Ore " + tag);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialRepository.save(material);
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("End Works " + tag);
    manufacturer.setAbbreviation("EW" + tag.substring(0, 3));
    manufacturerRepository.save(manufacturer);
    shipType = new ShipType();
    shipType.setName("End Hull " + tag);
    shipType.setManufacturer(manufacturer);
    shipTypeRepository.save(shipType);
    ruleId = jdbc.queryForObject("SELECT id FROM notification_rule LIMIT 1", UUID.class);
    UUID topicId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO promotion_topic (id, name, sort_order, owning_squadron_id)"
            + " VALUES (?, ?, 0, ?)",
        topicId,
        "End Topic " + tag,
        Squadron.IRIDIUM_ID);
    categoryId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO promotion_category (id, topic_id, name, sort_order) VALUES (?, ?, ?, 0)",
        categoryId,
        topicId,
        "End Category " + tag);
  }

  /**
   * Gives one member rows in every store the deletion purges or reassigns, plus a Lager offer that
   * stands on the first Lager row.
   *
   * @param owner the member
   * @param tag a unique marker for names
   * @param inventoryRows Lager rows
   * @param ships ships
   * @param blueprints personal blueprints
   * @param notifications notifications
   * @param refineryOrders refinery orders
   * @return the ids of the refinery orders
   */
  private Seeded seed(
      User owner,
      String tag,
      int inventoryRows,
      int ships,
      int blueprints,
      int notifications,
      int refineryOrders) {
    for (int i = 0; i < inventoryRows; i++) {
      InventoryItem item = new InventoryItem();
      item.setUser(owner);
      item.setMaterial(material);
      item.setLocation(location);
      item.setOwningOrgUnit(squadron);
      item.setQuality(500);
      item.setAmount(40.0);
      inventoryItemRepository.save(item);
      if (i == 0) {
        offerRepository.save(
            MaterialExchangeOffer.builder()
                .kind(MaterialExchangeOfferKind.MATERIAL)
                .inventoryItemId(item.getId())
                .owner(owner)
                .owningOrgUnit(squadron)
                .offeredAmount(10.0)
                .status(MaterialExchangeOfferStatus.ACTIVE)
                .releasedAt(Instant.parse("2026-09-27T00:00:00Z"))
                .build());
      }
    }
    for (int i = 0; i < ships; i++) {
      Ship ship = new Ship();
      ship.setName("End Ship " + tag + "-" + i);
      ship.setShipType(shipType);
      ship.setInsurance("LTI");
      ship.setLocation(location);
      ship.setOwner(owner);
      shipRepository.save(ship);
    }
    personalInventoryItemRepository.save(
        PersonalInventoryItem.builder()
            .ownerUserId(owner.getId())
            .name("End Item " + tag)
            .locationUexId(1)
            .locationType(PersonalInventoryLocationType.CITY)
            .locationNameSnapshot("End City")
            .quantity(1)
            .build());
    for (int i = 0; i < blueprints; i++) {
      personalBlueprintRepository.save(
          PersonalBlueprint.builder()
              .ownerUserId(owner.getId())
              .productKey("end-" + tag + "-" + i)
              .productName("End Product " + tag + "-" + i)
              .build());
    }
    entityManager.flush();
    for (int i = 0; i < notifications; i++) {
      jdbc.update(
          "INSERT INTO notification (id, recipient_user_id, type) VALUES (?, ?,"
              + " 'JOB_ORDER_CREATED')",
          UUID.randomUUID(),
          owner.getId());
    }
    jdbc.update(
        "INSERT INTO notification_rule_selector (id, rule_id, kind, user_id)"
            + " VALUES (?, ?, 'SPECIFIC_USER', ?)",
        UUID.randomUUID(),
        ruleId,
        owner.getId());
    jdbc.update(
        "INSERT INTO member_evaluation (id, user_id, category_id, assigned_level)"
            + " VALUES (?, ?, ?, 'LEVEL_A')",
        UUID.randomUUID(),
        owner.getId(),
        categoryId);
    Seeded seeded = new Seeded();
    for (int i = 0; i < refineryOrders; i++) {
      RefineryOrder order = new RefineryOrder();
      order.setOwner(owner);
      order.setLocation(location);
      order.setDurationMinutes(60L);
      order.setExpenses(100.0);
      seeded.refineryOrders.add(refineryOrderRepository.save(order).getId());
    }
    UUID orderId = joborder(tag, owner).getId();
    entityManager.flush();
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        orderId,
        owner.getId());
    return seeded;
  }

  private JobOrder joborder(String tag, User owner) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    SpecialCommand sk = new SpecialCommand();
    sk.setName("End-SK-" + tag + "-" + unique);
    sk.setShorthand("E" + unique);
    sk.setProfitEligible(true);
    specialCommandRepository.save(sk);
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(sk)
            .requestingOrgUnit(squadron)
            .handle("end-" + tag)
            .status(JobOrderStatus.OPEN)
            .build();
    QualityTier good = qualityTierRepository.findByCode("GOOD").orElseThrow();
    order.addMaterial(
        JobOrderMaterial.builder().material(material).qualityTier(good).amount(10.0).build());
    return jobOrderRepository.save(order);
  }

  private UUID mission(User leaver, User stayer, String tag) {
    Mission mission = new Mission();
    mission.setName("End Mission " + tag);
    mission.setStatus("PLANNED");
    mission.setIsInternal(false);
    mission.getManagers().add(leaver);
    mission.getManagers().add(stayer);
    MissionParticipant gone = new MissionParticipant();
    gone.setMission(mission);
    gone.setUser(leaver);
    mission.getParticipants().add(gone);
    MissionParticipant kept = new MissionParticipant();
    kept.setMission(mission);
    kept.setUser(stayer);
    mission.getParticipants().add(kept);
    mission = missionRepository.save(mission);
    missionService.updateMissionOwner(mission.getId(), leaver.getId(), 0L);
    return mission.getId();
  }

  private UUID claim(User leaver, String tag) {
    JobOrder order = joborder("c-" + tag, leaver);
    QualityTier good = qualityTierRepository.findByCode("GOOD").orElseThrow();
    return materialClaimRepository
        .save(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(material)
                .qualityTier(good)
                .claimingOrgUnit(squadron)
                .amount(5.0)
                .claimedByUser(leaver)
                .build())
        .getId();
  }

  /** The ids a seeding created that the test reads back. */
  private static final class Seeded {
    private final List<UUID> refineryOrders = new ArrayList<>();
  }
}
