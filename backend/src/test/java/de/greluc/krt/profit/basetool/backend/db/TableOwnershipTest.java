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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.annotations.Formula;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Holds the table-ownership map of REQ-DATA-020 against the migrated schema: every table has
 * exactly one owning module, and triggers and native SQL reach another module's table only through
 * a listed crossing.
 *
 * <p>The map is {@code architecture/table-ownership.txt}; {@code docs/specs/data-persistence.md}
 * renders it for readers. Every rule is proven able to fail by a fixture planted inside a
 * rolled-back transaction or a test-only class.
 */
@SpringBootTest
class TableOwnershipTest {

  /** The machine-readable map, one {@code table module} pair per line. */
  static final String OWNERSHIP_RESOURCE = "/architecture/table-ownership.txt";

  /** The backend modules of the target architecture that may own a table (plan §5.1). */
  static final Set<String> MODULES =
      Set.of(
          "kernel",
          "platform",
          "audit",
          "notification",
          "livesync",
          "catalogue",
          "identity",
          "orgunit",
          "scope",
          "admin",
          "dashboard",
          "orgchart",
          "promotion",
          "personalinventory",
          "hangar",
          "blueprint",
          "inventory",
          "mission",
          "refinery",
          "joborder",
          "materialexchange",
          "operation",
          "bank",
          "exchange",
          "privacy",
          "app");

  /** Today's table count; the sweep must see at least this many. */
  private static final int TABLE_FLOOR = 117;

  /** Today's user-defined trigger count; the sweep must see at least this many. */
  private static final int TRIGGER_FLOOR = 18;

  /** Today's count of native statements in repositories and entity formulas. */
  private static final int NATIVE_STATEMENT_FLOOR = 30;

  /**
   * A reviewed reach of a trigger or native statement into another module's tables.
   *
   * @param tables the foreign tables it names
   * @param reason why the crossing is accepted
   */
  record Crossing(@NotNull Set<String> tables, @NotNull String reason) {}

  /** The triggers that reach another module's tables, keyed by trigger name. */
  static final Map<String, Crossing> CROSS_MODULE_TRIGGERS =
      Map.of(
          "trg_personal_blueprint_exchange_change",
          new Crossing(
              Set.of("exchange_change", "app_user"),
              "V252 change feed: the exchange's key log is written with the blueprint row"
                  + " (ADR-0224); app_user is read to skip an account being deleted."),
          "trg_default_blueprint_exchange_change",
          new Crossing(
              Set.of("exchange_change", "app_user"),
              "V252 change feed for every owner of a default blueprint's product (ADR-0224)."),
          "trg_inventory_item_exchange_change",
          new Crossing(
              Set.of("exchange_change", "app_user"),
              "V252/V260 change feed: one entry per stock lot a row leaves or joins (ADR-0224)."),
          "trg_ship_exchange_change",
          new Crossing(
              Set.of("exchange_change", "app_user"),
              "V252 change feed: the ship id follows its owner (ADR-0224)."),
          "trg_guard_promotion_topic_owner_kind",
          new Crossing(
              Set.of("org_unit"),
              "Read-only guard that a topic's owning unit is a squadron; a downward read into"
                  + " orgunit."),
          "trg_guard_rank_requirement_owner_kind",
          new Crossing(
              Set.of("org_unit"),
              "Read-only guard that a rank requirement's owning unit is a squadron; a downward"
                  + " read into orgunit."));

  /**
   * The native statements that reach another module's tables, keyed {@code Owner#member}: a
   * repository method or an entity field carrying {@code @Formula}.
   */
  static final Map<String, Crossing> CROSS_MODULE_NATIVE_SQL =
      Map.of(
          "InventoryItemRepository#findExchangeLots",
          new Crossing(
              Set.of("city", "game_item", "location", "material", "space_station"),
              "Read-only join of catalogue names into the exchange's stock lots; a downward read"
                  + " into catalogue."),
          "InventoryItemRepository#findExchangeLotsByKeys",
          new Crossing(
              Set.of("city", "game_item", "location", "material", "space_station"),
              "The keyed variant of findExchangeLots; the same downward read into catalogue."),
          "PersonalBlueprintRepository#grantDefaultBlueprintsToAllUsers",
          new Crossing(
              Set.of("app_user"),
              "Reads the active accounts to grant the default set in one statement; a downward"
                  + " read into identity."));

