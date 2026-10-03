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

package de.greluc.krt.profit.basetool.testsupport.exchange;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The identifiers the ingest gateway, the backend and the frontend share on the frozen exchange
 * relay seam (REQ-XCH-037, D-05).
 *
 * <p>Each module's parity test asserts its own declarations against these values, so a rename on
 * one side alone fails that side's build. A deliberate change of the seam edits this class and
 * every side in one pull request.
 */
public final class ExchangeSeam {

  /** The path prefix of the exchange on the ingest gateway. */
  public static final String GATEWAY_PREFIX = "/exchange/v1";

  /** The path prefix of the backend surface the gateway relays to. */
  public static final String RELAY_PREFIX = "/api/v1/exchange";

  /** Stands for a route any granted exchange capability admits. */
  public static final String ANY_CAPABILITY = "*";

  /** The base capability every client holds. */
  public static final String CONNECT = "exchange.connect";

  /**
   * The 14 relay operations on 13 backend paths, each with the gateway route that relays it, the
   * capability both sides demand and the published schemas of its body and answer.
   */
  public static final @Unmodifiable List<RelayOperation> OPERATIONS =
      List.of(
          new RelayOperation(
              "GET",
              "",
              "/me/installation",
              CONNECT,
              false,
              null,
              "service-document.schema.json#/properties/installationId",
              "installationId"),
          new RelayOperation(
              "POST",
              "/me/installation",
              "/me/installation",
              CONNECT,
              false,
              "installation.schema.json",
              "installation.schema.json"),
          new RelayOperation(
              "POST",
              "/me/account-check",
              "/me/account-check",
              CONNECT,
              false,
              "account-check-request.schema.json",
              "account-check-response.schema.json"),
          new RelayOperation(
              "POST",
              "/catalog/resolve",
              "/catalog/resolve",
              ANY_CAPABILITY,
              false,
              "resolve-request.schema.json",
              "resolve-response.schema.json"),
          new RelayOperation(
              "GET",
              "/catalog/locations",
              "/catalog/locations",
              ANY_CAPABILITY,
              false,
              null,
              "location-list.schema.json"),
          new RelayOperation(
              "GET",
              "/me/blueprints",
              "/me/blueprints",
              "exchange.blueprints.read",
              true,
              null,
              "page.schema.json#/$defs/blueprintPage"),
          new RelayOperation(
              "POST",
              "/me/blueprints/changes",
              "/me/blueprints/changes",
              "exchange.blueprints.write",
              false,
              "change-set.schema.json#/$defs/blueprintChangeSet",
              "change-result.schema.json"),
          new RelayOperation(
              "GET",
              "/me/stock",
              "/me/stock",
              "exchange.stock.read",
              true,
              null,
              "page.schema.json#/$defs/stockPage"),
          new RelayOperation(
              "POST",
              "/me/stock/changes",
              "/me/stock/changes",
              "exchange.stock.write",
              false,
              "change-set.schema.json#/$defs/stockChangeSet",
              "change-result.schema.json"),
          new RelayOperation(
              "GET",
              "/me/ships",
              "/me/ships",
              "exchange.hangar.read",
              true,
              null,
              "page.schema.json#/$defs/shipPage"),
          new RelayOperation(
              "POST",
              "/me/ships/changes",
              "/me/ships/changes",
              "exchange.hangar.write",
              false,
              "change-set.schema.json#/$defs/shipChangeSet",
              "change-result.schema.json"),
          new RelayOperation(
              "GET",
              "/me/org-demand",
              "/me/org-demand",
              "exchange.demand.read",
              false,
              null,
              "org-demand.schema.json"),
          new RelayOperation(
              "POST",
              "/me/drafts/blueprints",
              "/me/drafts/blueprints",
              "exchange.drafts.blueprints",
              false,
              "blueprint-draft.schema.json",
              null),
          new RelayOperation(
              "POST",
              "/me/drafts/refinery-orders",
              "/me/drafts/refinery-orders",
              "exchange.drafts.refinery",
              false,
              "refinery-draft.schema.json",
              null));

  /** The query parameters a paged relay operation passes on. */
  public static final @Unmodifiable List<String> PAGE_PARAMETERS = List.of("cursor", "limit");

  /** The relay header naming the member the gateway acts for. */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /** The relay header naming the external client. */
  public static final String CLIENT_HEADER = "X-Exchange-Client";

  /** The relay header listing the relayed capabilities, comma-separated. */
  public static final String CAPABILITIES_HEADER = "X-Exchange-Capabilities";

  /** The relay header carrying the installation's DPoP key thumbprint. */
  public static final String INSTALLATION_HEADER = "X-Exchange-Installation";

  /** The relay header carrying the connection time in epoch seconds. */
  public static final String CONNECTED_AT_HEADER = "X-Exchange-Connected-At";

