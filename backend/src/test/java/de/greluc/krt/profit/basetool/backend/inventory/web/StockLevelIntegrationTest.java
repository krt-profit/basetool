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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelCreateRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelUpdateRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
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

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;

  private final JsonMapper json = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private UUID logistician;
  private UUID member;
  private UUID outsider;
  private UUID unit;
  private UUID otherUnit;
  private UUID material;
  private UUID location;

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
                        new StockLevelCreateRequest(unit, material, null, 1.0, null))))
        .andExpect(status().isForbidden());
    UUID id = createdId(null, 2.0, null);

    update(member, id, new StockLevelUpdateRequest(null, 3.0, null, 0L))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete(PATH + "/" + id).param("version", "0").with(memberOf(member)))
        .andExpect(status().isForbidden());
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
                    new StockLevelCreateRequest(unit, material, tier, minimum, target))));
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
