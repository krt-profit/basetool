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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeAccountCheckDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInstallationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationListDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeShipPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockPageDto;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The backend half of the frozen exchange checked against the contract the ingest gateway enforces
 * (REQ-XCH-036): every published request fixture of a relayed route is accepted by the backend,
 * every backend answer validates against the published schema the gateway checks it with, and every
 * published answer fixture survives a round trip through the backend's answer type.
 *
 * <p>A drift here is what the gateway turns into a {@code 502 BACKEND_RELAY_FAILED} or a {@code 400
 * SCHEMA_INVALID} for a body the contract allows.
 */
@SpringBootTest
class ExchangeWireContractTest {

  private static final String BASE = "https://ingest.profit-base.online/exchange/v1/schemas/";

  private static final Path SCHEMAS = Path.of("../ingest/src/main/resources/exchange/v1/schemas");

  private static final Path FIXTURES = Path.of("../docs/exchange/examples/v1");

  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";

  private static final String KEY = "Kx9_" + "c".repeat(39);

  /**
   * Members of a published answer fixture the backend never writes, per schema folder, each with
   * the reason.
   */
  private static final Map<String, Map<String, String>> GATEWAY_ONLY_MEMBERS =
      Map.of(
          "change-result",
          Map.of(
              "warnings", "the gateway adds the UNKNOWN_FIELD warnings",
              "cursor", "reserved; not sent in v1"));

