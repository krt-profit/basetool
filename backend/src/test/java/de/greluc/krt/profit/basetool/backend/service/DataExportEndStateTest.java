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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEvent;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
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
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-Postgres characterisation of the whole Art. 15 / Art. 20 export (REQ-SEC-058): a member who
 * has a row in every store the export reads, next to a second member whose rows and handle must not
 * appear, exported to JSON and compared with a committed document.
 *
 * <p>Ids, timestamps and database-assigned numbers are replaced by placeholders before the
 * comparison, so the document pins what the export says and in which order and not when it ran. The
 * test is the safety net for moving the sections into the owning modules: it states the observable
 * result, not the way it is reached.
 */
@SpringBootTest
@Transactional
class DataExportEndStateTest {

  private static final String GOLDEN = "/privacy/personal-data-export-end-state.json";

  private static final String SUBJECT = "ZzzExportSubjectZzz";
  private static final String OTHER = "ZzzExportOtherZzz";
  private static final String EXTERNAL = "ZzzExportExternalZzz";
  private static final String HIDDEN = "ZzzExportHiddenZzz";

  @Autowired private DataExportService dataExportService;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private PersonalInventoryItemRepository personalInventoryItemRepository;
  @Autowired private PersonalBlueprintRepository personalBlueprintRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private MaterialClaimRepository materialClaimRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private User subject;
  private User other;
  private Squadron squadron;
  private Location location;
  private Material material;
  private ShipType shipType;
  private Ship ship;

  @Test
  void aMemberWithRowsInEveryStoreExportsTheCommittedDocument() throws IOException {
    seedMembers();
    seedIdentityAndOrganisation();
    seedHoldings();
    seedExchange();
    seedNotifications();
    seedMissions();
    seedOrders();
    seedMarket();
    seedBank();
    seedAudit();
    entityManager.flush();
    entityManager.clear();

    DataExportService.DataExport export = dataExportService.export(subject.getId());

    String json = normalise(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export));
    Files.writeString(Path.of("build/personal-data-export-end-state.actual.json"), json);

