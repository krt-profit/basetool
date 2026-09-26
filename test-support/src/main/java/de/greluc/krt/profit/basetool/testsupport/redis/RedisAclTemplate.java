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

package de.greluc.krt.profit.basetool.testsupport.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Renders the committed Redis ACL template ({@code scripts/redis-users.acl.tmpl}) like {@code
 * scripts/render-redis-acl.py}, so tests run against the production rules (REQ-SEC-068).
 *
 * <p>{@code {{hash:NAME}}} becomes {@code #} plus the SHA-256 of NAME's value, {@code
 * {{state:NAME}}} becomes {@code on} or {@code off}; comment lines are dropped.
 */
public final class RedisAclTemplate {

  /** The ACL user the frontend authenticates as. */
  public static final String FRONTEND_USER = "basetool-frontend";

  /** The ACL user the backend authenticates as. */
  public static final String BACKEND_USER = "basetool-backend";

  /** The ACL user the ingest gateway authenticates as. */
  public static final String INGEST_USER = "basetool-ingest";

  /** The operator's ACL user. */
  public static final String ADMIN_USER = "admin";

  /** The redis_exporter's ACL user. */
  public static final String MONITORING_USER = "monitoring";

  /**
   * The throwaway passwords of the E2E stack, by the {@code .env} variable the template names.
   * Obviously synthetic and published on purpose: the E2E Redis is created and destroyed per run,
   * and {@code docker/test-redis/users.acl} carries their hashes. Never a production value.
   */
  public static final @Unmodifiable Map<String, String> E2E_PASSWORDS =
      Map.of(
          "REDIS_PASSWORD", "redis-e2e-pw-do-not-use-in-prod",
          "REDIS_EXPORTER_PASSWORD", "redis-e2e-monitoring-pw-do-not-use-in-prod",
          "REDIS_FRONTEND_PASSWORD", "redis-e2e-frontend-pw-do-not-use-in-prod",
          "REDIS_BACKEND_PASSWORD", "redis-e2e-backend-pw-do-not-use-in-prod",
          "REDIS_INGEST_PASSWORD", "redis-e2e-ingest-pw-do-not-use-in-prod");

  /** The template's path, relative to the repository root. */
  private static final String TEMPLATE = "scripts/redis-users.acl.tmpl";

  /** The two placeholder forms the renderer knows. */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{(hash|state):([A-Za-z_][A-Za-z0-9_]*)}}");

  /** No instances. */
  private RedisAclTemplate() {}

  /**
   * Renders the repository's template.
   *
   * @param values the template variables; every {@code hash} variable must be non-empty, a missing
   *     {@code state} variable means {@code on}
   * @return the ACL file content, one {@code user} line per rule, ending in a newline
   * @throws IllegalArgumentException when a required value is missing or a state is invalid
   */
  public static @NotNull String render(@NotNull Map<String, String> values) {
    return render(readTemplate(), values);
  }

  /**
   * Renders the given template text.
   *
   * @param template the template's text.
   * @param values the variables the template names.
   * @return the ACL file content.
   * @throws IllegalArgumentException when a required value is missing or a state is invalid.
   */
  public static @NotNull String render(
      @NotNull String template, @NotNull Map<String, String> values) {
    List<String> out = new ArrayList<>();
    for (String raw : template.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      Matcher matcher = PLACEHOLDER.matcher(line);
      StringBuilder rendered = new StringBuilder();
      while (matcher.find()) {
        String value = values.getOrDefault(matcher.group(2), "");
        String replacement;
        if ("hash".equals(matcher.group(1))) {
          if (value.isEmpty()) {
            throw new IllegalArgumentException(matcher.group(2) + " is unset or empty");
          }
          replacement = sha256Password(value);
        } else {
          replacement = value.isBlank() ? "on" : value.strip().toLowerCase(Locale.ROOT);
          if (!"on".equals(replacement) && !"off".equals(replacement)) {
            throw new IllegalArgumentException(matcher.group(2) + " is neither on nor off");
          }
        }
        matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
      }
      matcher.appendTail(rendered);
      out.add(rendered.toString());
    }
    return String.join("\n", out) + "\n";
  }

  /**
   * Returns a password in the form an ACL rule accepts in place of {@code >password}.
   *
   * @param password the clear-text password.
   * @return {@code #} followed by the lowercase hex SHA-256 of its UTF-8 bytes.
   */
  public static @NotNull String sha256Password(@NotNull String password) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
      return "#" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("every JVM ships SHA-256", impossible);
    }
  }

  /**
   * Resolves a path under the repository root, found by walking up from the working directory to
   * the directory holding the template.
   *
   * @param relative the path relative to the repository root
   * @return the absolute path
   * @throws IllegalStateException when no ancestor of the working directory holds the template
   */
  public static @NotNull Path repositoryPath(@NotNull String relative) {
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.exists(dir.resolve(TEMPLATE))) {
        return dir.resolve(relative);
      }
    }
    throw new IllegalStateException(
        TEMPLATE + " not found above " + Paths.get("").toAbsolutePath());
  }

  /**
   * Reads the committed template.
   *
   * @return its text.
   */
  private static @NotNull String readTemplate() {
    try {
      return Files.readString(repositoryPath(TEMPLATE), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