  /** The backend's answer type of every published answer schema it produces. */
  private static final Map<String, Class<?>> ANSWER_TYPES =
      Map.of(
          "installation", ExchangeInstallationDto.class,
          "account-check-response", ExchangeAccountCheckDto.class,
          "resolve-response", ExchangeResolveResponse.class,
          "location-list", ExchangeLocationListDto.class,
          "page--blueprintPage", ExchangeBlueprintPageDto.class,
          "page--stockPage", ExchangeStockPageDto.class,
          "page--shipPage", ExchangeShipPageDto.class,
          "change-result", ExchangeChangeResultDto.class,
          "org-demand", ExchangeOrgDemandDto.class);

  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder -> builder.schemas(ExchangeWireContractTest::publishedSchema));

  @Autowired private WebApplicationContext context;
  @Autowired private JsonMapper mapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> blueprints = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> cities = new ArrayList<>();
  private final List<UUID> shipTypes = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("wire-member-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
    client = "wc-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("Wire contract");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(EnumSet.allOf(ExchangeCapability.class));
    clientRepository.saveAndFlush(registered);
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
    jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", member);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM ship WHERE owner_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    blueprints.forEach(id -> jdbc.update("DELETE FROM blueprint WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    shipTypes.forEach(id -> jdbc.update("DELETE FROM ship_type WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    cities.forEach(id -> jdbc.update("DELETE FROM city WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void everyPublishedRequestFixtureIsAcceptedAndEveryAnswerMatchesItsSchema() throws Exception {
    int sent = 0;
    for (ExchangeSeam.RelayOperation operation : ExchangeSeam.OPERATIONS) {
      if (operation.requestSchema() == null) {
        continue;
      }
      String folder = folderOf(operation.requestSchema());
      List<Path> fixtures = jsonFiles(FIXTURES.resolve(folder).resolve("valid"));
      assertThat(fixtures).as("valid fixtures of " + folder).isNotEmpty();
      for (Path fixture : fixtures) {
        String name = folder + "/valid/" + fixture.getFileName();
        MockHttpServletResponse answer =
            relay(
                post(ExchangeSeam.RELAY_PREFIX + operation.relayPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(Files.readString(fixture, StandardCharsets.UTF_8)));
        assertThat(answer.getStatus())
            .as("%s %s answered %s", operation.relayed(), name, answer.getContentAsString())
            .isEqualTo(200);
        if (operation.responseSchema() != null) {
          assertMatches(operation.responseSchema(), answer.getContentAsString(), name);
        }
        sent++;
      }
    }
    assertThat(sent).as("request fixtures sent").isGreaterThanOrEqualTo(15);
  }

  @Test
  void aRefineryDraftWithAnEmptyRequiredListIsRefusedByTheBackendAsByItsSchema() throws Exception {
    for (String fixture : List.of("no-orders.json", "no-goods.json", "no-source-images.json")) {
      MockHttpServletResponse answer =
          relay(
              post(ExchangeSeam.RELAY_PREFIX + "/me/drafts/refinery-orders")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      Files.readString(
                          FIXTURES.resolve("refinery-draft/invalid/" + fixture),
                          StandardCharsets.UTF_8)));
      assertThat(answer.getStatus()).as(fixture + " " + answer.getContentAsString()).isEqualTo(400);
      assertThat(mapper.readTree(answer.getContentAsString()).path("code").asString())
          .as(fixture)
          .isEqualTo("VALIDATION_FAILED");
    }
  }

  @Test
  void everyReadAnswerMatchesItsSchemaOnSeededData() throws Exception {
    seed();
    Map<String, Integer> items = new TreeMap<>();
    for (ExchangeSeam.RelayOperation operation : ExchangeSeam.OPERATIONS) {
      if (!"GET".equals(operation.method())) {
        continue;
      }
      String path = ExchangeSeam.RELAY_PREFIX + operation.relayPath();
      if (!operation.paged()) {
        String answer = read(path);
        if (operation.answerMember() != null) {
          JsonNode member = mapper.readTree(answer).get(operation.answerMember());
          assertThat(member).as("%s carries %s", path, operation.answerMember()).isNotNull();
          answer = mapper.writeValueAsString(member);
        }
        assertMatches(operation.responseSchema(), answer, operation.relayed());
        continue;
      }
      String cursor = null;
      int pages = 0;
      int seen = 0;
      do {
        String page = read(path + "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
        assertMatches(operation.responseSchema(), page, operation.relayed());
        JsonNode node = mapper.readTree(page);
        seen += node.path("items").size();
        cursor = node.path("hasMore").asBoolean() ? node.path("nextCursor").asString() : null;
        pages++;
      } while (cursor != null && pages < 10);
      items.put(operation.relayPath(), seen);
    }
    assertThat(items)
        .as("every feed shows its seeded entry")
        .hasSize(3)
        .allSatisfy((path, seen) -> assertThat(seen).as(path).isPositive());
  }

  @Test
  void everyTombstoneMatchesItsSchema() throws Exception {
    seed();
    Map<String, String> cursors = new TreeMap<>();
    for (ExchangeSeam.RelayOperation operation : ExchangeSeam.OPERATIONS) {
      if (operation.paged()) {
        JsonNode snapshot =
            mapper.readTree(read(ExchangeSeam.RELAY_PREFIX + operation.relayPath()));
        assertThat(snapshot.path("hasMore").asBoolean()).isFalse();
        cursors.put(operation.relayPath(), snapshot.path("nextCursor").asString());
      }
    }
    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM ship WHERE owner_id = ?", member);
    Map<String, Integer> removed = new TreeMap<>();
    for (ExchangeSeam.RelayOperation operation : ExchangeSeam.OPERATIONS) {
      if (operation.paged()) {
        String feed =
            read(
                ExchangeSeam.RELAY_PREFIX
                    + operation.relayPath()
                    + "?cursor="
                    + cursors.get(operation.relayPath()));
        assertMatches(operation.responseSchema(), feed, operation.relayed() + " feed");
        removed.put(operation.relayPath(), mapper.readTree(feed).path("removed").size());
      }
    }
    assertThat(removed)
        .as("every feed reports its removed entry")
        .hasSize(3)
        .allSatisfy((path, count) -> assertThat(count).as(path).isPositive());
  }

  @Test
  void everyPublishedAnswerFixtureSurvivesTheBackendsAnswerType() throws IOException {
    int checked = 0;
    for (Map.Entry<String, Class<?>> entry : new TreeMap<>(ANSWER_TYPES).entrySet()) {
      String folder = entry.getKey();
      for (Path fixture : jsonFiles(FIXTURES.resolve(folder).resolve("valid"))) {
        String name = folder + "/valid/" + fixture.getFileName();
        JsonNode published = mapper.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
        Object answer = mapper.treeToValue(published, entry.getValue());
        String written = mapper.writeValueAsString(answer);
        assertMatches(schemaOf(folder), written, name);
        assertThat(withoutNulls(mapper.readTree(written)))
            .as(name)
            .isEqualTo(withoutNulls(withoutGatewayMembers(folder, published)));
        checked++;
      }
    }
    assertThat(checked).as("answer fixtures").isGreaterThanOrEqualTo(14);
  }

  @Test
  void theSchemaCheckRefusesAnAnswerTheGatewayWouldTurnIntoABadGateway() {
    assertThatThrownBy(
            () ->
                assertMatches(
                    "page.schema.json#/$defs/shipPage",
                    "{\"items\":[{\"shipId\":\"s\",\"shipType\":{\"bt\":\"b\",\"name\":\"n\"},"
                        + "\"insurance\":{\"kind\":\"LTI\"},\"fitted\":false}],"
                        + "\"removed\":[],\"hasMore\":false}",
                    "a ship without its version"))
        .isInstanceOf(AssertionError.class);
  }

  /** Seeds one owned blueprint, one stock lot at a UEX city and one ship for the member. */
  private void seed() {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName("Wire Rifle " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    Blueprint saved = blueprintRepository.saveAndFlush(blueprint);
    blueprints.add(saved.getId());
    String productKey = normalizer.normalize(saved.getOutputName());
    jdbc.update(
        "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        productKey,
        saved.getOutputName());

    UUID city = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO city (id, id_city, name) VALUES (?, ?, ?)", city, 90_001, "City " + city);
    cities.add(city);
    UUID location = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden, city_id) VALUES (?, ?, false, ?)",
        location,
        "Wire place " + location,
        city);
    locations.add(location);
    UUID material = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, id_commodity,"
            + " is_manual_raw_material, is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', 'SCU', NULL, false, false, true, 'UEX_ONLY')",
        material,
        "Wire material " + material);
    materials.add(material);
    jdbc.update(
        "INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,"
            + " personal, stolen, owning_org_unit_id, created_at, version)"
            + " VALUES (?, ?, ?, ?, 500, 12.5, true, false, NULL, ?, 0)",
        UUID.randomUUID(),
        member,
        material,
        location,
        Timestamp.from(Instant.now().minusSeconds(10)));

    UUID shipType = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship_type (id, name) VALUES (?, ?)", shipType, "Wire hull " + shipType);
    shipTypes.add(shipType);
    jdbc.update(
        "INSERT INTO ship (id, version, name, ship_type_id, insurance, fitted, owner_id)"
            + " VALUES (?, 0, ?, ?, 'LTI', false, ?)",
        UUID.randomUUID(),
        "Wire ship",
        shipType,
        member);
  }

  /**
   * Reads one relayed route.
   *
   * @param path the backend path and query
   * @return the {@code 200} answer's body
   * @throws Exception if the request fails
   */
  private @NotNull String read(@NotNull String path) throws Exception {
    MockHttpServletResponse answer = relay(get(path));
    assertThat(answer.getStatus())
        .as("%s answered %s", path, answer.getContentAsString())
        .isEqualTo(200);
    return answer.getContentAsString();
  }

  /**
   * Sends a request as the gateway relays it: its service identity, the acting member, the client,
   * every capability and the installation key.
   *
   * @param request the request
   * @return the backend's answer
   * @throws Exception if the request fails
   */
  private @NotNull MockHttpServletResponse relay(@NotNull MockHttpServletRequestBuilder request)
      throws Exception {
    String capabilities =
        Arrays.stream(ExchangeCapability.values())
            .map(ExchangeCapability::getScope)
            .sorted()
            .collect(Collectors.joining(","));
    return mockMvc
        .perform(
            request
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY))
        .andReturn()
        .getResponse();
  }

  /**
   * Asserts that a document validates against a published schema, as the gateway checks it.
   *
   * @param schema the schema's file name, optionally with a fragment
   * @param json the document
   * @param what the document's origin, for the message
   */
  private void assertMatches(@Nullable String schema, @NotNull String json, @NotNull String what) {
    assertThat(schema).as("schema of " + what).isNotNull();
    List<Error> errors =
        registry.getSchema(SchemaLocation.of(BASE + schema)).validate(json, InputFormat.JSON);
    assertThat(errors).as("%s against %s: %s", what, schema, json).isEmpty();
  }

  /**
   * Reads a published schema by its {@code $id}, from the gateway's committed schema folder.
   *
   * @param iri the schema's {@code $id}
   * @return its text, or {@code null} for a schema that is not one of the published ones
   */
  private static @Nullable String publishedSchema(@NotNull String iri) {
    if (!iri.startsWith(BASE)) {
      return null;
    }
    try {
      return Files.readString(
          SCHEMAS.resolve(iri.substring(BASE.length())), StandardCharsets.UTF_8);
    } catch (IOException missing) {
      return null;
    }
  }

  /**
   * Drops the members of a published answer the gateway adds or the backend never sends.
   *
   * @param folder the fixture folder
   * @param published the published answer
   * @return a copy without those members
   */
  private static @NotNull JsonNode withoutGatewayMembers(
      @NotNull String folder, @NotNull JsonNode published) {
    JsonNode copy = published.deepCopy();
    if (copy instanceof ObjectNode object) {
      GATEWAY_ONLY_MEMBERS.getOrDefault(folder, Map.of()).keySet().forEach(object::remove);
    }
    return copy;
  }

  /**
   * Drops every {@code null} member at any depth: a member the fixture omits and the backend writes
   * as {@code null} reads alike, and the written answer is checked against the schema separately.
   *
   * @param node the document
   * @return a copy without {@code null} members
   */
  private static @NotNull JsonNode withoutNulls(@NotNull JsonNode node) {
    JsonNode copy = node.deepCopy();
    strip(copy);
    return copy;
  }

  /**
   * Removes the {@code null} members of a node and its descendants in place.
   *
   * @param node the node
   */
  private static void strip(@NotNull JsonNode node) {
    if (node instanceof ObjectNode object) {
      List<String> nulls = new ArrayList<>();
      object
          .propertyNames()
          .forEach(
              name -> {
                if (object.get(name).isNull()) {
                  nulls.add(name);
                } else {
                  strip(object.get(name));
                }
              });
      nulls.forEach(object::remove);
    } else if (node.isArray()) {
      node.forEach(ExchangeWireContractTest::strip);
    }
  }

  /**
   * Maps a schema reference to its fixture folder.
   *
   * @param schema {@code name.schema.json} or {@code name.schema.json#/$defs/def}
   * @return {@code name} or {@code name--def}
   */
  private static @NotNull String folderOf(@NotNull String schema) {
    int hash = schema.indexOf('#');
    String file = (hash < 0 ? schema : schema.substring(0, hash)).replace(".schema.json", "");
    return hash < 0 ? file : file + "--" + schema.substring(schema.lastIndexOf('/') + 1);
  }

  /**
   * Maps a fixture folder to its schema reference.
   *
   * @param folder {@code name} or {@code name--def}
   * @return {@code name.schema.json} or {@code name.schema.json#/$defs/def}
   */
  private static @NotNull String schemaOf(@NotNull String folder) {
    int split = folder.indexOf("--");
    return split < 0
        ? folder + ".schema.json"
        : folder.substring(0, split) + ".schema.json#/$defs/" + folder.substring(split + 2);
  }

  /**
   * Lists the JSON fixtures in one folder.
   *
   * @param folder a {@code valid} fixture folder
   * @return the fixtures in a stable order, none when the folder does not exist
   * @throws IOException if the folder cannot be listed
   */
  private static @NotNull List<Path> jsonFiles(@NotNull Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
  }
}
