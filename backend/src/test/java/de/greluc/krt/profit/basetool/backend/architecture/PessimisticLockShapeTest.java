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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Pins that every pessimistic lock of a Spring Data repository is taken in the locking statement
 * itself (ADR-0363): Hibernate falls back to follow-on locking for an outer join, {@code DISTINCT},
 * {@code GROUP BY}, {@code HAVING}, an aggregate or an entity spread over several tables, and a
 * follow-on lock fails with an {@code AssertionFailure} on a row deleted while it waited.
 */
class PessimisticLockShapeTest {

  private static final Set<LockModeType> PESSIMISTIC =
      Set.of(
          LockModeType.PESSIMISTIC_READ,
          LockModeType.PESSIMISTIC_WRITE,
          LockModeType.PESSIMISTIC_FORCE_INCREMENT);

  /** The JPQL that sends a locking query down Hibernate's follow-on path. */
  private static final Pattern FOLLOW_ON_JPQL =
      Pattern.compile(
          "(?i)\\b(LEFT|RIGHT|FULL|OUTER)\\b|\\bDISTINCT\\b|\\bGROUP\\s+BY\\b|\\bHAVING\\b"
              + "|\\b(COUNT|SUM|AVG|MIN|MAX)\\s*\\(");

  /** Fewer lock methods than this means the scan stopped seeing the repositories. */
  private static final int LOCK_METHODS_FLOOR = 25;

  @Test
  void everyPessimisticLockIsTakenInTheLockingStatementItself() {
    List<Method> locks = pessimisticLocks();

    assertThat(locks)
        .as("the lock methods the test looks at")
        .hasSizeGreaterThan(LOCK_METHODS_FLOOR);
    assertThat(locks)
        .extracting(PessimisticLockShapeTest::name)
        .contains("InventoryRowLockRepository#lockById");
    assertThat(locks.stream().flatMap(lock -> problems(lock).stream()).toList()).isEmpty();
  }

  @Test
  void theCheckRefusesEveryFollowOnShape() {
    List<Method> shapes =
        Arrays.stream(FollowOnShapes.class.getDeclaredMethods())
            .sorted(Comparator.comparing(Method::getName))
            .toList();

    assertThat(shapes).hasSize(4).allSatisfy(shape -> assertThat(problems(shape)).hasSize(1));
    assertThat(problems(OrgUnitShapes.class.getDeclaredMethods()[0])).isEmpty();
  }

  /**
   * Collects the pessimistic lock methods of the backend's repository interfaces.
   *
   * @return the methods, sorted by repository and name
   */
  private static @NotNull List<Method> pessimisticLocks() {
    return ModuleSubjects.importBackendMainClasses().stream()
        .filter(JavaClass::isInterface)
        .filter(javaClass -> javaClass.isAssignableTo(Repository.class))
        .map(JavaClass::reflect)
        .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
        .filter(method -> method.isAnnotationPresent(Lock.class))
        .filter(method -> PESSIMISTIC.contains(method.getAnnotation(Lock.class).value()))
        .sorted(Comparator.comparing(PessimisticLockShapeTest::name))
        .toList();
  }

  /**
   * Names the reasons a lock method would take Hibernate's follow-on path.
   *
   * @param lock the lock method
   * @return one entry per reason; empty when the lock is taken in its own statement
   */
  private static @NotNull List<String> problems(@NotNull Method lock) {
    List<String> problems = new ArrayList<>();
    if (lock.isAnnotationPresent(EntityGraph.class)) {
      problems.add(name(lock) + " fetches an entity graph");
    }
    Query query = lock.getAnnotation(Query.class);
    if (query != null && !query.nativeQuery() && FOLLOW_ON_JPQL.matcher(query.value()).find()) {
      problems.add(name(lock) + " needs follow-on locking: " + query.value().strip());
    }
    Optional<Class<?>> entity = domainType(lock.getDeclaringClass());
    if (entity.isEmpty()) {
      problems.add(name(lock) + " has no resolvable domain type");
    } else if (spansSeveralTables(entity.get())) {
      problems.add(name(lock) + " locks " + entity.get().getSimpleName() + " over several tables");
    }
    return problems;
  }

  /**
   * Resolves the entity a repository interface manages from its {@link Repository} type argument.
   *
   * @param repository the repository interface
   * @return the entity class, or empty when no concrete type argument is found
   */
  private static @NotNull Optional<Class<?>> domainType(@NotNull Class<?> repository) {
    for (Type parent : repository.getGenericInterfaces()) {
      if (parent instanceof ParameterizedType parameterized
          && parameterized.getRawType() instanceof Class<?> raw
          && Repository.class.isAssignableFrom(raw)
          && parameterized.getActualTypeArguments()[0] instanceof Class<?> entity) {
        return Optional.of(entity);
      }
      if (parent instanceof Class<?> plain) {
        Optional<Class<?>> inherited = domainType(plain);
        if (inherited.isPresent()) {
          return inherited;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Whether loading an entity joins further tables: a secondary table, or a joined or
   * table-per-class inheritance anywhere above it.
   *
   * @param entity the entity class
   * @return {@code true} when a load of the entity reads more than one table
   */
  private static boolean spansSeveralTables(@NotNull Class<?> entity) {
    for (Class<?> type = entity; type != null; type = type.getSuperclass()) {
      Inheritance inheritance = type.getAnnotation(Inheritance.class);
      if (type.isAnnotationPresent(SecondaryTable.class)
          || type.isAnnotationPresent(SecondaryTables.class)
          || inheritance != null && inheritance.strategy() != InheritanceType.SINGLE_TABLE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Names a method by its repository's simple name and its own.
   *
   * @param method the method
   * @return {@code Repository#method}
   */
  private static @NotNull String name(@NotNull Method method) {
    return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
  }

  /** One lock per follow-on shape, each of which the check must refuse. */
  @NoRepositoryBean
  private interface FollowOnShapes extends Repository<InventoryItem, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i LEFT JOIN FETCH i.material WHERE i.id = :id")
    Optional<InventoryItem> anOuterFetchJoin(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"material"})
    @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
    Optional<InventoryItem> anEntityGraph(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT DISTINCT i FROM InventoryItem i WHERE i.id = :id")
    Optional<InventoryItem> distinctRows(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT count(i) FROM InventoryItem i WHERE i.amount > 0")
    long theCount();
  }

  /** A lock on a single-table hierarchy root, which the check must let through. */
  @NoRepositoryBean
  private interface OrgUnitShapes extends Repository<OrgUnit, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrgUnit o WHERE o.id = :id")
    Optional<OrgUnit> lockById(@Param("id") UUID id);
  }
}
