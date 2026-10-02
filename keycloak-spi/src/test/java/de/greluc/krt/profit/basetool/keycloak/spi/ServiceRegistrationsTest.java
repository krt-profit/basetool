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

import de.greluc.krt.profit.basetool.keycloak.spi.discord.DiscordFederatedIdentityMapper;
import de.greluc.krt.profit.basetool.keycloak.spi.discord.DiscordIdentityProviderFactory;
import de.greluc.krt.profit.basetool.keycloak.spi.discord.DiscordUserAttributeMapper;
import de.greluc.krt.profit.basetool.keycloak.spi.exchange.DeviceConsentLoginFormsProviderFactory;
import de.greluc.krt.profit.basetool.keycloak.spi.exchange.ExchangeClientSessionResourceProviderFactory;
import de.greluc.krt.profit.basetool.keycloak.spi.gate.DiscordGuildRoleGateAuthenticatorFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.broker.provider.IdentityProviderMapper;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.forms.login.LoginFormsProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.protocol.ProtocolMapper;
import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;

/**
 * Every service registration of the provider JAR loads: each {@code META-INF/services} file names
 * an SPI interface on the classpath, each entry resolves to a class implementing it that the {@link
 * ServiceLoader} path can instantiate, its provider id is the one the realm and the backend
 * reference, and no registration is missing or unexpected (REQ-SEC-016, REQ-XCH-005, REQ-XCH-008).
 */
class ServiceRegistrationsTest {

  private static final Path SERVICES = Path.of("src/main/resources/META-INF/services");

  /** The registrations the module ships, by SPI interface. */
  private static final Map<String, Registration> EXPECTED =
      Map.of(
          AuthenticatorFactory.class.getName(),
          new Registration(
              DiscordGuildRoleGateAuthenticatorFactory.class.getName(), "discord-guild-role-gate"),
          IdentityProviderMapper.class.getName(),
          new Registration(
              DiscordUserAttributeMapper.class.getName(), "discord-user-attribute-mapper"),
          SocialIdentityProviderFactory.class.getName(),
          new Registration(DiscordIdentityProviderFactory.class.getName(), "discord"),
          LoginFormsProviderFactory.class.getName(),
          new Registration(
              DeviceConsentLoginFormsProviderFactory.class.getName(), "krt-freemarker"),
          ProtocolMapper.class.getName(),
          new Registration(
              DiscordFederatedIdentityMapper.class.getName(), "discord-federated-identity-mapper"),
          AdminRealmResourceProviderFactory.class.getName(),
          new Registration(
              ExchangeClientSessionResourceProviderFactory.class.getName(), "basetool-exchange"));

  @Test
  void everyShippedRegistrationLoads() {
    assertEquals(List.of(), check(SERVICES, EXPECTED));
  }

  @Test
  void theServiceLoaderFindsEveryRegistration() {
    for (Map.Entry<String, Registration> entry : EXPECTED.entrySet()) {
      Class<?> spi = load(entry.getKey());
      List<String> found = new ArrayList<>();
      ServiceLoader.load(spi).stream()
          .forEach(
              provider -> {
                try {
                  found.add(provider.type().getName());
                } catch (ServiceConfigurationError ignored) {
                  found.add("?");
                }
              });
      assertTrue(
          found.contains(entry.getValue().implementation()),
          entry.getKey() + " does not discover " + entry.getValue().implementation());
    }
  }

  @Test
  void aCleanFixturePasses(@TempDir Path dir) throws IOException {
    write(dir, Runnable.class.getName(), PlantedRunnable.class.getName());

    assertEquals(
        List.of(),
        check(
            dir,
            Map.of(
                Runnable.class.getName(),
                new Registration(PlantedRunnable.class.getName(), null))));
  }

  @Test
  void anEntryNamingAMissingClassIsReported(@TempDir Path dir) throws IOException {
    String gone = "de.greluc.krt.profit.basetool.keycloak.spi.Gone";
    write(dir, Runnable.class.getName(), gone);

    assertProblem(
        check(dir, Map.of(Runnable.class.getName(), new Registration(gone, null))),
        "does not resolve");
  }

  @Test
  void anEntryNotImplementingTheInterfaceIsReported(@TempDir Path dir) throws IOException {
    write(dir, Runnable.class.getName(), NotRunnable.class.getName());

    assertProblem(
        check(
            dir,
            Map.of(Runnable.class.getName(), new Registration(NotRunnable.class.getName(), null))),
        "does not implement");
  }

  @Test
  void anEntryWithoutAPublicNoArgConstructorIsReported(@TempDir Path dir) throws IOException {
    write(dir, Runnable.class.getName(), NoNoArgRunnable.class.getName());

    assertProblem(
        check(
            dir,
            Map.of(
                Runnable.class.getName(), new Registration(NoNoArgRunnable.class.getName(), null))),
        "cannot be instantiated");
  }

  @Test
  void aChangedProviderIdIsReported(@TempDir Path dir) throws IOException {
    write(dir, ProviderFactory.class.getName(), PlantedFactory.class.getName());

    assertProblem(
        check(
            dir,
            Map.of(
                ProviderFactory.class.getName(),
                new Registration(PlantedFactory.class.getName(), "expected-id"))),
        "has provider id");
  }

  @Test
  void aMissingRegistrationIsReported(@TempDir Path dir) {
    assertProblem(
        check(
            dir,
            Map.of(
                Runnable.class.getName(), new Registration(PlantedRunnable.class.getName(), null))),
        "is not registered");
  }

