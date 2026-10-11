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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBulkUndoService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * An admin undoes one client's writes for every member: the client is suspended through the
 * registry first, each member is undone in a transaction of its own with the member's undo
 * semantics, what was left alone is listed, the members whose data changed are notified, and the
 * run is audited and instrumented (REQ-XCH-034). Writes commit.
 */
@SpringBootTest
class ExchangeBulkUndoControllerTest {

  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY_A = "Ba9_" + "a".repeat(39);
  private static final String KEY_B = "Bb9_" + "b".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private ExchangeBulkUndoService bulkUndoService;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID admin;
  private UUID memberA;
  private UUID memberB;
  private UUID bystander;
  private UUID registryId;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> blueprints = new ArrayList<>();
  private final List<UUID> shipTypes = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    admin = user("bulk-admin", Roles.KRT_MEMBER, Roles.ADMIN);
    memberA = user("bulk-a", Roles.KRT_MEMBER);
    memberB = user("bulk-b", Roles.KRT_MEMBER);
    bystander = user("bulk-c", Roles.KRT_MEMBER);
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.BLUEPRINTS_WRITE,
            ExchangeCapability.HANGAR_WRITE));
    registryId = clientRepository.saveAndFlush(registered).getId();
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", registryId);
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID id : List.of(memberA, memberB, bystander, admin)) {
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
      jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", id);
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    blueprints.forEach(id -> jdbc.update("DELETE FROM blueprint WHERE id = ?", id));
    shipTypes.forEach(id -> jdbc.update("DELETE FROM ship_type WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void theClientIsSuspendedAndEveryMembersWritesAreUndoneExceptWhatChangedAfterwards()
      throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");
    String knife = product("Knife");
    add(memberA, KEY_A, rifle);
    add(memberB, KEY_B, pistol);
    add(memberB, KEY_B, knife);
    jdbc.update(
        "DELETE FROM personal_blueprint WHERE owner_user_id = ? AND product_key = ?",
        memberB,
        knife);
    double succeeded = executions("success");

    preview("{\"since\":\"" + hourAgo() + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members").value(2))
        .andExpect(jsonPath("$.entries").value(3))
        .andExpect(jsonPath("$.clientActive").value(true));
    assertThat(clientStatus()).isEqualTo("ACTIVE");

    String runId = start("{\"since\":\"" + hourAgo() + "\"}");
    String run = awaitEnd(runId);

    assertThat(clientStatus()).isEqualTo("SUSPENDED");
    assertThat(JsonPath.<String>read(run, "$.run.status")).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(run, "$.run.membersTotal")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(run, "$.run.membersDone")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(run, "$.run.restored")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(run, "$.run.skipped")).isEqualTo(1);
    assertThat(JsonPath.<List<String>>read(run, "$.skipped[*].reason"))
        .containsExactly("CHANGED_AFTERWARDS");
    assertThat(JsonPath.<List<String>>read(run, "$.skipped[*].resource"))
        .containsExactly("BLUEPRINT");
    assertThat(owned(memberA)).isEmpty();
    assertThat(owned(memberB)).isEmpty();
    assertThat(audits("EXCHANGE_CLIENT_SUSPENDED")).isEqualTo(1);
    assertThat(audits("EXCHANGE_BULK_UNDO_STARTED")).isEqualTo(1);
    assertThat(audits("EXCHANGE_BULK_UNDO_FINISHED")).isEqualTo(1);
    assertThat(audits("EXCHANGE_CHANGES_UNDONE")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_user_id FROM audit_event WHERE subject_id = ?"
                    + " AND event_type = 'EXCHANGE_BULK_UNDO_FINISHED'",
                UUID.class,
                registryId))
        .isEqualTo(admin);
    awaitNotifications(memberA, 1);
    awaitNotifications(memberB, 1);
    assertThat(notifications(bystander)).isZero();
    assertThat(executions("success") - succeeded).isEqualTo(1.0);
  }

  @Test
  void aRunLimitedToOneResourceAndOneInstallationLeavesTheRestAlone() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");
    add(memberA, KEY_A, rifle);
    add(memberB, KEY_B, pistol);
    UUID cutlass = shipType("Cutlass Black");
    write(
            memberA,
            KEY_A,
            "ships",
            ("{\"ops\":[{\"op\":\"upsert\",\"externalId\":\"vk-1\",\"shipType\":{\"bt\":\"%s\"},"
                    + "\"name\":\"Created\",\"insurance\":{\"kind\":\"LTI\"}}]}")
                .formatted(cutlass))
        .andExpect(jsonPath("$.applied").value(1));
    UUID installationA =
        jdbc.queryForObject(
            "SELECT id FROM exchange_installation WHERE user_id = ? AND key_thumbprint = ?",
            UUID.class,
            memberA,
            KEY_A);

    mockMvc
        .perform(
            get("/api/v1/connected-apps/admin/clients/" + registryId + "/undo/installations")
                .param("since", hourAgo().toString())
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    String runId =
        start(
            "{\"since\":\"%s\",\"installationId\":\"%s\",\"resource\":\"SHIP\"}"
                .formatted(hourAgo(), installationA));
    String run = awaitEnd(runId);

    assertThat(JsonPath.<Integer>read(run, "$.run.restored")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(run, "$.run.membersTotal")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ship WHERE owner_id = ?", Integer.class, memberA))
        .isZero();
    assertThat(owned(memberA)).containsExactly(rifle);
    assertThat(owned(memberB)).containsExactly(pistol);
  }

  @Test
  void aMemberThatCannotBeUndoneFailsTheRunButNotTheOtherMembers() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");
    add(memberA, KEY_A, rifle);
    add(memberB, KEY_B, pistol);
    jdbc.update("UPDATE exchange_journal SET before_state = 'not json' WHERE user_id = ?", memberB);
    double failed = executions("failure");

    String run = awaitEnd(start("{\"since\":\"" + hourAgo() + "\"}"));

    assertThat(JsonPath.<String>read(run, "$.run.status")).isEqualTo("FAILED");
    assertThat(JsonPath.<Integer>read(run, "$.run.membersFailed")).isEqualTo(1);
    assertThat(JsonPath.<List<String>>read(run, "$.skipped[*].reason")).containsExactly("FAILED");
    assertThat(owned(memberA)).isEmpty();
    assertThat(owned(memberB)).containsExactly(pistol);
    assertThat(executions("failure") - failed).isEqualTo(1.0);
  }

  @Test
  void aSecondRunOfTheSameClientIsRefusedAndARestartFailsTheRunningOne() throws Exception {
    UUID running = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO exchange_bulk_undo_run (id, exchange_client_id, client_id, since, status,"
            + " members_total, started_at) VALUES (?, ?, ?, ?, 'RUNNING', 0, ?)",
        running,
        registryId,
        client,
        Timestamp.from(hourAgo()),
        Timestamp.from(Instant.now()));

    mockMvc
        .perform(
            post("/api/v1/connected-apps/admin/clients/" + registryId + "/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"since\":\"" + hourAgo() + "\"}")
                .with(asAdmin()))
        .andExpect(status().isConflict());
    assertThat(clientStatus()).isEqualTo("ACTIVE");

    bulkUndoService.failInterruptedRuns();

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM exchange_bulk_undo_run WHERE id = ?", String.class, running))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT details FROM audit_event WHERE subject_id = ?"
                    + " AND event_type = 'EXCHANGE_BULK_UNDO_FINISHED'",
                String.class,
                registryId))
        .contains("interrupted=true");
  }

  @Test
  void onlyAnAdminMayUndoForEveryoneAndTheSpanMustNotStartInTheFuture() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/connected-apps/admin/clients/" + registryId + "/undo/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"since\":\"" + hourAgo() + "\"}")
                .with(
                    jwt()
                        .jwt(t -> t.subject(memberA.toString()).claim("azp", "basetool-frontend"))))
        .andExpect(status().isForbidden());
    preview("{\"since\":\"" + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}")
        .andExpect(status().isBadRequest());
    preview("{\"since\":\"" + hourAgo() + "\",\"resource\":\"PLANETS\"}")
        .andExpect(status().isBadRequest());
    assertThat(clientStatus()).isEqualTo("ACTIVE");
  }

  private ResultActions preview(@NotNull String json) throws Exception {
    return mockMvc.perform(
        post("/api/v1/connected-apps/admin/clients/" + registryId + "/undo/preview")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(asAdmin()));
  }

  /**
   * Starts a run and returns its id.
   *
   * @param json the scope
   * @return the run id
   * @throws Exception if the request fails
   */
  private @NotNull String start(@NotNull String json) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/connected-apps/admin/clients/" + registryId + "/undo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .with(asAdmin()))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /**
   * Polls a run until it has ended.
   *
   * @param runId the run
   * @return the run's detail
   * @throws Exception if a request fails or the run does not end within 30 seconds
   */
  private @NotNull String awaitEnd(@NotNull String runId) throws Exception {
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (System.nanoTime() < deadline) {
      String body =
          mockMvc
              .perform(get("/api/v1/connected-apps/admin/undo-runs/" + runId).with(asAdmin()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      if (!"RUNNING".equals(JsonPath.read(body, "$.run.status"))) {
        return body;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("The bulk undo run did not end in time");
  }

  /**
   * Polls until a member has the expected number of bulk undo notifications.
   *
   * @param member the member
   * @param expected the number
   * @throws InterruptedException if interrupted while waiting
   */
  private void awaitNotifications(@NotNull UUID member, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline && notifications(member) < expected) {
      Thread.sleep(100);
    }
    assertThat(notifications(member)).isEqualTo(expected);
  }

  private int notifications(@NotNull UUID member) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE recipient_user_id = ?"
                + " AND type = 'EXCHANGE_BULK_UNDO_APPLIED'",
            Integer.class,
            member);
    return count == null ? 0 : count;
  }

  private int audits(@NotNull String type) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE subject_id = ? AND event_type = ?",
            Integer.class,
            registryId,
            type);
    return count == null ? 0 : count;
  }

  private double executions(@NotNull String outcome) {
    return meterRegistry
        .counter(
            MetricNames.SCHEDULED_JOB_EXECUTIONS,
            MetricNames.TAG_JOB,
            "exchange_bulk_undo",
            MetricNames.TAG_OUTCOME,
            outcome)
        .count();
  }

  private @NotNull String clientStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM exchange_client WHERE id = ?", String.class, registryId);
  }

  private static @NotNull Instant hourAgo() {
    return Instant.now().minus(1, ChronoUnit.HOURS);
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(t -> t.subject(admin.toString()).claim("azp", "basetool-frontend"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private void add(@NotNull UUID member, @NotNull String key, @NotNull String product)
      throws Exception {
    write(
            member,
            key,
            "blueprints",
            "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(product))
        .andExpect(jsonPath("$.applied").value(1));
  }

  /**
   * Posts a change set as one member's installation of the test client.
   *
   * @param member the member
   * @param key the installation's key thumbprint
   * @param resource {@code blueprints} or {@code ships}
   * @param json the change set
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions write(
      @NotNull UUID member, @NotNull String key, @NotNull String resource, @NotNull String json)
      throws Exception {
    String capability =
        "blueprints".equals(resource) ? "exchange.blueprints.write" : "exchange.hangar.write";
    return mockMvc
        .perform(
            post("/api/v1/exchange/me/" + resource + "/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capability)
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, key))
        .andExpect(status().isOk());
  }

  private @NotNull UUID user(@NotNull String prefix, @NotNull String... roles) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<de.greluc.krt.profit.basetool.backend.model.Role> granted = new HashSet<>();
    for (String role : roles) {
      granted.add(roleRepository.findByCode(role).orElseThrow());
    }
    user.setRoles(granted);
    return userRepository.saveAndFlush(user).getId();
  }

  private @NotNull String product(@NotNull String name) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName(name + " " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    Blueprint saved = blueprintRepository.saveAndFlush(blueprint);
    blueprints.add(saved.getId());
    return normalizer.normalize(saved.getOutputName());
  }

  private @NotNull List<String> owned(@NotNull UUID member) {
    return jdbc.queryForList(
        "SELECT product_key FROM personal_blueprint WHERE owner_user_id = ?", String.class, member);
  }

  private @NotNull UUID shipType(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO ship_type (id, name) VALUES (?, ?)", id, name + " " + id);
    shipTypes.add(id);
    return id;
  }
}
