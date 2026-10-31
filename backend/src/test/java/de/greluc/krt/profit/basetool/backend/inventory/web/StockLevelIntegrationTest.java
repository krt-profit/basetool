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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialService;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelCreateRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelUpdateRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The minimum stock level API end to end: gates, figures over the real pool stock, the 409s and the
 * audit trail (REQ-INV-067, REQ-INV-068).
 */
@SpringBootTest
@Transactional
class StockLevelIntegrationTest {

  private static final String PATH = "/api/v1/inventory/stock-levels";
  private static final UUID GOOD_TIER = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650");
  private static final UUID NO_TIER = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000000");

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialService materialService;
  @Autowired private EntityManager entityManager;

  private final JsonMapper json = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private UUID logistician;
  private UUID member;
  private UUID outsider;
  private UUID unit;
  private UUID otherUnit;
  private UUID material;
  private UUID location;
  private UUID item;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    unit = orgUnit("Alpha");
    otherUnit = orgUnit("Bravo");
    logistician = user("stock-logistician");
    member = user("stock-member");
    outsider = user("stock-outsider");
    joins(logistician, unit);
    joins(member, unit);
    joins(outsider, otherUnit);
    material = material();
    location = location();
    item = gameItem();
  }

  @Test
  void aLogisticianSetsALevelThatCountsOnlyFreeSharedStock() throws Exception {
    UUID earmarked = stock(member, 700, 10.0, false, false);
    earmarkToJobOrder(earmarked, 4.0);
    stock(member, 700, 3.0, true, false);
    stock(member, 700, 5.0, false, true);
    stock(member, 100, 2.0, false, false);

    create(logistician, GOOD_TIER, 8.0, 10.0)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.allocated").value(6.0))
        .andExpect(jsonPath("$.shortfall").value(4.0))
        .andExpect(jsonPath("$.low").value(true))
        .andExpect(jsonPath("$.qualityTier.code").value("GOOD"))
        .andExpect(jsonPath("$.editable").value(true));

    Map<String, Object> audit = audit("MIN_STOCK_LEVEL_SET").getFirst();
    assertThat((String) audit.get("details"))
        .contains("SPECIAL_COMMAND:" + unit)
        .contains("tier=GOOD")
        .doesNotContain("Alpha");
  }

  @Test
  void aPlainMemberAndAnotherUnitsLogisticianAreRefused() throws Exception {
    create(member, null, 1.0, null).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(PATH)
                .with(logisticianOf(outsider, otherUnit))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new StockLevelCreateRequest(unit, material, null, null, 1.0, null))))
        .andExpect(status().isForbidden());
    UUID id = createdId(null, 2.0, null);

    update(member, id, new StockLevelUpdateRequest(null, 3.0, null, 0L))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete(PATH + "/" + id).param("version", "0").with(memberOf(member)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aLogisticianOfOneUnitWhoIsMemberOfAnotherMaintainsOnlyTheFirst() throws Exception {
    joins(logistician, otherUnit);
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_stock_level (id, version, org_unit_id, material_id,"
            + " minimum_amount, target_amount) VALUES (?, 0, ?, ?, 1, 1)",
        foreign,
        otherUnit,
        material);
    RequestPostProcessor auth = logisticianOf(logistician, unit);

    mockMvc
        .perform(
            post(PATH)
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new StockLevelCreateRequest(otherUnit, material, null, null, 2.0, null))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(PATH + "/" + foreign)
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StockLevelUpdateRequest(null, 3.0, null, 0L))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete(PATH + "/" + foreign).param("version", "0").with(auth))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(PATH + "/units").with(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(unit.toString()));
    mockMvc
        .perform(get(PATH).with(auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].editable").value(false));
  }

  @Test
  void aPoolViewerReadsTheLevelAndAnOutsiderDoesNot() throws Exception {
    createdId(null, 2.0, null);

    mockMvc
        .perform(get(PATH).with(memberOf(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].editable").value(false))
        .andExpect(jsonPath("$[0].orgUnit.id").value(unit.toString()));
    mockMvc
        .perform(get(PATH).with(memberOf(outsider)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void aSecondLevelForTheSameKeyConflicts() throws Exception {
    createdId(null, 2.0, null);

    create(logistician, null, 5.0, null).andExpect(status().isConflict());
    create(logistician, GOOD_TIER, 5.0, null).andExpect(status().isCreated());
  }

  @Test
  void aTargetBelowTheMinimumIsRefused() throws Exception {
    create(logistician, null, 5.0, 4.0).andExpect(status().isBadRequest());
    create(logistician, null, 0.0, null).andExpect(status().isBadRequest());
  }

  @Test
  void aChangeIsAuditedWithFromAndToAndAnUnchangedPutIsNot() throws Exception {
    UUID id = createdId(null, 2.0, 4.0);

    update(logistician, id, new StockLevelUpdateRequest(null, 2.0, 4.0, 0L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));
    assertThat(audit("MIN_STOCK_LEVEL_CHANGED")).isEmpty();

    update(logistician, id, new StockLevelUpdateRequest(GOOD_TIER, 3.0, null, 0L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.target").value(3.0));
    assertThat((String) audit("MIN_STOCK_LEVEL_CHANGED").getFirst().get("details"))
        .contains("fromMinimum=2.0")
        .contains("toMinimum=3.0")
        .contains("fromTier=-")
        .contains("toTier=GOOD");
  }

  @Test
  void aStaleVersionConflictsAndAStockWriteDoesNot() throws Exception {
    UUID id = createdId(null, 2.0, null);
    stock(member, 500, 7.0, false, false);

    update(logistician, id, new StockLevelUpdateRequest(null, 3.0, null, 0L))
        .andExpect(status().isOk());
    update(logistician, id, new StockLevelUpdateRequest(null, 4.0, null, 0L))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            delete(PATH + "/" + id).param("version", "0").with(logisticianOf(logistician, unit)))
        .andExpect(status().isConflict());
  }

  @Test
  void aLogisticianRemovesALevel() throws Exception {
    UUID id = createdId(null, 2.0, null);

    mockMvc
        .perform(
            delete(PATH + "/" + id).param("version", "0").with(logisticianOf(logistician, unit)))
        .andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_stock_level WHERE id = ?", Long.class, id))
        .isZero();
    assertThat(audit("MIN_STOCK_LEVEL_REMOVED")).hasSize(1);
  }

  @Test
  void theUnitsTheCallerMaintainsAreListed() throws Exception {
    mockMvc
        .perform(get(PATH + "/units").with(logisticianOf(logistician, unit)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(unit.toString()));
    mockMvc
        .perform(get(PATH + "/units").with(memberOf(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void aGameItemLevelCountsFreeItemStockInWholeUnits() throws Exception {
    itemStock(member, 4, false);
    itemStock(member, 2, true);

    String body =
        createItemLevel(5.0, 6.0)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.material").doesNotExist())
            .andExpect(jsonPath("$.gameItem.id").value(item.toString()))
            .andExpect(jsonPath("$.qualityTier").doesNotExist())
            .andExpect(jsonPath("$.allocated").value(4.0))
            .andExpect(jsonPath("$.shortfall").value(2.0))
            .andExpect(jsonPath("$.low").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("Item " + item);
    Map<String, Object> audit = itemAudit("MIN_STOCK_LEVEL_SET").getFirst();
    assertThat((String) audit.get("details")).contains("gameItem=" + item);
  }

  @Test
  void aGameItemLevelRefusesFractionsATierAndAMissingOrDoubleSubject() throws Exception {
    createItemLevel(1.5, null).andExpect(status().isBadRequest());
    postLevel(new StockLevelCreateRequest(unit, null, item, GOOD_TIER, 1.0, null))
        .andExpect(status().isBadRequest());
    postLevel(new StockLevelCreateRequest(unit, material, item, null, 1.0, null))
        .andExpect(status().isBadRequest());
    postLevel(new StockLevelCreateRequest(unit, null, null, null, 1.0, null))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aSecondLevelForTheSameGameItemConflicts() throws Exception {
    createItemLevel(1.0, null).andExpect(status().isCreated());

    createItemLevel(2.0, null).andExpect(status().isConflict());
  }

  @Test
  void aGameItemLevelCannotGainATier() throws Exception {
    String body = createItemLevel(1.0, null).andReturn().getResponse().getContentAsString();
    UUID id = UUID.fromString(json.readTree(body).get("id").asString());

    update(logistician, id, new StockLevelUpdateRequest(GOOD_TIER, 1.0, null, 0L))
        .andExpect(status().isBadRequest());
    update(logistician, id, new StockLevelUpdateRequest(null, 2.0, null, 0L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.minimum").value(2.0));
  }

  @Test
  void requestedSumsOpenNeedsAndOrdersAtTheLevelsTierOrAboveAndIsNeverSubtracted()
      throws Exception {
    stock(member, 700, 3.0, false, false);
    need(unit, material, GOOD_TIER, 10.0, 3.0, "OPEN");
    need(unit, material, NO_TIER, 4.0, 0.0, "OPEN");
    need(unit, material, GOOD_TIER, 9.0, 0.0, "PAUSED");
    need(otherUnit, material, GOOD_TIER, 8.0, 0.0, "OPEN");
    requestingOrder(unit, material, GOOD_TIER, 5.0, "IN_PROGRESS");
    requestingOrder(unit, material, NO_TIER, 2.0, "OPEN");
    requestingOrder(unit, material, GOOD_TIER, 6.0, "COMPLETED");
    createdId(null, 8.0, null);
    createdId(GOOD_TIER, 2.0, null);

    String body =
        mockMvc
            .perform(get(PATH).with(memberOf(member)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode levels = json.readTree(body);
    JsonNode tiered = levels.get(0).get("qualityTier").isNull() ? levels.get(1) : levels.get(0);
    JsonNode untiered = tiered == levels.get(0) ? levels.get(1) : levels.get(0);
    assertThat(tiered.get("requested").asDouble()).isEqualTo(12.0);
    assertThat(untiered.get("requested").asDouble()).isEqualTo(18.0);
    assertThat(tiered.get("allocated").asDouble()).isEqualTo(2.0);
    assertThat(tiered.get("low").asBoolean()).isFalse();
  }

  @Test
  void deletingAMaterialRemovesItsLevelsWithOneCascadeEventEach() throws Exception {
    createdId(null, 2.0, null);
    createdId(GOOD_TIER, 1.0, null);

    materialService.deleteMaterial(material);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_stock_level WHERE org_unit_id = ?",
                Long.class,
                unit))
        .isZero();
    assertThat(audit("MIN_STOCK_LEVEL_REMOVED_BY_CASCADE")).hasSize(2);
    assertThat(audit("MIN_STOCK_LEVEL_REMOVED")).isEmpty();
  }

  @Test
  void levelsOfAnInactiveUnitOrCatalogueEntryStayStoredButHidden() throws Exception {
    createdId(null, 2.0, null);
    createItemLevel(1.0, null).andExpect(status().isCreated());
    assertThat(listed()).isEqualTo(2);

    jdbc.update("UPDATE org_unit SET active = FALSE WHERE id = ?", unit);
    assertThat(listed()).isZero();
    jdbc.update("UPDATE org_unit SET active = TRUE WHERE id = ?", unit);
    assertThat(listed()).isEqualTo(2);

    jdbc.update("UPDATE material SET scwiki_deleted_at = now() WHERE id = ?", material);
    assertThat(listed()).isEqualTo(1);
    jdbc.update("UPDATE game_item SET uex_deleted_at = now() WHERE id = ?", item);
    assertThat(listed()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_stock_level WHERE org_unit_id = ?",
                Long.class,
                unit))
        .isEqualTo(2);

    jdbc.update("UPDATE material SET scwiki_deleted_at = NULL WHERE id = ?", material);
    jdbc.update("UPDATE game_item SET uex_deleted_at = NULL WHERE id = ?", item);
    assertThat(listed()).isEqualTo(2);
  }

  @Test
  void aGameItemStillLiveAtOneSourceIsNotHidden() throws Exception {
    createItemLevel(1.0, null).andExpect(status().isCreated());

    jdbc.update(
        "UPDATE game_item SET uex_item_id = 987654, uex_deleted_at = now(),"
            + " scwiki_synced_at = now() WHERE id = ?",
        item);

    assertThat(listed()).isEqualTo(1);
  }

  @Test
  void aDeactivatedTierKeepsTheLevelAndFlagsIt() throws Exception {
    createdId(GOOD_TIER, 2.0, null);

    jdbc.update("UPDATE quality_tier SET active = FALSE WHERE id = ?", GOOD_TIER);
    entityManager.clear();

    mockMvc
        .perform(get(PATH).with(memberOf(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].qualityTier.active").value(false));
    assertThat(audit("MIN_STOCK_LEVEL_REMOVED_BY_CASCADE")).isEmpty();
  }

  private ResultActions postLevel(@NotNull StockLevelCreateRequest request) throws Exception {
    return mockMvc.perform(
        post(PATH)
            .with(logisticianOf(logistician, unit))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request)));
  }

  private ResultActions createItemLevel(double minimum, @Nullable Double target) throws Exception {
    return postLevel(new StockLevelCreateRequest(unit, null, item, null, minimum, target));
  }

  private int listed() throws Exception {
    String body =
        mockMvc
            .perform(get(PATH).with(memberOf(member)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).size();
  }

  private List<Map<String, Object>> itemAudit(@NotNull String type) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = ? AND subject_label = ?",
        type,
        "Item " + item);
  }

  private @NotNull UUID gameItem() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        id,
        "Item " + id);
    return id;
  }

  private void itemStock(@NotNull UUID holder, int amount, boolean personal) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, game_item_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, NULL, ?, ?, false, ?, 0)
        """,
        UUID.randomUUID(),
        holder,
        item,
        location,
        amount,
        personal,
        unit);
  }

  private void need(
      @NotNull UUID raisingUnit,
      @NotNull UUID subject,
      @NotNull UUID tier,
      double amount,
      double fulfilled,
      @NotNull String status) {
    jdbc.update(
        """
        INSERT INTO supply_need (id, org_unit_id, material_id, quality_tier_id, amount,
                                 fulfilled_amount, priority, status)
        VALUES (?, ?, ?, ?, ?, ?, 'NORMAL', ?)
        """,
        UUID.randomUUID(),
        raisingUnit,
        subject,
        tier,
        amount,
        fulfilled,
        status);
  }

  private void requestingOrder(
      @NotNull UUID requester,
      @NotNull UUID subject,
      @NotNull UUID tier,
      double amount,
      @NotNull String status) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, ?, ?, ?, 'MATERIAL')",
        order,
        status,
        requester,
        requester);
    jdbc.update(
        """
        INSERT INTO job_order_material (id, job_order_id, material_id, amount, created_at,
                                        updated_at, version, quality_tier_id)
        VALUES (?, ?, ?, ?, now(), now(), 0, ?)
        """,
        UUID.randomUUID(),
        order,
        subject,
        amount,
        tier);
  }

  private ResultActions create(
      @NotNull UUID caller, @Nullable UUID tier, double minimum, @Nullable Double target)
      throws Exception {
    RequestPostProcessor auth =
        caller.equals(logistician) ? logisticianOf(caller, unit) : memberOf(caller);
    return mockMvc.perform(
        post(PATH)
            .with(auth)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    new StockLevelCreateRequest(unit, material, null, tier, minimum, target))));
  }

  private @NotNull UUID createdId(@Nullable UUID tier, double minimum, @Nullable Double target)
      throws Exception {
    String body =
        create(logistician, tier, minimum, target)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = json.readTree(body);
    return UUID.fromString(node.get("id").asString());
  }

  private ResultActions update(
      @NotNull UUID caller, @NotNull UUID id, @NotNull StockLevelUpdateRequest request)
      throws Exception {
    RequestPostProcessor auth =
        caller.equals(logistician) ? logisticianOf(caller, unit) : memberOf(caller);
    return mockMvc.perform(
        put(PATH + "/" + id)
            .with(auth)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request)));
  }

  private List<Map<String, Object>> audit(@NotNull String type) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = ? AND subject_label = ?",
        type,
        "Stock " + material);
  }

  private static @NotNull RequestPostProcessor memberOf(@NotNull UUID caller) {
    return as(caller, List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }

  private static @NotNull RequestPostProcessor logisticianOf(
      @NotNull UUID caller, @NotNull UUID orgUnit) {
    return as(
        caller,
        List.of(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority("LOGISTICIAN", orgUnit)));
  }

  private static @NotNull RequestPostProcessor as(
      @NotNull UUID caller, @NotNull List<GrantedAuthority> authorities) {
    return jwt().jwt(builder -> builder.subject(caller.toString())).authorities(authorities);
  }

  private @NotNull UUID stock(
      @NotNull UUID holder, int quality, double amount, boolean personal, boolean stolen) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        holder,
        material,
        location,
        quality,
        amount,
        personal,
        stolen,
        unit);
    return id;
  }

  private void earmarkToJobOrder(@NotNull UUID row, double amount) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        unit,
        unit);
    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, ?, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        row,
        order,
        amount);
  }

  private @NotNull UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        "Stock " + id);
    return id;
  }

  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Stock " + id);
    return id;
  }

  private @NotNull UUID orgUnit(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE, FALSE)",
        id,
        name + " " + id,
        "S" + id.toString().substring(0, 8));
    return id;
  }

  private void joins(@NotNull UUID user, @NotNull UUID orgUnit) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', false)",
        user,
        orgUnit);
  }

  private @NotNull UUID user(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    return userRepository.saveAndFlush(user).getId();
  }
}
