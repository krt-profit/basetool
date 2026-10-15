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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEvent;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** The owner changes a ship's org unit afterwards (REQ-HANGAR-009, ADR-0258). */
@SpringBootTest
@Transactional
class HangarShipOrgUnitChangeIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private User owner;
  private User stranger;
  private SpecialCommand ownersCommand;
  private SpecialCommand foreignCommand;
  private ShipType fighter;
  private UUID shipId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = saveUser("unit-owner");
    stranger = saveUser("unit-stranger");
    ownersCommand = saveCommand("Owner SK", "OSK");
    foreignCommand = saveCommand("Foreign SK", "FSK");
    saveMembership(owner, Squadron.IRIDIUM_ID);
    saveMembership(owner, ownersCommand.getId());
    saveMembership(stranger, Squadron.IRIDIUM_ID);
    saveMembership(stranger, foreignCommand.getId());

    ShipType type = new ShipType();
    type.setName("Unit Change Fighter");
    fighter = shipTypeRepository.save(type);

    Ship ship = new Ship();
    ship.setName("Iron Mule");
    ship.setShipType(fighter);
    ship.setOwner(owner);
    ship.setInsurance("LTI");
    shipId = shipRepository.save(ship).getId();
  }

  @Test
  @DisplayName("a ship without a unit joins one of the owner's units and leaves it again")
  void assignsAndClearsTheUnit() throws Exception {
    changeUnit(owner, ownersCommand.getId(), 0L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owningSquadron.id").value(ownersCommand.getId().toString()))
        .andExpect(jsonPath("$.owningOrgUnitVersion").value(1));
    assertThat(unitOf(shipId)).isEqualTo(ownersCommand.getId());

    changeUnit(owner, null, 1L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owningSquadron").doesNotExist())
        .andExpect(jsonPath("$.owningOrgUnitVersion").value(2));
    assertThat(unitOf(shipId)).isNull();
  }

  @Test
  @DisplayName("a unit change records from and to as kind:id, without names")
  void recordsTheChange() throws Exception {
    changeUnit(owner, ownersCommand.getId(), 0L).andExpect(status().isOk());

    List<AuditEvent> events = unitEvents();
    assertThat(events).hasSize(1);
    AuditEvent event = events.getFirst();
    assertThat(event.getSubjectLabel()).isEqualTo(fighter.getName());
    assertThat(event.getDetails())
        .contains("none")
        .contains("SPECIAL_COMMAND:" + ownersCommand.getId())
        .doesNotContain("Owner SK")
        .doesNotContain("Iron Mule");
  }

  @Test
  @DisplayName("the unit-room probe admits a unit the caller's overview shows and refuses another")
  void unitOverviewAccessFollowsTheUnitOverviewScope() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/hangar/units/" + ownersCommand.getId() + "/overview-access")
                .with(as(owner)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            get("/api/v1/hangar/units/" + foreignCommand.getId() + "/overview-access")
                .with(as(owner)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/hangar/units/" + ownersCommand.getId() + "/overview-access")
                .with(jwt().jwt(builder -> builder.subject(owner.getId().toString()))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("another member's ship is refused with 403")
  void refusesAForeignShip() throws Exception {
    changeUnit(stranger, foreignCommand.getId(), 0L).andExpect(status().isForbidden());
    assertThat(unitOf(shipId)).isNull();
  }

  @Test
  @DisplayName("a unit the owner is not a direct member of is refused with 400")
  void refusesAForeignUnit() throws Exception {
    changeUnit(owner, foreignCommand.getId(), 0L).andExpect(status().isBadRequest());
    assertThat(unitOf(shipId)).isNull();
    assertThat(unitEvents()).isEmpty();
  }

  @Test
  @DisplayName("a stale unit counter is refused with 409")
  void refusesAStaleCounter() throws Exception {
    changeUnit(owner, ownersCommand.getId(), 0L).andExpect(status().isOk());
    changeUnit(owner, Squadron.IRIDIUM_ID, 0L).andExpect(status().isConflict());
    assertThat(unitOf(shipId)).isEqualTo(ownersCommand.getId());
  }

  @Test
  @DisplayName("a missing unit counter is refused with 400")
  void refusesAMissingCounter() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/hangar/ships/" + shipId + "/org-unit")
                .with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgUnitId\":\"" + ownersCommand.getId() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an unchanged unit writes nothing and records nothing")
  void unchangedUnitIsANoOp() throws Exception {
    changeUnit(owner, null, 0L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owningOrgUnitVersion").value(0));
    assertThat(unitEvents()).isEmpty();
  }

  @Test
  @DisplayName("keeping a unit the owner has since left is a no-op, not a 400")
  void keepingAFormerUnitIsANoOp() throws Exception {
    changeUnit(owner, ownersCommand.getId(), 0L).andExpect(status().isOk());
    orgUnitMembershipRepository.deleteById(
        new OrgUnitMembershipId(owner.getId(), ownersCommand.getId()));

    changeUnit(owner, ownersCommand.getId(), 1L).andExpect(status().isOk());
    changeUnit(owner, foreignCommand.getId(), 1L).andExpect(status().isBadRequest());

    assertThat(unitOf(shipId)).isEqualTo(ownersCommand.getId());
    assertThat(unitEvents()).hasSize(1);
  }

  @Test
  @DisplayName("an edit with the old ship version succeeds after a unit change and keeps the unit")
  void fieldEditAfterUnitChange() throws Exception {
    long shipVersion = shipRepository.findById(shipId).orElseThrow().getVersion();
    changeUnit(owner, ownersCommand.getId(), 0L).andExpect(status().isOk());
    assertThat(shipRepository.findById(shipId).orElseThrow().getVersion()).isEqualTo(shipVersion);

    editName(shipVersion, "Renamed").andExpect(status().isOk());

    Ship ship = shipRepository.findById(shipId).orElseThrow();
    assertThat(ship.getName()).isEqualTo("Renamed");
    assertThat(unitOf(shipId)).isEqualTo(ownersCommand.getId());
    assertThat(ship.getOwningOrgUnitVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("a unit change with the old unit counter succeeds after an edit of the ship")
  void unitChangeAfterFieldEdit() throws Exception {
    long shipVersion = shipRepository.findById(shipId).orElseThrow().getVersion();
    editName(shipVersion, "Renamed").andExpect(status().isOk());
    assertThat(shipRepository.findById(shipId).orElseThrow().getOwningOrgUnitVersion()).isZero();

    changeUnit(owner, ownersCommand.getId(), 0L).andExpect(status().isOk());

    assertThat(shipRepository.findById(shipId).orElseThrow().getName()).isEqualTo("Renamed");
    assertThat(unitOf(shipId)).isEqualTo(ownersCommand.getId());
  }

  @Test
  @DisplayName("an edit flushed from a stale entity never overwrites a concurrent unit change")
  void staleFieldEditKeepsTheConcurrentUnit() {
    shipRepository.flush();
    Ship loaded = shipRepository.findById(shipId).orElseThrow();
    jdbcTemplate.update(
        "UPDATE ship SET owning_org_unit_id = ?, owning_org_unit_version = 1 WHERE id = ?",
        ownersCommand.getId(),
        shipId);

    loaded.setName("Renamed");
    shipRepository.flush();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT owning_org_unit_id FROM ship WHERE id = ?", UUID.class, shipId))
        .isEqualTo(ownersCommand.getId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT owning_org_unit_version FROM ship WHERE id = ?", Long.class, shipId))
        .isEqualTo(1L);
  }

  private @NotNull ResultActions changeUnit(
      @NotNull User caller, @Nullable UUID orgUnitId, long unitVersion) throws Exception {
    return mockMvc.perform(
        put("/api/v1/hangar/ships/" + shipId + "/org-unit")
            .with(as(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    new ShipOrgUnitRequestDto(orgUnitId, unitVersion))));
  }

  private @NotNull ResultActions editName(long shipVersion, @NotNull String name) throws Exception {
    return mockMvc.perform(
        put("/api/v1/hangar/ships/" + shipId)
            .with(as(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    new ShipRequestDto(
                        name, fighter.getId(), "LTI", null, false, shipVersion, null))));
  }

  private static @NotNull RequestPostProcessor as(@NotNull User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"));
  }

  private @Nullable UUID unitOf(@NotNull UUID id) {
    OrgUnit unit = shipRepository.findById(id).orElseThrow().getOwningOrgUnit();
    return unit == null ? null : unit.getId();
  }

  private @NotNull List<AuditEvent> unitEvents() {
    return auditEventRepository.findAll().stream()
        .filter(e -> e.getEventType() == AuditEventType.HANGAR_SHIP_ORG_UNIT_CHANGED)
        .filter(e -> shipId.equals(e.getSubjectId()))
        .toList();
  }

  private @NotNull User saveUser(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  private @NotNull SpecialCommand saveCommand(@NotNull String name, @NotNull String shorthand) {
    SpecialCommand command = new SpecialCommand();
    command.setName(name);
    command.setShorthand(shorthand);
    return specialCommandRepository.save(command);
  }

  private void saveMembership(@NotNull User user, @NotNull UUID orgUnitId) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), orgUnitId));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }
}
