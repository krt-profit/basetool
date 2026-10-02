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

package de.greluc.krt.profit.basetool.ingest.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import java.util.ArrayList;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test for the single-use, per-subject Redis handoff staging (REQ-INGEST-003), against
 * a real Redis in Testcontainers.
 */
@Testcontainers
class HandoffStagingServiceTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS)).withExposedPorts(6379);

  /**
   * The Redis key schema the frontend's {@code IngestHandoffService} consumes, spelled out as a
   * literal rather than borrowed from {@link HandoffStagingService#KEY_PREFIX}: the point of the
   * consume helper below is to read what the <em>frontend</em> reads, so a rename on the gateway
   * side alone fails here instead of silently orphaning every staged draft.
   */
  private static final String FRONTEND_KEY_PREFIX = "ingest:handoff:";

  /** The registry client the drafts of most tests come from. */
  private static final String CLIENT = "sc-extractor";

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private StringRedisTemplate redisTemplate;
  private HandoffStagingService service;

  @BeforeEach
  void setUp() {
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    service = service(TestProperties.ingest("handoff-ttl", "PT5M"));
  }

  /**
   * Builds the service over the shared Redis with the given configuration.
   *
   * @param properties the ingest configuration under test
   * @return a staging service writing to the Testcontainers Redis
   */
  private HandoffStagingService service(IngestProperties properties) {
    return new HandoffStagingService(redisTemplate, objectMapper, properties);
  }

  /**
   * Stages a draft of {@link #CLIENT} with a cap of ten.
   *
   * @param sub the member
   * @param kind the draft's kind
   * @param json the draft
   * @return the handoff id
   */
  private String draft(String sub, HandoffKind kind, String json) {
    return service.stageDraft(CLIENT, sub, kind, json, 10).handoffId();
  }

  /**
   * Consumes a staged handoff the way the frontend does: an atomic {@code GETDEL} on {@code
   * ingest:handoff:<sub>:<id>}.
   *
   * @param sub the subject to consume under
   * @param handoffId the handoff id
   * @return the staged handoff, or empty when there is none for that subject
   */
  private Optional<StagedHandoff> consume(String sub, String handoffId) {
    String value =
        redisTemplate.opsForValue().getAndDelete(FRONTEND_KEY_PREFIX + sub + ":" + handoffId);
    return value == null
        ? Optional.empty()
        : Optional.of(objectMapper.readValue(value, StagedHandoff.class));
  }

  @Test
  void shouldStageAndConsumeOnce() {
    String handoffId = draft("user-1", HandoffKind.REFINERY, "{\"goodsMatched\":2}");

    Optional<StagedHandoff> first = consume("user-1", handoffId);
    Optional<StagedHandoff> second = consume("user-1", handoffId);

    assertThat(first).isPresent();
    assertThat(first.get().kind()).isEqualTo(HandoffKind.REFINERY);
    assertThat(first.get().draftJson()).isEqualTo("{\"goodsMatched\":2}");
    assertThat(second).isEmpty();
  }

  @Test
  void shouldLogTheDraftLengthButNeverTheDraftOrTheRawIds() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            HandoffStagingService.class,
            Level.INFO,
            () -> draft("user-1", HandoffKind.REFINERY, "{\"goodsMatched\":2}"));

    assertThat(events).hasSize(1);
    String line = events.getFirst().getFormattedMessage();
    assertThat(line).contains("draftLen=18").contains("sub=u-").contains("hid=h-");
    assertThat(line).doesNotContain("goodsMatched").doesNotContain("user-1");
  }

  @Test
  void shouldNotConsumeUnderADifferentSubject() {
    String handoffId = draft("owner", HandoffKind.BLUEPRINT, "{\"total\":1}");

    assertThat(consume("intruder", handoffId)).isEmpty();
    assertThat(consume("owner", handoffId)).isPresent();
  }

  @Test
  void shouldReturnEmptyForUnknownId() {
    assertThat(consume("user-1", "does-not-exist")).isEmpty();
  }

  /**
   * Staging beyond the cap evicts the oldest drafts, bounding the memory used in the shared,
   * non-evicting Redis instance.
   */
  @Test
  void shouldEvictTheOldestDraftsBeyondTheCap() {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      ids.add(
          service
              .stageDraft(CLIENT, "user-cap", HandoffKind.REFINERY, "{\"n\":" + i + "}", 3)
              .handoffId());
    }

    assertThat(consume("user-cap", ids.get(0)))
        .describedAs("the oldest entry is evicted once the cap is exceeded")
        .isEmpty();
    assertThat(consume("user-cap", ids.get(1))).isPresent();
    assertThat(consume("user-cap", ids.get(2))).isPresent();
    assertThat(consume("user-cap", ids.get(3))).isPresent();
  }

  /**
   * The eviction reads the index length from the RPUSH answer instead of a separate LLEN
   * (ING-PERF-02); the index therefore has to hold exactly the cap after an overflow, never one
   * more or one fewer.
   */
  @Test
  void shouldKeepTheIndexAtExactlyTheCap() {
    for (int i = 0; i < 5; i++) {
      service.stageDraft(CLIENT, "user-index", HandoffKind.REFINERY, "{\"n\":" + i + "}", 2);
    }

    assertThat(
            redisTemplate
                .opsForList()
                .size(HandoffStagingService.DRAFT_INDEX_PREFIX + CLIENT + ":user-index"))
        .isEqualTo(2L);
  }

  /**
   * A staged mass change has a slot of its own per client and member: the same client's newer one
   * replaces it, drafts stay.
   */
  @Test
  void shouldKeepOneMassChangePerClientAndMemberWithoutEvictingDrafts() {
    String draft =
        service
            .stageDraft("versekit", "user-mass", HandoffKind.BLUEPRINT, "{\"total\":1}", 1)
            .handoffId();
    HandoffStagingService.Staged first =
        service.stageMassChange("versekit", "user-mass", "{\"resource\":\"stock\"}", 4096);
    HandoffStagingService.Staged second =
        service.stageMassChange("versekit", "user-mass", "{\"resource\":\"ships\"}", 4096);

    assertThat(consume("user-mass", first.handoffId())).isEmpty();
    Optional<StagedHandoff> kept = consume("user-mass", second.handoffId());
    assertThat(kept).isPresent();
    assertThat(kept.get().kind()).isEqualTo(HandoffKind.MASS_CHANGE);
    assertThat(second.key()).isEqualTo(FRONTEND_KEY_PREFIX + "user-mass:" + second.handoffId());
    assertThat(second.bytes()).isPositive();
    assertThat(consume("user-mass", draft)).isPresent();
  }

  /**
   * Exchange drafts have slots of their own per client and member: a client flooding drafts evicts
   * only its own oldest ones, never another client's drafts.
   */
  @Test
  void shouldKeepOneClientsDraftsApartFromOtherClients() {
    HandoffStagingService.Staged other =
        service.stageDraft(CLIENT, "user-x", HandoffKind.REFINERY, "{\"other\":1}", 3);
    List<HandoffStagingService.Staged> flood = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      flood.add(
          service.stageDraft("versekit", "user-x", HandoffKind.BLUEPRINT, "{\"n\":" + i + "}", 3));
    }

    assertThat(consume("user-x", other.handoffId())).isPresent();
    assertThat(consume("user-x", flood.get(0).handoffId())).isEmpty();
    assertThat(consume("user-x", flood.get(1).handoffId())).isEmpty();
    assertThat(consume("user-x", flood.get(4).handoffId())).isPresent();
    assertThat(
            redisTemplate
                .opsForList()
                .size(HandoffStagingService.DRAFT_INDEX_PREFIX + "versekit:user-x"))
        .isEqualTo(3L);
  }

  /** A client's staged mass change never replaces another client's pending one. */
  @Test
  void shouldKeepOneMassChangePerClientApartFromOtherClients() {
    HandoffStagingService.Staged mine =
        service.stageMassChange("versekit", "user-two", "{\"resource\":\"stock\"}", 4096);
    HandoffStagingService.Staged theirs =
        service.stageMassChange("other-app", "user-two", "{\"resource\":\"ships\"}", 4096);

    assertThat(consume("user-two", mine.handoffId())).isPresent();
    assertThat(consume("user-two", theirs.handoffId())).isPresent();
    assertThat(
            redisTemplate
                .opsForList()
                .size(HandoffStagingService.MASS_CHANGE_INDEX_PREFIX + "versekit:user-two"))
        .isEqualTo(1L);
  }

  /** A staged mass change above its own cap is refused. */
  @Test
  void shouldRefuseAMassChangeAboveItsCap() {
    String oversized = "{\"pad\":\"" + "x".repeat(4096) + "\"}";

    assertThatThrownBy(() -> service.stageMassChange("versekit", "user-big", oversized, 1024))
        .isInstanceOf(de.greluc.krt.profit.basetool.ingest.problem.BadRequestException.class);
  }

  /** A draft above the staging budget is refused rather than parked in the shared Redis. */
  @Test
  void shouldRefuseADraftAboveTheStagingBudget() {
    HandoffStagingService service = service(TestProperties.ingest("max-handoff-bytes", "1024"));
    String oversized = "{\"pad\":\"" + "x".repeat(4096) + "\"}";

    assertThatThrownBy(
            () -> service.stageDraft(CLIENT, "user-big", HandoffKind.BLUEPRINT, oversized, 10))
        .isInstanceOf(de.greluc.krt.profit.basetool.ingest.problem.BadRequestException.class);
  }

  /** The cap is per member, so one member's flood cannot evict another member's draft. */
  @Test
  void shouldNotEvictAnotherSubjectsHandoff() {
    String mine =
        service.stageDraft(CLIENT, "user-a", HandoffKind.REFINERY, "{\"n\":1}", 2).handoffId();
    for (int i = 0; i < 5; i++) {
      service.stageDraft(CLIENT, "user-b", HandoffKind.REFINERY, "{\"n\":" + i + "}", 2);
    }

    assertThat(consume("user-a", mine)).isPresent();
  }
}
