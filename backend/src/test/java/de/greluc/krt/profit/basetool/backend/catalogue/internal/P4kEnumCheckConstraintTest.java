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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fails when a single-column {@code CHECK} constraint on a P4K table, as Flyway leaves it, allows
 * other values than the constants of the enum the entity persists there as a string.
 *
 * <p>Several unreleased migrations redefine the same constraint, and only the last one counts, so a
 * later migration that rebuilds a list without an earlier one's values breaks the import at the
 * first insert of such a value.
 */
@SpringBootTest
class P4kEnumCheckConstraintTest {

  /** The tables of the P4K import whose enum columns this test covers. */
  private static final Set<String> P4K_TABLES =
      Set.of(
          "p4k_import_run",
          "p4k_import_run_kind",
          "p4k_import_change",
          "p4k_review_case",
          "shop",
          "starmap_object",
          "ship_type_loadout_port");

  /** Columns that must be found, so a mapping change cannot make the test pass on nothing. */
  private static final Set<String> REQUIRED_COLUMNS =
      Set.of(
          "p4k_import_run_kind.kind",
          "p4k_import_change.kind",
          "p4k_import_change.change_type",
          "p4k_review_case.kind",
          "p4k_review_case.reason",
          "p4k_review_case.status",
          "p4k_review_case.resolution");

  /**
   * The definitions of the single-column check constraints on one column; a multi-column check such
   * as {@code chk_p4k_review_case_resolved} is not a value list.
   */
  private static final String COLUMN_CHECKS =
      """
      SELECT pg_get_constraintdef(c.oid)
      FROM pg_constraint c
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
      WHERE c.contype = 'c'
        AND c.conrelid = to_regclass(?)
        AND cardinality(c.conkey) = 1
        AND a.attname = ?
      """;

  /** A string literal of a constraint definition, such as {@code 'SHOP'::character varying}. */
  private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void everyP4kEnumColumnAllowsExactlyTheEnumsConstants() {
    Map<String, Set<String>> expected = enumColumns();
    Map<String, Set<String>> allowed = new TreeMap<>();
    expected.keySet().forEach(column -> allowed.put(column, allowedValues(column)));

    assertThat(expected.keySet()).containsAll(REQUIRED_COLUMNS);
    assertThat(allowed).containsAllEntriesOf(expected);
  }

  private Map<String, Set<String>> enumColumns() {
    SessionFactoryImplementor sessionFactory =
        entityManagerFactory.unwrap(SessionFactoryImplementor.class);
    Map<String, Set<String>> columns = new TreeMap<>();
    sessionFactory
        .getMappingMetamodel()
        .forEachEntityDescriptor(
            persister -> persister.forEachAttributeMapping(m -> collect(m, columns)));
    return columns;
  }

  /**
   * Records a string-mapped enum column, descending into embedded values and element collections of
   * embeddables, whose columns live in the collection table.
   */
  private static void collect(AttributeMapping mapping, Map<String, Set<String>> columns) {
    if (mapping instanceof PluralAttributeMapping plural
        && plural.getElementDescriptor() instanceof EmbeddableValuedModelPart element) {
      element.getEmbeddableTypeDescriptor().forEachAttributeMapping(m -> collect(m, columns));
    } else if (mapping instanceof EmbeddableValuedModelPart embedded) {
      embedded.getEmbeddableTypeDescriptor().forEachAttributeMapping(m -> collect(m, columns));
    } else if (mapping instanceof BasicValuedModelPart part
        && P4K_TABLES.contains(part.getContainingTableExpression())
        && isStringEnum(mapping)) {
      columns.put(
          part.getContainingTableExpression() + "." + part.getSelectionExpression(),
          constants(mapping.getJavaType().getJavaTypeClass()));
    }
  }

  private static boolean isStringEnum(AttributeMapping mapping) {
    if (!mapping.getJavaType().getJavaTypeClass().isEnum()
        || !(mapping.getPropertyAccess().getGetter().getMember() instanceof Field field)) {
      return false;
    }
    Enumerated enumerated = field.getAnnotation(Enumerated.class);
    return enumerated != null && enumerated.value() == EnumType.STRING;
  }

  private static Set<String> constants(Class<?> enumType) {
    return Arrays.stream(enumType.getEnumConstants())
        .map(constant -> ((Enum<?>) constant).name())
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private Set<String> allowedValues(String column) {
    String[] tableAndColumn = column.split("\\.", 2);
    List<String> definitions =
        jdbcTemplate.queryForList(
            COLUMN_CHECKS, String.class, tableAndColumn[0], tableAndColumn[1]);
    Set<String> values = new TreeSet<>();
    for (String definition : definitions) {
      Matcher literal = LITERAL.matcher(definition);
      while (literal.find()) {
        values.add(literal.group(1));
      }
    }
    return values;
  }
}
