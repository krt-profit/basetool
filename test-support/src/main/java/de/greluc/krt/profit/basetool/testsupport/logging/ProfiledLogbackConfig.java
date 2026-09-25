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
 * Loads an application's real {@code logback-spring.xml} into a <em>fresh</em>, private {@link
 * LoggerContext} as Spring Boot would for a given set of active profiles, so a test can send an
 * event through exactly the appenders, layouts and encoders production runs.
 *
 * <p>Why not Spring Boot's own {@code LogbackLoggingSystem}: it reconfigures the one global context
 * every other test in the JVM logs through, and some of those capture log output to assert on it.
 * Plain Joran on a private context touches nothing shared — but Joran does not know Boot's {@code
 * <springProfile>} element, so this class resolves those blocks textually first: a block whose
 * {@code name} matches the active profiles is unwrapped, any other block is dropped. The expression
 * grammar is the subset the three configurations use — a comma-separated list of profile names,
 * each optionally negated with {@code !}, matching when any entry matches — and a nested {@code
 * <springProfile>} is rejected rather than misread.
 *
 * <p>Every {@code logs/} path in the configuration is redirected into the caller's directory, so
 * the file appenders write to a test-owned temporary directory instead of the module's working
 * directory.
 */
public final class ProfiledLogbackConfig {

  /** A whole {@code <springProfile name="…">…</springProfile>} block, non-greedy. */
  private static final Pattern PROFILE_BLOCK =
      Pattern.compile(
          "<springProfile\\s+name=\"([^\"]*)\"\\s*>(.*?)</springProfile>", Pattern.DOTALL);

  private ProfiledLogbackConfig() {}

  /**
   * Configures and starts a new {@link LoggerContext} from the classpath resource {@code resource}
   * with {@code activeProfiles} active and every {@code logs/} path redirected into {@code logDir}.
   * The context gets its own MDC adapter, so a test puts MDC values through {@link
   * LoggerContext#getMDCAdapter()} without touching the global MDC. Stop the context when done:
   * that drains the asynchronous appenders and closes the files.
   *
   * @param resource the classpath location of the configuration, e.g. {@code logback-spring.xml}
   * @param activeProfiles the Spring profiles to treat as active
   * @param logDir the directory that replaces {@code logs/} in every file path
   * @return the started context, configured without a single ERROR status
   * @throws IllegalStateException if the resource is missing, a profile block is nested, or Joran
   *     reports an ERROR status while configuring
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
   * Reads a classpath resource as UTF-8 text through the thread's context class loader, which in a
   * test JVM sees the consuming module's {@code src/main/resources}.
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
