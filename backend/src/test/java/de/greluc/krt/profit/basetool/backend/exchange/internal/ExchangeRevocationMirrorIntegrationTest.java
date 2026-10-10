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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class ExchangeRevocationMirrorIntegrationTest {

  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440e1");
  private static final String KEY = "r".repeat(43);

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  @Autowired private ConnectedAppsService connectedAppsService;
  @Autowired private ExchangeRevocationSync revocationSync;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeInstallationRepository installationRepository;
  @Autowired private ExchangeClientRevocationRepository revocationRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoSpyBean private ExchangeRevocationMirror revocationMirror;
  @MockitoBean private KeycloakService keycloakService;

  private ExchangeInstallation installation;

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

  @BeforeEach
  void setUp() {
    User member = new User();
    member.setId(MEMBER);
    member.setUsername("revocation-member");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    userRepository.saveAndFlush(member);
    ExchangeClient client = new ExchangeClient();
    client.setClientId("versekit-rev");
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT));
    clientRepository.saveAndFlush(client);
    installation = new ExchangeInstallation();
    installation.setClient(client);
    installation.setUser(member);
    installation.setKeyThumbprint(KEY);
    installation.setFirstSeenAt(Instant.now());
    installation.setLastSeenAt(Instant.now());
    installation = installationRepository.saveAndFlush(installation);
  }

  @AfterEach
  void cleanUp() {
    reset(revocationMirror);
    clientRepository.findAll().stream()
        .filter(c -> "versekit-rev".equals(c.getClientId()))
        .forEach(clientRepository::delete);
    auditEventRepository.deleteAll(
        auditEventRepository.findAll().stream()
            .filter(e -> MEMBER.equals(e.getTargetUserId()))
            .toList());
    userRepository.deleteById(MEMBER);
  }

  @Test
  void aDisconnectedInstallationIsDeniedBeforeTheCommit() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              connectedAppsService.disconnectInstallation(MEMBER, installation.getId());
              assertThat(revocationMirror.isDenied(KEY))
                  .as("denied before the outer transaction commits")
                  .isTrue();
            });

    assertThat(admin().getExpire("exchange:deny:" + KEY)).isPositive();
  }

  @Test
  void aFailedMirrorWriteLeavesTheInstallationConnected() {
    doThrow(new RedisConnectionFailureException("down")).when(revocationMirror).deny(any(), any());

    assertThatThrownBy(
            () -> connectedAppsService.disconnectInstallation(MEMBER, installation.getId()))
        .isInstanceOf(ExternalServiceException.class);

    assertThat(installationRepository.findById(installation.getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  @Test
  void aDisconnectedClientIsMirroredWithItsTime() {
    connectedAppsService.disconnectClient(MEMBER, "versekit-rev");

    assertThat(admin().opsForValue().get("exchange:revoked:versekit-rev:" + MEMBER)).isNotBlank();
  }

  @Test
  void theRepairWritesBackALostDenial() {
    connectedAppsService.disconnectInstallation(MEMBER, installation.getId());
    admin().delete("exchange:deny:" + KEY);

    assertThat(revocationSync.repair()).isEqualTo(1);
    assertThat(revocationMirror.isDenied(KEY)).isTrue();
    assertThat(revocationSync.repair()).isZero();
  }

  /**
   * Opens a template as the Redis admin user, to inspect and tamper with the mirror.
   *
   * @return the template
   */
  private static @NotNull StringRedisTemplate admin() {
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
