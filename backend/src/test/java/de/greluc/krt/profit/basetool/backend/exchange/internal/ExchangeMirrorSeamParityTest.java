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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the Redis documents the backend writes for the ingest gateway to {@link ExchangeSeam}
 * (REQ-XCH-037): the registry mirror document, its default key and format version, and the
 * revocation keys and values. The gateway parses the same sample, so a field the backend renames
 * fails here instead of being read by the gateway as absent.
 */
class ExchangeMirrorSeamParityTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String MEMBER = "6f1c2e7a-3b4d-4c5e-8f60-718293a4b5c6";

  @Test
  void theRegistryMirrorDocumentIsTheSeamsSample() {
    assertWrites(MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE));
  }

  @Test
  void aRenamedRegistryFieldIsCaught() {
    String renamed =
        ExchangeSeam.REGISTRY_SAMPLE.replace("\"requestsPerMinute\"", "\"requestsPerMin\"");
    assertThatThrownBy(() -> assertWrites(MAPPER.readTree(renamed)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void theSampleNamesExactlyTheSeamsFields() {
    JsonNode sample = MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE);
    assertThat(sample.propertyNames())
        .containsExactlyElementsOf(ExchangeSeam.REGISTRY_DOCUMENT_FIELDS);
    assertThat(sample.path("clients").size()).isEqualTo(2);
    for (JsonNode client : sample.path("clients")) {
      assertThat(client.propertyNames())
          .containsExactlyElementsOf(ExchangeSeam.REGISTRY_CLIENT_FIELDS);
    }
    assertThat(
            Arrays.stream(ExchangeRegistryMirrorDocument.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactlyElementsOf(ExchangeSeam.REGISTRY_DOCUMENT_FIELDS);
    assertThat(
            Arrays.stream(ExchangeRegistrySnapshot.Client.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactlyElementsOf(ExchangeSeam.REGISTRY_CLIENT_FIELDS);
  }

  @Test
  void theMirrorKeyFormatAndActiveStatusAreTheSeams() throws Exception {
    DefaultValue key =
        ExchangeMirrorProperties.class
            .getDeclaredConstructor(boolean.class, String.class, Duration.class, boolean.class)
            .getParameters()[1]
            .getAnnotation(DefaultValue.class);
    assertThat(key.value()).containsExactly(ExchangeSeam.REGISTRY_KEY);
    assertThat(ExchangeRegistryMirrorDocument.SCHEMA_VERSION)
        .isEqualTo(ExchangeSeam.REGISTRY_SCHEMA_VERSION);
    assertThat(ExchangeClientStatus.ACTIVE.name()).isEqualTo(ExchangeSeam.REGISTRY_STATUS_ACTIVE);
  }

  @Test
  void theRevocationEntriesUseTheSeamsKeysAndEpochSeconds() {
    assertRevocationKeys(ExchangeSeam.denyKey("thumb"), ExchangeSeam.revokedKey("vk", MEMBER));
    assertThatThrownBy(
            () ->
                assertRevocationKeys(
                    "exchange:denied:thumb", ExchangeSeam.revokedKey("vk", MEMBER)))
        .isInstanceOf(AssertionError.class);
  }

  /**
   * Asserts that the registry mirror writes the expected document for the seam's sample registry.
   *
   * @param expected the document the gateway reads
   */
  private static void assertWrites(@NotNull JsonNode expected) {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock();
    when(template.opsForValue()).thenReturn(values);
    Clock clock =
        Clock.fixed(Instant.parse(ExchangeSeam.REGISTRY_SAMPLE_WRITTEN_AT), ZoneOffset.UTC);
    TreeMap<String, ExchangeRegistrySnapshot.Client> clients = new TreeMap<>();
    clients.put(
        "versekit",
        new ExchangeRegistrySnapshot.Client(
            "VerseKit",
            ExchangeClientStatus.ACTIVE,
            List.of("exchange.stock.read", "exchange.connect"),
            "2.0.0",
            120,
            500));
    clients.put(
        "sx-tool",
        new ExchangeRegistrySnapshot.Client(
            "SC Extractor",
            ExchangeClientStatus.SUSPENDED,
            List.of("exchange.connect"),
            null,
            null,
            null));

    new RedisExchangeRegistryMirror(template, ExchangeSeam.REGISTRY_KEY, clock)
        .write(new ExchangeRegistrySnapshot(true, clients), ExchangeSeam.REGISTRY_SAMPLE_REVISION);

    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(values).set(eq(ExchangeSeam.REGISTRY_KEY), written.capture());
    assertThat(MAPPER.readTree(written.getValue())).isEqualTo(expected);
  }

  /**
   * Asserts that a deny and a revocation land under the given keys with the revocation's epoch
   * second as value.
   *
   * @param denyKey the expected key of the installation deny
   * @param revokedKey the expected key of the client revocation
   */
  private static void assertRevocationKeys(@NotNull String denyKey, @NotNull String revokedKey) {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock();
    when(template.opsForValue()).thenReturn(values);
    Instant revokedAt = Instant.parse("2026-10-01T12:00:00Z");
    RedisExchangeRevocationMirror mirror =
        new RedisExchangeRevocationMirror(
            template, Clock.fixed(revokedAt.plusSeconds(60), ZoneOffset.UTC));

    mirror.deny("thumb", revokedAt);
    mirror.revoke("vk", UUID.fromString(MEMBER), revokedAt);

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
    verify(values, times(2)).set(keys.capture(), written.capture(), any(Duration.class));
    assertThat(keys.getAllValues()).containsExactly(denyKey, revokedKey);
    assertThat(written.getAllValues()).containsOnly(String.valueOf(revokedAt.getEpochSecond()));

    List<String> read = new ArrayList<>();
    when(values.get(anyString()))
        .thenAnswer(
            invocation -> {
              read.add(invocation.getArgument(0));
              return null;
            });
    mirror.isDenied("thumb");
    mirror.revokedAt("vk", UUID.fromString(MEMBER));
    assertThat(read).containsExactly(denyKey, revokedKey);
  }
}
