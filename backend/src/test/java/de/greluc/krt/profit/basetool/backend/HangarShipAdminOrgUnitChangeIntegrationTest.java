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
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** An admin changes the org unit of a member's ships, singly and in bulk (REQ-HANGAR-012). */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class HangarShipAdminOrgUnitChangeIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ApplicationEvents events;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private User member;
  private User admin;
  private SpecialCommand membersCommand;
  private SpecialCommand adminsCommand;
  private ShipType fighter;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    member = saveUser("admin-unit-member");
    admin = saveUser("admin-unit-admin");
    membersCommand = saveCommand("Admin Unit Member SK", "AUMS");
    adminsCommand = saveCommand("Admin Unit Admin SK", "AUAS");
    saveMembership(member, Squadron.IRIDIUM_ID);
    saveMembership(member, membersCommand.getId());
    saveMembership(admin, adminsCommand.getId());

    ShipType type = new ShipType();
    type.setName("Admin Unit Fighter");
    fighter = shipTypeRepository.save(type);
  }

  @Test
  @DisplayName("an admin moves one ship of a member into one of the member's units")
  void movesOneShip() throws Exception {
    UUID ship = saveShip(member, "Alpha");

    single(admin, member, ship, membersCommand.getId(), 0L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owningOrgUnitVersion").value(1));

    assertThat(unitOf(ship)).isEqualTo(membersCommand.getId());
    AuditEvent event = onlyEvent(AuditEventType.HANGAR_SHIP_ORG_UNIT_CHANGED);
    assertThat(event.getActorUserId()).isEqualTo(admin.getId());
    assertThat(event.getSubjectId()).isEqualTo(ship);
    assertThat(event.getTargetUserId()).isEqualTo(member.getId());
    assertThat(event.getDetails())
        .contains("to=SPECIAL_COMMAND:" + membersCommand.getId())
        .doesNotContain("Alpha")
        .doesNotContain("Admin Unit Member SK");
  }

  @Test
  @DisplayName("the member hears about a single change once, as a changed hangar")
  void singleChangeTellsTheMember() throws Exception {
    UUID ship = saveShip(member, "Alpha");

    single(admin, member, ship, membersCommand.getId(), 0L).andExpect(status().isOk());

    List<NoticeEvent> notices = notices();
    assertThat(notices).hasSize(1);
    NoticeEvent notice = notices.getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.HANGAR_CHANGED_BY_ADMIN);
    assertThat(notice.contextRecipientUserId()).isEqualTo(member.getId());
    assertThat(notice.actorSub()).isEqualTo(admin.getId());
    assertThat(notice.renderParams())
        .containsEntry("changeCode", "ORG_UNIT_CHANGED")
        .containsEntry("shipType", "Admin Unit Fighter");
  }

  @Test
  @DisplayName("a unit outside the member's memberships is refused with 400, even the admin's own")
  void refusesTheAdminsUnit() throws Exception {
    UUID ship = saveShip(member, "Alpha");

    single(admin, member, ship, adminsCommand.getId(), 0L).andExpect(status().isBadRequest());
    bulk(admin, member, List.of(ship), adminsCommand.getId()).andExpect(status().isBadRequest());

    assertThat(unitOf(ship)).isNull();
    assertThat(notices()).isEmpty();
  }

  @Test
  @DisplayName("a member calling the admin endpoints is refused with 403")
  void nonAdminIsRefused() throws Exception {
    UUID ship = saveShip(member, "Alpha");

    mockMvc
        .perform(
            put(singleUrl(member, ship))
                .with(asMember(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ShipOrgUnitRequestDto(membersCommand.getId(), 0L))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(bulkUrl(member))
                .with(asMember(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ShipBulkOrgUnitRequestDto(List.of(ship), membersCommand.getId()))))
        .andExpect(status().isForbidden());

    assertThat(unitOf(ship)).isNull();
  }

  @Test
  @DisplayName("a stale unit counter on the admin path 409s")
  void staleCounterConflicts() throws Exception {
    UUID ship = saveShip(member, "Alpha");
    single(admin, member, ship, membersCommand.getId(), 0L).andExpect(status().isOk());

    single(admin, member, ship, Squadron.IRIDIUM_ID, 0L).andExpect(status().isConflict());
  }

  @Test
  @DisplayName("a ship of another member under the member's path is refused with 403")
  void refusesAShipOfAnotherMember() throws Exception {
    UUID foreign = saveShip(admin, "Intruder");

    single(admin, member, foreign, membersCommand.getId(), 0L).andExpect(status().isForbidden());
    bulk(admin, member, List.of(foreign), membersCommand.getId()).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("an admin moves several ships and the member hears one summary notice")
  void bulkChangeTellsTheMemberOnce() throws Exception {
    UUID first = saveShip(member, "Alpha");
    UUID second = saveShip(member, "Bravo");
    UUID already = saveShip(member, "Charlie");
    single(admin, member, already, membersCommand.getId(), 0L).andExpect(status().isOk());
    events.clear();

    bulk(admin, member, List.of(first, second, already), membersCommand.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2))
        .andExpect(jsonPath("$.skipped").value(1));

    assertThat(unitOf(first)).isEqualTo(membersCommand.getId());
    assertThat(unitOf(second)).isEqualTo(membersCommand.getId());
    List<NoticeEvent> notices = notices();
    assertThat(notices).hasSize(1);
    NoticeEvent notice = notices.getFirst();
    assertThat(notice.eventType())
        .isEqualTo(NotificationEventType.HANGAR_SHIPS_ORG_UNIT_CHANGED_BY_ADMIN);
    assertThat(notice.contextRecipientUserId()).isEqualTo(member.getId());
    assertThat(notice.renderParams()).containsEntry("count", "2");

    AuditEvent event = onlyEvent(AuditEventType.HANGAR_SHIPS_BULK_ORG_UNIT_CHANGED);
    assertThat(event.getActorUserId()).isEqualTo(admin.getId());
    assertThat(event.getTargetUserId()).isEqualTo(member.getId());
    assertThat(event.getDetails()).contains("changed=2").doesNotContain("Alpha");
  }

  @Test
  @DisplayName("a bulk change that moves one ship tells the member about that ship")
  void bulkChangeOfOneShipNamesItsType() throws Exception {
    UUID ship = saveShip(member, "Alpha");

    bulk(admin, member, List.of(ship), Squadron.IRIDIUM_ID).andExpect(status().isOk());

    List<NoticeEvent> notices = notices();
    assertThat(notices).hasSize(1);
    assertThat(notices.getFirst().eventType())
        .isEqualTo(NotificationEventType.HANGAR_CHANGED_BY_ADMIN);
    assertThat(notices.getFirst().renderParams())
        .containsEntry("changeCode", "ORG_UNIT_CHANGED")
        .containsEntry("shipType", "Admin Unit Fighter");
  }

  @Test
  @DisplayName("an unchanged unit writes nothing and tells nobody")
  void unchangedIsSilent() throws Exception {
    UUID ship = saveShip(member, "Alpha");

    single(admin, member, ship, null, 0L).andExpect(status().isOk());
    bulk(admin, member, List.of(ship), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(0));

    assertThat(notices()).isEmpty();
  }

  @Test
  @DisplayName("an admin moving their own ships hears nothing")
  void ownShipsAreSilent() throws Exception {
    UUID first = saveShip(admin, "Alpha");
    UUID second = saveShip(admin, "Bravo");

    single(admin, admin, first, adminsCommand.getId(), 0L).andExpect(status().isOk());
    bulk(admin, admin, List.of(first, second), null).andExpect(status().isOk());

    assertThat(unitOf(first)).isNull();
    assertThat(notices()).isEmpty();
  }

  private @NotNull ResultActions single(
      @NotNull User caller,
      @NotNull User owner,
      @NotNull UUID shipId,
      @Nullable UUID orgUnitId,
      long version)
      throws Exception {
    return mockMvc.perform(
        put(singleUrl(owner, shipId))
            .with(asAdmin(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(new ShipOrgUnitRequestDto(orgUnitId, version))));
  }

  private @NotNull ResultActions bulk(
      @NotNull User caller,
      @NotNull User owner,
      @NotNull List<UUID> shipIds,
      @Nullable UUID orgUnitId)
      throws Exception {
    return mockMvc.perform(
        post(bulkUrl(owner))
            .with(asAdmin(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    new ShipBulkOrgUnitRequestDto(shipIds, orgUnitId))));
  }

  private static @NotNull String singleUrl(@NotNull User owner, @NotNull UUID shipId) {
    return "/api/v1/hangar/admin/users/" + owner.getId() + "/ships/" + shipId + "/org-unit";
  }

  private static @NotNull String bulkUrl(@NotNull User owner) {
    return "/api/v1/hangar/admin/users/" + owner.getId() + "/ships/bulk-org-unit";
  }

  private static @NotNull RequestPostProcessor asAdmin(@NotNull User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static @NotNull RequestPostProcessor asMember(@NotNull User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"));
  }

  private @NotNull List<NoticeEvent> notices() {
    return events.stream(NoticeEvent.class).toList();
  }

  private @NotNull AuditEvent onlyEvent(@NotNull AuditEventType type) {
    List<AuditEvent> matching =
        auditEventRepository.findAll().stream()
            .filter(e -> e.getEventType() == type)
            .filter(e -> admin.getId().equals(e.getActorUserId()))
            .filter(e -> member.getId().equals(e.getTargetUserId()))
            .toList();
    assertThat(matching).hasSize(1);
    return matching.getFirst();
  }

  private @Nullable UUID unitOf(@NotNull UUID id) {
    OrgUnit unit = shipRepository.findById(id).orElseThrow().getOwningOrgUnit();
    return unit == null ? null : unit.getId();
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