  /**
   * The only classes allowed to run SQL that no repository declares, each with the reason: the GDPR
   * registries, whose statements span every module by design, and the change-source binding.
   */
  static final Map<String, String> DYNAMIC_NATIVE_SQL_CLASSES =
      Map.of(
          "de.greluc.krt.profit.basetool.backend.service.DataExportService",
          "Runs the Art. 15 export sections of DataExportSections across every module.",
          "de.greluc.krt.profit.basetool.backend.service.PersonSearchService",
          "Runs the person search over the PersonSearchTargets registry across every module.",
          "de.greluc.krt.profit.basetool.backend.service.UserAccountMergeService",
          "Re-points the member-referencing columns of every module during an account merge.",
          "de.greluc.krt.profit.basetool.backend.exchange.internal.ChangeSourceTransactionManager",
          "Binds the change-source setting the V252 triggers read; names no table.");

  private static final String BASE_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  @DisplayName("every table of the schema has exactly one owning module, and no owner is stale")
  void everyTableHasExactlyOneOwner() {
    SchemaCatalog schema = SchemaCatalog.read(new JdbcTemplate(dataSource));
    assertThat(schema.tables())
        .as("tables in the schema (a sweep matching none would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(TABLE_FLOOR);

    assertThat(duplicateOwners(readOwnershipLines()))
        .as("tables listed more than once in %s; a table has exactly one owner", OWNERSHIP_RESOURCE)
        .isEmpty();
    Map<String, String> owners = ownership();
    assertThat(new TreeSet<>(owners.values()))
        .as("owners that are not a module of the target architecture (plan §5.1)")
        .isSubsetOf(MODULES);
    assertThat(unownedTables(schema, owners))
        .as(
            """
            Tables without an owner. Add each to %s and to the ownership table of \
            docs/specs/data-persistence.md, naming the module whose aggregate it stores.\
            """,
            OWNERSHIP_RESOURCE)
        .isEmpty();
    Set<String> stale = new TreeSet<>(owners.keySet());
    stale.removeAll(schema.tables());
    assertThat(stale).as("owned tables that no longer exist in the schema").isEmpty();
  }

  @Test
  @DisplayName("triggers reach another module's tables only through a listed crossing")
  void triggersCrossModulesOnlyThroughListedCrossings() {
    SchemaCatalog schema = SchemaCatalog.read(new JdbcTemplate(dataSource));
    assertThat(schema.triggers())
        .as("user-defined triggers (an emptied selection would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(TRIGGER_FLOOR);

    Map<String, Set<String>> crossings = triggerCrossings(schema, ownership());
    assertThat(crossings)
        .as(
            """
            Triggers that reach another module's tables, against CROSS_MODULE_TRIGGERS. A trigger \
            is a write path the owning module cannot see: move the reaction into the owner's \
            module API, or list the crossing with its reason.\
            """)
        .isEqualTo(expectedTables(CROSS_MODULE_TRIGGERS));
  }

  @Test
  @DisplayName("native statements reach another module's tables only through a listed crossing")
  void nativeStatementsCrossModulesOnlyThroughListedCrossings() {
    Map<String, String> owners = ownership();
    Set<String> tables = SchemaCatalog.read(new JdbcTemplate(dataSource)).tables();
    Map<String, NativeStatement> statements = new TreeMap<>();
    for (Class<?> repository : repositoryInterfaces()) {
      statements.putAll(nativeStatementsOf(repository));
    }
    statements.putAll(formulaStatements());
    assertThat(statements)
        .as("native statements found (an emptied selection would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(NATIVE_STATEMENT_FLOOR);

    Map<String, Set<String>> crossings = nativeCrossings(statements, tables, owners);
    assertThat(crossings)
        .as(
            """
            Native statements that reach another module's tables, against \
            CROSS_MODULE_NATIVE_SQL. JPQL and the module API keep a dependency visible; a native \
            string does not. Use the owner's API, or list the crossing with its reason.\
            """)
        .isEqualTo(expectedTables(CROSS_MODULE_NATIVE_SQL));
  }

  @Test
  @DisplayName("SQL outside repositories runs only in the listed registry classes")
  void dynamicNativeSqlRunsOnlyInListedClasses() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
    Set<String> callers = dynamicSqlCallers(production);
    assertThat(callers)
        .as(
            """
            Classes that run SQL no repository declares, against DYNAMIC_NATIVE_SQL_CLASSES. Such \
            a string can name any module's table unseen; declare the statement on the owning \
            module's repository instead.\
            """)
        .isEqualTo(new TreeSet<>(DYNAMIC_NATIVE_SQL_CLASSES.keySet()));
  }

  @Test
  @DisplayName("proof: a planted table without an owner is reported")
  void aPlantedTableWithoutAnOwnerIsReported() throws SQLException {
    Set<String> unowned =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute("CREATE TABLE g10_planted_unowned (id UUID PRIMARY KEY)");
              return unownedTables(SchemaCatalog.read(jdbc), ownership());
            });
    assertThat(unowned).containsExactly("g10_planted_unowned");
  }