  @Test
  void anUnexpectedRegistrationIsReported(@TempDir Path dir) throws IOException {
    write(dir, Runnable.class.getName(), PlantedRunnable.class.getName());

    assertProblem(check(dir, Map.of()), "unexpected");
  }

  /**
   * Compares a {@code META-INF/services} directory with the expected registrations.
   *
   * @param dir the directory holding one file per SPI interface
   * @param expected the registration each SPI interface must hold
   * @return one line per problem, empty when every registration loads
   */
  static List<String> check(Path dir, Map<String, Registration> expected) {
    Map<String, List<String>> actual = read(dir);
    List<String> problems = new ArrayList<>();
    for (String spi : new TreeSet<>(actual.keySet())) {
      if (!expected.containsKey(spi)) {
        problems.add(spi + ": unexpected service file " + actual.get(spi));
      }
    }
    for (Map.Entry<String, Registration> entry : new TreeMap<>(expected).entrySet()) {
      String spi = entry.getKey();
      Registration registration = entry.getValue();
      List<String> entries = actual.get(spi);
      if (entries == null || !entries.contains(registration.implementation())) {
        problems.add(spi + ": " + registration.implementation() + " is not registered");
      }
      if (entries == null) {
        continue;
      }
      for (String implementation : entries) {
        problems.addAll(checkEntry(spi, implementation, expected.get(spi)));
      }
    }
    return problems;
  }

  /**
   * Checks one entry of a service file.
   *
   * @param spi the SPI interface's binary name
   * @param implementation the entry's binary name
   * @param registration the expected registration of that interface
   * @return the entry's problems
   */
  private static List<String> checkEntry(
      String spi, String implementation, Registration registration) {
    Class<?> spiType;
    Class<?> type;
    try {
      spiType = Class.forName(spi);
      type = Class.forName(implementation);
    } catch (ClassNotFoundException | LinkageError e) {
      return List.of(spi + ": " + implementation + " does not resolve (" + e + ")");
    }
    if (!spiType.isAssignableFrom(type)) {
      return List.of(spi + ": " + implementation + " does not implement it");
    }
    Object instance;
    try {
      Constructor<?> constructor = type.getConstructor();
      if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
        return List.of(
            spi + ": " + implementation + " cannot be instantiated (not a public class)");
      }
      instance = constructor.newInstance();
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      return List.of(spi + ": " + implementation + " cannot be instantiated (" + e + ")");
    }
    if (registration.id() != null && instance instanceof ProviderFactory<?> factory) {
      String id = factory.getId();
      if (!registration.id().equals(id)) {
        return List.of(
            spi + ": " + implementation + " has provider id " + id + ", not " + registration.id());
      }
    }
    return List.of();
  }

  /**
   * Reads every service file of a directory.
   *
   * @param dir the directory
   * @return the non-blank, non-comment entries by SPI interface name
   */
  private static Map<String, List<String>> read(Path dir) {
    Map<String, List<String>> result = new TreeMap<>();
    if (!Files.isDirectory(dir)) {
      return result;
    }
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        List<String> entries =
            Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(line -> line.replaceFirst("#.*", "").strip())
                .filter(line -> !line.isEmpty())
                .toList();
        result.put(file.getFileName().toString(), entries);
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + dir, e);
    }
    return result;
  }

  /**
   * Loads a class the expectation names.
   *
   * @param name the binary name
   * @return the class
   */
  private static Class<?> load(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(name, e);
    }
  }

  /**
   * Writes one service file.
   *
   * @param dir the services directory
   * @param spi the SPI interface's binary name, the file name
   * @param entry the one entry
   * @throws IOException if the file cannot be written
   */
  private static void write(Path dir, String spi, String entry) throws IOException {
    Files.writeString(dir.resolve(spi), entry + "\n", StandardCharsets.UTF_8);
  }

  /**
   * Asserts that exactly one problem was found and that it names the expected defect.
   *
   * @param problems the problems
   * @param fragment the text the problem must contain
   */
  private static void assertProblem(List<String> problems, String fragment) {
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains(fragment), problems.get(0));
  }

  /**
   * One expected registration.
   *
   * @param implementation the registered class's binary name
   * @param id the provider id it must report, or {@code null} for a type that has none
   */
  record Registration(String implementation, String id) {}

  /** A planted, well-formed registration. */
  public static final class PlantedRunnable implements Runnable {

    /** Does nothing. */
    @Override
    public void run() {}
  }

  /** A planted entry that does not implement the interface it is registered for. */
  public static final class NotRunnable {}

  /** A planted entry the service loader cannot instantiate. */
  public static final class NoNoArgRunnable implements Runnable {

    /**
     * Requires an argument, so no public no-arg constructor exists.
     *
     * @param ignored any value
     */
    public NoNoArgRunnable(int ignored) {}

    /** Does nothing. */
    @Override
    public void run() {}
  }

  /** A planted provider factory whose id differs from the expected one. */
  public static final class PlantedFactory implements ProviderFactory<Provider> {

    /**
     * Creates nothing.
     *
     * @param session the session
     * @return always {@code null}
     */
    @Override
    public Provider create(KeycloakSession session) {
      return null;
    }

    /**
     * Reads no configuration.
     *
     * @param config the scope
     */
    @Override
    public void init(Config.Scope config) {}

    /**
     * Does nothing after start-up.
     *
     * @param factory the session factory
     */
    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    /** Holds nothing to close. */
    @Override
    public void close() {}

    /**
     * Reports an id other than the expected one.
     *
     * @return {@code planted-id}
     */
    @Override
    public String getId() {
      return "planted-id";
    }
  }
}
