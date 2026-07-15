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

package de.greluc.krt.profit.basetool.frontend.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Seeds the minimal backend state the ephemeral-stack create-flows need, via the backend REST API
 * with a bearer token for the synthetic test user.
 *
 * <p>{@code UserService.syncUser} creates only an {@code app_user} row on first login, never an
 * {@code org_unit_membership}, so staffel-scoped creates (Mission, Ship, RefineryOrder) would
 * otherwise 400 with "user has no org-unit membership". This helper assigns the test user to the
 * seeded IRIDIUM Squadron via {@code PATCH /api/v1/users/{id}/squadron} so those flows can run.
 *
 * <p>Local-stack only: it talks to Keycloak on {@code http://localhost:18080} and the backend on
 * {@code https://localhost:11261} (self-signed dev cert — the HTTP client trusts only that cert).
 * The bearer token is minted with the Keycloak password grant on the public {@code
 * basetool-frontend} client; the backend resource server accepts it on issuer + signature alone (no
 * audience check).
 */
public final class BackendSeeder {

  private static final String KEYCLOAK_TOKEN_URL =
      "http://localhost:18080/realms/iri/protocol/openid-connect/token";
  private static final String BACKEND_BASE_URL = "https://localhost:11261";
  private static final String CLIENT_ID = "basetool-frontend";
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /** JDBC coordinates of the ephemeral backend Postgres (published on the host loopback). */
  private static final String JDBC_URL = "jdbc:postgresql://localhost:15432/krt_basetool_e2e";

  private static final String DB_USER = "basetool_e2e";
  private static final String DB_PASSWORD = "basetool-e2e-pw-do-not-use-in-prod";

  /**
   * How many times to re-read the user and retry the squadron PATCH on a 409. The per-request
   * {@code syncUser} can bump the {@code @Version} between our read and write; each retry re-reads
   * the fresh version, so a small bound converges.
   */
  private static final int MAX_VERSION_RETRIES = 4;

  private final HttpClient http;

  /** Builds a seeder whose HTTP client trusts the backend's self-signed dev certificate. */
  public BackendSeeder() {
    this.http =
        HttpClient.newBuilder()
            .sslContext(backendCertContext())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Ensures the given test user is a member of the IRIDIUM Squadron so staffel-scoped create
   * endpoints accept its requests. Idempotent: a no-op when the user already has a squadron.
   *
   * <p><b>Requires an ADMIN caller.</b> This <em>self-assigns</em> — it logs {@code username} in
   * and PATCHes that user's own membership with that user's token. The membership endpoint ({@code
   * PATCH /api/v1/users/&#123;id&#125;/memberships}) is {@code ADMIN}-gated, so calling this for a
   * non-admin who has no squadron yet 403s ("Membership seeding PATCH failed: HTTP 403"). To home a
   * non-admin fixture user, use {@link #assignStaffelMembership} with admin credentials instead.
   *
   * @param username the Keycloak username of the test user; must be an ADMIN
   * @param password the Keycloak password of the test user
   */
  public void ensureIridiumMembership(String username, String password) {
    try {
      String token = passwordGrant(username, password);
      JsonObject me = getJson("/api/v1/users/me", token);
      if (me.has("squadron") && !me.get("squadron").isJsonNull()) {
        return; // already a member — nothing to seed
      }
      String userId = me.get("id").getAsString();
      // REQ-ORG-017: assign via the membership-delta reconcile (the legacy /squadron endpoint was
      // removed). The reconcile carries no user-row version, so there is no 409 retry loop.
      int status = patchSquadron(token, userId);
      if (status < 200 || status >= 300) {
        throw new IllegalStateException("Membership seeding PATCH failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.ensureIridiumMembership failed", e);
    }
  }

  /**
   * Seeds UEX-owned catalog reference data (a refinery-hosting location, a ship type, a refining
   * method) directly into the backend Postgres over JDBC, from the {@code /uex-catalog-seed.sql}
   * classpath fixture. These rows are normally UEX-synced and cannot be created via the admin REST
   * API on a fresh DB. Idempotent (fixed UUIDs + {@code ON CONFLICT}). Run once after the ephemeral
   * stack is healthy.
   */
  public void seedCatalog() {
    try {
      String body =
          readResource("/uex-catalog-seed.sql")
              .lines()
              .filter(line -> !line.strip().startsWith("--"))
              .collect(Collectors.joining("\n"));
      try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
          Statement statement = connection.createStatement()) {
        for (String rawStatement : body.split(";")) {
          String sql = rawStatement.strip();
          if (!sql.isEmpty()) {
            statement.execute(sql);
          }
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.seedCatalog failed", e);
    }
  }

  /**
   * Reads a UTF-8 classpath resource into a string.
   *
   * @param path absolute classpath path (leading {@code /})
   * @return the resource contents
   * @throws IOException if the resource is missing or unreadable
   */
  private String readResource(String path) throws IOException {
    try (InputStream in = BackendSeeder.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException(path + " not found on the e2e classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Creates a job-order-pickable material via {@code POST /api/v1/materials} and returns its id, so
   * the job-order create form's material dropdown (filtered to {@code isJobOrder=true}) has an
   * entry to select. {@code categoryId} is optional and omitted.
   *
   * @param username the Keycloak username of the (admin) test user
   * @param password the Keycloak password of the test user
   * @param name the material name to create
   * @return the created material's id
   */
  public String ensureJobOrderMaterial(String username, String password, String name) {
    try {
      String token = passwordGrant(username, password);
      String body =
          "{\"name\":\""
              + name
              + "\",\"type\":\"RAW\",\"quantityType\":\"SCU\",\"isJobOrder\":true}";
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/materials"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Material create failed: HTTP " + response.statusCode() + " " + response.body());
      }
      return JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.ensureJobOrderMaterial failed", e);
    }
  }

  /**
   * Creates an entity via an authenticated {@code POST} and returns its id — used to seed the
   * reference data (locations, refining methods, materials) that the create-flows select from.
   *
   * @param username Keycloak username of the (admin) test user
   * @param password Keycloak password
   * @param path backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the created entity's id
   */
  public String seedEntity(String username, String password, String path, String jsonBody) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "POST " + path + " failed: HTTP " + response.statusCode() + " " + response.body());
      }
      return JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.seedEntity(" + path + ") failed", e);
    }
  }

  /**
   * Seeds a {@code PLANNED} operation via {@code POST /api/v1/operations} so the operation-detail
   * in-place write flows (#576) have a target. The backend stamps the owning org unit from the
   * actor's active scope, so only a name and status are sent.
   *
   * @param username Keycloak username of the (mission-manager-or-above) test user
   * @param password Keycloak password
   * @param name the operation name
   * @return the created operation's id
   */
  public String createOperation(String username, String password, String name) {
    return seedEntity(
        username,
        password,
        "/api/v1/operations",
        "{\"name\":\"" + name + "\",\"status\":\"PLANNED\"}");
  }

  /**
   * Creates a refinery {@code Location} the create form's location dropdown can select.
   *
   * @param username admin username
   * @param password admin password
   * @param name location name
   * @return the created location id
   */
  public String createLocation(String username, String password, String name) {
    // LocationDto has a primitive `boolean hidden`; omitting it fails Jackson deserialization.
    return seedEntity(
        username, password, "/api/v1/locations", "{\"name\":\"" + name + "\",\"hidden\":false}");
  }

  /**
   * Resolves the id of an existing {@code Location} by its (unique) name via {@code GET
   * /api/v1/locations/lookup}, so a test can anchor an inventory row at a location it did NOT
   * create itself — notably the bootstrap catalog location {@code E2E Refinery Hub}, which (unlike
   * a freshly {@link #createLocation}d one) is guaranteed to be present in the frontend's 10-minute
   * locations-lookup cache and therefore preselectable in the book-out transfer dropdown.
   *
   * @param username the Keycloak username of the (authenticated) test user
   * @param password the Keycloak password of the test user
   * @param name the exact location name to match
   * @return the matching location's id, or {@code null} if no location carries that name
   */
  public String findLocationIdByName(String username, String password, String name) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/locations/lookup"))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Locations lookup failed: HTTP " + response.statusCode());
      }
      for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
        JsonObject location = element.getAsJsonObject();
        if (location.has("name")
            && !location.get("name").isJsonNull()
            && name.equals(location.get("name").getAsString())) {
          return location.get("id").getAsString();
        }
      }
      return null;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.findLocationIdByName failed", e);
    }
  }

  /**
   * Resolves the id of an existing {@code ShipType} by its (unique) name via {@code GET
   * /api/v1/ship-types?size=1000}, so a test can seed ships of the catalog-provided {@code E2E Ship
   * Type} without hard-coding its UUID. The endpoint returns a {@code PageResponse}, so the match
   * is scanned over the {@code content} array.
   *
   * @param username the Keycloak username of the (authenticated) test user
   * @param password the Keycloak password of the test user
   * @param name the exact ship-type name to match
   * @return the matching ship-type's id, or {@code null} if none carries that name
   */
  public String findShipTypeIdByName(String username, String password, String name) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/ship-types?size=1000"))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Ship-types lookup failed: HTTP " + response.statusCode());
      }
      for (JsonElement element :
          JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("content")) {
        JsonObject shipType = element.getAsJsonObject();
        if (shipType.has("name")
            && !shipType.get("name").isJsonNull()
            && name.equals(shipType.get("name").getAsString())) {
          return shipType.get("id").getAsString();
        }
      }
      return null;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.findShipTypeIdByName failed", e);
    }
  }

  /**
   * Seeds a single ship into the test user's own hangar via {@code POST /api/v1/hangar/ships} so
   * the personal-hangar pagination test (REQ-HANGAR-002) has enough rows to span multiple pages
   * without driving the add-ship modal once per ship. The owning org unit is auto-stamped from the
   * user's single IRIDIUM membership ({@code owningOrgUnitId} omitted), and {@code fitted} is
   * always sent because the request record carries a primitive {@code boolean}.
   *
   * @param username the Keycloak username of the test user (the ship owner)
   * @param password the Keycloak password of the test user
   * @param name the ship's display name
   * @param shipTypeId the ship type's id (see {@link #findShipTypeIdByName})
   * @param insurance the insurance string ({@code 0}, {@code 1}–{@code 120} or {@code LTI})
   * @return the created ship's id
   */
  public String seedShip(
      String username, String password, String name, String shipTypeId, String insurance) {
    return seedEntity(
        username,
        password,
        "/api/v1/hangar/ships",
        "{\"name\":\""
            + name
            + "\",\"shipTypeId\":\""
            + shipTypeId
            + "\",\"insurance\":\""
            + insurance
            + "\",\"fitted\":false}");
  }

  /**
   * Creates a {@code RefiningMethod} the create form's method dropdown can select.
   *
   * @param username admin username
   * @param password admin password
   * @param name refining-method name
   * @return the created refining-method id
   */
  public String createRefiningMethod(String username, String password, String name) {
    return seedEntity(
        username, password, "/api/v1/refining-methods", "{\"name\":\"" + name + "\"}");
  }

  /**
   * Creates a RAW material flagged {@code isManualRawMaterial=true} so it appears in the refinery
   * input-material dropdown (filtered to {@code type=='RAW' or isManualRawMaterial==true}).
   *
   * @param username admin username
   * @param password admin password
   * @param name material name
   * @return the created material id
   */
  public String createRefineryMaterial(String username, String password, String name) {
    return seedEntity(
        username,
        password,
        "/api/v1/materials",
        "{\"name\":\""
            + name
            + "\",\"type\":\"RAW\",\"quantityType\":\"SCU\",\"isManualRawMaterial\":true}");
  }

  /**
   * Resolves the id of an existing material by its (unique) name via {@code GET
   * /api/v1/materials/lookup}, mirroring {@link #findLocationIdByName(String, String, String)}.
   *
   * @param username the Keycloak username of the (authenticated) test user
   * @param password the Keycloak password of the test user
   * @param name the exact material name to match
   * @return the matching material's id, or {@code null} if no material carries that name
   */
  public String findMaterialIdByName(String username, String password, String name) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/materials/lookup"))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Materials lookup failed: HTTP " + response.statusCode());
      }
      for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
        JsonObject material = element.getAsJsonObject();
        if (material.has("name")
            && !material.get("name").isJsonNull()
            && name.equals(material.get("name").getAsString())) {
          return material.get("id").getAsString();
        }
      }
      return null;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.findMaterialIdByName failed", e);
    }
  }

  /**
   * Get-or-create variant of {@link #createRefineryMaterial(String, String, String)}: returns the
   * id of the existing material with that name, creating it only when absent.
   *
   * <p>Needed because the frontend caches the materials lookup for 10 minutes: the first
   * create-page render of the suite freezes the dropdown list for every later test class, so every
   * class that drives the refinery create form must be able to pre-seed the <em>union</em> of the
   * dropdown materials idempotently — a blind {@code POST} would fail on the duplicate name when
   * the sibling class seeded the material first.
   *
   * @param username admin username
   * @param password admin password
   * @param name material name
   * @return the existing or freshly created material's id
   */
  public String ensureRefineryMaterial(String username, String password, String name) {
    String existing = findMaterialIdByName(username, password, name);
    return existing != null ? existing : createRefineryMaterial(username, password, name);
  }

  /**
   * Creates a refinery order via {@code POST /api/v1/refinery-orders} owned by the caller and
   * returns its id, so the refinery store / lifecycle / tenancy flows have a persisted order to
   * drive against without round-tripping the create UI each time. The order targets the given
   * refinery-hosting location (the catalog-seeded {@code E2E Refinery Hub}) with a single goods row
   * over the given manual RAW input material — fixed input/output quantities of {@code 100} units
   * (so the store dialog pre-fills {@code 1.00 SCU}) and quality {@code 750}. The input material
   * has no refined counterpart, so the backend stamps the output material equal to the input, which
   * is therefore also the material of every row the store flow later inserts into the Lager.
   *
   * @param username the Keycloak username of the order owner (must be an org-unit member, or the
   *     order lands ownerless)
   * @param password the Keycloak password of the order owner
   * @param locationId the id of the refinery-hosting location the order runs at
   * @param inputMaterialId the id of the manual RAW input material of the single goods row
   * @param owningOrgUnitId the R5.d owner-picker output: the OrgUnit to stamp the order onto, or
   *     {@code null} to auto-stamp the owner's single membership (or leave it ownerless)
   * @param missionId the id of a mission to link the order to, or {@code null} for no mission
   * @return the created refinery order's id
   */
  public String createRefineryOrder(
      String username,
      String password,
      String locationId,
      String inputMaterialId,
      String owningOrgUnitId,
      String missionId) {
    String missionJson = missionId == null ? "" : ",\"mission\":{\"id\":\"" + missionId + "\"}";
    String owningJson =
        owningOrgUnitId == null ? "" : ",\"owningOrgUnitId\":\"" + owningOrgUnitId + "\"";
    String body =
        "{\"location\":{\"id\":\""
            + locationId
            + "\",\"hidden\":false,\"homeLocation\":false}"
            + missionJson
            + ",\"status\":\"OPEN\",\"goods\":[{\"inputMaterial\":{\"id\":\""
            + inputMaterialId
            + "\"},\"inputQuantity\":100,\"outputQuantity\":100,\"quality\":750}]"
            + owningJson
            + "}";
    return seedEntity(username, password, "/api/v1/refinery-orders", body);
  }

  /**
   * Attempts {@code POST /api/v1/refinery-orders} and returns the HTTP status WITHOUT throwing, so
   * a test can assert the create-time OrgUnit-stamping matrix (REQ-ORG-004) and the create-time
   * validation edges: a single-membership user auto-stamps; a membershipless user yields an
   * ownerless order; a multi-membership user with no pick is rejected (400); any foreign pick is
   * rejected (400); a non-refinery location is rejected (400); and an empty goods list is rejected
   * (400). Passing a {@code null} {@code inputMaterialId} sends an empty goods array to exercise
   * the {@code @NotEmpty} constraint; a {@code null} {@code owningOrgUnitId} sends no picker
   * output.
   *
   * @param username the Keycloak username of the creating user
   * @param password the Keycloak password of the creating user
   * @param locationId the id of the location the order would run at (a non-refinery location
   *     triggers the 400)
   * @param inputMaterialId the manual RAW input material id, or {@code null} to send empty goods
   * @param owningOrgUnitId the picked owner OrgUnit id, or {@code null} for the no-pick case
   * @return the HTTP status code of the create attempt
   */
  public int attemptCreateRefineryOrderStatus(
      String username,
      String password,
      String locationId,
      String inputMaterialId,
      String owningOrgUnitId) {
    String goodsJson =
        inputMaterialId == null
            ? "[]"
            : "[{\"inputMaterial\":{\"id\":\""
                + inputMaterialId
                + "\"},\"inputQuantity\":100,\"outputQuantity\":100,\"quality\":750}]";
    String owningJson =
        owningOrgUnitId == null ? "" : ",\"owningOrgUnitId\":\"" + owningOrgUnitId + "\"";
    String body =
        "{\"location\":{\"id\":\""
            + locationId
            + "\",\"hidden\":false,\"homeLocation\":false},\"status\":\"OPEN\",\"goods\":"
            + goodsJson
            + owningJson
            + "}";
    try {
      return postStatus(passwordGrant(username, password), "/api/v1/refinery-orders", body);
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptCreateRefineryOrderStatus failed", e);
    }
  }

  /**
   * Reads the optimistic-lock {@code version} of a refinery order as the given user, so a test can
   * drive the 409 conflict (send a stale version) or the owner gate (send the current version so
   * the version check passes and the owner check fires). The caller must be allowed to see the
   * order (same org-unit scope, owner, or admin) or the backend 403s and this throws.
   *
   * @param username the Keycloak username of the (in-scope) reader
   * @param password the Keycloak password of the reader
   * @param orderId the refinery order id
   * @return the order's current {@code @Version} value
   */
  public long getRefineryOrderVersion(String username, String password, String orderId) {
    try {
      return getJson("/api/v1/refinery-orders/" + orderId, passwordGrant(username, password))
          .get("version")
          .getAsLong();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.getRefineryOrderVersion failed", e);
    }
  }

  /**
   * Attempts {@code PUT /api/v1/refinery-orders/{id}} carrying the given optimistic-lock version
   * and returns the HTTP status WITHOUT throwing, so a test can assert the update edges: a stale
   * version surfaces as 409 (the version check runs first), while a non-owner non-logistician
   * caller sending the <em>current</em> version is rejected by the service owner gate with 403. The
   * body re-sends the order's location + a single goods row (the service replaces the goods
   * wholesale) and flips the status to {@code IN_PROGRESS}.
   *
   * @param username the Keycloak username of the acting user
   * @param password the Keycloak password of the acting user
   * @param orderId the refinery order id to update
   * @param locationId the id of the refinery-hosting location to re-send
   * @param inputMaterialId the id of the manual RAW input material to re-send
   * @param version the optimistic-lock version to submit (current → passes; stale → 409)
   * @return the HTTP status code of the update attempt
   */
  public int attemptUpdateRefineryOrderStatus(
      String username,
      String password,
      String orderId,
      String locationId,
      String inputMaterialId,
      long version) {
    String body =
        "{\"id\":\""
            + orderId
            + "\",\"location\":{\"id\":\""
            + locationId
            + "\",\"hidden\":false,\"homeLocation\":false},\"status\":\"IN_PROGRESS\","
            + "\"goods\":[{\"inputMaterial\":{\"id\":\""
            + inputMaterialId
            + "\"},\"inputQuantity\":100,\"outputQuantity\":100,\"quality\":750}],\"version\":"
            + version
            + "}";
    try {
      return put(passwordGrant(username, password), "/api/v1/refinery-orders/" + orderId, body);
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptUpdateRefineryOrderStatus failed", e);
    }
  }

  /**
   * Stores (einlagert) a refinery order's output into the Lager via {@code POST
   * /api/v1/refinery-orders/{id}/store} and throws on a non-2xx status, so a test can seed an
   * already-completed order or drive the store as a chosen assignee. The single store item carries
   * the output material, the target location, the quality and the amount; an optional {@code
   * assigneeUserId} redirects the resulting {@code InventoryItem} to another member (the stored row
   * is then stamped with that assignee's owning org unit), and an optional {@code note} is
   * propagated to the inventory row (REQ-INV-001).
   *
   * @param username the Keycloak username of the acting user (owner, logistician or admin)
   * @param password the Keycloak password of the acting user
   * @param orderId the refinery order id to store
   * @param materialId the output material id to store (equals the input material for a manual RAW)
   * @param locationId the storage location id of the resulting inventory row
   * @param quality the quality of the stored material ({@code 0..1000})
   * @param amount the stored amount (overrides the order's calculated output)
   * @param assigneeUserId the user to credit the stored row to, or {@code null} for the order owner
   * @param note the note to attach to the resulting inventory row, or {@code null} for none
   */
  public void storeRefineryOrder(
      String username,
      String password,
      String orderId,
      String materialId,
      String locationId,
      int quality,
      double amount,
      String assigneeUserId,
      String note) {
    int status =
        attemptStoreRefineryOrderStatus(
            username,
            password,
            orderId,
            materialId,
            locationId,
            quality,
            amount,
            assigneeUserId,
            note);
    if (status < 200 || status >= 300) {
      throw new IllegalStateException("Refinery store failed: HTTP " + status);
    }
  }

  /**
   * Attempts {@code POST /api/v1/refinery-orders/{id}/store} and returns the HTTP status WITHOUT
   * throwing, so a test can assert the store edges: re-storing an already-{@code COMPLETED} order
   * is rejected (400), a viewer outside the order's owning OrgUnit scope is rejected by the {@code
   * canEditRefineryOrder} gate (403), and storing to a multi-membership assignee with no per-output
   * picker is rejected (400). The item shape mirrors {@link #storeRefineryOrder}.
   *
   * @param username the Keycloak username of the acting user
   * @param password the Keycloak password of the acting user
   * @param orderId the refinery order id to store
   * @param materialId the output material id to store
   * @param locationId the storage location id
   * @param quality the quality of the stored material ({@code 0..1000})
   * @param amount the stored amount
   * @param assigneeUserId the user to credit the stored row to, or {@code null} for the order owner
   * @param note the note to attach to the resulting inventory row, or {@code null} for none
   * @return the HTTP status code of the store attempt
   */
  public int attemptStoreRefineryOrderStatus(
      String username,
      String password,
      String orderId,
      String materialId,
      String locationId,
      int quality,
      double amount,
      String assigneeUserId,
      String note) {
    String userJson = assigneeUserId == null ? "" : ",\"userId\":\"" + assigneeUserId + "\"";
    String noteJson = note == null ? "" : ",\"note\":\"" + note + "\"";
    String body =
        "{\"items\":[{\"materialId\":\""
            + materialId
            + "\",\"locationId\":\""
            + locationId
            + "\",\"quality\":"
            + quality
            + ",\"amount\":"
            + amount
            + userJson
            + noteJson
            + "}]}";
    try {
      return postStatus(
          passwordGrant(username, password), "/api/v1/refinery-orders/" + orderId + "/store", body);
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptStoreRefineryOrderStatus failed", e);
    }
  }

  /**
   * Cancels (soft-deletes) a refinery order via {@code DELETE /api/v1/refinery-orders/{id}} as the
   * given user and throws on a non-2xx status, so a test can drive an order to {@code CANCELED}
   * without the detail-page cancel button. The caller must own the order or be a logistician/admin.
   *
   * @param username the Keycloak username of the acting user
   * @param password the Keycloak password of the acting user
   * @param orderId the refinery order id to cancel
   */
  public void deleteRefineryOrder(String username, String password, String orderId) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(BACKEND_BASE_URL + "/api/v1/refinery-orders/" + orderId))
              .header("Authorization", "Bearer " + token)
              .DELETE()
              .build();
      int status = http.send(request, BodyHandlers.ofString()).statusCode();
      if (status < 200 || status >= 300) {
        throw new IllegalStateException("Refinery cancel failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.deleteRefineryOrder failed", e);
    }
  }

  /**
   * Issues an authenticated {@code GET} as the given user and returns the HTTP status WITHOUT
   * throwing, so a test can assert a read gate directly — notably the strict-staffel refinery read
   * gate where a foreign-scope viewer must be rejected with 403 on {@code GET
   * /api/v1/refinery-orders/{id}}.
   *
   * @param username the Keycloak username to authenticate as
   * @param password the Keycloak password
   * @param path the backend path beginning with {@code /}
   * @return the HTTP status code of the GET
   */
  public int attemptGetStatus(String username, String password, String path) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      return http.send(request, BodyHandlers.ofString()).statusCode();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptGetStatus(" + path + ") failed", e);
    }
  }

  /**
   * Issues an authenticated {@code POST} as the given user and returns the raw response body,
   * throwing on a non-2xx status — the bank-flow counterpart of {@link #seedEntity} for endpoints
   * whose response is not a single {@code {id}} object (e.g. a grant with a composite key, or a
   * {@code BankTransactionDto}).
   *
   * @param username the Keycloak username to authenticate as
   * @param password the Keycloak password
   * @param path the backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the raw response body
   */
  public String postBody(String username, String password, String path, String jsonBody) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "POST " + path + " failed: HTTP " + response.statusCode() + " " + response.body());
      }
      return response.body();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.postBody(" + path + ") failed", e);
    }
  }

  /**
   * Issues an authenticated {@code POST} as the given user and returns only the HTTP status, so a
   * bank test can assert a stable 409 (overdraft, self-transfer, closed account) or a 403 gate
   * without the 2xx-or-throw behaviour of {@link #postBody}.
   *
   * @param username the Keycloak username to authenticate as
   * @param password the Keycloak password
   * @param path the backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the HTTP status code
   */
  public int postForStatus(String username, String password, String path, String jsonBody) {
    try {
      return postStatus(passwordGrant(username, password), path, jsonBody);
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.postForStatus(" + path + ") failed", e);
    }
  }

  /**
   * Issues an authenticated {@code PUT} as the given user and returns only the HTTP status (the
   * {@code PUT} counterpart of {@link #postForStatus}), so the role-appointment matrix can assert a
   * delegated squadron-rank {@code @PreAuthorize} verdict (2xx allowed / 403 denied) on {@code PUT
   * /api/v1/squadrons/{id}/ranks/{userId}} without the 2xx-or-throw behaviour.
   *
   * @param username the Keycloak username to authenticate as
   * @param password the Keycloak password
   * @param path the backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the HTTP status code
   */
  public int putForStatus(String username, String password, String path, String jsonBody) {
    try {
      return put(passwordGrant(username, password), path, jsonBody);
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.putForStatus(" + path + ") failed", e);
    }
  }

  /**
   * Creates a bank account as a management user via {@code POST /api/v1/bank/accounts} and returns
   * its id.
   *
   * @param username a {@code BANK_MANAGEMENT} (or admin) Keycloak username
   * @param password the password
   * @param name the account display name
   * @param type the {@code BankAccountType} name (e.g. {@code SPECIAL})
   * @return the created account id
   */
  public String createBankAccount(String username, String password, String name, String type) {
    String body = "{\"name\":\"" + name + "\",\"type\":\"" + type + "\"}";
    return JsonParser.parseString(postBody(username, password, "/api/v1/bank/accounts", body))
        .getAsJsonObject()
        .get("id")
        .getAsString();
  }

  /**
   * Registers a bank holder for the given tool user via {@code POST /api/v1/bank/holders} and
   * returns the holder id.
   *
   * @param username a {@code BANK_MANAGEMENT} (or admin) Keycloak username
   * @param password the password
   * @param userId the tool user id to register as a holder
   * @return the created holder id (the existing one when the user is already a holder)
   */
  public String registerBankHolder(String username, String password, String userId) {
    // Idempotent across e2e classes that share the stack: a user is a holder at most once
    // (V151 UNIQUE user_id). GET the registry first and reuse an existing row rather than POSTing a
    // duplicate (which a sibling class's earlier registration would 409) — and never double-POST.
    String existing = findHolderIdByUserId(username, password, userId);
    if (existing != null) {
      return existing;
    }
    String body = "{\"userId\":\"" + userId + "\"}";
    return JsonParser.parseString(postBody(username, password, "/api/v1/bank/holders", body))
        .getAsJsonObject()
        .get("id")
        .getAsString();
  }

  /**
   * Resolves the holder id registered for the given tool user from {@code GET
   * /api/v1/bank/holders}, or {@code null} when the user is not yet a holder.
   *
   * @param username a {@code BANK_MANAGEMENT} (or admin) Keycloak username
   * @param password the password
   * @param userId the tool user id whose holder row to find
   * @return the matching holder id, or {@code null}
   */
  private String findHolderIdByUserId(String username, String password, String userId) {
    var holders =
        JsonParser.parseString(getBody(username, password, "/api/v1/bank/holders"))
            .getAsJsonArray();
    for (var element : holders) {
      var holder = element.getAsJsonObject();
      if (holder.has("userId")
          && !holder.get("userId").isJsonNull()
          && userId.equals(holder.get("userId").getAsString())) {
        return holder.get("id").getAsString();
      }
    }
    return null;
  }

  /**
   * Grants a bank employee per-account capabilities via {@code POST /api/v1/bank/grants}.
   *
   * @param username a {@code BANK_MANAGEMENT} (or admin) Keycloak username
   * @param password the password
   * @param granteeUserId the employee's tool user id
   * @param accountId the account to grant on
   * @param canDeposit deposit capability
   * @param canWithdraw withdraw capability
   * @param canTransfer transfer capability
   */
  public void createBankGrant(
      String username,
      String password,
      String granteeUserId,
      String accountId,
      boolean canDeposit,
      boolean canWithdraw,
      boolean canTransfer) {
    String body =
        "{\"userId\":\""
            + granteeUserId
            + "\",\"accountId\":\""
            + accountId
            + "\",\"canDeposit\":"
            + canDeposit
            + ",\"canWithdraw\":"
            + canWithdraw
            + ",\"canTransfer\":"
            + canTransfer
            + "}";
    postBody(username, password, "/api/v1/bank/grants", body);
  }

  /**
   * Idempotently grants a user access to a bank account: creates the grant, tolerating a {@code
   * 409} when the user already holds one on that account (an org-unit account auto-grants some
   * members, so an explicit grant on top duplicates the unique {@code (user, account)} row). Either
   * outcome leaves the grantee able to act on the account, which is all a test precondition needs.
   *
   * @param username the granting caller's Keycloak username
   * @param password the granting caller's Keycloak password
   * @param granteeUserId the user id being granted access
   * @param accountId the bank account id
   * @param canDeposit whether the grantee may deposit
   * @param canWithdraw whether the grantee may withdraw
   * @param canTransfer whether the grantee may transfer
   */
  public void ensureBankGrant(
      String username,
      String password,
      String granteeUserId,
      String accountId,
      boolean canDeposit,
      boolean canWithdraw,
      boolean canTransfer) {
    String body =
        "{\"userId\":\""
            + granteeUserId
            + "\",\"accountId\":\""
            + accountId
            + "\",\"canDeposit\":"
            + canDeposit
            + ",\"canWithdraw\":"
            + canWithdraw
            + ",\"canTransfer\":"
            + canTransfer
            + "}";
    try {
      int status = postStatus(passwordGrant(username, password), "/api/v1/bank/grants", body);
      if (status != 409 && (status < 200 || status >= 300)) {
        throw new IllegalStateException("ensureBankGrant failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.ensureBankGrant failed", e);
    }
  }

  /**
   * Raises a confirm-before-post {@code DEPOSIT} booking request against the given account via
   * {@code POST /api/v1/org-units/bank/requests} and returns the created request's id. The request
   * is recorded {@code PENDING} and moves no money; a deposit may target any active account and is
   * never approval-limited (REQ-BANK-042), so any authenticated caller may raise it. Used by the
   * live-sync e2e to seed a pending request a staff decision then broadcasts to peer viewers.
   *
   * @param username the requester's Keycloak username
   * @param password the password
   * @param accountId the source (credited) account
   * @param amount the whole-aUEC amount ({@code >= 1})
   * @return the created pending request's id
   */
  public String raiseBankDepositRequest(
      String username, String password, String accountId, long amount) {
    String body =
        "{\"sourceAccountId\":\""
            + accountId
            + "\",\"type\":\"DEPOSIT\",\"amount\":"
            + amount
            + ",\"note\":\"e2e live-sync\"}";
    return JsonParser.parseString(
            postBody(username, password, "/api/v1/org-units/bank/requests", body))
        .getAsJsonObject()
        .get("id")
        .getAsString();
  }

  /**
   * Books a deposit via {@code POST /api/v1/bank/deposits}.
   *
   * @param username the booking user's Keycloak username
   * @param password the password
   * @param accountId the target account
   * @param holderId the credited holder
   * @param amount the whole-aUEC amount
   * @return the HTTP status (201 on success)
   */
  public int bankDeposit(
      String username, String password, String accountId, String holderId, long amount) {
    return postForStatus(
        username, password, "/api/v1/bank/deposits", bookingBody(accountId, holderId, amount));
  }

  /**
   * Books a withdrawal via {@code POST /api/v1/bank/withdrawals}.
   *
   * @param username the booking user's Keycloak username
   * @param password the password
   * @param accountId the source account
   * @param holderId the debited holder
   * @param amount the whole-aUEC amount
   * @return the HTTP status (201 on success, 409 on overdraft)
   */
  public int bankWithdraw(
      String username, String password, String accountId, String holderId, long amount) {
    // REQ-BANK-045: the e2e booking accounts are SPECIAL (justification-mandating), so a withdrawal
    // must carry a non-blank Begründung.
    String body =
        "{\"accountId\":\""
            + accountId
            + "\",\"holderId\":\""
            + holderId
            + "\",\"amount\":"
            + amount
            + ",\"justification\":\"E2E withdrawal reason\"}";
    return postForStatus(username, password, "/api/v1/bank/withdrawals", body);
  }

  /**
   * Builds a deposit/withdrawal JSON body.
   *
   * @param accountId the account
   * @param holderId the holder
   * @param amount the amount
   * @return the JSON request body
   */
  private static String bookingBody(String accountId, String holderId, long amount) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"holderId\":\""
        + holderId
        + "\",\"amount\":"
        + amount
        + "}";
  }

  /**
   * Get-or-creates the single {@code ORG_UNIT} bank account owned by the given org unit via {@code
   * POST /api/v1/bank/accounts} (epic #666 F1) and returns its id. Idempotent across the shared
   * ephemeral stack: the V150 partial unique index permits at most one account per org unit, so an
   * existing one is reused rather than re-POSTed (which would 409).
   *
   * @param mgmtUser a {@code BANK_MANAGEMENT} (or admin) Keycloak username
   * @param mgmtPassword the password
   * @param name the account display name
   * @param orgUnitId the owning org unit's id
   * @return the existing or freshly created org-unit account's id
   */
  public String ensureOrgUnitBankAccount(
      String mgmtUser, String mgmtPassword, String name, String orgUnitId) {
    String existing = findOrgUnitBankAccountId(mgmtUser, mgmtPassword, orgUnitId);
    if (existing != null) {
      return existing;
    }
    String body =
        "{\"name\":\"" + name + "\",\"type\":\"ORG_UNIT\",\"orgUnitId\":\"" + orgUnitId + "\"}";
    return JsonParser.parseString(postBody(mgmtUser, mgmtPassword, "/api/v1/bank/accounts", body))
        .getAsJsonObject()
        .get("id")
        .getAsString();
  }

  /**
   * Resolves the id of the {@code ORG_UNIT} account owned by the given org unit from the management
   * account list ({@code GET /api/v1/bank/accounts}), or {@code null} when none exists.
   *
   * @param mgmtUser a {@code BANK_MANAGEMENT} (or admin) Keycloak username
   * @param mgmtPassword the password
   * @param orgUnitId the owning org unit's id
   * @return the matching account id, or {@code null}
   */
  private String findOrgUnitBankAccountId(String mgmtUser, String mgmtPassword, String orgUnitId) {
    JsonObject page =
        JsonParser.parseString(getBody(mgmtUser, mgmtPassword, "/api/v1/bank/accounts?size=500"))
            .getAsJsonObject();
    for (JsonElement element : page.getAsJsonArray("content")) {
      JsonObject account = element.getAsJsonObject();
      if (account.has("orgUnit") && account.get("orgUnit").isJsonObject()) {
        JsonObject orgUnit = account.getAsJsonObject("orgUnit");
        if (orgUnit.has("id") && orgUnitId.equals(orgUnit.get("id").getAsString())) {
          return account.get("id").getAsString();
        }
      }
    }
    return null;
  }

  /**
   * Reads the compute-on-read balance of a bank account via the management detail endpoint ({@code
   * GET /api/v1/bank/accounts/{id}}), as whole aUEC. Used to assert a confirmation actually moved
   * money (epic #666 F2).
   *
   * @param username a username that may see the account (management / admin / a grantee)
   * @param password the password
   * @param accountId the account id
   * @return the account balance, truncated to whole aUEC
   */
  public long bankAccountBalance(String username, String password, String accountId) {
    JsonObject detail =
        JsonParser.parseString(getBody(username, password, "/api/v1/bank/accounts/" + accountId))
            .getAsJsonObject();
    return detail.getAsJsonObject("account").get("balance").getAsBigDecimal().longValue();
  }

  /**
   * Finds the id of the caller's own {@code PENDING} booking request on the given account with the
   * given amount, from {@code GET /api/v1/org-units/bank/requests} (epic #666 F2), or {@code null}.
   * Lets a test target the exact request it just raised (distinct amounts per test method).
   *
   * @param username the requesting officer/lead's Keycloak username
   * @param password the password
   * @param accountId the target account id
   * @param amount the requested whole-aUEC amount
   * @return the matching pending request's id, or {@code null}
   */
  public String findOwnPendingBookingRequestId(
      String username, String password, String accountId, long amount) {
    for (JsonElement element : ownBookingRequests(username, password)) {
      JsonObject request = element.getAsJsonObject();
      if ("PENDING".equals(request.get("status").getAsString())
          && accountId.equals(request.get("accountId").getAsString())
          && request.get("amount").getAsBigDecimal().longValue() == amount) {
        return request.get("id").getAsString();
      }
    }
    return null;
  }

  /**
   * Reads the lifecycle status of one of the caller's own booking requests by id, from {@code GET
   * /api/v1/org-units/bank/requests} (epic #666 F2), or {@code null} when absent.
   *
   * @param username the requesting officer/lead's Keycloak username
   * @param password the password
   * @param requestId the request id
   * @return the status (PENDING / CONFIRMED / REJECTED / CANCELLED), or {@code null}
   */
  public String bookingRequestStatus(String username, String password, String requestId) {
    for (JsonElement element : ownBookingRequests(username, password)) {
      JsonObject request = element.getAsJsonObject();
      if (requestId.equals(request.get("id").getAsString())) {
        return request.get("status").getAsString();
      }
    }
    return null;
  }

  /**
   * Fetches the caller's own booking requests as a JSON array.
   *
   * @param username the requesting officer/lead's Keycloak username
   * @param password the password
   * @return the caller's requests
   */
  private JsonArray ownBookingRequests(String username, String password) {
    return JsonParser.parseString(getBody(username, password, "/api/v1/org-units/bank/requests"))
        .getAsJsonArray();
  }

  /**
   * Creates a Job Order via {@code POST /api/v1/orders} with a single material line and returns its
   * id, so the handover flow has an order to record a handover against. The material line's {@code
   * minQuality} must be at least 650 ({@code CreateJobOrderMaterialDto} constraint).
   *
   * <p>The given org unit is named as BOTH the responsible (processing) and the requesting
   * (customer) unit — these flows model a squadron that owns and fulfils its own order. The backend
   * requires a {@code responsibleOrgUnitId} that resolves to a <em>profit-eligible</em> unit; the
   * canonical IRIDIUM Squadron is opted in once during stack bootstrap ({@link E2eStackExtension}),
   * so passing it here succeeds. A squadron-responsible order is private to that squadron + admins,
   * which is exactly the ownership these flows assert.
   *
   * @param username the Keycloak username of the (admin) test user
   * @param password the Keycloak password of the test user
   * @param orgUnitId the org unit named as both the responsible (processing) and requesting
   *     (customer) unit of the order; must be a profit-eligible squadron (see bootstrap seeding)
   * @param handle the free-text contact handle of the order
   * @param materialId the id of the (job-order) material to request
   * @param minQuality the minimum acceptable quality of the requested material ({@code >= 650})
   * @param amount the requested amount of the material
   * @return the created job order's id
   */
  public String createJobOrder(
      String username,
      String password,
      String orgUnitId,
      String handle,
      String materialId,
      int minQuality,
      double amount) {
    String body =
        "{\"responsibleOrgUnitId\":\""
            + orgUnitId
            + "\",\"requestingOrgUnitId\":\""
            + orgUnitId
            + "\",\"handle\":\""
            + handle
            + "\",\"materials\":[{\"materialId\":\""
            + materialId
            + "\",\"minQuality\":"
            + minQuality
            + ",\"amount\":"
            + amount
            + "}]}";
    return seedEntity(username, password, "/api/v1/orders", body);
  }

  /**
   * Overload of {@link #createJobOrder(String, String, String, String, String, int, double)} that
   * names <em>distinct</em> responsible (processing) and requesting (customer) org units — used to
   * model an order a (possibly non-profit) ordering unit <em>placed</em> with a profit-eligible
   * processing unit, so the requesting unit's members can exercise the requesting-owner escape
   * (REQ-ORDERS-023). The responsible unit must be profit-eligible; the requesting unit may be any
   * org unit.
   *
   * @param username the Keycloak username of the (admin) test user
   * @param password the Keycloak password of the test user
   * @param responsibleOrgUnitId the responsible (processing) org unit id; must be profit-eligible
   * @param requestingOrgUnitId the requesting (customer / Auftraggeber) org unit id
   * @param handle the free-text contact handle of the order
   * @param materialId the id of the (job-order) material to request
   * @param minQuality the minimum acceptable quality of the requested material ({@code >= 650})
   * @param amount the requested amount of the material
   * @return the created job order's id
   */
  public String createJobOrder(
      String username,
      String password,
      String responsibleOrgUnitId,
      String requestingOrgUnitId,
      String handle,
      String materialId,
      int minQuality,
      double amount) {
    String body =
        "{\"responsibleOrgUnitId\":\""
            + responsibleOrgUnitId
            + "\",\"requestingOrgUnitId\":\""
            + requestingOrgUnitId
            + "\",\"handle\":\""
            + handle
            + "\",\"materials\":[{\"materialId\":\""
            + materialId
            + "\",\"minQuality\":"
            + minQuality
            + ",\"amount\":"
            + amount
            + "}]}";
    return seedEntity(username, password, "/api/v1/orders", body);
  }

  /**
   * Creates an inventory item linked to a job order via {@code POST /api/v1/inventory} and returns
   * its id, so it surfaces in the order's handover item dropdown (populated from {@code
   * findByJobOrderIdOrdered}). The item is non-personal (personal items may not carry a job-order
   * link) and stored at the given location; its quality should meet the order material's {@code
   * minQuality} to be a valid fulfillment.
   *
   * @param username the Keycloak username of the test user
   * @param password the Keycloak password of the test user
   * @param materialId the id of the material the item holds (matching the order's material)
   * @param locationId the id of the storage location of the item
   * @param jobOrderId the id of the job order to link the item to
   * @param quality the quality of the held material ({@code 0..1000})
   * @param amount the amount held (available for handover)
   * @return the created inventory item's id
   */
  public String createInventoryItemForJobOrder(
      String username,
      String password,
      String materialId,
      String locationId,
      String jobOrderId,
      int quality,
      double amount) {
    String body =
        "{\"materialId\":\""
            + materialId
            + "\",\"locationId\":\""
            + locationId
            + "\",\"quality\":"
            + quality
            + ",\"amount\":"
            + amount
            + ",\"jobOrderId\":\""
            + jobOrderId
            + "\",\"personal\":false}";
    return seedEntity(username, password, "/api/v1/inventory", body);
  }

  /**
   * Books production (Herstellung) for an item order's single line up to its full ordered amount,
   * so the manufactured amount reaches the ordered amount and the line becomes deliverable
   * (REQ-ORDERS-025). For each derived recipe material it links exactly the required stock at the
   * given location and consumes it through {@code POST
   * /api/v1/orders/{id}/items/{itemId}/production}. Since delivery is now gated by manufacture,
   * item handover flows must call this first.
   *
   * @param username the Keycloak username of the (logistician/admin) test user
   * @param password the Keycloak password of the test user
   * @param orderId the item order whose single line to fully manufacture
   * @param locationId the storage location for the linked recipe-material stock
   */
  public void manufactureItemOrderLineFully(
      String username, String password, String orderId, String locationId) {
    JsonObject order =
        JsonParser.parseString(getBody(username, password, "/api/v1/orders/" + orderId))
            .getAsJsonObject();
    JsonObject item = order.getAsJsonArray("items").get(0).getAsJsonObject();
    String itemId = item.get("id").getAsString();
    long itemVersion = item.get("version").getAsLong();
    int lineAmount = item.get("amount").getAsInt();

    StringBuilder consumption = new StringBuilder("[");
    JsonArray materials = item.getAsJsonArray("materials");
    for (JsonElement materialElement : materials) {
      JsonObject mat = materialElement.getAsJsonObject();
      String materialId = mat.getAsJsonObject("material").get("id").getAsString();
      double requiredTotal = mat.get("requiredQuantity").getAsDouble();
      if (requiredTotal <= 0) {
        continue;
      }
      String stockId =
          createInventoryItemForJobOrder(
              username, password, materialId, locationId, orderId, 1000, requiredTotal);
      long stockVersion =
          orderMaterialStockVersion(username, password, orderId, materialId, stockId);
      if (consumption.length() > 1) {
        consumption.append(',');
      }
      consumption
          .append("{\"inventoryItemId\":\"")
          .append(stockId)
          .append("\",\"materialId\":\"")
          .append(materialId)
          .append("\",\"amount\":")
          .append(requiredTotal)
          .append(",\"version\":")
          .append(stockVersion)
          .append('}');
    }
    consumption.append(']');
    String body =
        "{\"amount\":"
            + lineAmount
            + ",\"version\":"
            + itemVersion
            + ",\"consumption\":"
            + consumption
            + "}";
    postBody(
        username, password, "/api/v1/orders/" + orderId + "/items/" + itemId + "/production", body);
  }

  /**
   * Resolves the current optimistic-lock version of a just-linked inventory entry from the order's
   * per-material inventory read ({@code GET /api/v1/orders/{id}/materials/{matId}/inventory}), so
   * the production consumption can echo the correct version instead of guessing it.
   *
   * @param username the Keycloak username of the test user
   * @param password the Keycloak password of the test user
   * @param orderId the item order the inventory is linked to
   * @param materialId the recipe material the inventory holds
   * @param inventoryItemId the id of the linked inventory entry whose version is wanted
   * @return the inventory entry's current {@code version}
   * @throws IllegalStateException if the entry is not found in the order's per-material inventory
   */
  private long orderMaterialStockVersion(
      String username, String password, String orderId, String materialId, String inventoryItemId) {
    JsonArray items =
        JsonParser.parseString(
                getBody(
                    username,
                    password,
                    "/api/v1/orders/" + orderId + "/materials/" + materialId + "/inventory"))
            .getAsJsonArray();
    for (JsonElement element : items) {
      JsonObject inventory = element.getAsJsonObject();
      if (inventoryItemId.equals(inventory.get("id").getAsString())) {
        return inventory.get("version").getAsLong();
      }
    }
    throw new IllegalStateException(
        "Linked inventory " + inventoryItemId + " not found for material " + materialId);
  }

  /**
   * Creates a non-personal inventory item linked to <em>neither</em> a job order <em>nor</em> a
   * mission via {@code POST /api/v1/inventory} and returns its id — the overwhelmingly common Lager
   * case (plain squadron stock). Because the seeding user is an IRIDIUM member, create-time
   * stamping sets the item's {@code owningOrgUnit} to IRIDIUM while {@code jobOrder} and {@code
   * mission} stay {@code null}. This is exactly the row shape the group-on-read stack queries
   * ({@code findGlobalStacks} / {@code findUserStacks}) must still surface: a
   * constructor-expression projection over those nullable associations renders an implicit inner
   * join that silently drops such rows (REQ-INV-002), so this seeder backs the {@code
   * InventoryStackViewE2eTest} regression.
   *
   * @param username the Keycloak username of the test user (must be an org-unit member)
   * @param password the Keycloak password of the test user
   * @param materialId the id of the material the item holds
   * @param locationId the id of the storage location of the item
   * @param quality the quality of the held material ({@code 0..1000})
   * @param amount the amount held
   * @return the created inventory item's id
   */
  public String createInventoryItem(
      String username,
      String password,
      String materialId,
      String locationId,
      int quality,
      double amount) {
    String body =
        "{\"materialId\":\""
            + materialId
            + "\",\"locationId\":\""
            + locationId
            + "\",\"quality\":"
            + quality
            + ",\"amount\":"
            + amount
            + ",\"personal\":false}";
    return seedEntity(username, password, "/api/v1/inventory", body);
  }

  /**
   * Creates a Mission via {@code POST /api/v1/missions} as the given user and returns its id, so
   * cross-Staffel visibility flows have a mission owned by the caller's Staffel. {@code
   * owningOrgUnitId} is omitted, so the resolver auto-stamps the caller's home Staffel (the caller
   * must have exactly one membership). The planned start is set a week out to clear the
   * not-in-the-past check.
   *
   * @param username the Keycloak username of the creating user (a member of the owning Staffel)
   * @param password the Keycloak password
   * @param name the mission name (used to find its row in the list)
   * @param isInternal {@code true} for an internal (staffel-private) mission, {@code false} for a
   *     public one visible cross-Staffel
   * @return the created mission's id
   */
  public String createMission(String username, String password, String name, boolean isInternal) {
    String plannedStart = Instant.now().plus(Duration.ofDays(7)).toString();
    String body =
        "{\"name\":\""
            + name
            + "\",\"status\":\"PLANNED\",\"isInternal\":"
            + isInternal
            + ",\"plannedStartTime\":\""
            + plannedStart
            + "\"}";
    return seedEntity(username, password, "/api/v1/missions", body);
  }

  /**
   * Creates a Mission that belongs to the given operation via {@code POST /api/v1/missions}, so the
   * operation detail page's embedded missions table and finance roll-up render this mission — the
   * setup the {@code operation:{id}} {@code missions}/{@code finance} cross-publish e2e (#1241)
   * needs. Same auto-stamping and planned-start semantics as {@link #createMission(String, String,
   * String, boolean)}, with {@code operationId} added to the request.
   *
   * @param username the Keycloak username of the creating user (a member of the owning Staffel)
   * @param password the Keycloak password
   * @param name the mission name
   * @param isInternal {@code true} for an internal (staffel-private) mission, {@code false} for a
   *     public one
   * @param operationId the id of the parent operation the mission is linked to
   * @return the created mission's id
   */
  public String createMissionInOperation(
      String username, String password, String name, boolean isInternal, String operationId) {
    String plannedStart = Instant.now().plus(Duration.ofDays(7)).toString();
    String body =
        "{\"name\":\""
            + name
            + "\",\"status\":\"PLANNED\",\"isInternal\":"
            + isInternal
            + ",\"plannedStartTime\":\""
            + plannedStart
            + "\",\"operationId\":\""
            + operationId
            + "\"}";
    return seedEntity(username, password, "/api/v1/missions", body);
  }

  /**
   * Adds a guest participant to a mission via {@code POST /api/v1/missions/{id}/participants/add},
   * so the mission's finance "Neuer Eintrag" modal has a selectable entry in its {@code required}
   * participant dropdown (a finance entry must be attributed to a participant). A free-text {@code
   * guestName} with no {@code userId} takes the public self-signup path and is only ever linked to
   * a registered member when it exactly matches an existing account name — so a synthetic name that
   * no test user carries always yields a genuine guest. The endpoint answers with the full {@link
   * de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto}, whose top-level {@code id} is the
   * mission id {@link #seedEntity} extracts and returns.
   *
   * @param username the Keycloak username of the mission's creator (who can see, and thus sign up
   *     to, their own mission)
   * @param password the Keycloak password
   * @param missionId the mission to add the participant to
   * @param guestName the guest participant's display name; must not match any registered user's
   *     name
   * @return the mission id echoed back by the endpoint's {@code MissionDto} response
   */
  public String addGuestParticipant(
      String username, String password, String missionId, String guestName) {
    return seedEntity(
        username,
        password,
        "/api/v1/missions/" + missionId + "/participants/add",
        "{\"guestName\":\"" + guestName + "\"}");
  }

  /**
   * Attempts {@code POST /api/v1/orders} naming the given org unit as the responsible (processing)
   * unit and returns the HTTP status WITHOUT throwing, so a test can assert the documented 400 when
   * the named unit is not profit-eligible. Only profit-eligible squadrons / Spezialkommandos may
   * process orders (V128); a freshly created SK is not profit-eligible by default, so naming it as
   * the responsible unit is rejected with 400 ("not profit-eligible").
   *
   * @param username the Keycloak username (an admin, to create in all-squadrons scope)
   * @param password the Keycloak password
   * @param responsibleOrgUnitId the responsible (processing) OrgUnit id under test (a
   *     non-profit-eligible SK, to trigger the 400)
   * @param requestingOrgUnitId the requesting (customer) OrgUnit id
   * @param handle the order contact handle
   * @param materialId the requested material id
   * @param minQuality the minimum quality ({@code >= 650})
   * @param amount the requested amount
   * @return the HTTP status code of the create attempt
   */
  public int attemptCreateJobOrderStatus(
      String username,
      String password,
      String responsibleOrgUnitId,
      String requestingOrgUnitId,
      String handle,
      String materialId,
      int minQuality,
      double amount) {
    try {
      String token = passwordGrant(username, password);
      String body =
          "{\"responsibleOrgUnitId\":\""
              + responsibleOrgUnitId
              + "\",\"requestingOrgUnitId\":\""
              + requestingOrgUnitId
              + "\",\"handle\":\""
              + handle
              + "\",\"materials\":[{\"materialId\":\""
              + materialId
              + "\",\"minQuality\":"
              + minQuality
              + ",\"amount\":"
              + amount
              + "}]}";
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/orders"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return http.send(request, BodyHandlers.ofString()).statusCode();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptCreateJobOrderStatus failed", e);
    }
  }

  /**
   * Issues an authenticated {@code GET} as the given user and returns the raw response body, so a
   * test can assert on its contents — e.g. that a foreign-Staffel inventory item id does NOT appear
   * in this user's org-scoped Lager-View. Throws on a non-2xx status.
   *
   * @param username the Keycloak username to authenticate as
   * @param password the Keycloak password
   * @param path the backend path beginning with {@code /}
   * @return the raw response body
   */
  public String getBody(String username, String password, String path) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GET " + path + " failed: HTTP " + response.statusCode() + " " + response.body());
      }
      return response.body();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.getBody(" + path + ") failed", e);
    }
  }

  /**
   * Logs in as the given user and returns their {@code app_user} id (the JWT {@code sub}). The call
   * also triggers {@code UserService.syncUser}, so invoking it once materialises the user's row
   * before an admin assigns memberships to it.
   *
   * @param username the Keycloak username
   * @param password the Keycloak password
   * @return the user's app_user id
   */
  public String getUserId(String username, String password) {
    try {
      String token = passwordGrant(username, password);
      return getJson("/api/v1/users/me", token).get("id").getAsString();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.getUserId(" + username + ") failed", e);
    }
  }

  /**
   * Posts a {@code RefineryExtract} JSON to {@code POST /api/v1/refinery-orders/import-extract} and
   * returns the backend's draft answer verbatim — exactly what the ingest gateway forwards and then
   * stages in Redis as a handoff's {@code draftJson}. Used by the ingest-handoff e2e to reproduce a
   * staged handoff from the real backend matcher (resolving the fixture's names against the seeded
   * catalog) rather than hand-crafting draft JSON with fragile per-run ids.
   *
   * @param username the Keycloak username of the (member) test user
   * @param password the Keycloak password
   * @param extractJson the {@code RefineryExtract} document to match
   * @return the backend {@code RefineryImportDraftDto} JSON (the draft is not persisted)
   */
  public String importRefineryExtractDraft(String username, String password, String extractJson) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(BACKEND_BASE_URL + "/api/v1/refinery-orders/import-extract"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(extractJson))
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "import-extract failed: HTTP " + response.statusCode() + " " + response.body());
      }
      return response.body();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.importRefineryExtractDraft failed", e);
    }
  }

  /**
   * Creates a second {@code SQUADRON} OrgUnit via {@code POST /api/v1/squadrons} (admin-only) and
   * returns its id, so cross-Staffel flows have a Staffel B alongside the canonical IRIDIUM.
   *
   * @param adminUser an admin Keycloak username
   * @param adminPassword the admin password
   * @param name the squadron name (unique across all OrgUnits)
   * @param shorthand the squadron shorthand (unique across all OrgUnits)
   * @return the created squadron's id
   */
  public String createSquadron(
      String adminUser, String adminPassword, String name, String shorthand) {
    return seedEntity(
        adminUser,
        adminPassword,
        "/api/v1/squadrons",
        "{\"name\":\""
            + name
            + "\",\"shorthand\":\""
            + shorthand
            + "\",\"isPromotionEnabled\":true}");
  }

  /**
   * Creates a {@code BEREICH} OrgUnit (area) via {@code POST /api/v1/org-hierarchy/bereiche}
   * (admin-only) and returns its id (epic #692, REQ-ORG-014). Backs the Phase 7 org-hierarchy
   * visibility-matrix e2e. Name/shorthand are unique across all OrgUnits; department/parent are
   * omitted (the matrix wires leadership + ownership separately).
   *
   * @param adminUser an admin Keycloak username
   * @param adminPassword the admin password
   * @param name the Bereich name (unique across all OrgUnits)
   * @param shorthand the Bereich shorthand (unique across all OrgUnits)
   * @return the created Bereich's id
   */
  public String createBereich(
      String adminUser, String adminPassword, String name, String shorthand) {
    return seedEntity(
        adminUser,
        adminPassword,
        "/api/v1/org-hierarchy/bereiche",
        "{\"name\":\"" + name + "\",\"shorthand\":\"" + shorthand + "\"}");
  }

  /**
   * Grants a user a Bereichsleitung role on a Bereich via {@code POST
   * /api/v1/org-hierarchy/bereiche/{id}/members} (admin-only, epic #692 REQ-ORG-017). The user must
   * hold no Staffel membership (the leader-excludes-Staffel invariant). Backs the Phase 7
   * visibility-matrix e2e.
   *
   * @param adminUser an admin Keycloak username
   * @param adminPassword the admin password
   * @param bereichId the Bereich id
   * @param userId the user to grant the role to
   * @param role the Bereichsleitung role name: {@code LEITER}, {@code KOORDINATOR} or {@code
   *     OPERATOR}
   */
  public void addBereichLeader(
      String adminUser, String adminPassword, String bereichId, String userId, String role) {
    postBody(
        adminUser,
        adminPassword,
        "/api/v1/org-hierarchy/bereiche/" + bereichId + "/members",
        "{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}");
  }

  /**
   * Opts a squadron into (or out of) Job-Order processing by setting its {@code is_profit_eligible}
   * flag via {@code PATCH /api/v1/squadrons/{id}/profit-eligible} (admin-only, body {@code
   * {"eligible": …}}). Only profit-eligible org units may be a job order's responsible (processing)
   * unit and appear in the create form's responsible picker (V128), so this is a precondition for
   * seeding any order owned by the squadron. Throws on a non-2xx status.
   *
   * @param adminUser an admin Keycloak username (the endpoint is ADMIN-gated)
   * @param adminPassword the admin password
   * @param squadronId the squadron OrgUnit id to toggle
   * @param eligible the new {@code is_profit_eligible} value
   */
  public void setSquadronProfitEligible(
      String adminUser, String adminPassword, String squadronId, boolean eligible) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      int status =
          patch(
              token,
              "/api/v1/squadrons/" + squadronId + "/profit-eligible",
              "{\"eligible\":" + eligible + "}");
      if (status < 200 || status >= 300) {
        throw new IllegalStateException("Profit-eligibility PATCH failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.setSquadronProfitEligible failed", e);
    }
  }

  /**
   * Creates a {@code SPECIAL_COMMAND} OrgUnit (SK) via {@code POST /api/v1/special-commands}
   * (admin-only) and returns its id.
   *
   * @param adminUser an admin Keycloak username
   * @param adminPassword the admin password
   * @param name the SK name (unique across all OrgUnits)
   * @param shorthand the SK shorthand (unique across all OrgUnits)
   * @return the created SK's id
   */
  public String createSpecialCommand(
      String adminUser, String adminPassword, String name, String shorthand) {
    return seedEntity(
        adminUser,
        adminPassword,
        "/api/v1/special-commands",
        "{\"name\":\"" + name + "\",\"shorthand\":\"" + shorthand + "\"}");
  }

  /**
   * Opts a Spezialkommando into (or out of) Job-Order processing by setting its {@code
   * is_profit_eligible} flag via {@code PATCH /api/v1/special-commands/{id}/profit-eligible}
   * (admin-only, body {@code {"eligible": …}}). The SK counterpart of {@link
   * #setSquadronProfitEligible}: only profit-eligible org units may be a job order's responsible
   * (processing) unit and appear in the create form's responsible picker (V128). Throws on a
   * non-2xx status.
   *
   * @param adminUser an admin Keycloak username (the endpoint is ADMIN-gated)
   * @param adminPassword the admin password
   * @param specialCommandId the SK OrgUnit id to toggle
   * @param eligible the new {@code is_profit_eligible} value
   */
  public void setSpecialCommandProfitEligible(
      String adminUser, String adminPassword, String specialCommandId, boolean eligible) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      int status =
          patch(
              token,
              "/api/v1/special-commands/" + specialCommandId + "/profit-eligible",
              "{\"eligible\":" + eligible + "}");
      if (status < 200 || status >= 300) {
        throw new IllegalStateException("SK profit-eligibility PATCH failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.setSpecialCommandProfitEligible failed", e);
    }
  }

  /**
   * Sets a system setting's value via {@code PUT /api/v1/settings/{key}} (admin-only, body {@code
   * {value, version}}), re-reading the optimistic-lock version on a 409 like {@link
   * #ensureIridiumMembership}. Used to point {@code job_order.intake_special_command_id} at the
   * seeded intake Spezialkommando, so guest order creations — and the create form's
   * responsible-picker preselection — resolve to it. Throws once retries are exhausted.
   *
   * @param adminUser an admin Keycloak username (the endpoint is ADMIN-gated)
   * @param adminPassword the admin password
   * @param key the setting key (table primary key)
   * @param value the new setting value
   */
  public void setSystemSetting(String adminUser, String adminPassword, String key, String value) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      for (int attempt = 1; attempt <= MAX_VERSION_RETRIES; attempt++) {
        long version = getJson("/api/v1/settings/" + key, token).get("version").getAsLong();
        String body = "{\"value\":\"" + value + "\",\"version\":" + version + "}";
        int status = put(token, "/api/v1/settings/" + key, body);
        if (status >= 200 && status < 300) {
          return;
        }
        if (status != 409) {
          throw new IllegalStateException("Setting PUT failed: HTTP " + status);
        }
      }
      throw new IllegalStateException("System-setting update exhausted retries on HTTP 409");
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.setSystemSetting failed", e);
    }
  }

  /**
   * Seeds one orderable item — a {@code game_item} plus an active {@code blueprint} that outputs it
   * with a single resolved RESOURCE ingredient — directly over JDBC, so the item-order create
   * form's (frontend-cached) item picker has at least one entry. An item is "orderable" iff it is
   * the output of an active blueprint that has a RESOURCE ingredient resolved to a {@code material}
   * ({@code BlueprintRepository.findOrderableItems}); this seeds exactly that minimal shape, with
   * the ingredient pointing at the given (already-created) material. Local-stack only (JDBC to the
   * ephemeral Postgres). Returns the created game item's id.
   *
   * @param gameItemName the display name of the orderable item (shown in the picker)
   * @param materialId the id of an existing material used as the blueprint's RESOURCE ingredient
   * @return the created game item's id
   */
  public String seedOrderableItem(String gameItemName, String materialId) {
    UUID gameItemId = UUID.randomUUID();
    UUID blueprintId = UUID.randomUUID();
    UUID ingredientId = UUID.randomUUID();
    UUID scwikiUuid = UUID.randomUUID();
    try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO game_item (id, name, kind) VALUES (?, ?, 'GENERIC')"
                  + " ON CONFLICT DO NOTHING")) {
        ps.setObject(1, gameItemId);
        ps.setString(2, gameItemName);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO blueprint (id, scwiki_uuid, output_item_id, output_name,"
                  + " is_available_by_default) VALUES (?, ?, ?, ?, TRUE) ON CONFLICT DO NOTHING")) {
        ps.setObject(1, blueprintId);
        ps.setObject(2, scwikiUuid);
        ps.setObject(3, gameItemId);
        ps.setString(4, gameItemName);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO blueprint_ingredient (id, blueprint_id, order_index, kind, material_id,"
                  + " quantity_scu) VALUES (?, ?, 0, 'RESOURCE', ?, 1.0) ON CONFLICT DO NOTHING")) {
        ps.setObject(1, ingredientId);
        ps.setObject(2, blueprintId);
        ps.setObject(3, UUID.fromString(materialId));
        ps.executeUpdate();
      }
      return gameItemId.toString();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.seedOrderableItem failed", e);
    }
  }

  /**
   * Makes a material sellable by seeding a {@code terminal} plus a {@code material_price} row that
   * lists the terminal with a positive sell price, directly over JDBC. The book-out modal enables
   * its "Verkauf" (SELL) radio only when {@code GET /api/v1/materials/{id}/terminals} returns a
   * non-empty list, and that endpoint joins {@code material_price} to {@code terminal} filtering on
   * {@code statusSell = true OR priceSell > 0} (both set here). Terminal/price rows are normally
   * UEX-synced and not creatable via the admin REST API on a fresh DB, hence the direct insert —
   * mirroring {@link #seedOrderableItem}. The backend stores the chosen terminal name as a free
   * string (no FK validation on book-out), so this is purely the UI-enabling precondition.
   *
   * <p>Idempotency note: a fresh random {@code terminal_id} is used per call, so repeated calls for
   * the same material never trip the {@code material_price (material_id, terminal_id)} unique
   * constraint; {@code id_terminal} is left {@code null} (its unique index permits many nulls).
   * Local-stack only (JDBC to the ephemeral Postgres).
   *
   * @param materialId the id of the material to make sellable (a terminal offers to buy it)
   * @return the seeded terminal's display name (the value the SELL dropdown option carries)
   */
  public String seedSellableTerminal(String materialId) {
    UUID terminalId = UUID.randomUUID();
    UUID priceId = UUID.randomUUID();
    String terminalName = "E2E Sell Terminal";
    try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO terminal (id, name, hidden) VALUES (?, ?, false)"
                  + " ON CONFLICT DO NOTHING")) {
        ps.setObject(1, terminalId);
        ps.setString(2, terminalName);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO material_price (id, material_id, terminal_id, price_sell, status_sell)"
                  + " VALUES (?, ?, ?, 500, true) ON CONFLICT DO NOTHING")) {
        ps.setObject(1, priceId);
        ps.setObject(2, UUID.fromString(materialId));
        ps.setObject(3, terminalId);
        ps.executeUpdate();
      }
      return terminalName;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.seedSellableTerminal failed", e);
    }
  }

  /**
   * Adds the given user to a Spezialkommando (SK) via {@code POST
   * /api/v1/special-commands/{id}/members/{userId}} (admin-only here), creating an {@code
   * org_unit_membership} row of kind {@code SPECIAL_COMMAND} with all role flags initially false.
   * Independent of any squadron membership — a user may hold an SK membership with no squadron — so
   * this is how multi-tenancy tests build the SK-only and squadron-plus-SK viewer profiles. A 409
   * (already a member) is treated as success so the call is safe to repeat.
   *
   * @param adminUser an admin Keycloak username (the endpoint gates on ADMIN or SK-lead)
   * @param adminPassword the admin password
   * @param specialCommandId the SK OrgUnit id to add the user to
   * @param targetUserId the app_user id to add (must be materialised — see {@link #getUserId})
   */
  public void addSpecialCommandMember(
      String adminUser, String adminPassword, String specialCommandId, String targetUserId) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      BACKEND_BASE_URL
                          + "/api/v1/special-commands/"
                          + specialCommandId
                          + "/members/"
                          + targetUserId))
              .header("Authorization", "Bearer " + token)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      int status = http.send(request, BodyHandlers.ofString()).statusCode();
      if (status != 409 && (status < 200 || status >= 300)) {
        throw new IllegalStateException("SK member add failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.addSpecialCommandMember failed", e);
    }
  }

  /**
   * Removes the given user from a Spezialkommando (SK) via {@code DELETE
   * /api/v1/special-commands/{id}/members/{userId}} (admin-only here), deleting the {@code
   * org_unit_membership} row. Used by tenancy tests to model a user leaving an org unit they still
   * own inventory in: when this is <em>not</em> the user's last membership, the {@code
   * InventoryOrgUnitReconciler} leaves the {@code owning_org_unit_id} stamp untouched
   * (REQ-INV-004), so the user keeps an org-stamped inventory item without belonging to that org
   * unit — the REQ-ORG-011 owner-escape scenario.
   *
   * @param adminUser an admin Keycloak username (the endpoint gates on ADMIN or SK-lead)
   * @param adminPassword the admin password
   * @param specialCommandId the SK OrgUnit id to remove the user from
   * @param targetUserId the app_user id to remove
   */
  public void removeSpecialCommandMember(
      String adminUser, String adminPassword, String specialCommandId, String targetUserId) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      BACKEND_BASE_URL
                          + "/api/v1/special-commands/"
                          + specialCommandId
                          + "/members/"
                          + targetUserId))
              .header("Authorization", "Bearer " + token)
              .DELETE()
              .build();
      int status = http.send(request, BodyHandlers.ofString()).statusCode();
      if (status < 200 || status >= 300) {
        throw new IllegalStateException("SK member remove failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.removeSpecialCommandMember failed", e);
    }
  }

  /**
   * Creates a non-personal inventory item and explicitly stamps its owning OrgUnit via the {@code
   * owningOrgUnitId} picker field, returning its id. Needed when the creating user belongs to more
   * than one OrgUnit (squadron + SK), where the create endpoint refuses to auto-stamp and demands
   * an explicit pick; the picked OrgUnit must be one of the user's memberships or the backend 400s.
   *
   * @param username the Keycloak username of the creating user (a member of {@code
   *     owningOrgUnitId})
   * @param password the Keycloak password of the creating user
   * @param materialId the id of the material the item holds
   * @param locationId the id of the storage location of the item
   * @param quality the quality of the held material ({@code 0..1000})
   * @param amount the amount held
   * @param owningOrgUnitId the OrgUnit (squadron or SK) to stamp as the item's owner
   * @return the created inventory item's id
   */
  public String createInventoryItemOwnedBy(
      String username,
      String password,
      String materialId,
      String locationId,
      int quality,
      double amount,
      String owningOrgUnitId) {
    String body =
        "{\"materialId\":\""
            + materialId
            + "\",\"locationId\":\""
            + locationId
            + "\",\"quality\":"
            + quality
            + ",\"amount\":"
            + amount
            + ",\"personal\":false,\"owningOrgUnitId\":\""
            + owningOrgUnitId
            + "\"}";
    return seedEntity(username, password, "/api/v1/inventory", body);
  }

  /**
   * Attempts {@code POST /api/v1/inventory} and returns the HTTP status WITHOUT throwing, so a test
   * can assert the create-time OrgUnit-stamping matrix (REQ-ORG-004): a membershipless user with no
   * pick succeeds as ownerless; a single-membership user auto-stamps; a multi-membership user with
   * no pick is rejected (400, forced choice); and any foreign pick is rejected (400). A {@code
   * null} {@code owningOrgUnitId} is sent as a JSON {@code null} (the no-pick case).
   *
   * @param username the Keycloak username of the creating user
   * @param password the Keycloak password of the creating user
   * @param materialId the id of the material the item would hold
   * @param locationId the id of the storage location
   * @param quality the quality ({@code 0..1000})
   * @param amount the amount
   * @param owningOrgUnitId the picked owner OrgUnit id, or {@code null} for the no-pick case
   * @return the HTTP status code of the create attempt
   */
  public int attemptCreateInventoryStatus(
      String username,
      String password,
      String materialId,
      String locationId,
      int quality,
      double amount,
      String owningOrgUnitId) {
    try {
      String token = passwordGrant(username, password);
      String ownerJson = owningOrgUnitId == null ? "null" : "\"" + owningOrgUnitId + "\"";
      String body =
          "{\"materialId\":\""
              + materialId
              + "\",\"locationId\":\""
              + locationId
              + "\",\"quality\":"
              + quality
              + ",\"amount\":"
              + amount
              + ",\"personal\":false,\"owningOrgUnitId\":"
              + ownerJson
              + "}";
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/inventory"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return http.send(request, BodyHandlers.ofString()).statusCode();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptCreateInventoryStatus failed", e);
    }
  }

  /**
   * Attempts {@code POST /api/v1/inventory/{id}/book-out} (a plain DISCARD) and returns the HTTP
   * status WITHOUT throwing, so a test can assert the edit gate: a viewer outside the item's owning
   * OrgUnit scope is rejected with 403 ({@code canEditInventoryItem}), while an in-scope owner
   * succeeds. The body carries only the amount, the DISCARD type and the optimistic-lock version;
   * for the 403 path the {@code @PreAuthorize} gate fires before the version is even consulted.
   *
   * @param username the Keycloak username of the acting user
   * @param password the Keycloak password of the acting user
   * @param itemId the inventory item id to attempt to book out
   * @param amount the amount to discard
   * @param version the optimistic-lock version last known for the item
   * @return the HTTP status code of the book-out attempt
   */
  public int attemptBookOutStatus(
      String username, String password, String itemId, double amount, long version) {
    try {
      String token = passwordGrant(username, password);
      String body = "{\"amount\":" + amount + ",\"type\":\"DISCARD\",\"version\":" + version + "}";
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(BACKEND_BASE_URL + "/api/v1/inventory/" + itemId + "/book-out"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return http.send(request, BodyHandlers.ofString()).statusCode();
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.attemptBookOutStatus failed", e);
    }
  }

  /**
   * Issues an authenticated {@code GET} carrying the {@code X-Active-Org-Unit-Id} pin header and
   * returns the raw response body, so a test can assert the admin-pin scope behaviour
   * (REQ-ORG-008): an admin who pins one OrgUnit is scoped to that unit exactly like a member,
   * instead of seeing everything. The backend reads this header as the active pin. Throws on a
   * non-2xx status.
   *
   * @param username the Keycloak username to authenticate as
   * @param password the Keycloak password
   * @param path the backend path beginning with {@code /}
   * @param activeOrgUnitId the OrgUnit id to pin via the {@code X-Active-Org-Unit-Id} header
   * @return the raw response body
   */
  public String getBodyWithActiveOrgUnit(
      String username, String password, String path, String activeOrgUnitId) {
    try {
      String token = passwordGrant(username, password);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
              .header("Authorization", "Bearer " + token)
              .header("X-Active-Org-Unit-Id", activeOrgUnitId)
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GET "
                + path
                + " (pinned) failed: HTTP "
                + response.statusCode()
                + " "
                + response.body());
      }
      return response.body();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.getBodyWithActiveOrgUnit failed", e);
    }
  }

  /**
   * Files a Job Order as an ANONYMOUS guest (no bearer token) via {@code POST /api/v1/orders}
   * ({@code permitAll}) and returns the parsed response body, so a test can assert the guest
   * redaction's retained fields — notably {@code responsibleOrgUnit} (honoured when
   * profit-eligible, else the configured intake SK) and {@code requestingOrgUnit}. A {@code null}
   * responsibleOrgUnitId is sent as a JSON {@code null}, exercising the omitted-responsible
   * fallback. Throws on a non-2xx status.
   *
   * @param responsibleOrgUnitId the responsible (processing) OrgUnit id to request, or {@code null}
   *     to omit it (guest fallback to the intake SK)
   * @param requestingOrgUnitId the requesting (customer) OrgUnit id (mandatory)
   * @param handle the order contact handle
   * @param materialId the requested material id
   * @param minQuality the minimum quality ({@code >= 650})
   * @param amount the requested amount
   * @return the created order as a {@link JsonObject} (guest-redacted but org-unit-bearing)
   */
  public JsonObject anonymousCreateMaterialOrder(
      String responsibleOrgUnitId,
      String requestingOrgUnitId,
      String handle,
      String materialId,
      int minQuality,
      double amount) {
    String responsibleJson =
        responsibleOrgUnitId == null ? "null" : "\"" + responsibleOrgUnitId + "\"";
    String body =
        "{\"responsibleOrgUnitId\":"
            + responsibleJson
            + ",\"requestingOrgUnitId\":\""
            + requestingOrgUnitId
            + "\",\"handle\":\""
            + handle
            + "\",\"materials\":[{\"materialId\":\""
            + materialId
            + "\",\"minQuality\":"
            + minQuality
            + ",\"amount\":"
            + amount
            + "}]}";
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/orders"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Anonymous order create failed: HTTP " + response.statusCode() + " " + response.body());
      }
      return JsonParser.parseString(response.body()).getAsJsonObject();
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.anonymousCreateMaterialOrder failed", e);
    }
  }

  /**
   * Reads back the Job Order carrying the given (unique) contact handle as an admin via {@code GET
   * /api/v1/orders?size=1000&status=OPEN}, so a test can assert the responsible / requesting org
   * units that an anonymous UI submission persisted — the guest cannot read the order back itself.
   * An admin with no active org-unit pin sees the full cross-scope list, so the order is visible
   * whatever its responsible unit. Returns {@code null} when no open order carries the handle.
   *
   * @param adminUser an admin Keycloak username
   * @param adminPassword the admin password
   * @param handle the unique contact handle to match
   * @return the matching order as a {@link JsonObject}, or {@code null} if none matches
   */
  public JsonObject findOrderByHandle(String adminUser, String adminPassword, String handle) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      JsonObject page = getJson("/api/v1/orders?size=1000&status=OPEN", token);
      for (JsonElement element : page.getAsJsonArray("content")) {
        JsonObject order = element.getAsJsonObject();
        if (order.has("handle")
            && !order.get("handle").isJsonNull()
            && handle.equals(order.get("handle").getAsString())) {
          return order;
        }
      }
      return null;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.findOrderByHandle failed", e);
    }
  }

  /**
   * Assigns {@code targetUserId} to a Staffel and sets its per-squadron role flags in one
   * transaction via the membership-delta {@code PATCH /api/v1/users/{id}/memberships} (admin-only),
   * sending the desired complete Staffel set {@code {staffeln:[{squadronId, isLogistician,
   * isMissionManager}]}} (REQ-ORG-017). The reconcile adds the Staffel (re-homing the user, since
   * the desired set is authoritative) and patches the flags; it is idempotent and needs no user-row
   * version, so there is no 409 retry loop here.
   *
   * @param adminUser an admin Keycloak username
   * @param adminPassword the admin password
   * @param targetUserId the app_user id to assign (see {@link #getUserId})
   * @param squadronId the Staffel OrgUnit id
   * @param isLogistician whether to set the {@code is_logistician} flag on the Staffel membership
   * @param isMissionManager whether to set the {@code is_mission_manager} flag on the membership
   */
  public void assignStaffelMembership(
      String adminUser,
      String adminPassword,
      String targetUserId,
      String squadronId,
      boolean isLogistician,
      boolean isMissionManager) {
    try {
      String token = passwordGrant(adminUser, adminPassword);
      String body =
          "{\"staffeln\":[{\"squadronId\":\""
              + squadronId
              + "\",\"isLogistician\":"
              + isLogistician
              + ",\"isMissionManager\":"
              + isMissionManager
              + "}]}";
      int status = patch(token, "/api/v1/users/" + targetUserId + "/memberships", body);
      if (status < 200 || status >= 300) {
        throw new IllegalStateException(
            "Staffel membership assignment PATCH failed: HTTP " + status);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("BackendSeeder.assignStaffelMembership failed", e);
    }
  }

  /**
   * Issues an authenticated {@code PATCH} and returns the HTTP status, so callers can react to a
   * 409.
   *
   * @param token bearer token
   * @param path backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the HTTP status code
   * @throws Exception on transport failure
   */
  private int patch(String token, String path, String jsonBody) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    return http.send(request, BodyHandlers.ofString()).statusCode();
  }

  /**
   * Issues an authenticated {@code PUT} and returns the HTTP status, so callers can react to a 409
   * optimistic-lock conflict (the system-setting update carries its version in the body).
   *
   * @param token bearer token
   * @param path backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the HTTP status code
   * @throws Exception on transport failure
   */
  private int put(String token, String path, String jsonBody) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    return http.send(request, BodyHandlers.ofString()).statusCode();
  }

  /**
   * Issues an authenticated {@code POST} and returns the HTTP status, so the {@code attempt*}
   * create / store probes can assert a 4xx without the 2xx-or-throw behaviour of {@link
   * #seedEntity}.
   *
   * @param token bearer token
   * @param path backend path beginning with {@code /}
   * @param jsonBody the JSON request body
   * @return the HTTP status code
   * @throws Exception on transport failure
   */
  private int postStatus(String token, String path, String jsonBody) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    return http.send(request, BodyHandlers.ofString()).statusCode();
  }

  /**
   * Performs a Keycloak Resource-Owner-Password-Credentials grant on the public {@code
   * basetool-frontend} client and returns the access token.
   *
   * @param username Keycloak username
   * @param password Keycloak password
   * @return the raw JWT access token
   * @throws Exception if the token endpoint is unreachable or returns a non-200
   */
  private String passwordGrant(String username, String password) throws Exception {
    String form =
        "grant_type=password&client_id="
            + CLIENT_ID
            + "&username="
            + enc(username)
            + "&password="
            + enc(password)
            + "&scope=openid";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(KEYCLOAK_TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Keycloak password grant failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return JsonParser.parseString(response.body())
        .getAsJsonObject()
        .get("access_token")
        .getAsString();
  }

  /**
   * Issues an authenticated {@code GET} against a backend path and returns the parsed JSON object.
   *
   * @param path backend path beginning with {@code /}
   * @param token bearer token
   * @return the response body as a {@link JsonObject}
   * @throws Exception on transport failure or a non-200 status
   */
  private JsonObject getJson(String path, String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GET " + path + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  /**
   * Assigns the IRIDIUM Staffel to the user via the membership-delta reconcile ({@code PATCH
   * /api/v1/users/{id}/memberships}) and returns the HTTP status. The reconcile is declarative and
   * carries no user-row version, so there is no optimistic-lock 409 to react to.
   *
   * @param token bearer token
   * @param userId the app_user id to assign
   * @return the HTTP status code of the PATCH
   * @throws Exception on transport failure
   */
  private int patchSquadron(String token, String userId) throws Exception {
    String body = "{\"staffeln\":[{\"squadronId\":\"" + IRIDIUM_SQUADRON_ID + "\"}]}";
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(BACKEND_BASE_URL + "/api/v1/users/" + userId + "/memberships"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString()).statusCode();
  }

  /**
   * URL-encodes a form value.
   *
   * @param value raw value
   * @return the {@code application/x-www-form-urlencoded} encoding
   */
  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Builds an {@link SSLContext} that trusts ONLY the e2e backend's self-signed dev certificate,
   * loaded from the keystore {@link E2eStackExtension} generates at the repository root. The cert's
   * SAN covers {@code localhost}, so the JDK's default hostname verification still applies — this
   * is a scoped trust anchor, not a trust-all manager.
   *
   * @return a TLS context trusting only the backend dev cert
   */
  private static SSLContext backendCertContext() {
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(locateKeystore())) {
        keyStore.load(in, E2eStackExtension.KEYSTORE_PW.toCharArray());
      }
      // The generated store holds a private-key entry; its certificate is not a trust anchor until
      // copied into a trust store as a trusted-certificate entry.
      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      trustStore.load(null, null);
      for (String alias : Collections.list(keyStore.aliases())) {
        Certificate cert = keyStore.getCertificate(alias);
        if (cert != null) {
          trustStore.setCertificateEntry(alias, cert);
        }
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Could not build the backend-cert SSLContext", e);
    }
  }

  /**
   * Locates the throwaway {@code keystore.p12} that {@link E2eStackExtension} generated, by walking
   * up from the working directory (the e2e tests run with the {@code frontend} module as CWD; the
   * keystore sits at the repository root).
   *
   * @return the path to the e2e keystore
   * @throws IllegalStateException if no {@code keystore.p12} is found up to the filesystem root
   */
  private static Path locateKeystore() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      Path candidate = p.resolve("keystore.p12");
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "keystore.p12 not found walking up from " + Paths.get("").toAbsolutePath());
  }
}
