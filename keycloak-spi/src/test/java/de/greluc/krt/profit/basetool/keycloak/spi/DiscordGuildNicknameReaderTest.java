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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fail-open matrix for {@link DiscordGuildNicknameReader} (REQ-DATA-008), served by a throwaway
 * in-process HTTP server. Covers: nickname captured on HTTP 200; empty result when {@code nick} is
 * null / blank / absent; and the fail-open empties on a non-200 status, a malformed body and a
 * timeout — capturing a nickname must never throw, so a Discord hiccup can never break the login.
 * Also covers the two views of the member object: {@link
 * DiscordGuildNicknameReader#readGuildDisplayName} falling back to {@code user.global_name} (the
 * approval-queue label), and {@link DiscordGuildNicknameReader#readNickname} staying nick-only (the
 * conservative precheck candidate). The pure {@code extractNick} / {@code extractGuildDisplayName}
 * parsing is checked directly too.
 */
class DiscordGuildNicknameReaderTest {

  private static final String GUILD = "123456";
  private static final String TOKEN = "brokered-access-token";

  @Test
  void presentNick_isReturnedTrimmed() throws IOException {
    HttpServer server =
        start(respond(200, "{\"nick\":\"  Vanguard Pilot  \",\"roles\":[\"999\"]}"));
    try {
      assertEquals(Optional.of("Vanguard Pilot"), read(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void nullNick_isEmpty() throws IOException {
    HttpServer server = start(respond(200, "{\"nick\":null,\"roles\":[\"999\"]}"));
    try {
      assertTrue(read(server, Duration.ofSeconds(2)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void absentNick_isEmpty() throws IOException {
    HttpServer server = start(respond(200, "{\"roles\":[\"999\"]}"));
    try {
      assertTrue(read(server, Duration.ofSeconds(2)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void notInGuild404_isEmpty() throws IOException {
    HttpServer server = start(respond(404, ""));
    try {
      assertTrue(read(server, Duration.ofSeconds(2)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void serverError5xx_isEmpty() throws IOException {
    HttpServer server = start(respond(500, "{\"message\":\"boom\"}"));
    try {
      assertTrue(read(server, Duration.ofSeconds(2)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void malformedBody_isEmpty() throws IOException {
    HttpServer server = start(respond(200, "this is not json"));
    try {
      assertTrue(read(server, Duration.ofSeconds(2)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void timeout_isEmpty() throws IOException {
    HttpServer server =
        start(
            exchange -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              writeResponse(exchange, 200, "{\"nick\":\"too slow\"}");
            });
    try {
      // 300 ms request timeout against a 2 s server => fail open (empty), never throws.
      assertTrue(read(server, Duration.ofMillis(300)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void extractNick_blankAndAbsentAndUnparseable_areEmpty() {
    assertTrue(DiscordGuildNicknameReader.extractNick("{\"nick\":\"   \"}").isEmpty());
    assertTrue(DiscordGuildNicknameReader.extractNick("{}").isEmpty());
    assertTrue(DiscordGuildNicknameReader.extractNick("not json").isEmpty());
    assertEquals(
        Optional.of("Wing Lead"),
        DiscordGuildNicknameReader.extractNick("{\"nick\":\"Wing Lead\"}"));
  }

  @Test
  void readGuildDisplayName_fallsBackToGlobalNameWhenNickAbsent() throws IOException {
    // The reported conrad7247/MadrukSedras case: no per-guild nick, server name is the global name.
    HttpServer server =
        start(respond(200, "{\"nick\":null,\"user\":{\"global_name\":\"MadrukSedras\"}}"));
    try {
      assertEquals(Optional.of("MadrukSedras"), readDisplay(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readGuildDisplayName_prefersNickOverGlobalName() throws IOException {
    HttpServer server =
        start(respond(200, "{\"nick\":\"Wing Lead\",\"user\":{\"global_name\":\"Ignored\"}}"));
    try {
      assertEquals(Optional.of("Wing Lead"), readDisplay(server, Duration.ofSeconds(2)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readNickname_ignoresGlobalName_soThePrecheckStaysConservative() throws IOException {
    // readNickname (the precheck candidate) must NOT fall back to the global name — otherwise a
    // common display name could trigger a false account-collision denial at first-broker login.
    HttpServer server =
        start(respond(200, "{\"nick\":null,\"user\":{\"global_name\":\"MadrukSedras\"}}"));
    try {
      assertTrue(read(server, Duration.ofSeconds(2)).isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void extractGuildDisplayName_nickElseGlobalNameElseEmpty() {
    assertEquals(
        Optional.of("Nick"),
        DiscordGuildNicknameReader.extractGuildDisplayName(
            "{\"nick\":\"Nick\",\"user\":{\"global_name\":\"Global\"}}"));
    assertEquals(
        Optional.of("Global"),
        DiscordGuildNicknameReader.extractGuildDisplayName(
            "{\"nick\":\"   \",\"user\":{\"global_name\":\"Global\"}}"));
    assertTrue(
        DiscordGuildNicknameReader.extractGuildDisplayName("{\"user\":{\"global_name\":null}}")
            .isEmpty());
    assertTrue(DiscordGuildNicknameReader.extractGuildDisplayName("{}").isEmpty());
    assertTrue(DiscordGuildNicknameReader.extractGuildDisplayName("not json").isEmpty());
  }

  private static Optional<String> read(HttpServer server, Duration requestTimeout) {
    DiscordGuildNicknameReader reader =
        new DiscordGuildNicknameReader(HttpClient.newHttpClient(), requestTimeout);
    return reader.readNickname(baseUrl(server), GUILD, TOKEN);
  }

  private static Optional<String> readDisplay(HttpServer server, Duration requestTimeout) {
    DiscordGuildNicknameReader reader =
        new DiscordGuildNicknameReader(HttpClient.newHttpClient(), requestTimeout);
    return reader.readGuildDisplayName(baseUrl(server), GUILD, TOKEN);
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