  /** The five relay headers, in the order above. */
  public static final @Unmodifiable List<String> RELAY_HEADERS =
      List.of(
          ON_BEHALF_OF_HEADER,
          CLIENT_HEADER,
          CAPABILITIES_HEADER,
          INSTALLATION_HEADER,
          CONNECTED_AT_HEADER);

  /** The ten capability scopes. */
  public static final @Unmodifiable Set<String> CAPABILITY_SCOPES =
      Set.of(
          CONNECT,
          "exchange.blueprints.read",
          "exchange.blueprints.write",
          "exchange.stock.read",
          "exchange.stock.write",
          "exchange.hangar.read",
          "exchange.hangar.write",
          "exchange.demand.read",
          "exchange.drafts.blueprints",
          "exchange.drafts.refinery");

  /**
   * The registry gate's codes, each with the only status either side answers it with (REQ-XCH-025).
   */
  public static final @Unmodifiable Map<String, Integer> GATE_STATUSES =
      Map.of(
          "EXCHANGE_DISABLED", 503,
          "REGISTRY_UNAVAILABLE", 503,
          "CLIENT_NOT_ALLOWED", 403,
          "CLIENT_SUSPENDED", 403,
          "INSTALLATION_REVOKED", 401,
          "CLIENT_REVOKED", 401,
          "SCOPE_MISSING", 403);

  /** The codes outside the gate a backend refusal reaches a client with, as they are. */
  public static final @Unmodifiable Set<String> PASSED_THROUGH_CODES =
      Set.of(
          "TERMS_NOT_ACCEPTED",
          "PENDING_APPROVAL",
          "NO_ROLE",
          "ACTING_MEMBER_REFUSED",
          "NOT_PERMITTED",
          "SCHEMA_INVALID",
          "VERSION_CONFLICT",
          "CURSOR_EXPIRED",
          "MASS_CHANGE_CONFIRMATION_REQUIRED");

  /** The backend-wide codes the gateway translates into a code of the exchange registry. */
  public static final @Unmodifiable Map<String, String> TRANSLATED_CODES =
      Map.of(
          "ACCESS_DENIED", "NOT_PERMITTED",
          "VALIDATION_FAILED", "SCHEMA_INVALID",
          "BAD_REQUEST", "SCHEMA_INVALID",
          "OPTIMISTIC_LOCK", "VERSION_CONFLICT");

  /** The Redis key the backend writes the registry mirror under by default. */
  public static final String REGISTRY_KEY = "exchange:registry";

  /** The registry mirror document's format version. */
  public static final int REGISTRY_SCHEMA_VERSION = 1;

  /** The registry status that admits a client. */
  public static final String REGISTRY_STATUS_ACTIVE = "ACTIVE";

  /** The registry mirror document's top-level members. */
  public static final @Unmodifiable List<String> REGISTRY_DOCUMENT_FIELDS =
      List.of("schemaVersion", "revision", "writtenAt", "enabled", "clients");

  /** The members of one client in the registry mirror document. */
  public static final @Unmodifiable List<String> REGISTRY_CLIENT_FIELDS =
      List.of(
          "displayName",
          "status",
          "capabilities",
          "minClientVersion",
          "requestsPerMinute",
          "writesPerDay");

  /**
   * The registry mirror document the backend writes for {@link #REGISTRY_SAMPLE_REVISION}, {@link
   * #REGISTRY_SAMPLE_WRITTEN_AT}, the switch on and the two clients it lists, and the gateway reads
   * in full.
   */
  public static final String REGISTRY_SAMPLE =
      """
      {"schemaVersion":1,"revision":42,"writtenAt":"2026-10-01T12:00:00Z","enabled":true,\
      "clients":{\
      "sx-tool":{"displayName":"SC Extractor","status":"SUSPENDED",\
      "capabilities":["exchange.connect"],"minClientVersion":null,\
      "requestsPerMinute":null,"writesPerDay":null},\
      "versekit":{"displayName":"VerseKit","status":"ACTIVE",\
      "capabilities":["exchange.connect","exchange.stock.read"],"minClientVersion":"2.0.0",\
      "requestsPerMinute":120,"writesPerDay":500}}}\
      """;

  /** The revision {@link #REGISTRY_SAMPLE} carries. */
  public static final long REGISTRY_SAMPLE_REVISION = 42L;

  /** The write time {@link #REGISTRY_SAMPLE} carries. */
  public static final String REGISTRY_SAMPLE_WRITTEN_AT = "2026-10-01T12:00:00Z";

  /** The Redis key prefix of an installation the member disconnected. */
  public static final String DENY_PREFIX = "exchange:deny:";

  /** The Redis key prefix of a member's whole-client revocation. */
  public static final String REVOKED_PREFIX = "exchange:revoked:";

  /** The Redis key prefix of a staged browser handoff. */
  public static final String HANDOFF_PREFIX = "ingest:handoff:";