  @Test
  @DisplayName("proof: a duplicate owner line is reported")
  void aDuplicateOwnerLineIsReported() {
    assertThat(duplicateOwners(List.of("city catalogue", "city hangar", "ship hangar")))
        .containsExactly("city");
  }

  @Test
  @DisplayName("proof: a planted trigger writing another module's table is reported")
  void aPlantedCrossModuleTriggerIsReported() throws SQLException {
    Map<String, Set<String>> crossings =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute(
                  """
                  CREATE FUNCTION g10_planted_city_changed() RETURNS TRIGGER AS $$
                  BEGIN
                      INSERT INTO system_setting (key, value) VALUES ('g10', NEW.name);
                      RETURN NULL;
                  END;
                  $$ LANGUAGE plpgsql
                  """);
              jdbc.execute(
                  """
                  CREATE TRIGGER g10_planted_city_trigger AFTER INSERT ON city
                  FOR EACH ROW EXECUTE FUNCTION g10_planted_city_changed()
                  """);
              return triggerCrossings(SchemaCatalog.read(jdbc), ownership());
            });
    assertThat(crossings).containsEntry("g10_planted_city_trigger", Set.of("system_setting"));
  }

  @Test
  @DisplayName("proof: a planted native query reaching another module's table is reported")
  void aPlantedCrossModuleNativeQueryIsReported() {
    Set<String> tables = SchemaCatalog.read(new JdbcTemplate(dataSource)).tables();
    Map<String, Set<String>> crossings =
        nativeCrossings(nativeStatementsOf(PlantedCityRepository.class), tables, ownership());
    assertThat(crossings)
        .containsEntry(
            PlantedCityRepository.class.getSimpleName() + "#countAuditRows", Set.of("audit_event"))
        .doesNotContainKey(PlantedCityRepository.class.getSimpleName() + "#countCities");
  }

  @Test
  @DisplayName("proof: a planted class running SQL outside a repository is reported")
  void aPlantedDynamicSqlCallerIsReported() {
    JavaClasses planted = new ClassFileImporter().importClasses(PlantedNativeSqlCaller.class);
    assertThat(dynamicSqlCallers(planted)).containsExactly(PlantedNativeSqlCaller.class.getName());
  }

  /**
   * Reads the ownership map.
   *
   * @return table mapped to its owning module
   */
  static @NotNull Map<String, String> ownership() {
    Map<String, String> owners = new TreeMap<>();
    for (String line : readOwnershipLines()) {
      String[] parts = line.split("\\s+");
      owners.put(parts[0], parts[1]);
    }
    return owners;
  }

  private static List<String> readOwnershipLines() {
    InputStream in = TableOwnershipTest.class.getResourceAsStream(OWNERSHIP_RESOURCE);
    Objects.requireNonNull(in, OWNERSHIP_RESOURCE);
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          lines.add(line.strip());
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(OWNERSHIP_RESOURCE, e);
    }
    return lines;
  }

  private static Set<String> duplicateOwners(List<String> lines) {
    Set<String> seen = new TreeSet<>();
    Set<String> duplicates = new TreeSet<>();
    for (String line : lines) {
      String table = line.split("\\s+")[0];
      if (!seen.add(table)) {
        duplicates.add(table);
      }
    }
    return duplicates;
  }

  private static Set<String> unownedTables(SchemaCatalog schema, Map<String, String> owners) {
    Set<String> unowned = new TreeSet<>(schema.tables());
    unowned.removeAll(owners.keySet());
    return unowned;
  }

  private static Map<String, Set<String>> triggerCrossings(
      SchemaCatalog schema, Map<String, String> owners) {
    Map<String, Set<String>> crossings = new TreeMap<>();
    for (SchemaCatalog.Trigger trigger : schema.triggers()) {
      String owner = owners.get(trigger.table());
      Set<String> foreign = new TreeSet<>();
      for (String source : schema.reachableFunctions(trigger.function()).values()) {
        for (String table : SqlReferences.tables(source, schema.tables())) {
          if (!Objects.equals(owners.get(table), owner)) {
            foreign.add(table);
          }
        }
      }
      if (!foreign.isEmpty()) {
        crossings.put(trigger.name(), foreign);
      }
    }
    return crossings;
  }

  /**
   * One native statement and the table whose module owns it.
   *
   * @param ownerTable the table of the repository's entity or of the entity carrying the formula
   * @param sql the statement
   */
  private record NativeStatement(String ownerTable, String sql) {}

  private Map<String, NativeStatement> nativeStatementsOf(Class<?> repository) {
    Class<?> entity =
        ResolvableType.forClass(repository).as(Repository.class).getGeneric(0).resolve();
    if (entity == null) {
      return Map.of();
    }
    String ownerTable = tableOf(entity);
    Map<String, NativeStatement> statements = new LinkedHashMap<>();
    for (Method method : repository.getDeclaredMethods()) {
      Query query = method.getAnnotation(Query.class);
      NativeQuery nativeQuery = method.getAnnotation(NativeQuery.class);
      String sql = null;
      if (query != null && query.nativeQuery()) {
        sql = query.value();
      } else if (nativeQuery != null) {
        sql = nativeQuery.value();
      }
      if (sql != null) {
        statements.put(
            repository.getSimpleName() + "#" + method.getName(),
            new NativeStatement(ownerTable, sql));
      }
    }
    return statements;
  }

  private Map<String, NativeStatement> formulaStatements() {
    Map<String, NativeStatement> statements = new LinkedHashMap<>();
    for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
      Class<?> type = entity.getJavaType();
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          Formula formula = field.getAnnotation(Formula.class);
          if (formula != null) {
            statements.put(
                c.getSimpleName() + "#" + field.getName(),
                new NativeStatement(tableOf(type), formula.value()));
          }
        }
      }
    }
    return statements;
  }

  private String tableOf(Class<?> entity) {
    return entityManagerFactory
        .unwrap(SessionFactoryImplementor.class)
        .getMappingMetamodel()
        .getEntityDescriptor(entity)
        .getMappedTableDetails()
        .getTableName()
        .toLowerCase(Locale.ROOT);
  }

  private static Map<String, Set<String>> nativeCrossings(
      Map<String, NativeStatement> statements, Set<String> tables, Map<String, String> owners) {
    Map<String, Set<String>> crossings = new TreeMap<>();
    statements.forEach(
        (key, statement) -> {
          String owner = owners.get(statement.ownerTable());
          Set<String> foreign = new TreeSet<>();
          for (String table : SqlReferences.tables(statement.sql(), tables)) {
            if (!Objects.equals(owners.get(table), owner)) {
              foreign.add(table);
            }
          }
          if (!foreign.isEmpty()) {
            crossings.put(key, foreign);
          }
        });
    return crossings;
  }

  private static Collection<Class<?>> repositoryInterfaces() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
    List<Class<?>> repositories = new ArrayList<>();
    for (JavaClass c : production) {
      if (c.isInterface() && c.isAssignableTo(Repository.class)) {
        repositories.add(c.reflect());
      }
    }
    return repositories;
  }

  private static Set<String> dynamicSqlCallers(JavaClasses classes) {
    Set<String> callers = new TreeSet<>();
    for (JavaClass c : classes) {
      for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
        if (isDynamicSql(call)) {
          callers.add(c.getName());
        }
      }
    }
    return callers;
  }

  private static boolean isDynamicSql(JavaMethodCall call) {
    String owner = call.getTargetOwner().getName();
    return call.getName().startsWith("createNative")
        || owner.equals("org.springframework.jdbc.core.JdbcTemplate")
        || owner.equals("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
        || owner.equals("org.springframework.jdbc.core.simple.JdbcClient")
        || owner.equals("java.sql.Statement")
        || owner.equals("java.sql.Connection");
  }

  private static Map<String, Set<String>> expectedTables(Map<String, Crossing> crossings) {
    Map<String, Set<String>> expected = new TreeMap<>();
    crossings.forEach((key, crossing) -> expected.put(key, new TreeSet<>(crossing.tables())));
    return expected;
  }

  /** A planted repository with one native query inside its module and one reaching audit. */
  interface PlantedCityRepository extends Repository<City, UUID> {

    /**
     * Counts cities.
     *
     * @return the count
     */
    @Query(value = "SELECT count(*) FROM city", nativeQuery = true)
    long countCities();

    /**
     * Counts audit rows from the catalogue module.
     *
     * @return the count
     */
    @Query(value = "SELECT count(*) FROM audit_event", nativeQuery = true)
    long countAuditRows();
  }

  /** A planted class that runs SQL outside any repository. */
  static final class PlantedNativeSqlCaller {

    private PlantedNativeSqlCaller() {}

    /**
     * Runs a native statement through the entity manager.
     *
     * @param entityManager the entity manager
     * @return the result
     */
    static Object run(EntityManager entityManager) {
      return entityManager.createNativeQuery("SELECT 1").getSingleResult();
    }
  }
}
