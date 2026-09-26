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
 * Seeds the backend state the ephemeral-stack flows need through the backend REST API, with a
 * bearer token from a Keycloak password grant on the {@code basetool-frontend} client.
 *
 * <p>Local-stack only: Keycloak on {@code http://localhost:18080}, backend on {@code
 * https://localhost:11261}.
 */
public final class BackendSeeder {

  private static final String KEYCLOAK_TOKEN_URL =
      "http://localhost:18080/auth/realms/iri/protocol/openid-connect/token";
  private static final String BACKEND_BASE_URL = "https://localhost:11261";
  private static final String CLIENT_ID = "basetool-frontend";
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /** JDBC coordinates of the ephemeral backend Postgres (published on the host loopback). */
  private static final String JDBC_URL = "jdbc:postgresql://localhost:15432/krt_basetool_e2e";

  private static final String DB_USER = "basetool_e2e";
  private static final String DB_PASSWORD = "basetool-e2e-pw-do-not-use-in-prod";

  /**
   * Users whose Terms-of-Use consent this run has already recorded. {@link #passwordGrant} runs on
   * every seeder entry point, so without this the acceptance call would repeat ~30 times per run.
   */
  private final java.util.Set<String> termsAcceptedUsers =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

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
   * Ensures the given test user is a member of the IRIDIUM Squadron; a no-op when it already has a
   * squadron.
   *
   * <p>Requires an ADMIN user, since it patches the user's own membership with that user's token;
   * use {@link #assignStaffelMembership} for a non-admin.
   *
   * @param username the Keycloak username of the test user; must be an ADMIN
   * @param password the Keycloak password of the test user
   */
  public void ensureIridiumMembership(String username, String password) {
    try {
      String token = passwordGrant(username, password);
      JsonObject me = getJson("/api/v1/users/me", token);
      if (me.has("squadron") && !me.get("squadron").isJsonNull()) {
        return;
      }
      String userId = me.get("id").getAsString();
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
   * Seeds a {@code PLANNED} operation via {@code POST /api/v1/operations}; the backend stamps the
   * owning org unit from the actor's active scope.
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
    return seedEntity(
        username, password, "/api/v1/locations", "{\"name\":\"" + name + "\",\"hidden\":false}");
  }

  /**
   * Resolves the id of an existing {@code Location} by its unique name via {@code GET
   * /api/v1/locations/lookup}.
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
   * Resolves the id of an existing {@code ShipType} by its unique name via {@code GET
   * /api/v1/ship-types?size=1000}.
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
   * Seeds one ship into the test user's own hangar via {@code POST /api/v1/hangar/ships}; the
   * owning org unit is auto-stamped from the user's single membership.
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
   * Returns the id of the existing material with that name, creating it via {@link
   * #createRefineryMaterial(String, String, String)} only when absent.
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
   * Get-or-creates a manual RAW refinery input material whose {@code refinedMaterialId} points at a
   * REFINED output material, seeding the output first.
   *
   * @param username admin username
   * @param password admin password
   * @param rawName name of the manual RAW input material
   * @param refinedName name of the REFINED output material the input refines into
   * @return the existing or freshly created RAW input material's id
   */
  public String ensureRefineryMaterialWithRefinedOutput(
      String username, String password, String rawName, String refinedName) {
    String existingRaw = findMaterialIdByName(username, password, rawName);
    if (existingRaw != null) {
      return existingRaw;
    }
    String refinedId = findMaterialIdByName(username, password, refinedName);
    if (refinedId == null) {
      refinedId =
          seedEntity(
              username,
              password,
              "/api/v1/materials",
              "{\"name\":\"" + refinedName + "\",\"type\":\"REFINED\",\"quantityType\":\"SCU\"}");
    }
    return seedEntity(
        username,
        password,
        "/api/v1/materials",
        "{\"name\":\""
            + rawName
            + "\",\"type\":\"RAW\",\"quantityType\":\"SCU\",\"isManualRawMaterial\":true,"
            + "\"refinedMaterialId\":\""
            + refinedId
            + "\"}");
  }

  /**
   * Creates a refinery order via {@code POST /api/v1/refinery-orders} with a single goods row of
   * 100 input/output units at quality 750, and returns its id. Without a refined counterpart, the
   * output material equals the input.
   *
   * @param username the Keycloak username of the order owner (must be an org-unit member, or the
   *     order lands ownerless)
   * @param password the Keycloak password of the order owner
   * @param locationId the id of the refinery-hosting location the order runs at
   * @param inputMaterialId the id of the manual RAW input material of the single goods row
   * @param owningOrgUnitId the OrgUnit to stamp the order onto, or {@code null} to auto-stamp the
   *     owner's single membership
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
   * Attempts {@code POST /api/v1/refinery-orders} and returns the HTTP status without throwing, to
   * assert the create-time OrgUnit stamping (REQ-ORG-004) and validation edges.
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
   * Attempts {@code PUT /api/v1/refinery-orders/{id}} with the given version, setting the status to
   * {@code IN_PROGRESS}, and returns the HTTP status without throwing.
   *
   * @param username the Keycloak username of the acting user
   * @param password the Keycloak password of the acting user
   * @param orderId the refinery order id to update
   * @param locationId the id of the refinery-hosting location to re-send
   * @param inputMaterialId the id of the manual RAW input material to re-send
   * @param version the optimistic-lock version to submit (current passes; stale gives 409)
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
   * Stores a refinery order's output into the Lager via {@code POST
   * /api/v1/refinery-orders/{id}/store}; throws on a non-2xx status (REQ-INV-001).
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
   * Attempts {@code POST /api/v1/refinery-orders/{id}/store} and returns the HTTP status without
   * throwing; same item shape as {@link #storeRefineryOrder}.
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
   * Grants a user access to a bank account, treating a {@code 409} for an existing grant as
   * success.
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
   * Raises a {@code PENDING} deposit booking request via {@code POST
   * /api/v1/org-units/bank/requests} and returns its id (REQ-BANK-042).
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
   * POST /api/v1/bank/accounts} and returns its id.
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
   * Reads a bank account's balance via {@code GET /api/v1/bank/accounts/{id}}, as whole aUEC.
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
   * Finds the caller's own {@code PENDING} booking request on the given account with the given
   * amount, from {@code GET /api/v1/org-units/bank/requests}.
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
   * Reads the status of one of the caller's own booking requests from {@code GET
   * /api/v1/org-units/bank/requests}.
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
   * Creates a job order with a single material line via {@code POST /api/v1/orders}, naming the
   * given org unit as both responsible and requesting unit, and returns its id.
   *
   * @param username the Keycloak username of the (admin) test user
   * @param password the Keycloak password of the test user
   * @param orgUnitId the responsible and requesting org unit; must be a profit-eligible squadron
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
   * Creates a job order with distinct responsible and requesting org units (REQ-ORDERS-023).
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
   * Books production for an item order's single line up to its full ordered amount, linking and
   * consuming the required recipe stock at the given location and booking the produced units in
   * there (REQ-ORDERS-025, REQ-INV-032).
   *
   * @param username the Keycloak username of the (logistician/admin) test user
   * @param password the Keycloak password of the test user
   * @param orderId the item order whose single line to fully manufacture
   * @param locationId the location of the consumed stock and of the produced stock
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
            + ",\"bookIn\":{\"locationId\":\""
            + locationId
            + "\"}}";
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
   * Creates a personal inventory item of the test user via {@code POST /api/v1/inventory} and
   * returns its id; the owning org unit is stamped from the user's membership.
   *
   * @param username the Keycloak username of the test user (must be an org-unit member)
   * @param password the Keycloak password of the test user
   * @param materialId the id of the material the item holds
   * @param locationId the id of the storage location of the item
   * @param quality the quality of the held material ({@code 0..1000})
   * @param amount the amount held
   * @return the created inventory item's id
   */
  public String createPersonalInventoryItem(
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
            + ",\"personal\":true}";
    return seedEntity(username, password, "/api/v1/inventory", body);
  }

  /**
   * Creates a non-personal inventory item linked to neither a job order nor a mission via {@code
   * POST /api/v1/inventory} and returns its id.
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
   * Creates a non-personal game-item stock row via {@code POST /api/v1/inventory} and returns its
   * id — the item sibling of {@link #createInventoryItem} (REQ-INV-029, ADR-0101). The payload
   * carries {@code gameItemId} instead of {@code materialId}, no quality (a game-item row forbids
   * one) and a positive whole-unit amount; create-time stamping sets {@code owningOrgUnit} from the
   * seeding user's membership exactly like a material row.
   *
   * @param username the Keycloak username of the test user (must be an org-unit member)
   * @param password the Keycloak password of the test user
   * @param gameItemId the id of the bookable game item the row holds (see {@link
   *     #seedOrderableItem})
   * @param locationId the id of the storage location of the row
   * @param amount the whole-unit amount held ({@code >= 1})
   * @return the created inventory row's id
   */
  public String createItemInventoryEntry(
      String username, String password, String gameItemId, String locationId, int amount) {
    String body =
        "{\"gameItemId\":\""
            + gameItemId
            + "\",\"locationId\":\""
            + locationId
            + "\",\"amount\":"
            + amount
            + ",\"personal\":false}";
    return seedEntity(username, password, "/api/v1/inventory", body);
  }

  /**
   * Creates an ITEM job order with a single line via {@code POST /api/v1/orders/items}, using the
   * first blueprint the item catalog offers, and returns its id.
   *
   * @param username the Keycloak username of the (admin) test user
   * @param password the Keycloak password of the test user
   * @param orgUnitId the org unit named as responsible and requesting unit; must be profit-eligible
   * @param handle the free-text contact handle of the order
   * @param gameItemId the finished item the order's single line requests
   * @param amount the whole-unit count to order ({@code >= 1})
   * @return the created ITEM job order's id
   */
  public String createItemJobOrder(
      String username,
      String password,
      String orgUnitId,
      String handle,
      String gameItemId,
      int amount) {
    JsonArray blueprints =
        JsonParser.parseString(
                getBody(
                    username,
                    password,
                    "/api/v1/orders/item-catalog/" + gameItemId + "/blueprints"))
            .getAsJsonArray();
    if (blueprints.isEmpty()) {
      throw new IllegalStateException(
          "No blueprint produces game item " + gameItemId + " — seed one via seedOrderableItem");
    }
    String blueprintId = blueprints.get(0).getAsJsonObject().get("id").getAsString();
    String body =
        "{\"responsibleOrgUnitId\":\""
            + orgUnitId
            + "\",\"requestingOrgUnitId\":\""
            + orgUnitId
            + "\",\"handle\":\""
            + handle
            + "\",\"items\":[{\"gameItemId\":\""
            + gameItemId
            + "\",\"blueprintId\":\""
            + blueprintId
            + "\",\"amount\":"
            + amount
            + "}]}";
    return seedEntity(username, password, "/api/v1/orders/items", body);
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
   * Creates a mission linked to the given operation via {@code POST /api/v1/missions}; otherwise
   * like {@link #createMission(String, String, String, boolean)}.
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
   * Adds an external participant with no account to a mission via {@code POST
   * /api/v1/missions/{id}/participants/add}.
   *
   * @param username the Keycloak username of the mission's creator
   * @param password the Keycloak password
   * @param missionId the mission to add the participant to
   * @param externalName the external participant's name; must not match any registered user's name
   * @return the mission id echoed back by the endpoint's {@code MissionDto} response
   */
  public String addExternalParticipant(
      String username, String password, String missionId, String externalName) {
    return seedEntity(
        username,
        password,
        "/api/v1/missions/" + missionId + "/participants/add",
        "{\"guestName\":\"" + externalName + "\"}");
  }

  /**
   * Registers an existing app user as a mission participant via {@code POST
   * /api/v1/missions/{id}/participants/slim}.
   *
   * @param username the Keycloak username of the mission's manager (its creator)
   * @param password the Keycloak password
   * @param missionId the mission to add the participant to
   * @param userId the {@code app_user} id to register (see {@link #getUserId})
   * @return the mission id, unchanged
   */
  public String addRegisteredParticipant(
      String username, String password, String missionId, String userId) {
    postBody(
        username,
        password,
        "/api/v1/missions/" + missionId + "/participants/slim",
        "{\"userId\":\"" + userId + "\"}");
    return missionId;
  }

  /**
   * Adds a unit to a mission via {@code POST /api/v1/missions/{id}/units/slim} with an explicit
   * responsible person, so the crew board renders the responsible chip and the unit-edit modal
   * pre-selects it. Only a registered participant (see {@link #addRegisteredParticipant}) is
   * offered in the modal's responsible picker, so the {@code responsibleUserId} must already be a
   * participant for the edit modal to show the name rather than an empty box.
   *
   * @param username the Keycloak username of the mission's manager (its creator)
   * @param password the Keycloak password
   * @param missionId the mission to add the unit to
   * @param name the unit's display name (its single mandatory field)
   * @param responsibleUserId the {@code app_user} id pinned as the unit's responsible person
   * @return the mission id, unchanged — the slim endpoint answers with the unit list
   */
  public String addUnitWithResponsible(
      String username, String password, String missionId, String name, String responsibleUserId) {
    postBody(
        username,
        password,
        "/api/v1/missions/" + missionId + "/units/slim",
        "{\"name\":\"" + name + "\",\"responsibleUserId\":\"" + responsibleUserId + "\"}");
    return missionId;
  }

  /**
   * Attempts {@code POST /api/v1/orders} with the given responsible org unit and returns the HTTP
   * status without throwing; a non-profit-eligible responsible unit gives 400.
   *
   * @param username the Keycloak username (an admin, to create in all-squadrons scope)
   * @param password the Keycloak password
   * @param responsibleOrgUnitId the responsible (processing) OrgUnit id under test
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
   * returns the backend's draft answer verbatim.
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
   * Creates a {@code BEREICH} OrgUnit via {@code POST /api/v1/org-hierarchy/bereiche} and returns
   * its id (REQ-ORG-014).
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
   * Grants a user a Bereichsleitung role via {@code POST
   * /api/v1/org-hierarchy/bereiche/{id}/members} (REQ-ORG-017). The user must hold no Staffel
   * membership.
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
   * Sets a squadron's {@code is_profit_eligible} flag via {@code PATCH
   * /api/v1/squadrons/{id}/profit-eligible}; throws on a non-2xx status.
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
        throw new IllegalStateException(
            "Profit-eligibility PATCH failed: HTTP " + status + " body=" + lastBodyForMessage());
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
   * Sets a Spezialkommando's {@code is_profit_eligible} flag via {@code PATCH
   * /api/v1/special-commands/{id}/profit-eligible}; throws on a non-2xx status.
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
   * Seeds an orderable item over JDBC: a {@code game_item} plus an active {@code blueprint}
   * outputting it with one RESOURCE ingredient resolved to the given material. Local-stack only.
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
   * Makes a material sellable by seeding over JDBC a {@code terminal} and a {@code material_price}
   * row with a positive sell price. Each call uses a fresh terminal. Local-stack only.
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
   * Adds the given user to a Spezialkommando via {@code POST
   * /api/v1/special-commands/{id}/members/{userId}}, with all role flags false; a 409 counts as
   * success.
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
   * Removes the given user from a Spezialkommando via {@code DELETE
   * /api/v1/special-commands/{id}/members/{userId}}.
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
   * Attempts a DISCARD book-out via {@code POST /api/v1/inventory/{id}/book-out} and returns the
   * HTTP status without throwing.
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
   * Reads the open job order carrying the given unique contact handle as an admin via {@code GET
   * /api/v1/orders?size=1000&amp;status=OPEN}.
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
   * Assigns {@code targetUserId} to a Staffel with the given role flags via {@code PATCH
   * /api/v1/users/{id}/memberships} (REQ-ORG-017). The sent Staffel set is authoritative;
   * idempotent.
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
    java.net.http.HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
    lastResponseBody = response.body();
    return response.statusCode();
  }

  /** The body of the most recent {@link #patch} response, included in failure messages. */
  private String lastResponseBody = "";

  /**
   * The last response body, trimmed to something a failure message can carry.
   *
   * @return at most 400 characters of the body, or a marker when there was none
   */
  private String lastBodyForMessage() {
    if (lastResponseBody == null || lastResponseBody.isBlank()) {
      return "<empty body>";
    }
    return lastResponseBody.length() > 400
        ? lastResponseBody.substring(0, 400) + "…"
        : lastResponseBody;
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
   * Performs a Keycloak Resource-Owner-Password-Credentials grant on the {@code basetool-frontend}
   * client (confidential in the E2E realm, so with its throwaway secret) and returns the access
   * token.
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
            + "&client_secret="
            + enc(E2eStackExtension.FRONTEND_CLIENT_SECRET)
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
    String token =
        JsonParser.parseString(response.body()).getAsJsonObject().get("access_token").getAsString();
    ensureTermsAccepted(username, token);
    return token;
  }

  /**
   * Records the user's Terms of Use consent via {@code POST /api/v1/terms/acceptance}, so its API
   * calls are not refused with {@code 403 TERMS_NOT_ACCEPTED} (REQ-SEC-028). Runs once per user per
   * run.
   *
   * @param username the user the token belongs to, used as the cache key
   * @param token that user's bearer token
   * @throws Exception on transport failure
   */
  private void ensureTermsAccepted(String username, String token) throws Exception {
    if (!termsAcceptedUsers.add(username)) {
      return;
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BACKEND_BASE_URL + "/api/v1/terms/acceptance"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    int status = http.send(request, BodyHandlers.ofString()).statusCode();
    if (status < 200 || status >= 300) {
      termsAcceptedUsers.remove(username);
      System.out.printf("[E2E][seeder] terms acceptance returned HTTP %d%n", status);
    }
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
   * Builds an HTTP client that trusts only the committed test CA (ADR-0139), with hostname
   * verification left on.
   *
   * @return an HTTP client trusting only the test CA
   */
  /**
   * An HTTP client trusting only the committed test CA, for callers outside the seeder that talk to
   * the stack — {@code ServedBuildCheck} reads the frontend's landing page with it. The CA signed
   * the frontend's leaf as well, and that leaf names {@code localhost} too.
   *
   * @return a client that verifies the stack's certificates against the test CA
   */
  static HttpClient trustingTestCa() {
    return HttpClient.newBuilder()
        .sslContext(backendCertContext())
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();
  }

  private static SSLContext backendCertContext() {
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(locateKeystore())) {
        keyStore.load(in, E2eStackExtension.KEYSTORE_PW.toCharArray());
      }
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
   * Locates the committed test truststore under {@code docker/test-tls/} (ADR-0139) by walking up
   * from the working directory.
   *
   * @return the path to the committed test truststore
   * @throws IllegalStateException if the truststore is not found up to the filesystem root
   */
  private static Path locateKeystore() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      Path candidate =
          p.resolve("docker").resolve("test-tls").resolve("basetool-test-truststore.p12");
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "docker/test-tls/basetool-test-truststore.p12 not found walking up from "
            + Paths.get("").toAbsolutePath());
  }
}
