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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeClientUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.task.ExchangeRegistryReconcileTask;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "app.exchange.mirror.enabled=true",
      "app.exchange.mirror.reconcile-interval=PT1H"
    })
@Testcontainers
class ExchangeRegistryMirrorIntegrationTest {

  private static final String KEY = "exchange:registry";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  @Autowired private ExchangeRegistryService registryService;
  @Autowired private ExchangeRegistryMirrorSync mirrorSync;
  @Autowired private ExchangeRegistryReconcileTask reconcileTask;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MeterRegistry meterRegistry;
  @MockitoSpyBean private ExchangeRegistryMirror mirror;

  /**
   * Points the backend at the container, as its own ACL user.
   *
   * @param registry the property registry
   */
  @DynamicPropertySource
  static void redisProperties(@NotNull DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.username", () -> RedisAclTemplate.BACKEND_USER);
    registry.add(
        "spring.data.redis.password",
        () -> RedisAclTemplate.E2E_PASSWORDS.get("REDIS_BACKEND_PASSWORD"));
  }

  @AfterEach
  void cleanUp() {
    reset(mirror);
    clientRepository.deleteAll();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(false);
    settingsRepository.saveAndFlush(settings);
    auditEventRepository.deleteAll(
        auditEventRepository.findAll().stream()
            .filter(event -> event.getDomain() == AuditDomain.CONNECTED_APPS)
            .toList());
    mirrorSync.resync(ExchangeMirrorPhase.RECONCILE);
  }

  @Test
  void aNewClientReachesTheMirrorAfterTheCommit() {
    ExchangeClient client = register("versekit");

    assertThat(mirrored().clients().get("versekit"))
        .extracting(ExchangeRegistrySnapshot.Client::status)
        .isEqualTo(ExchangeClientStatus.ACTIVE);
    assertThat(client.getCapabilities()).contains(ExchangeCapability.CONNECT);
    assertThat(
            auditEventRepository.findAll().stream()
                .filter(event -> event.getDomain() == AuditDomain.CONNECTED_APPS)
                .count())
        .isEqualTo(1);
  }

  @Test
  void aSuspensionReachesTheMirrorBeforeTheCommit() {
    ExchangeClient client = register("versekit");

    inOuterTransaction(
        () -> {
          registryService.suspendClient(client.getId(), client.getVersion());
          assertThat(mirrored().clients().get("versekit").status())
              .as("written before the outer transaction commits")
              .isEqualTo(ExchangeClientStatus.SUSPENDED);
        },
        false);

    assertThat(mirrored().clients().get("versekit").status())
        .isEqualTo(ExchangeClientStatus.SUSPENDED);
  }

  @Test
  void anActivationReachesTheMirrorOnlyAfterTheCommit() {
    ExchangeClient client = register("versekit");
    ExchangeClient suspended = registryService.suspendClient(client.getId(), client.getVersion());

    inOuterTransaction(
        () -> {
          registryService.activateClient(suspended.getId(), suspended.getVersion());
          assertThat(mirrored().clients().get("versekit").status())
              .as("not before the commit")
              .isEqualTo(ExchangeClientStatus.SUSPENDED);
        },
        false);

    assertThat(mirrored().clients().get("versekit").status())
        .isEqualTo(ExchangeClientStatus.ACTIVE);
  }

  @Test
  void aRolledBackSuspensionIsTakenBackOutOfTheMirror() {
    ExchangeClient client = register("versekit");

    inOuterTransaction(
        () -> registryService.suspendClient(client.getId(), client.getVersion()), true);

    assertThat(mirrored().clients().get("versekit").status())
        .isEqualTo(ExchangeClientStatus.ACTIVE);
    assertThat(clientRepository.findById(client.getId()).orElseThrow().getStatus())
        .isEqualTo(ExchangeClientStatus.ACTIVE);
  }

  @Test
  void aFailedMirrorWriteFailsTheRestrictionAndChangesNothing() {
    ExchangeClient client = register("versekit");
    doThrow(new RedisConnectionFailureException("down")).when(mirror).write(any(), anyLong());
    double failuresBefore = count("pre_commit", "failed");

    assertThatThrownBy(() -> registryService.suspendClient(client.getId(), client.getVersion()))
        .isInstanceOf(ExternalServiceException.class);

    assertThat(clientRepository.findById(client.getId()).orElseThrow().getStatus())
        .isEqualTo(ExchangeClientStatus.ACTIVE);
    assertThat(count("pre_commit", "failed")).isEqualTo(failuresBefore + 1);
  }

  @Test
  void aFailedWriteAfterAPermissiveCommitKeepsTheChangeAndTheReconcileHealsIt() {
    doThrow(new RedisConnectionFailureException("down")).when(mirror).write(any(), anyLong());

    ExchangeClient client = register("versekit");

    assertThat(clientRepository.findById(client.getId())).isPresent();
    assertThat(mirror.read().map(s -> s.clients().containsKey("versekit")).orElse(false)).isFalse();
    reset(mirror);
    reconcileTask.reconcile();
    assertThat(mirrored().clients()).containsKey("versekit");
  }

