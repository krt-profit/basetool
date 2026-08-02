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

package de.greluc.krt.profit.basetool.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.model.dto.StagedHandoff;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test for the single-use, per-subject Redis handoff staging (REQ-INGEST-003), against
 * a real Redis in Testcontainers.
 */
@Testcontainers
class HandoffStagingServiceTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private HandoffStagingService service;

  @BeforeEach
  void setUp() {
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    IngestProperties properties = new IngestProperties();
    properties.setHandoffTtl(Duration.ofMinutes(5));
    service = new HandoffStagingService(redisTemplate, JsonMapper.builder().build(), properties);
  }

  @Test
  void shouldStageAndConsumeOnce() {
    // Given
    String handoffId = service.stage("user-1", HandoffKind.REFINERY, "{\"goodsMatched\":2}");

    // When
    Optional<StagedHandoff> first = service.consume("user-1", handoffId);
    Optional<StagedHandoff> second = service.consume("user-1", handoffId);

    // Then
    assertThat(first).isPresent();
    assertThat(first.get().kind()).isEqualTo(HandoffKind.REFINERY);
    assertThat(first.get().draftJson()).isEqualTo("{\"goodsMatched\":2}");
    assertThat(second).isEmpty();
  }

  @Test
  void shouldLogTheDraftLengthButNeverTheDraftOrTheRawIds() {
    // "Das vorausgefüllte Formular ist leer" is answered by draftLen alone: a 2-byte draft is an
    // empty backend response. The draft, the raw sub and the raw handoff id must all stay out —
    // the id is bearer-grade and travels in the browser URL (REQ-OBS-004, REQ-INGEST-003).
    List<ILoggingEvent> events =
        LogCapture.capture(
            HandoffStagingService.class,
            Level.INFO,
            () -> service.stage("user-1", HandoffKind.REFINERY, "{\"goodsMatched\":2}"));

    assertThat(events).hasSize(1);
    String line = events.getFirst().getFormattedMessage();
    assertThat(line).contains("draftLen=18").contains("sub=u-").contains("hid=h-");
    assertThat(line).doesNotContain("goodsMatched").doesNotContain("user-1");
  }

  @Test
  void shouldNotConsumeUnderADifferentSubject() {
    // Given
    String handoffId = service.stage("owner", HandoffKind.BLUEPRINT, "{\"total\":1}");

    // When / Then
    assertThat(service.consume("intruder", handoffId)).isEmpty();
    // The rightful owner can still consume it (the foreign read did not delete it).
    assertThat(service.consume("owner", handoffId)).isPresent();
  }

  @Test
  void shouldReturnEmptyForUnknownId() {
    assertThat(service.consume("user-1", "does-not-exist")).isEmpty();
  }
}
