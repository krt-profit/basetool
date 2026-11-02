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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests of the officer-reach-scoped {@link PromotionTopicRepository} reads against real
 * Postgres: a {@code null} scope spans every squadron, a set restricts to its squadrons.
 */
@SpringBootTest
@Transactional
class PromotionTopicScopedQueriesDataTest {

  @Autowired private PromotionTopicRepository topicRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /** A set scope returns the topics of its squadrons only, in both the list and the paged read. */
  @Test
  void setScope_returnsOnlyTheTopicsOfTheScopedSquadrons() {
    Squadron other = squadronRepository.saveAndFlush(newSquadron());
    UUID ownTopic = saveTopic("own", squadronRepository.getReferenceById(Squadron.IRIDIUM_ID));
    UUID foreignTopic = saveTopic("foreign", other);
    entityManager.flush();
    entityManager.clear();

    Set<UUID> scope = Set.of(Squadron.IRIDIUM_ID);
    List<UUID> listed =
        topicRepository.findAllScoped(scope).stream().map(PromotionTopic::getId).toList();
    List<UUID> paged =
        topicRepository
            .findAllScoped(scope, PageRequest.of(0, 5000))
            .map(PromotionTopic::getId)
            .getContent();

    assertThat(listed).contains(ownTopic).doesNotContain(foreignTopic);
    assertThat(paged).contains(ownTopic).doesNotContain(foreignTopic);
  }

  /** The {@code null} scope (admin "all squadrons" mode) returns the topics of every squadron. */
  @Test
  void nullScope_returnsEveryTopic() {
    Squadron other = squadronRepository.saveAndFlush(newSquadron());
    UUID ownTopic = saveTopic("own", squadronRepository.getReferenceById(Squadron.IRIDIUM_ID));
    UUID foreignTopic = saveTopic("foreign", other);
    entityManager.flush();
    entityManager.clear();

    List<UUID> listed =
        topicRepository.findAllScoped(null).stream().map(PromotionTopic::getId).toList();

    assertThat(listed).contains(ownTopic, foreignTopic);
  }

  private static Squadron newSquadron() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("TopicScope " + suffix);
    squadron.setShorthand("TS" + suffix);
    return squadron;
  }

  private UUID saveTopic(String name, Squadron owner) {
    PromotionTopic topic = new PromotionTopic();
    topic.setName(name + " " + UUID.randomUUID());
    topic.setOwningSquadron(owner);
    return topicRepository.saveAndFlush(topic).getId();
  }
}