  /** The members of a staged handoff's value. */
  public static final @Unmodifiable List<String> HANDOFF_FIELDS = List.of("kind", "draftJson");

  /** The kinds of a staged handoff. */
  public static final @Unmodifiable List<String> HANDOFF_KINDS =
      List.of("REFINERY", "BLUEPRINT", "MASS_CHANGE");

  /** The members of a staged mass change the confirmation page reads. */
  public static final @Unmodifiable Set<String> MASS_CHANGE_FIELDS =
      Set.of("clientId", "installationKey", "resource", "stagedAt", "changeSet");

  /** Not instantiable. */
  private ExchangeSeam() {}

  /**
   * Returns the Redis key of a disconnected installation.
   *
   * @param keyThumbprint the installation's DPoP key thumbprint
   * @return {@code exchange:deny:<thumbprint>}
   */
  public static @NotNull String denyKey(@NotNull String keyThumbprint) {
    return DENY_PREFIX + keyThumbprint;
  }

  /**
   * Returns the Redis key of a member's whole-client revocation.
   *
   * @param clientId the registry client id
   * @param member the member's subject
   * @return {@code exchange:revoked:<clientId>:<member>}
   */
  public static @NotNull String revokedKey(@NotNull String clientId, @NotNull String member) {
    return REVOKED_PREFIX + clientId + ":" + member;
  }

  /**
   * Returns the Redis key of a staged handoff.
   *
   * @param member the member's subject
   * @param handoffId the handoff id
   * @return {@code ingest:handoff:<member>:<handoffId>}
   */
  public static @NotNull String handoffKey(@NotNull String member, @NotNull String handoffId) {
    return HANDOFF_PREFIX + member + ":" + handoffId;
  }

  /**
   * Returns every relay operation as {@code METHOD /api/v1/exchange/...}.
   *
   * @return the 14 operations, sorted
   */
  public static @NotNull @Unmodifiable Set<String> relayOperations() {
    Set<String> operations = new TreeSet<>();
    OPERATIONS.forEach(operation -> operations.add(operation.relayed()));
    return Set.copyOf(operations);
  }

  /**
   * Returns every backend path the relay targets.
   *
   * @return the 13 paths, sorted
   */
  public static @NotNull @Unmodifiable Set<String> relayPaths() {
    Set<String> paths = new TreeSet<>();
    OPERATIONS.forEach(operation -> paths.add(RELAY_PREFIX + operation.relayPath()));
    return Set.copyOf(paths);
  }

  /**
   * One operation of the relay seam.
   *
   * @param method the HTTP method on both sides
   * @param gatewayPath the gateway route below {@link #GATEWAY_PREFIX} that relays it, empty for
   *     the service document
   * @param relayPath the backend path below {@link #RELAY_PREFIX}
   * @param capability the capability both sides demand, or {@link #ANY_CAPABILITY}
   * @param paged whether the relay passes {@link #PAGE_PARAMETERS} on
   * @param requestSchema the published schema of the body, or {@code null} for none
   * @param responseSchema the published schema of the backend's answer, or of its {@code
   *     answerMember}; {@code null} when the gateway stages the answer instead of relaying it
   * @param answerMember the one member of the answer the gateway takes over, or {@code null} when
   *     it relays the whole answer
   */
  public record RelayOperation(
      @NotNull String method,
      @NotNull String gatewayPath,
      @NotNull String relayPath,
      @NotNull String capability,
      boolean paged,
      @Nullable String requestSchema,
      @Nullable String responseSchema,
      @Nullable String answerMember) {

    /**
     * Creates an operation whose whole answer the gateway relays.
     *
     * @param method the HTTP method on both sides
     * @param gatewayPath the gateway route below {@link #GATEWAY_PREFIX}
     * @param relayPath the backend path below {@link #RELAY_PREFIX}
     * @param capability the capability both sides demand, or {@link #ANY_CAPABILITY}
     * @param paged whether the relay passes {@link #PAGE_PARAMETERS} on
     * @param requestSchema the published schema of the body, or {@code null} for none
     * @param responseSchema the published schema of the answer, or {@code null} when it is staged
     */
    public RelayOperation(
        @NotNull String method,
        @NotNull String gatewayPath,
        @NotNull String relayPath,
        @NotNull String capability,
        boolean paged,
        @Nullable String requestSchema,
        @Nullable String responseSchema) {
      this(method, gatewayPath, relayPath, capability, paged, requestSchema, responseSchema, null);
    }

    /**
     * Renders the backend side as {@code GET /api/v1/exchange/me/stock}.
     *
     * @return the method and the full backend path
     */
    public @NotNull String relayed() {
      return method + " " + RELAY_PREFIX + relayPath;
    }

    /**
     * Renders the gateway side as {@code GET /exchange/v1/me/stock}.
     *
     * @return the method and the full gateway path
     */
    public @NotNull String gateway() {
      return method + " " + GATEWAY_PREFIX + gatewayPath;
    }
  }
}