  @Test
  void theReconcileRewritesADivergedMirror() {
    register("versekit");
    StringRedisTemplate admin = adminTemplate();
    admin
        .opsForValue()
        .set(
            KEY,
            "{\"schemaVersion\":1,\"revision\":1,\"writtenAt\":\"2026-01-01T00:00:00Z\",\"enabled\":true,\"clients\":{}}");

    assertThat(mirrorSync.resync(ExchangeMirrorPhase.RECONCILE)).isTrue();

    ExchangeRegistrySnapshot healed = mirrored();
    assertThat(healed.enabled()).isFalse();
    assertThat(healed.clients()).containsKey("versekit");
    assertThat(mirrorSync.resync(ExchangeMirrorPhase.RECONCILE))
        .as("an agreeing mirror is not rewritten")
        .isFalse();
  }

  @Test
  void theClientGaugesFollowCommittedChangesAndTheReconcile() {
    ExchangeClient first = register("versekit");
    register("other-kit");

    assertThat(clients("ACTIVE")).isEqualTo(2.0d);
    assertThat(clients("SUSPENDED")).isZero();

    registryService.suspendClient(first.getId(), first.getVersion());
    assertThat(clients("ACTIVE")).isEqualTo(1.0d);
    assertThat(clients("SUSPENDED")).isEqualTo(1.0d);

    clientRepository.deleteAll();
    reconcileTask.reconcile();
    assertThat(clients("ACTIVE")).isZero();
    assertThat(clients("SUSPENDED")).isZero();
  }

  @Test
  void removingACapabilityAndSwitchingOffAreMirroredBeforeTheCommit() {
    ExchangeClient client = register("versekit");
    ExchangeSettings settings =
        registryService.updateSettings(true, registryService.getSettings().getVersion());

    inOuterTransaction(
        () -> {
          registryService.updateClient(
              client.getId(),
              new ExchangeClientUpdateRequest(
                  "VerseKit",
                  Set.of(ExchangeCapability.CONNECT),
                  null,
                  null,
                  null,
                  null,
                  client.getVersion()));
          registryService.updateSettings(false, settings.getVersion());
          ExchangeRegistrySnapshot during = mirrored();
          assertThat(during.enabled()).isFalse();
          assertThat(during.clients().get("versekit").capabilities())
              .containsExactly("exchange.connect");
        },
        false);

    assertThat(mirrored().enabled()).isFalse();
  }

  /**
   * Registers a client with the connect and blueprint-read capabilities.
   *
   * @param clientId the client id
   * @return the saved client
   */
  private @NotNull ExchangeClient register(@NotNull String clientId) {
    return registryService.createClient(
        new ExchangeClientCreateRequest(
            clientId,
            "VerseKit",
            Set.of(ExchangeCapability.CONNECT, ExchangeCapability.BLUEPRINTS_READ),
            null,
            null,
            null,
            null));
  }

  /**
   * Runs work inside one outer transaction that the registry calls join.
   *
   * @param work the work
   * @param rollback whether to roll the transaction back instead of committing
   */
  private void inOuterTransaction(@NotNull Runnable work, boolean rollback) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              work.run();
              if (rollback) {
                status.setRollbackOnly();
              }
            });
  }

  /**
   * Reads the mirror through the real Redis mirror.
   *
   * @return the mirrored snapshot
   */
  private @NotNull ExchangeRegistrySnapshot mirrored() {
    Optional<ExchangeRegistrySnapshot> snapshot = mirror.read();
    assertThat(snapshot).isPresent();
    return snapshot.orElseThrow();
  }

  /**
   * Reads one mirror-write counter.
   *
   * @param phase the phase label
   * @param outcome the outcome label
   * @return the count, {@code 0} when never incremented
   */
  private double count(@NotNull String phase, @NotNull String outcome) {
    var counter =
        meterRegistry
            .find("basetool.exchange.mirror.writes")
            .tag("phase", phase)
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Reads one registry client gauge.
   *
   * @param status the status label
   * @return the gauge's value
   */
  private double clients(@NotNull String status) {
    return meterRegistry.get("basetool.exchange.clients").tag("status", status).gauge().value();
  }

  /**
   * Opens a template as the Redis admin user, to tamper with the mirror.
   *
   * @return the template
   */
  private static @NotNull StringRedisTemplate adminTemplate() {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    configuration.setUsername(RedisAclTemplate.ADMIN_USER);
    configuration.setPassword(RedisAclTemplate.E2E_PASSWORDS.get("REDIS_PASSWORD"));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  /**
   * The ACL template's variables for this suite.
   *
   * @return the E2E throwaway passwords plus {@code REDIS_DEFAULT_USER=off}
   */
  private static @NotNull Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }
}
