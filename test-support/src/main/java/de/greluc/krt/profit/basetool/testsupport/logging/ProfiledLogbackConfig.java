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

package de.greluc.krt.profit.basetool.testsupport.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Loads an application's {@code logback-spring.xml} into a fresh, private {@link LoggerContext} for
 * a given set of active profiles, so tests exercise the production appenders.
 *
 * <p>{@code <springProfile>} blocks are resolved textually (comma-separated names, optionally
 * negated; nesting is rejected), and every {@code logs/} path is redirected into the caller's
 * directory.
 */
public final class ProfiledLogbackConfig {

  /** A whole {@code <springProfile name="…">…</springProfile>} block, non-greedy. */
  private static final Pattern PROFILE_BLOCK =
      Pattern.compile(
          "<springProfile\\s+name=\"([^\"]*)\"\\s*>(.*?)</springProfile>", Pattern.DOTALL);

  private ProfiledLogbackConfig() {}

  /**
   * Configures and starts a new {@link LoggerContext} from {@code resource} with its own MDC
   * adapter; stop it when done to flush the asynchronous appenders.
   *
   * @param resource the classpath location of the configuration, e.g. {@code logback-spring.xml}
   * @param activeProfiles the Spring profiles to treat as active
   * @param logDir the directory replacing {@code logs/} in every file path
   * @return the started context, configured without any ERROR status
   * @throws IllegalStateException if the resource is missing, a profile block is nested, or Joran
   *     reports an ERROR status
   * @throws UncheckedIOException if the resource cannot be read
   */
  public static @NotNull LoggerContext configure(
      @NotNull String resource, @NotNull Set<String> activeProfiles, @NotNull Path logDir) {
    String xml = resolveProfiles(read(resource), activeProfiles);
    String dir = logDir.toAbsolutePath().toString().replace('\\', '/');
    xml = xml.replace("logs/", dir + "/");

    LoggerContext context = new LoggerContext();
    context.setMDCAdapter(new LogbackMDCAdapter());
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    try {
      configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (JoranException e) {
      context.stop();
      throw new IllegalStateException("Joran rejected " + resource, e);
    }
    List<Status> problems =
        context.getStatusManager().getCopyOfStatusList().stream()
            .filter(status -> status.getLevel() >= Status.ERROR)
            .toList();
    if (!problems.isEmpty()) {
      context.stop();
      throw new IllegalStateException(
          "Configuring " + resource + " reported " + problems.size() + " problem(s): " + problems);
    }
    return context;
  }

  /**
   * Resolves every {@code <springProfile>} block in {@code xml} against {@code activeProfiles}: the
   * content of a matching block is kept without its wrapper, a non-matching block is removed.
   *
   * @param xml the configuration text
   * @param activeProfiles the Spring profiles to treat as active
   * @return the configuration with no {@code <springProfile>} element left
   * @throws IllegalStateException if a block contains another {@code <springProfile>}
   */
  static @NotNull String resolveProfiles(@NotNull String xml, @NotNull Set<String> activeProfiles) {
    Matcher matcher = PROFILE_BLOCK.matcher(xml);
    StringBuilder resolved = new StringBuilder(xml.length());
    while (matcher.find()) {
      String body = matcher.group(2);
      if (body.contains("<springProfile")) {
        throw new IllegalStateException("nested <springProfile> is not supported: " + body);
      }
      String replacement = matches(matcher.group(1), activeProfiles) ? body : "";
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  /**
   * Evaluates one profile expression the way {@code <springProfile name="…">} does for the subset
   * used here: any listed name that is active, or any {@code !name} that is not, matches.
   *
   * @param expression the {@code name} attribute, e.g. {@code prod} or {@code !prod}
   * @param activeProfiles the Spring profiles to treat as active
   * @return {@code true} if the block's content applies
   */
  static boolean matches(@NotNull String expression, @NotNull Set<String> activeProfiles) {
    return Arrays.stream(expression.split(","))
        .map(String::trim)
        .anyMatch(
            entry ->
                entry.startsWith("!")
                    ? !activeProfiles.contains(entry.substring(1).trim())
                    : activeProfiles.contains(entry));
  }

  /**
   * Reads a classpath resource as UTF-8 through the thread's context class loader.
   *
   * @param resource the classpath location
   * @return the resource's content
   * @throws IllegalStateException if the resource does not exist
   * @throws UncheckedIOException if it cannot be read
   */
  private static @NotNull String read(@NotNull String resource) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    try (InputStream in = loader.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("No classpath resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
