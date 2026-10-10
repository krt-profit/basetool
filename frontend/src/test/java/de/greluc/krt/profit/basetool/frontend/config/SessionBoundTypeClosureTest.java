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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.FrontendApplication;
import de.greluc.krt.profit.basetool.frontend.config.SessionBoundTypeScan.SessionWrite;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Derives the exact set of types the frontend can store in the HTTP session and holds the session
 * type allow-list to it (REQ-FE-027, ADR-0206).
 *
 * <p>Every session write of the compiled main classes is resolved to its static type by {@link
 * SessionBoundTypeScan}; the closure over the application types' members must be admitted by the
 * enforcing validator, so a flashed form moved out of {@code frontend.model} fails here instead of
 * being dropped in production. The application part of the closure must equal {@link
 * SessionTypeAllowList#SESSION_BOUND_TYPES}, the exact list the allow-list admits (D-10): a new
 * session-bound type without an entry, and an entry no session write reaches, both fail.
 */
class SessionBoundTypeClosureTest {

  /** Selection floor: session writes in the main classes when the test was added. */
  private static final int MIN_WRITES = 317;

  /** Selection floor: session-bound application types when the test was added. */
  private static final int MIN_APPLICATION_TYPES = 21;

  /**
   * Session writes whose static type the scan cannot determine, resolved by review: {@code site ->
   * sink} to the classes the value really holds.
   *
   * <ul>
   *   <li>{@code BackendRoleSyncFilter#syncRoles}: {@code new ArrayList<>(backend.asserted())}
   *       copies a {@code Set<String>} of authority names.
   * </ul>
   */
  private static final Map<String, Set<String>> REVIEWED =
      Map.of(
          "de.greluc.krt.profit.basetool.frontend.config.BackendRoleSyncFilter#syncRoles"
              + " -> HttpSession.setAttribute",
          Set.of("java.util.ArrayList", "java.lang.String"));

  private final ClassLoader loader = getClass().getClassLoader();

  @Test
  void everySessionWriteHasADeterminedTypeOrAReviewedOne() {
    List<SessionWrite> writes = mainWrites();
    Set<String> unresolved = new TreeSet<>();
    Set<String> unresolvedKeys = new TreeSet<>();
    for (SessionWrite write : writes) {
      if (!write.resolved()) {
        unresolvedKeys.add(key(write));
        if (!REVIEWED.containsKey(key(write))) {
          unresolved.add(write.toString());
        }
      }
    }

    assertThat(writes)
        .as("selection floor: the scan must find at least today's session writes")
        .hasSizeGreaterThanOrEqualTo(MIN_WRITES);
    assertThat(unresolved)
        .as(
            "session writes whose static type does not determine the stored classes; give the"
                + " value a precise type, or add a reviewed entry to REVIEWED")
        .isEmpty();
    assertThat(unresolvedKeys)
        .as("a REVIEWED entry that no longer matches an unresolved write is stale")
        .containsAll(REVIEWED.keySet());
  }

  @Test
  void everySessionBoundTypeIsAdmittedByTheAllowList() {
    List<String> problems = new ArrayList<>();
    Set<String> closure = SessionBoundTypeScan.closure(roots(mainWrites()), loader, problems);

    assertThat(applicationTypes(closure))
        .as("selection floor: session-bound application types")
        .hasSizeGreaterThanOrEqualTo(MIN_APPLICATION_TYPES);
    assertThat(problems)
        .as("members of session-bound types whose static type does not determine the class")
        .isEmpty();
    assertThat(refused(closure))
        .as(
            "session-bound types the enforcing allow-list refuses: under ENFORCE their flash"
                + " attributes would be dropped after every redirect")
        .isEmpty();
  }

  @Test
  void theAllowListNamesExactlyTheSessionBoundTypes() {
    Set<String> closure =
        SessionBoundTypeScan.closure(roots(mainWrites()), loader, new ArrayList<>());

    assertThat(SessionTypeAllowList.SESSION_BOUND_TYPES).doesNotHaveDuplicates();
    assertThat(
            listDifference(
                applicationTypes(closure), Set.copyOf(SessionTypeAllowList.SESSION_BOUND_TYPES)))
        .as(
            "SessionTypeAllowList.SESSION_BOUND_TYPES must name exactly the derived session-bound"
                + " application types; add a missing type there in the same change, remove a"
                + " stale one")
        .isEmpty();
  }

  @Test
  void aPlantedSessionBoundTypeWithoutAnEntryFailsTheExactList() {
    Set<String> closure =
        SessionBoundTypeScan.closure(
            SessionBoundTypeScan.writes(classBytes(FlashingFixture.class), loader)
                .getFirst()
                .types(),
            loader,
            new ArrayList<>());
    Set<String> derived = new TreeSet<>(SessionTypeAllowList.SESSION_BOUND_TYPES);
    derived.addAll(applicationTypes(closure));
    Set<String> listed = new TreeSet<>(SessionTypeAllowList.SESSION_BOUND_TYPES);
    listed.add("de.greluc.krt.profit.basetool.frontend.model.form.RetiredForm");

    assertThat(listDifference(derived, listed))
        .containsExactlyInAnyOrder(
            "missing from SESSION_BOUND_TYPES: " + MovedForm.class.getName(),
            "missing from SESSION_BOUND_TYPES: " + MovedRow.class.getName(),
            "stale in SESSION_BOUND_TYPES: "
                + "de.greluc.krt.profit.basetool.frontend.model.form.RetiredForm");
    assertThat(refused(Set.of(MovedForm.class.getName(), MovedRow.class.getName())))
        .as("the enforcing validator refuses a session-bound type that has no entry")
        .containsExactlyInAnyOrder(MovedForm.class.getName(), MovedRow.class.getName());
  }

  /**
   * Names every type the derivation and the list disagree on.
   *
   * @param derived the session-bound application types derived from the code
   * @param listed the types the allow-list names
   * @return one line per missing or stale entry, sorted
   */
  private static Set<String> listDifference(Set<String> derived, Set<String> listed) {
    Set<String> difference = new TreeSet<>();
    for (String name : derived) {
      if (!listed.contains(name)) {
        difference.add("missing from SESSION_BOUND_TYPES: " + name);
      }
    }
    for (String name : listed) {
      if (!derived.contains(name)) {
        difference.add("stale in SESSION_BOUND_TYPES: " + name);
      }
    }
    return difference;
  }

  @Test
  void nothingElseBindsValuesToTheSession() throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(mainClassesRoot())) {
      for (Path file : tree.filter(p -> p.toString().endsWith(".class")).toList()) {
        found.addAll(SessionBoundTypeScan.otherSessionMechanisms(Files.readAllBytes(file)));
      }
    }

    assertThat(found)
        .as(
            "@SessionAttributes and session-scoped beans store values the closure does not see;"
                + " teach SessionBoundTypeScan about them before using one")
        .isEmpty();
  }

  @Test
  void aPlantedFlashOfATypeOutsideTheModelPackageIsCaught() {
    List<SessionWrite> writes =
        SessionBoundTypeScan.writes(classBytes(FlashingFixture.class), loader);

    assertThat(writes).hasSize(3);
    assertThat(writes.getFirst().types()).containsExactly(MovedForm.class.getName());
    assertThat(writes.getFirst().resolved()).isTrue();
    assertThat(writes.get(1).problems())
        .containsExactly("raw java.util.ArrayList: element types unknown");
    assertThat(writes.get(2).problems())
        .containsExactly("java.lang.Object does not determine the stored class");

    List<String> problems = new ArrayList<>();
    Set<String> closure = SessionBoundTypeScan.closure(writes.getFirst().types(), loader, problems);

    assertThat(problems).isEmpty();
    assertThat(closure)
        .contains(
            MovedForm.class.getName(),
            MovedRow.class.getName(),
            ConcurrentHashMap.class.getName(),
            BigDecimal.class.getName());
    assertThat(refused(closure))
        .containsExactlyInAnyOrder(
            MovedForm.class.getName(), MovedRow.class.getName(), ConcurrentHashMap.class.getName());
  }

  @Test
  void aPlantedSessionAttributesControllerIsCaught() {
    assertThat(
            SessionBoundTypeScan.otherSessionMechanisms(classBytes(SessionAttributesFixture.class)))
        .containsExactly(SessionAttributesFixture.class.getName() + " @SessionAttributes");
  }

  /**
   * The key a reviewed resolution is filed under.
   *
   * @param write the write
   * @return {@code site -> sink}
   */
  private static String key(SessionWrite write) {
    return write.site() + " -> " + write.sink();
  }

  /**
   * The classes the writes store, with the reviewed resolutions in place of the unresolved ones.
   *
   * @param writes the session writes
   * @return the root class names
   */
  private static Set<String> roots(List<SessionWrite> writes) {
    Set<String> roots = new TreeSet<>();
    for (SessionWrite write : writes) {
      roots.addAll(write.resolved() ? write.types() : REVIEWED.getOrDefault(key(write), Set.of()));
    }
    return roots;
  }

  /**
   * The application types of a closure.
   *
   * @param closure the closure
   * @return the names under {@link SessionBoundTypeScan#APPLICATION_PACKAGE}, sorted
   */
  private static Set<String> applicationTypes(Set<String> closure) {
    Set<String> application = new TreeSet<>();
    for (String name : closure) {
      if (name.startsWith(SessionBoundTypeScan.APPLICATION_PACKAGE)) {
        application.add(name);
      }
    }
    return application;
  }

  /**
   * Scans every compiled main class.
   *
   * @return every session write of the frontend's main classes
   */
  private List<SessionWrite> mainWrites() {
    List<SessionWrite> writes = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(mainClassesRoot())) {
      for (Path file : tree.filter(p -> p.toString().endsWith(".class")).toList()) {
        writes.addAll(SessionBoundTypeScan.writes(Files.readAllBytes(file), loader));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return writes;
  }

  /**
   * The directory the frontend's main classes are compiled to.
   *
   * @return the class output root
   */
  private static Path mainClassesRoot() {
    try {
      return Path.of(
          FrontendApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Asks the enforcing session validator, as production builds it, about each class name.
   *
   * @param names the class names
   * @return the names it refuses, sorted
   */
  private Set<String> refused(Set<String> names) {
    Set<String> refused = new TreeSet<>();
    RedisSerializer<Object> enforcing =
        new GenericJacksonJsonRedisSerializer(
            RedisSessionConfig.buildSessionJsonMapper(
                loader,
                SessionTypeAllowList.validatorBuilder(
                    SessionTypeAllowList.Mode.ENFORCE, (name, mode) -> refused.add(name))));
    for (String name : names) {
      byte[] probe =
          ("[\"java.util.ArrayList\",[{\"@class\":\"" + name + "\"}]]")
              .getBytes(StandardCharsets.UTF_8);
      try {
        enforcing.deserialize(probe);
      } catch (RuntimeException expected) {
        Objects.requireNonNull(expected);
      }
    }
    return refused;
  }

  /**
   * Reads a compiled test class.
   *
   * @param type the class
   * @return its class file
   */
  private static byte[] classBytes(Class<?> type) {
    String resource = type.getName().substring(type.getPackageName().length() + 1) + ".class";
    try (InputStream in = type.getResourceAsStream(resource)) {
      return Objects.requireNonNull(in, resource).readAllBytes();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /**
   * A planted flash site: a form outside {@code frontend.model}, a raw list and an untyped value.
   */
  static final class FlashingFixture {

    /**
     * Flashes the three values.
     *
     * @param redirect the redirect attributes
     * @param form the form to re-show
     */
    void flash(RedirectAttributes redirect, MovedForm form) {
      redirect.addFlashAttribute("form", form);
      redirect.addFlashAttribute("rows", new ArrayList<MovedRow>());
      redirect.addFlashAttribute("any", anything());
    }

    /**
     * Returns a value whose static type says nothing.
     *
     * @return a string, typed as {@code Object}
     */
    Object anything() {
      return "value";
    }
  }

  /** A form moved out of {@code frontend.model}, with a nested row type. */
  @Getter
  static class MovedForm {

    /** Nested rows; reached only through the form. */
    private List<MovedRow> rows = new ArrayList<>();

    /** A JDK value the list admits. */
    private BigDecimal amount = BigDecimal.ONE;
  }

  /** A row type reached through {@link MovedForm}, holding a JDK type the list does not admit. */
  @Getter
  static class MovedRow {

    /** A {@code java.util.concurrent} map, outside the {@code java.util} entry. */
    private ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
  }

  /** A planted {@code @SessionAttributes} holder; not a controller, so nothing scans it. */
  @SessionAttributes("form")
  static final class SessionAttributesFixture {}
}
