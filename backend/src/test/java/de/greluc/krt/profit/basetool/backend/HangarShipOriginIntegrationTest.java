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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipBulkOriginRequestDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A ship's origin is chosen on create, edited behind the ship's version, set in bulk, and locked by
 * an import outside the version (REQ-HANGAR-017, REQ-HANGAR-018).
 */
@SpringBootTest
@Transactional
class HangarShipOriginIntegrationTest {

  private static final String TYPE_NAME = "Origin Probe 3329";

  @Autowired private WebApplicationContext context;
  @Autowired private ShipRepository shipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private User owner;
  private User stranger;
  private ShipType probe;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = saveUser("origin-owner");
    stranger = saveUser("origin-stranger");
    ShipType type = new ShipType();
    type.setName(TYPE_NAME);
    probe = shipTypeRepository.save(type);
  }

  @Test
  @DisplayName("the web create needs PLEDGE or IN_GAME and stores the choice")
  void createNeedsAChoice() throws Exception {
    create(null).andExpect(status().isBadRequest());
    create(ShipOrigin.UNKNOWN).andExpect(status().isBadRequest());

    create(ShipOrigin.IN_GAME)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.origin.value").value("IN_GAME"))
        .andExpect(jsonPath("$.origin.locked").value(false));
  }

  @Test
  @DisplayName("an origin edit moves the version, so a stale edit 409s")
  void originEditIsPartOfTheVersion() throws Exception {
    UUID ship = saveShip(owner, ShipOrigin.UNKNOWN, false);

    edit(ship, 0L, ShipOrigin.PLEDGE).andExpect(status().isOk());
    shipRepository.flush();
    edit(ship, 0L, ShipOrigin.IN_GAME).andExpect(status().isConflict());

    Ship stored = shipRepository.findById(ship).orElseThrow();
    assertThat(stored.getOrigin()).isEqualTo(ShipOrigin.PLEDGE);
    assertThat(stored.getVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("a locked ship refuses another origin")
  void lockedShipRefusesAnotherOrigin() throws Exception {
    UUID ship = saveShip(owner, ShipOrigin.UNKNOWN, true);

    edit(ship, 0L, ShipOrigin.IN_GAME).andExpect(status().isBadRequest());
    edit(ship, 0L, null).andExpect(status().isOk());
  }

  @Test
  @DisplayName("the bulk action changes, skips the locked and the unchanged, and records counts")
  void bulkChangesAndSkips() throws Exception {
    UUID unknown = saveShip(owner, ShipOrigin.UNKNOWN, false);
    UUID already = saveShip(owner, ShipOrigin.IN_GAME, false);
    UUID locked = saveShip(owner, ShipOrigin.UNKNOWN, true);

    bulk(owner, List.of(unknown, already, locked), ShipOrigin.IN_GAME)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(1))
        .andExpect(jsonPath("$.skipped").value(2));

    assertThat(shipRepository.findById(unknown).orElseThrow().effectiveOrigin())
        .isEqualTo(ShipOrigin.IN_GAME);
    assertThat(shipRepository.findById(locked).orElseThrow().effectiveOrigin())
        .isEqualTo(ShipOrigin.PLEDGE);
    edit(unknown, 0L, ShipOrigin.PLEDGE).andExpect(status().isConflict());

    List<AuditEvent> events = bulkEvents();
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getDetails())
        .contains("changed=1")
        .contains("skipped=2")
        .doesNotContain("IN_GAME");
  }

  @Test
  @DisplayName("a bulk selection with a foreign or unknown ship, or an unknown origin, is refused")
  void bulkRefusals() throws Exception {
    UUID own = saveShip(owner, ShipOrigin.UNKNOWN, false);
    UUID foreign = saveShip(stranger, ShipOrigin.UNKNOWN, false);

    bulk(owner, List.of(own, foreign), ShipOrigin.PLEDGE).andExpect(status().isForbidden());
    bulk(owner, List.of(own, UUID.randomUUID()), ShipOrigin.PLEDGE)
        .andExpect(status().isNotFound());
    bulk(owner, List.of(own), ShipOrigin.UNKNOWN).andExpect(status().isBadRequest());

    assertThat(shipRepository.findById(own).orElseThrow().getOrigin())
        .isEqualTo(ShipOrigin.UNKNOWN);
    assertThat(bulkEvents()).isEmpty();
  }

  @Test
  @DisplayName("only an admin may change the origin of a member's ships")
  void adminBulk() throws Exception {
    UUID ship = saveShip(owner, ShipOrigin.UNKNOWN, false);
    String url = "/api/v1/hangar/admin/users/" + owner.getId() + "/ships/bulk-origin";
    String body =
        objectMapper.writeValueAsString(
            new ShipBulkOriginRequestDto(List.of(ship), ShipOrigin.PLEDGE));

    mockMvc
        .perform(post(url).with(as(stranger)).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(url)
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(stranger.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(1));

    assertThat(shipRepository.findById(ship).orElseThrow().getOrigin())
        .isEqualTo(ShipOrigin.PLEDGE);
  }

  @Test
  @DisplayName("an import locks a matched ship to PLEDGE without moving its version")
  void importLocksWithoutTheVersion() throws Exception {
    UUID ship = saveShip(owner, ShipOrigin.UNKNOWN, false);
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "fleetview.json",
            "application/json",
            ("[{\"name\":\"" + TYPE_NAME + "\",\"shipname\":\"\",\"type\":\"ship\"}]")
                .getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/v1/hangar/import/ships").file(file).with(as(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1));
    shipRepository.flush();

    Ship stored = shipRepository.findById(ship).orElseThrow();
    assertThat(stored.isOriginLocked()).isTrue();
    assertThat(stored.effectiveOrigin()).isEqualTo(ShipOrigin.PLEDGE);
    assertThat(stored.getVersion()).isZero();
    edit(ship, 0L, null).andExpect(status().isOk());
  }

  @Test
  @DisplayName("an import never matches a ship bought in-game")
  void importSkipsInGameShips() throws Exception {
    UUID inGame = saveShip(owner, ShipOrigin.IN_GAME, false);
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "fleetview.json",
            "application/json",
            ("[{\"name\":\"" + TYPE_NAME + "\",\"shipname\":\"\",\"type\":\"ship\"}]")
                .getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/v1/hangar/import/ships").file(file).with(as(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(1));

    assertThat(shipRepository.findById(inGame).orElseThrow().isOriginLocked()).isFalse();
    assertThat(shipRepository.findByOwnerId(owner.getId()))
        .extracting(Ship::effectiveOrigin)
        .containsExactlyInAnyOrder(ShipOrigin.IN_GAME, ShipOrigin.PLEDGE);
  }

  private @NotNull ResultActions create(@Nullable ShipOrigin origin) throws Exception {
    return mockMvc.perform(
        post("/api/v1/hangar/ships")
            .with(as(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    new ShipRequestDto(
                        "Probe", probe.getId(), "LTI", null, false, null, null, origin))));
  }

  private @NotNull ResultActions edit(
      @NotNull UUID shipId, long version, @Nullable ShipOrigin origin) throws Exception {
    return mockMvc.perform(
        put("/api/v1/hangar/ships/" + shipId)
            .with(as(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    new ShipRequestDto(
                        "Probe", probe.getId(), "LTI", null, false, version, null, origin))));
  }

  private @NotNull ResultActions bulk(
      @NotNull User caller, @NotNull List<UUID> shipIds, @NotNull ShipOrigin origin)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/hangar/ships/bulk-origin")
            .with(as(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(new ShipBulkOriginRequestDto(shipIds, origin))));
  }

  private static @NotNull RequestPostProcessor as(@NotNull User caller) {
    return jwt()
        .jwt(builder -> builder.subject(caller.getId().toString()))
        .authorities(new SimpleGrantedAuthority("HANGAR_WRITE"));
  }

  private @NotNull List<AuditEvent> bulkEvents() {
    return auditEventRepository.findAll().stream()
        .filter(e -> e.getEventType() == AuditEventType.HANGAR_SHIPS_BULK_ORIGIN_CHANGED)
        .filter(e -> owner.getId().equals(e.getActorUserId()))
        .toList();
  }

  private @NotNull UUID saveShip(
      @NotNull User shipOwner, @NotNull ShipOrigin origin, boolean lock) {
    Ship ship = new Ship();
    ship.setName("Probe");
    ship.setShipType(probe);
    ship.setOwner(shipOwner);
    ship.setInsurance("LTI");
    ship.setOrigin(origin);
    ship.setOriginLocked(lock);
    return shipRepository.saveAndFlush(ship).getId();
  }

  private @NotNull User saveUser(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }
}
