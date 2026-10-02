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

package de.greluc.krt.profit.basetool.ingest.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeGatewayProperties;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.model.dto.StagedHandoff;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.web.ExchangeController;
import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins the gateway half of the frozen exchange relay seam to {@link ExchangeSeam} (REQ-XCH-037):
 * the relay headers, the gate codes with their statuses, the codes passed through or translated,
 * every route with the capability it demands, the relay prefix, the registry mirror document the
 * reader parses field by field, and the revocation and handoff keys. The backend and the frontend
 * pin their halves to the same class.
 */
class ExchangeRelaySeamParityTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * Members of the registry mirror document the gateway deliberately does not read, each with the
   * reason; every other member must change what the reader returns when it is renamed.
   */
  private static final Map<String, String> UNREAD_REGISTRY_FIELDS =
      Map.of("writtenAt", "informational; the gateway ages the mirror by its own last read");

  @Test
  void theRelaySendsTheSeamsHeaders() {
    assertRelayHeaders(ExchangeSeam.RELAY_HEADERS);
    List<String> renamed = new ArrayList<>(ExchangeSeam.RELAY_HEADERS);
    renamed.set(0, "X-Ingest-OnBehalfOf");
    assertThatThrownBy(() -> assertRelayHeaders(renamed)).isInstanceOf(AssertionError.class);
  }

  @Test
  void theRelayAdmitsTheSeamsGateCodesWithTheirStatuses() {
    assertGateStatuses(ExchangeSeam.GATE_STATUSES);
    Map<String, Integer> otherStatus = new HashMap<>(ExchangeSeam.GATE_STATUSES);
    otherStatus.put("SCOPE_MISSING", 401);
    assertThatThrownBy(() -> assertGateStatuses(otherStatus)).isInstanceOf(AssertionError.class);
  }

  @Test
  void theRelayPassesAndTranslatesTheSeamsCodes() {
    assertRelayedCodes(ExchangeSeam.PASSED_THROUGH_CODES, ExchangeSeam.TRANSLATED_CODES);
    Map<String, String> otherTranslation = new HashMap<>(ExchangeSeam.TRANSLATED_CODES);
    otherTranslation.put("OPTIMISTIC_LOCKING", otherTranslation.remove("OPTIMISTIC_LOCK"));
    assertThatThrownBy(
            () -> assertRelayedCodes(ExchangeSeam.PASSED_THROUGH_CODES, otherTranslation))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void everyRouteDemandsTheSeamsCapability() {
    assertRoutes(gatewayRoutes());
    Map<String, String> otherCapability = gatewayRoutes();
    otherCapability.put("POST /exchange/v1/catalog/resolve", "exchange.connect");
    assertThatThrownBy(() -> assertRoutes(otherCapability)).isInstanceOf(AssertionError.class);
  }

  @Test
  void theControllerRelaysBelowTheSeamsPrefix() throws Exception {
    Field backend = ExchangeController.class.getDeclaredField("BACKEND");
    backend.setAccessible(true);
    assertThat(backend.get(null)).isEqualTo(ExchangeSeam.RELAY_PREFIX);
  }

  @Test
  void theReaderParsesTheSeamsRegistrySampleCompletely() {
    assertThat(ExchangeRegistryReader.parse(MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE)))
        .isEqualTo(expectedRegistry());
    assertThat(ExchangeRegistryReader.SCHEMA_VERSION)
        .isEqualTo(ExchangeSeam.REGISTRY_SCHEMA_VERSION);
    assertThat(ExchangeRegistryReader.STATUS_ACTIVE).isEqualTo(ExchangeSeam.REGISTRY_STATUS_ACTIVE);
  }

  @Test
  void aRenamedRegistryFieldChangesWhatTheReaderReturns() {
    List<String> read = new ArrayList<>();
    for (String field : ExchangeSeam.REGISTRY_DOCUMENT_FIELDS) {
      if (UNREAD_REGISTRY_FIELDS.containsKey(field)) {
        continue;
      }
      ObjectNode renamed = (ObjectNode) MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE);
      renamed.set("x" + field, renamed.remove(field));
      assertReadDiffers(renamed, field);
      read.add(field);
    }
    for (String field : ExchangeSeam.REGISTRY_CLIENT_FIELDS) {
      ObjectNode renamed = (ObjectNode) MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE);
      ObjectNode client = (ObjectNode) renamed.path("clients").path("versekit");
      client.set("x" + field, client.remove(field));
      assertReadDiffers(renamed, "clients.versekit." + field);
      read.add(field);
    }
    assertThat(read).hasSize(10);
    assertThat(UNREAD_REGISTRY_FIELDS.keySet()).isSubsetOf(ExchangeSeam.REGISTRY_DOCUMENT_FIELDS);
  }

  @Test
  void theFieldCheckCatchesAReaderThatIgnoresAField() {
    ObjectNode untouched = (ObjectNode) MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE);
    untouched.put("unrelated", 1);
    assertThatThrownBy(() -> assertReadDiffers(untouched, "unrelated"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void theRegistryKeyDefaultIsTheSeams() throws Exception {
    DefaultValue key =
        ExchangeGatewayProperties.class
            .getDeclaredConstructor(String.class, Duration.class, String.class)
            .getParameters()[0]
            .getAnnotation(DefaultValue.class);
    assertThat(key.value()).containsExactly(ExchangeSeam.REGISTRY_KEY);
  }

  @Test
  void theRevocationReaderLooksUpTheSeamsKeys() {
    assertRevocationKeys(
        ExchangeSeam.denyKey("thumb"), ExchangeSeam.revokedKey("versekit", "member-1"));
    assertThatThrownBy(
            () -> assertRevocationKeys("exchange:deny:thumb", "exchange:revoked:member-1:versekit"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void theHandoffKeyValueAndKindsAreTheSeams() {
    assertThat(HandoffStagingService.KEY_PREFIX).isEqualTo(ExchangeSeam.HANDOFF_PREFIX);
    assertThat(
            Arrays.stream(StagedHandoff.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactlyElementsOf(ExchangeSeam.HANDOFF_FIELDS);
    assertThat(Arrays.stream(HandoffKind.values()).map(Enum::name))
        .containsExactlyElementsOf(ExchangeSeam.HANDOFF_KINDS);
  }

  /**
   * Asserts that the relay names the given headers.
   *
   * @param expected the on-behalf-of, client, capabilities, installation and connected-at headers
   */
  private static void assertRelayHeaders(@NotNull List<String> expected) {
    assertThat(
            List.of(
                ExchangeRelay.ON_BEHALF_OF_HEADER,
                ExchangeRelay.CLIENT_HEADER,
                ExchangeRelay.CAPABILITIES_HEADER,
                ExchangeRelay.INSTALLATION_HEADER,
                ExchangeRelay.CONNECTED_AT_HEADER))
        .isEqualTo(expected);
  }

  /**
   * Asserts that the relay admits a gate code only with the given status.
   *
   * @param expected each gate code to its status
   */
  private static void assertGateStatuses(@NotNull Map<String, Integer> expected) {
    assertThat(new TreeMap<>(ExchangeRelay.GATE_STATUSES))
        .hasSize(7)
        .isEqualTo(new TreeMap<>(expected));
    Set<String> gateConstants =
        Set.of(
            ExchangeRefusals.EXCHANGE_DISABLED,
            ExchangeRefusals.REGISTRY_UNAVAILABLE,
            ExchangeRefusals.CLIENT_NOT_ALLOWED,
            ExchangeRefusals.CLIENT_SUSPENDED,
            ExchangeRefusals.INSTALLATION_REVOKED,
            ExchangeRefusals.CLIENT_REVOKED,
            ExchangeRefusals.SCOPE_MISSING);
    assertThat(gateConstants).isEqualTo(expected.keySet());
  }

  /**
   * Asserts that the relay passes exactly the given codes and translates the given backend codes.
   *
   * @param passedThrough the codes outside the gate a client sees as they are
   * @param translated each backend code to the registry code it becomes
   */
  private static void assertRelayedCodes(
      @NotNull Set<String> passedThrough, @NotNull Map<String, String> translated) {
    Set<String> expected = new TreeSet<>(passedThrough);
    expected.addAll(ExchangeSeam.GATE_STATUSES.keySet());
    assertThat(new TreeSet<>(ExchangeRelay.PASSED_THROUGH)).isEqualTo(expected);
    assertThat(new TreeMap<>(ExchangeRelay.TRANSLATED)).isEqualTo(new TreeMap<>(translated));
  }

  /**
   * Returns the seam's operations as the gateway serves them.
   *
   * @return {@code METHOD /exchange/v1/...} to the demanded capability
   */
  private static @NotNull Map<String, String> gatewayRoutes() {
    Map<String, String> routes = new TreeMap<>();
    ExchangeSeam.OPERATIONS.forEach(
        operation -> routes.put(operation.gateway(), operation.capability()));
    assertThat(routes).as("one gateway route per relay operation").hasSize(14);
    return routes;
  }

  /**
   * Asserts that the gateway's route table holds exactly the given routes with their capabilities
   * and that every capability scope appears.
   *
   * @param expected {@code METHOD path} to capability, {@link ExchangeSeam#ANY_CAPABILITY} for any
   */
  private static void assertRoutes(@NotNull Map<String, String> expected) {
    assertThat(ExchangeRoutes.ANY).isEqualTo(ExchangeSeam.ANY_CAPABILITY);
    assertThat(ExchangeRoutes.CONNECT).isEqualTo(ExchangeSeam.CONNECT);
    Map<String, String> routes = new TreeMap<>();
    Set<String> scopes = new TreeSet<>();
    for (ExchangeRoutes.Route route : ExchangeRoutes.ROUTES) {
      routes.put(route.template(), route.capability());
      if (!ExchangeRoutes.ANY.equals(route.capability())) {
        scopes.add(route.capability());
      }
    }
    assertThat(routes).hasSize(14).isEqualTo(new TreeMap<>(expected));
    assertThat(scopes).isEqualTo(new TreeSet<>(ExchangeSeam.CAPABILITY_SCOPES));
  }

  /**
   * The registry the seam's sample describes.
   *
   * @return the expected parse result
   */
  private static @NotNull ExchangeRegistry expectedRegistry() {
    return new ExchangeRegistry(
        ExchangeSeam.REGISTRY_SAMPLE_REVISION,
        true,
        Map.of(
            "versekit",
            new ExchangeRegistry.Client(
                "VerseKit",
                true,
                Set.of("exchange.connect", "exchange.stock.read"),
                "2.0.0",
                120,
                500),
            "sx-tool",
            new ExchangeRegistry.Client(
                "SC Extractor", false, Set.of("exchange.connect"), null, null, null)));
  }

  /**
   * Asserts that a document with one member renamed reads differently from the sample, or not at
   * all.
   *
   * @param renamed the sample with the member renamed
   * @param what the renamed member, for the message
   */
  private static void assertReadDiffers(@NotNull ObjectNode renamed, @NotNull String what) {
    ExchangeRegistry read;
    try {
      read = ExchangeRegistryReader.parse(renamed);
    } catch (ExchangeUnavailableException refused) {
      return;
    }
    assertThat(read)
        .as("renaming %s must not fall back to what the sample reads", what)
        .isNotEqualTo(expectedRegistry());
  }

  /**
   * Asserts that the revocation reader looks up exactly the given keys.
   *
   * @param denyKey the expected key of the installation deny
   * @param revokedKey the expected key of the client revocation
   */
  private static void assertRevocationKeys(@NotNull String denyKey, @NotNull String revokedKey) {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock();
    when(template.opsForValue()).thenReturn(values);
    Set<String> read = new HashSet<>();
    when(values.get(anyString()))
        .thenAnswer(
            invocation -> {
              read.add(invocation.getArgument(0));
              return "1790000000";
            });
    ExchangeRevocationReader reader = new ExchangeRevocationReader(template);

    assertThat(reader.isDenied("thumb")).isTrue();
    assertThat(reader.revokedAt("versekit", "member-1")).isEqualTo(1_790_000_000L);
    assertThat(read).containsExactlyInAnyOrder(denyKey, revokedKey);
  }
}
