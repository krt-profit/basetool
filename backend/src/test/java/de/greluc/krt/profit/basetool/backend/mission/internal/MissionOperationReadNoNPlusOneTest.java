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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionListDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionOperationDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.operation.internal.Operation;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationRepository;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies against real Postgres that a page of missions embeds each mission's operation with the
 * same number of SQL statements whether it holds a few or many missions, and that the embedded
 * operation carries every field of {@link MissionOperationDto} (REQ-DATA-003, plan §7.5 P3-8a).
 */
@SpringBootTest
class MissionOperationReadNoNPlusOneTest {

  private static final int FEW = 3;

  private static final int MANY = 25;

  private static final int STATEMENT_BOUND = 12;

  @Autowired private MissionService missionService;
  @Autowired private MissionMapper missionMapper;
  @Autowired private MissionRepository missionRepository;
  @Autowired private OperationRepository operationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private PlatformTransactionManager transactionManager;

  private Squadron unit;

  private final List<Mission> missions = new ArrayList<>();

  private final List<Operation> operations = new ArrayList<>();

  @BeforeEach
  void signInAsAdmin() {
    String tag = UUID.randomUUID().toString().substring(0, 6);
    Squadron squadron = new Squadron();
    squadron.setName("Embed Unit " + tag);
    squadron.setShorthand("M" + tag);
    squadron.setProfitEligible(true);
    unit = squadronRepository.save(squadron);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("mission-operation-reads")
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void signOutAndCleanUp() {
    SecurityContextHolder.clearContext();
    for (Mission mission : missions) {
      missionRepository.deleteById(mission.getId());
    }
    for (Operation operation : operations) {
      operationRepository.deleteById(operation.getId());
    }
    missions.clear();
    operations.clear();
    squadronRepository.deleteById(unit.getId());
  }

  @Test
  void aPageOfMissionsEmbedsItsOperationsWithAStatementCountIndependentOfItsSize() {
    String fewTag = seed(FEW);
    String manyTag = seed(MANY);
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    stats.clear();
    List<MissionListDto> few = listRows(fewTag);
    long fewStatements = stats.getPrepareStatementCount();
    stats.clear();
    List<MissionListDto> many = listRows(manyTag);
    long manyStatements = stats.getPrepareStatementCount();

    assertThat(few).hasSize(FEW + 1);
    assertThat(many).hasSize(MANY + 1);
    assertThat(many)
        .filteredOn(row -> row.operation() != null)
        .hasSize(MANY)
        .allSatisfy(
            row -> {
              assertThat(row.operation().name()).startsWith("Embed Op");
              assertThat(row.operation().owningSquadron().id()).isEqualTo(unit.getId());
            });
    assertThat(many).filteredOn(row -> row.operation() == null).hasSize(1);
    assertThat(manyStatements)
        .as("statements for " + MANY + " missions against " + FEW + " (N+1)")
        .isEqualTo(fewStatements)
        .isLessThanOrEqualTo(STATEMENT_BOUND);
  }

  @Test
  void theEmbeddedOperationCarriesEveryFieldOfTheOperationDto() {
    seed(1);
    Operation operation = operations.getFirst();
    Mission linked = missions.getFirst();
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    MissionOperationDto embedded =
        readOnly.execute(
            _ -> {
              Mission loaded = missionRepository.findById(linked.getId()).orElseThrow();
              MissionDto dto = missionMapper.toDto(loaded);
              return dto.operation();
            });

    assertThat(embedded).isNotNull();
    assertThat(embedded.id()).isEqualTo(operation.getId());
    assertThat(embedded.name()).isEqualTo(operation.getName());
    assertThat(embedded.description()).isEqualTo("Embedded description");
    assertThat(embedded.status()).isEqualTo(OperationStatus.ACTIVE.name());
    assertThat(embedded.owningSquadron())
        .isEqualTo(new SquadronReferenceDto(unit.getId(), unit.getName(), unit.getShorthand()));
    assertThat(embedded.version()).isNotNull();
    assertThat(embedded.createdAt()).isNotNull();
    assertThat(embedded.updatedAt()).isNotNull();
    assertThat(embedded.payoutPreliminary()).isNull();
  }

  @Test
  void aMissionWithoutAnOperationEmbedsNone() {
    Mission lone = mission("Embed Lone " + UUID.randomUUID(), null);
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    MissionDto dto =
        readOnly.execute(
            _ -> missionMapper.toDto(missionRepository.findById(lone.getId()).orElseThrow()));

    assertThat(dto).isNotNull();
    assertThat(dto.operation()).isNull();
  }

  private List<MissionListDto> listRows(String tag) {
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    return readOnly.execute(
        _ ->
            missionService
                .searchMissions(tag, null, null, null, null, null, PageRequest.of(0, 100))
                .map(mission -> missionMapper.toListDto(mission, 0L, false))
                .getContent());
  }

  private String seed(int linkedMissions) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    for (int i = 0; i < linkedMissions; i++) {
      Operation operation = new Operation();
      operation.setName("Embed Op " + tag + " " + i);
      operation.setDescription("Embedded description");
      operation.setStatus(OperationStatus.ACTIVE);
      operation.setOwningOrgUnit(unit);
      operation = operationRepository.save(operation);
      operations.add(operation);
      mission("Embed " + tag + " " + i, operation);
    }
    mission("Embed " + tag + " lone", null);
    return tag;
  }

  private Mission mission(String name, Operation operation) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setStatus("PLANNED");
    mission.setOwningOrgUnit(unit);
    mission.setOperationId(operation == null ? null : operation.getId());
    Mission saved = missionRepository.save(mission);
    missions.add(saved);
    return saved;
  }
}
