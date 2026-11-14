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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * The sandbox seed grants {@code sandbox-client} every capability and switches the bank route
 * family on, as the sandbox page promises (REQ-XCH-029).
 */
class SandboxSeedCapabilitiesTest {

  private static final Path SEED = Path.of("../docker/sandbox/seed.sql");

  /** The registry row of {@code sandbox-client} in the seed. */
  private static final String SANDBOX_CLIENT = "'5a4d0000-0000-4000-8000-000000002301'::uuid";

  private static final Pattern SCOPE = Pattern.compile("'(exchange\\.[a-z.]+)'");

  @Test
  void theSandboxClientIsGrantedEveryCapability() throws IOException {
    Set<String> every = new TreeSet<>();
    Arrays.stream(ExchangeCapability.values()).forEach(c -> every.add(c.getScope()));

    assertThat(sandboxClientScopes()).containsExactlyElementsOf(every);
  }

  @Test
  void theSeedSwitchesTheBankRouteFamilyOn() throws IOException {
    assertThat(Files.readString(SEED))
        .containsPattern("(?s)UPDATE exchange_settings\\s+SET[^;]*bank_enabled = true");
  }

  /**
   * Reads the scopes the seed grants {@code sandbox-client}: the values list of its select, up to
   * the next client's.
   *
   * @return the scopes, sorted
   * @throws IOException when the seed cannot be read
   */
  private static @NotNull Set<String> sandboxClientScopes() throws IOException {
    String seed = Files.readString(SEED);
    int start = seed.indexOf("SELECT " + SANDBOX_CLIENT);
    int end = seed.indexOf("UNION ALL", start);
    assertThat(start).isNotNegative();
    assertThat(end).isGreaterThan(start);
    Set<String> scopes = new TreeSet<>();
    Matcher matcher = SCOPE.matcher(seed.substring(start, end));
    while (matcher.find()) {
      scopes.add(matcher.group(1));
    }
    return scopes;
  }
}
