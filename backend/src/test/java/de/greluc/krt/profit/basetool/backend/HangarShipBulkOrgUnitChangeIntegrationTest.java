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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipBulkOrgUnitRequestDto;
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
import java.util.ArrayList;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** The owner changes the org unit of a selection of ships in one action (REQ-HANGAR-010). */
@SpringBootTest
@Transactional
class HangarShipBulkOrgUnitChangeIntegrationTest {

  private static final String BULK_URL = "/api/v1/hangar/ships/bulk-org-unit";

  @Autowired private WebApplicationContext context;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private User owner;
  private User stranger;
  private SpecialCommand ownersCommand;
  private SpecialCommand foreignCommand;
  private ShipType fighter;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = saveUser("bulk-unit-owner");
    stranger = saveUser("bulk-unit-stranger");
    ownersCommand = saveCommand("Bulk Owner SK", "BOSK");
    foreignCommand = saveCommand("Bulk Foreign SK", "BFSK");
    saveMembership(owner, Squadron.IRIDIUM_ID);
    saveMembership(owner, ownersCommand.getId());
    saveMembership(stranger, foreignCommand.getId());

    ShipType type = new ShipType();
    type.setName("Bulk Unit Fighter");
    fighter = shipTypeRepository.save(type);
  }

  @Test
  @DisplayName("every selected ship moves to the target unit and its unit counter goes up")
  void movesTheWholeSelection() throws Exception {
    UUID first = saveShip(owner, "Alpha");
    UUID second = saveShip(owner, "Bravo");

    bulk(owner, List.of(first, second), ownersCommand.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2))
        .andExpect(jsonPath("$.skipped").value(0));

    assertThat(unitOf(first)).isEqualTo(ownersCommand.getId());
    assertThat(unitOf(second)).isEqualTo(ownersCommand.getId());
    assertThat(shipRepository.findById(first).orElseThrow().getOwningOrgUnitVersion())
        .isEqualTo(1L);

    bulk(owner, List.of(first, second), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2));
    assertThat(unitOf(first)).isNull();
    assertThat(unitOf(second)).isNull();
  }

  @Test
  @DisplayName("a bulk change bumps the unit counter, so a stale single change 409s")
  void bulkInvalidatesAStaleSingleChange() throws Exception {
    UUID ship = saveShip(owner, "Alpha");
    bulk(owner, List.of(ship), ownersCommand.getId()).andExpect(status().isOk());

    mockMvc
        .perform(
            put("/api/v1/hangar/ships/" + ship + "/org-unit")
                .with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ShipOrgUnitRequestDto(Squadron.IRIDIUM_ID, 0L))))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("ships already in the target unit are skipped and counted")
  void skipsShipsAlreadyInTheTarget() throws Exception {
    UUID moved = saveShip(owner, "Alpha");
    UUID already = saveShip(owner, "Bravo");
    bulk(owner, List.of(already), ownersCommand.getId()).andExpect(status().isOk());

    bulk(owner, List.of(moved, already), ownersCommand.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(1))
        .andExpect(jsonPath("$.skipped").value(1));

    assertThat(shipRepository.findById(already).orElseThrow().getOwningOrgUnitVersion())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("a selection with a foreign ship is refused as a whole with 403")
  void refusesASelectionWithAForeignShip() throws Exception {
    UUID own = saveShip(owner, "Alpha");
    UUID foreign = saveShip(stranger, "Intruder");

    bulk(owner, List.of(own, foreign), ownersCommand.getId()).andExpect(status().isForbidden());

    assertThat(unitOf(own)).isNull();
    assertThat(unitOf(foreign)).isNull();
    assertThat(bulkEvents()).isEmpty();
  }

  @Test
  @DisplayName("a selection with an unknown ship is refused as a whole with 404")
  void refusesASelectionWithAnUnknownShip() throws Exception {
    UUID own = saveShip(owner, "Alpha");

    bulk(owner, List.of(own, UUID.randomUUID()), ownersCommand.getId())
        .andExpect(status().isNotFound());

    assertThat(unitOf(own)).isNull();
  }

  @Test
  @DisplayName("a unit the owner is not a direct member of is refused with 400")
  void refusesAForeignUnit() throws Exception {
    UUID own = saveShip(owner, "Alpha");

    bulk(owner, List.of(own), foreignCommand.getId()).andExpect(status().isBadRequest());

    assertThat(unitOf(own)).isNull();
    assertThat(bulkEvents()).isEmpty();
  }

  @Test
  @DisplayName("an empty selection is refused with 400")
  void refusesAnEmptySelection() throws Exception {
    bulk(owner, List.of(), ownersCommand.getId()).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("one summary event records the counts and from / to as kind:id, without names")
  void recordsOneSummaryEvent() throws Exception {
    UUID fromNone = saveShip(owner, "Alpha");
    UUID fromSquadron = saveShip(owner, "Bravo");
    bulk(owner, List.of(fromSquadron), Squadron.IRIDIUM_ID).andExpect(status().isOk());
    UUID already = saveShip(owner, "Charlie");
    bulk(owner, List.of(already), ownersCommand.getId()).andExpect(status().isOk());

    bulk(owner, List.of(fromNone, fromSquadron, already), ownersCommand.getId())
        .andExpect(status().isOk());

    List<AuditEvent> events = bulkEvents();
    assertThat(events).hasSize(3);
    AuditEvent last =
        events.stream().filter(e -> e.getDetails().contains("changed=2")).findFirst().orElseThrow();
    assertThat(last.getActorUserId()).isEqualTo(owner.getId());
    assertThat(last.getDetails())
        .contains("skipped=1")
        .contains("none")
        .contains("SQUADRON:" + Squadron.IRIDIUM_ID)
        .contains("to=SPECIAL_COMMAND:" + ownersCommand.getId())
        .doesNotContain("Bulk Owner SK")
        .doesNotContain("Alpha");
  }

  @Test
  @DisplayName("a selection already wholly in the target writes and records nothing")
  void unchangedSelectionIsANoOp() throws Exception {
    UUID ship = saveShip(owner, "Alpha");

    bulk(owner, List.of(ship), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(0))
        .andExpect(jsonPath("$.skipped").value(1));

    assertThat(shipRepository.findById(ship).orElseThrow().getOwningOrgUnitVersion()).isZero();
    assertThat(bulkEvents()).isEmpty();
  }

  @Test
  @DisplayName("a selection already in a unit the owner has since left is a no-op, not a 400")
  void keepingAFormerUnitIsANoOp() throws Exception {
    UUID ship = saveShip(owner, "Alpha");
    bulk(owner, List.of(ship), ownersCommand.getId()).andExpect(status().isOk());
    orgUnitMembershipRepository.deleteById(
        new OrgUnitMembershipId(owner.getId(), ownersCommand.getId()));

    bulk(owner, List.of(ship), ownersCommand.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(0));

    UUID other = saveShip(owner, "Bravo");
    bulk(owner, List.of(ship, other), ownersCommand.getId()).andExpect(status().isBadRequest());
    assertThat(unitOf(other)).isNull();
  }

  @Test
  @DisplayName("a selection larger than the limit is refused with 400")
  void refusesAnOversizedSelection() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i <= ShipBulkOrgUnitRequestDto.MAX_SHIPS; i++) {
      ids.add(UUID.randomUUID());
    }
    bulk(owner, ids, null).andExpect(status().isBadRequest());
  }

  private @NotNull ResultActions bulk(
      @NotNull User caller, @NotNull List<UUID> shipIds, @Nullable UUID orgUnitId)
      throws Exception {
    return mockMvc.perform(
        post(BULK_URL)
            .with(as(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    new ShipBulkOrgUnitRequestDto(shipIds, orgUnitId))));
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

  private @NotNull List<AuditEvent> bulkEvents() {
    return auditEventRepository.findAll().stream()
        .filter(e -> e.getEventType() == AuditEventType.HANGAR_SHIPS_BULK_ORG_UNIT_CHANGED)
        .filter(e -> owner.getId().equals(e.getActorUserId()))
        .toList();
  }

  private @NotNull UUID saveShip(@NotNull User shipOwner, @NotNull String name) {
    Ship ship = new Ship();
    ship.setName(name);
    ship.setShipType(fighter);
    ship.setOwner(shipOwner);
    ship.setInsurance("LTI");
    return shipRepository.save(ship).getId();
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
