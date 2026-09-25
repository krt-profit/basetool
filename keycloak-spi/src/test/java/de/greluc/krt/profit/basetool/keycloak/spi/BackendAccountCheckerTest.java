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

package de.greluc.krt.profit.basetool.keycloak.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.greluc.krt.profit.basetool.keycloak.spi.BackendAccountChecker.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Fail-open matrix for {@link BackendAccountChecker}, served by a throwaway in-process HTTP server
 * (REQ-SEC-022). The checker is the duplicate-account guard: only a clean HTTP 200 with {@code
 * exists=true} denies; every other outcome (200/false, 503, 401, malformed/absent field, timeout)
 * is {@link Result#UNKNOWN}, which the caller treats as allow. Also pins the request contract: the
 * shared-secret header and the JSON candidate body are sent.
 */
class BackendAccountCheckerTest {

  private static final String SECRET = "shared-secret";

  @Test
  void exists_true_isExists() throws IOException {
    HttpServer server = start(respond(200, "{\"exists\":true}"));
    try {
      assertEquals(Result.EXISTS, check(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void exists_false_isNotExists() throws IOException {
    HttpServer server = start(respond(200, "{\"exists\":false}"));
    try {
      assertEquals(Result.NOT_EXISTS, check(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void featureDisabled503_failsOpenUnknown() throws IOException {
    HttpServer server = start(respond(503, ""));
    try {
      assertEquals(Result.UNKNOWN, check(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void badSecret401_failsOpenUnknown() throws IOException {
    HttpServer server = start(respond(401, ""));
    try {
      assertEquals(Result.UNKNOWN, check(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void malformedBody_failsOpenUnknown() throws IOException {
    HttpServer server = start(respond(200, "this is not json"));
    try {
      assertEquals(Result.UNKNOWN, check(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void missingExistsField_failsOpenUnknown() throws IOException {
    HttpServer server = start(respond(200, "{\"other\":true}"));
    try {
      assertEquals(Result.UNKNOWN, check(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void timeout_failsOpenUnknown() throws IOException {
    HttpServer server =
        start(
            exchange -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              writeResponse(exchange, 200, "{\"exists\":true}");
            });
    try {
      assertEquals(Result.UNKNOWN, check(server, Duration.ofMillis(300)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void sendsSecretHeaderAndJsonCandidateBody() throws IOException {
    AtomicReference<String> secretHeader = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server =
        start(
            exchange -> {
              secretHeader.set(
                  exchange.getRequestHeaders().getFirst(BackendAccountChecker.SECRET_HEADER));
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              writeResponse(exchange, 200, "{\"exists\":false}");
            });
    try {
      Result result =
          new BackendAccountChecker(HttpClient.newHttpClient(), Duration.ofSeconds(2))
              .check(baseUrl(server), SECRET, "Maverick", "mav@example.com", "Mav");

      assertEquals(Result.NOT_EXISTS, result);
      assertEquals(SECRET, secretHeader.get());
      assertTrue(body.get().contains("\"username\":\"Maverick\""), body.get());
      assertTrue(body.get().contains("\"email\":\"mav@example.com\""), body.get());
      assertTrue(body.get().contains("\"serverNickname\":\"Mav\""), body.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void omitsBlankCandidateFields() throws IOException {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server =
        start(
            exchange -> {
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              writeResponse(exchange, 200, "{\"exists\":false}");
            });
    try {
      new BackendAccountChecker(HttpClient.newHttpClient(), Duration.ofSeconds(2))
          .check(baseUrl(server), SECRET, "Maverick", null, "  ");

      assertTrue(body.get().contains("\"username\":\"Maverick\""), body.get());
      assertTrue(!body.get().contains("email"), body.get());
      assertTrue(!body.get().contains("serverNickname"), body.get());
    } finally {
      server.stop(0);
    }
  }

  private static Result check(HttpServer server, Duration requestTimeout) {
    BackendAccountChecker checker =
        new BackendAccountChecker(HttpClient.newHttpClient(), requestTimeout);
    return checker.check(baseUrl(server), SECRET, "user", "user@example.com", "nick");
  }

  private static String baseUrl(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static HttpServer start(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.start();
    return server;
  }

  private static HttpHandler respond(int status, String body) {
    return exchange -> writeResponse(exchange, status, body);
  }

  private static void writeResponse(
      com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }
}
