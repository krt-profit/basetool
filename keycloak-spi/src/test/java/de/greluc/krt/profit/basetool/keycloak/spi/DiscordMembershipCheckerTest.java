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

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.greluc.krt.profit.basetool.keycloak.spi.DiscordMembershipChecker.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed matrix for {@link DiscordMembershipChecker}, served by a throwaway in-process HTTP
 * server (REQ-SEC-016). Covers: member-with-role allow; member-without-role, not-in-guild (404)
 * deny; and the fail-closed denials on 5xx, malformed body, 429-exhausted and timeout.
 */
class DiscordMembershipCheckerTest {

  private static final String GUILD = "123456";
  private static final String ROLE = "999";
  private static final String TOKEN = "brokered-access-token";

  @Test
  void inGuildWithRequiredRole_isAllowed() throws IOException {
    HttpServer server = start(respond(200, "{\"roles\":[\"111\",\"999\",\"222\"]}"));
    try {
      assertEquals(Result.ALLOWED, check(server, Duration.ofSeconds(2), 0));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void inGuildWithoutRequiredRole_isDeniedNotMember() throws IOException {
    HttpServer server = start(respond(200, "{\"roles\":[\"111\",\"222\"]}"));
    try {
      assertEquals(Result.DENIED_NOT_MEMBER, check(server, Duration.ofSeconds(2), 0));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void notInGuild404_isDeniedNotMember() throws IOException {
    HttpServer server = start(respond(404, ""));
    try {
      assertEquals(Result.DENIED_NOT_MEMBER, check(server, Duration.ofSeconds(2), 0));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void serverError5xx_failsClosed() throws IOException {
    HttpServer server = start(respond(500, "{\"message\":\"boom\"}"));
    try {
      assertEquals(Result.DENIED_ERROR, check(server, Duration.ofSeconds(2), 0));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void malformedBody_failsClosed() throws IOException {
    HttpServer server = start(respond(200, "this is not json"));
    try {
      assertEquals(Result.DENIED_ERROR, check(server, Duration.ofSeconds(2), 0));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rateLimited429AfterRetryBudget_failsClosed() throws IOException {
    HttpServer server = start(respond(429, "{\"retry_after\":0}"));
    try {
      assertEquals(Result.DENIED_ERROR, check(server, Duration.ofSeconds(2), 0));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void timeout_failsClosed() throws IOException {
    HttpServer server =
        start(
            exchange -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              writeResponse(exchange, 200, "{\"roles\":[\"999\"]}");
            });
    try {
      assertEquals(Result.DENIED_ERROR, check(server, Duration.ofMillis(300), 0));
    } finally {
      server.stop(0);
    }
  }

  private static Result check(HttpServer server, Duration requestTimeout, int retries) {
    DiscordMembershipChecker checker =
        new DiscordMembershipChecker(
            HttpClient.newHttpClient(), requestTimeout, retries, Duration.ofMillis(50));
    return checker.check(baseUrl(server), GUILD, ROLE, TOKEN);
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