    assertThat(json).doesNotContain(OTHER).doesNotContain(HIDDEN);
    assertThat(export.thirdPartyHandlesRemoved()).isTrue();
    assertThat(export.sections())
        .as("every store seeded for the subject answers with at least one row")
        .allSatisfy(section -> assertThat(section.rows()).as(section.key()).isNotEmpty());
    assertThat(json).isEqualTo(golden());
  }

  private String golden() throws IOException {
    try (InputStream in = DataExportEndStateTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(in).as("the committed document " + GOLDEN).isNotNull();
      return normalise(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static String normalise(String json) {
    return json.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
        .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<instant>")
        .replaceAll("\"display_id\" : \\d+", "\"display_id\" : <n>")
        .replaceAll("\"job_order\" : \\d+", "\"job_order\" : <n>")
        .replace("\r\n", "\n");
  }

  private void seedMembers() {
    subject = member(SUBJECT);
    subject.setDescription("Fliegt oft mit " + OTHER + " und " + EXTERNAL);
    subject.setDisplayName("Zzz Subject Display");
    subject = userRepository.save(subject);
    other = member(OTHER);
    squadron = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    location = new Location();
    location.setName("Zzz Export Hub");
    locationRepository.save(location);
    material = new Material();
    material.setName("Zzz Export Ore");
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialRepository.save(material);
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Zzz Export Works");
    manufacturer.setAbbreviation("ZXW");
    manufacturerRepository.save(manufacturer);
    shipType = new ShipType();
    shipType.setName("Zzz Export Hull");
    shipType.setManufacturer(manufacturer);
    shipTypeRepository.save(shipType);
  }

  private User member(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.save(user);
  }

  private void seedIdentityAndOrganisation() {
    entityManager.flush();
    UUID s = subject.getId();
    jdbc.update(
        "INSERT INTO terms_acceptance (id, user_id, terms_version, accepted_at)"
            + " VALUES (?, ?, '2026-01', '2026-01-05T10:00:00Z')",
        UUID.randomUUID(),
        s);
    jdbc.update(
        "INSERT INTO user_approval_event (id, user_id, decision, reason, decided_by_id)"
            + " VALUES (?, ?, 'APPROVED', ?, ?)",
        UUID.randomUUID(),
        s,
        "Freigabe nach Absprache mit " + OTHER,
        other.getId());
    jdbc.update(
        "INSERT INTO deletion_request (id, user_id, status, erase_history_requested)"
            + " VALUES (?, ?, 'PENDING', FALSE)",
        UUID.randomUUID(),
        s);
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, kind, role)"
            + " VALUES (?, ?, 'SQUADRON', 'MEMBER')",
        s,
        Squadron.IRIDIUM_ID);
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, kind, role)"
            + " VALUES (?, ?, 'SQUADRON', 'MEMBER')",
        other.getId(),
        Squadron.IRIDIUM_ID);
    jdbc.update(
        "INSERT INTO org_chart_position (id, position_type, user_id, sort_index)"
            + " VALUES (?, 'AREA_LEAD', ?, 0)",
        UUID.randomUUID(),
        s);
    Role role = new Role();
    role.setName("ZZZ Export Role");
    role.setCode("ZZZ_EXPORT_ROLE");
    role = roleRepository.save(role);
    subject.getRoles().add(role);
    userRepository.save(subject);
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, grand_admiral_user_id)"
            + " VALUES (?, 'ORGANISATIONSLEITUNG', ?, ?, TRUE, FALSE, FALSE, ?)",
        UUID.randomUUID(),
        "Zzz Export Leitung",
        "ZXL",
        s);
    UUID topic = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO promotion_topic (id, name, sort_order, owning_squadron_id)"
            + " VALUES (?, 'Zzz Export Topic', 0, ?)",
        topic,
        Squadron.IRIDIUM_ID);
    UUID category = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO promotion_category (id, topic_id, name, sort_order)"
            + " VALUES (?, ?, 'Zzz Export Category', 0)",
        category,
        topic);
    jdbc.update(
        "INSERT INTO member_evaluation (id, user_id, category_id, assigned_level)"
            + " VALUES (?, ?, ?, 'LEVEL_A')",
        UUID.randomUUID(),
        s,
        category);
    jdbc.update(
        "INSERT INTO member_evaluation (id, user_id, category_id, assigned_level)"
            + " VALUES (?, ?, ?, 'LEVEL_B')",
        UUID.randomUUID(),
        other.getId(),
        category);
  }

  private void seedHoldings() {
    for (User owner : new User[] {subject, other}) {
      InventoryItem item = new InventoryItem();
      item.setUser(owner);
      item.setMaterial(material);
      item.setLocation(location);
      item.setOwningOrgUnit(squadron);
      item.setQuality(500);
      item.setAmount(40.0);
      item.setNote("Lager von " + owner.getUsername() + " fuer " + OTHER);
      inventoryItemRepository.save(item);
      Ship owned = new Ship();
      owned.setName("Schiff von " + owner.getUsername() + " und " + OTHER);
      owned.setShipType(shipType);
      owned.setInsurance("LTI");
      owned.setLocation(location);
      owned.setOwner(owner);
      shipRepository.save(owned);
      if (owner == subject) {
        ship = owned;
      }
      personalInventoryItemRepository.save(
          PersonalInventoryItem.builder()
              .ownerUserId(owner.getId())
              .name("Probe " + OTHER)
              .note("Uebergabe an " + OTHER + " erledigt")
              .locationUexId(1)
              .locationType(PersonalInventoryLocationType.CITY)
              .locationNameSnapshot("Export City")
              .quantity(1)
              .build());
      personalBlueprintRepository.save(
          PersonalBlueprint.builder()
              .ownerUserId(owner.getId())
              .productKey("export-" + owner.getUsername())
              .productName("Export Product")
              .note("Plan von " + OTHER)
              .build());
    }
    entityManager.flush();
  }

  private void seedExchange() {
    entityManager.flush();
    UUID client = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO exchange_client (id, client_id, display_name, status, created_at)"
            + " VALUES (?, 'zzz-export-client', 'Zzz Export Client', 'ACTIVE', now())",
        client);
    UUID installation = UUID.randomUUID();
    String thumbprint = "A".repeat(43);
    jdbc.update(
        "INSERT INTO exchange_installation (id, exchange_client_id, user_id, key_thumbprint, label,"
            + " first_seen_at, last_seen_at, created_at) VALUES (?, ?, ?, ?, ?, now(), now(),"
            + " now())",
        installation,
        client,
        subject.getId(),
        thumbprint,
        "Rechner von " + OTHER);
    jdbc.update(
        "INSERT INTO exchange_client_revocation (exchange_client_id, user_id, revoked_at)"
            + " VALUES (?, ?, now())",
        client,
        subject.getId());
    jdbc.update(
        "INSERT INTO exchange_journal (id, user_id, client_id, installation_key, batch_id,"
            + " resource, entity_key, action, removal, before_state, after_state)"
            + " VALUES (?, ?, 'zzz-export-client', ?, ?, 'STOCK', 'stock-1', 'STOCK_SET_QUANTITY',"
            + " FALSE, ?, ?)",
        UUID.randomUUID(),
        subject.getId(),
        thumbprint,
        UUID.randomUUID(),
        "{\"von\":\"" + OTHER + "\"}",
        "{\"an\":\"" + OTHER + "\"}");
    UUID run = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO exchange_bulk_undo_run (id, exchange_client_id, client_id, status,"
            + " members_total, since, started_at)"
            + " VALUES (?, ?, 'zzz-export-client', 'COMPLETED', 1, now(), now())",
        run,
        client);
    jdbc.update(
        "INSERT INTO exchange_bulk_undo_skip (id, run_id, user_id, resource, reason)"
            + " VALUES (?, ?, ?, 'STOCK', 'GONE')",
        UUID.randomUUID(),
        run,
        subject.getId());
    jdbc.update(
        "INSERT INTO exchange_ship_link (id, user_id, client_id, installation_key, external_id,"
            + " ship_id) VALUES (?, ?, 'zzz-export-client', ?, 'ext-1', ?)",
        UUID.randomUUID(),
        subject.getId(),
        thumbprint,
        ship.getId());
  }

  private void seedNotifications() {
    entityManager.flush();
    UUID rule = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO notification_rule (id, event_type, notification_type, description)"
            + " VALUES (?, 'JOB_ORDER_CREATED', 'JOB_ORDER_CREATED', ?)",
        rule,
        "Regel fuer " + OTHER);
    for (User recipient : new User[] {subject, other}) {
      jdbc.update(
          "INSERT INTO notification (id, recipient_user_id, type, params)"
              + " VALUES (?, ?, 'JOB_ORDER_CREATED', ?)",
          UUID.randomUUID(),
          recipient.getId(),
          "{\"actor\":\"" + OTHER + "\"}");
      jdbc.update(
          "INSERT INTO notification_mute (id, user_id, notification_type)"
              + " VALUES (?, ?, 'JOB_ORDER_CREATED')",
          UUID.randomUUID(),
          recipient.getId());
      jdbc.update(
          "INSERT INTO notification_rule_selector (id, rule_id, kind, user_id)"
              + " VALUES (?, ?, 'SPECIFIC_USER', ?)",
          UUID.randomUUID(),
          rule,
          recipient.getId());
    }
  }

  private void seedMissions() {
    entityManager.flush();
    UUID owned = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version, owner_id, description, status, meeting_point)"
            + " VALUES (?, ?, 0, ?, ?, 'PLANNED', ?)",
        owned,
        "Konvoi mit " + OTHER,
        subject.getId(),
        "Absprache mit " + EXTERNAL + " und " + OTHER,
        "Treffpunkt " + OTHER);
    jdbc.update(
        "INSERT INTO mission_participant (id, mission_id, user_id, comment, payout_preference)"
            + " VALUES (?, ?, ?, ?, 'PERSONAL')",
        UUID.randomUUID(),
        owned,
        subject.getId(),
        "Dabei mit " + OTHER);
    jdbc.update(
        "INSERT INTO mission_participant (id, mission_id, user_id, comment, payout_preference)"
            + " VALUES (?, ?, ?, 'Fremder Kommentar', 'PERSONAL')",
        UUID.randomUUID(),
        owned,
        other.getId());
    jdbc.update(
        "INSERT INTO mission_managers (mission_id, user_id) VALUES (?, ?)", owned, subject.getId());
    jdbc.update(
        "INSERT INTO mission_managers (mission_id, user_id) VALUES (?, ?)", owned, other.getId());
    jdbc.update(
        "INSERT INTO mission_unit (id, mission_id, name, high_value_unit, version,"
            + " responsible_user_id, ship_type_id, note) VALUES (?, ?, ?, TRUE, 0, ?, ?, ?)",
        UUID.randomUUID(),
        owned,
        "Hornet von " + OTHER,
        subject.getId(),
        shipType.getId(),
        "Absprache mit " + HIDDEN);
    jdbc.update(
        "INSERT INTO mission_unit (id, mission_id, name, high_value_unit, version,"
            + " responsible_user_id) VALUES (?, ?, 'Fremde Einheit', FALSE, 0, ?)",
        UUID.randomUUID(),
        owned,
        other.getId());
    jdbc.update(
        "INSERT INTO mission (id, name, version, party_lead_user_id, status) VALUES (?, ?, 0, ?,"
            + " 'PLANNED')",
        UUID.randomUUID(),
        "Bergung mit " + OTHER,
        subject.getId());
  }

  private void seedOrders() {
    entityManager.flush();
    for (User owner : new User[] {subject, other}) {
      RefineryOrder order = new RefineryOrder();
      order.setOwner(owner);
      order.setLocation(location);
      order.setDurationMinutes(60L);
      order.setExpenses(100.0);
      refineryOrderRepository.save(order);
    }
    JobOrder jobOrder = jobOrder("export-subject");
    JobOrder otherOrder = jobOrder("export-other");
    entityManager.flush();
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        jobOrder.getId(),
        subject.getId(),
        "Uebernommen von " + OTHER);
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        otherOrder.getId(),
        other.getId(),
        "Fremde Notiz");
    QualityTier good = qualityTierRepository.findByCode("GOOD").orElseThrow();
    materialClaimRepository.save(
        MaterialClaim.builder()
            .jobOrder(jobOrder)
            .material(material)
            .qualityTier(good)
            .claimingOrgUnit(squadron)
            .amount(5.0)
            .claimedByUser(subject)
            .build());
  }

  private JobOrder jobOrder(String handle) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    SpecialCommand sk = new SpecialCommand();
    sk.setName("Export-SK-" + unique);
    sk.setShorthand("X" + unique);
    sk.setProfitEligible(true);
    specialCommandRepository.save(sk);
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(sk)
            .requestingOrgUnit(squadron)
            .handle(handle)
            .status(JobOrderStatus.OPEN)
            .build();
    QualityTier good = qualityTierRepository.findByCode("GOOD").orElseThrow();
    order.addMaterial(
        JobOrderMaterial.builder().material(material).qualityTier(good).amount(10.0).build());
    return jobOrderRepository.save(order);
  }

  private void seedMarket() {
    entityManager.flush();
    UUID offer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_offer (id, offer_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, remark, status, released_at, version, created_at,"
            + " updated_at) VALUES (?, 'ITEM', 'zzz-offer', 'Zzz Angebot', 3, ?, ?, 'ACTIVE',"
            + " now(), 0, now(), now())",
        offer,
        subject.getId(),
        "Abholung bei " + OTHER);
    UUID othersOffer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_offer (id, offer_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, remark, status, released_at, version, created_at,"
            + " updated_at) VALUES (?, 'ITEM', 'zzz-other-offer', 'Zzz Fremdes Angebot', 1, ?, ?,"
            + " 'ACTIVE', now(), 0, now(), now())",
        othersOffer,
        other.getId(),
        "Fremde Anmerkung");
    jdbc.update(
        "INSERT INTO material_exchange_interest (id, offer_id, interested_user_id, version,"
            + " created_at, updated_at) VALUES (?, ?, ?, 0, now(), now())",
        UUID.randomUUID(),
        othersOffer,
        subject.getId());
    UUID request = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_request (id, request_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, remark, status, posted_at, version, created_at,"
            + " updated_at) VALUES (?, 'ITEM', 'zzz-request', 'Zzz Gesuch', 2, ?, ?, 'ACTIVE',"
            + " now(), 0, now(), now())",
        request,
        subject.getId(),
        "Melden bei " + OTHER);
    UUID othersRequest = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_request (id, request_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, remark, status, posted_at, version, created_at,"
            + " updated_at) VALUES (?, 'ITEM', 'zzz-other-request', 'Zzz Fremdes Gesuch', 2, ?, ?,"
            + " 'ACTIVE', now(), 0, now(), now())",
        othersRequest,
        other.getId(),
        "Fremde Anmerkung");
    jdbc.update(
        "INSERT INTO material_exchange_request_interest (id, request_id, interested_user_id,"
            + " version, created_at, updated_at) VALUES (?, ?, ?, 0, now(), now())",
        UUID.randomUUID(),
        othersRequest,
        subject.getId());
  }

  private void seedBank() {
    entityManager.flush();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_account (id, account_no, name, type, status)"
            + " VALUES (?, ?, ?, 'SPECIAL', 'ACTIVE')",
        account,
        "ZZZ-EXPORT-1",
        "Kasse von " + OTHER);
    jdbc.update(
        "INSERT INTO bank_holder (id, user_id, handle, active, role_managed)"
            + " VALUES (?, ?, ?, TRUE, FALSE)",
        UUID.randomUUID(),
        subject.getId(),
        SUBJECT);
    jdbc.update(
        "INSERT INTO bank_account_view_grant (id, account_id, grantee_kind, grantee_user_id)"
            + " VALUES (?, ?, 'USER', ?)",
        UUID.randomUUID(),
        account,
        subject.getId());
    jdbc.update(
        "INSERT INTO bank_account_grant (user_id, account_id, can_deposit, can_withdraw,"
            + " can_transfer, granted_by) VALUES (?, ?, TRUE, FALSE, TRUE, ?)",
        subject.getId(),
        account,
        other.getId());
    jdbc.update(
        "INSERT INTO bank_account_approval_limit (id, account_id, grantee_kind, grantee_user_id,"
            + " limit_amount) VALUES (?, ?, 'USER', ?, 5000)",
        UUID.randomUUID(),
        account,
        subject.getId());
    jdbc.update(
        "INSERT INTO bank_transaction (id, type, initiated_by, note, justification,"
            + " counterparty_user_id, counterparty_handle, created_at)"
            + " VALUES (?, 'DEPOSIT', ?, ?, ?, ?, ?, now())",
        UUID.randomUUID(),
        other.getId(),
        "Einzahlung von " + OTHER,
        "Abgesprochen mit " + EXTERNAL,
        subject.getId(),
        SUBJECT);
    bookingRequest(account, subject, other);
    bookingRequest(account, other, subject);
    jdbc.update(
        "INSERT INTO bank_audit_event (id, occurred_at, actor_user_id, actor_handle, event_type,"
            + " target_user_id, details) VALUES (?, now(), ?, ?, 'ACCOUNT_CREATED', ?, ?)",
        UUID.randomUUID(),
        subject.getId(),
        SUBJECT,
        other.getId(),
        "Konto fuer " + OTHER);
    jdbc.update(
        "INSERT INTO bank_audit_event (id, occurred_at, actor_user_id, actor_handle, event_type,"
            + " target_user_id, details) VALUES (?, now(), ?, ?, 'ACCOUNT_CREATED', ?, ?)",
        UUID.randomUUID(),
        other.getId(),
        OTHER,
        subject.getId(),
        "Konto fuer " + SUBJECT);
  }

  private void bookingRequest(UUID account, User requester, User counterparty) {
    jdbc.update(
        "INSERT INTO bank_booking_request (id, account_id, type, amount, note, justification,"
            + " requested_by, requester_handle, counterparty_user_id, counterparty_handle,"
            + " staff_note, reject_reason, status)"
            + " VALUES (?, ?, 'DEPOSIT', 1500, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
        UUID.randomUUID(),
        account,
        "Einzahlung von " + OTHER,
        "Abgesprochen mit " + OTHER,
        requester.getId(),
        requester.getUsername(),
        counterparty.getId(),
        counterparty.getUsername(),
        "Intern: " + HIDDEN,
        "Abgelehnt wegen " + HIDDEN);
  }

  private void seedAudit() {
    auditEvent(subject.getId(), null);
    auditEvent(other.getId(), subject.getId());
  }

  private void auditEvent(UUID actor, UUID target) {
    auditEventRepository.save(
        AuditEvent.builder()
            .occurredAt(Instant.parse("2026-02-01T10:00:00Z"))
            .domain(AuditEventType.JOB_ORDER_CREATED.domain())
            .eventType(AuditEventType.JOB_ORDER_CREATED)
            .actorUserId(actor)
            .actorHandle(actor.equals(subject.getId()) ? SUBJECT : OTHER)
            .subjectLabel("#1 '" + HIDDEN + "'")
            .targetUserId(target)
            .build());
  }
}
