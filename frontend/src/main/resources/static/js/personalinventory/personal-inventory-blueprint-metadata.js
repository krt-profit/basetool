import { formatNumber } from '/js/kernel/krt-number-format.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

/** @typedef {ApiDto<'BlueprintCatalogueMetadata'>} BlueprintMetadata */
/** @typedef {NonNullable<BlueprintMetadata['tiers']>[number]} BlueprintTier */
/** @typedef {NonNullable<BlueprintTier['requirements']>} BlueprintRequirement */
/** @typedef {NonNullable<BlueprintMetadata['unlockingMissions']>[number]} BlueprintMission */
/** @typedef {NonNullable<BlueprintMetadata['loadedSections']>[number]} BlueprintSection */

/**
 * Creates a text-only element in the metadata panel.
 *
 * @param {string} tag element name
 * @param {string} text visible text
 * @param {string} [className] design-system classes
 * @returns {HTMLElement} detached element
 */
function metadataText(tag, text, className) {
    const element = document.createElement(tag);
    element.textContent = text;
    if (className) {
        element.className = className;
    }
    return element;
}

/**
 * Formats a localized metadata message.
 *
 * @param {KrtI18nDict} dict page translations
 * @param {string} key translation key
 * @param {Array<string | number>} [values] positional message values
 * @returns {string} localized message
 */
function metadataMessage(dict, key, values = []) {
    let message = krtI18nText(dict[key], `krtBlueprintsRecipeI18n.${key}`);
    values.forEach((value, index) => {
        message = message.replace(`{${index}}`, String(value));
    });
    return message;
}

/**
 * Accepts only source links on the public Wiki's HTTPS host.
 *
 * @param {string | null | undefined} value source address
 * @returns {string | null} validated address
 */
function wikiMissionUrl(value) {
    if (!value) {
        return null;
    }
    try {
        const url = new URL(value);
        return url.origin === 'https://api.star-citizen.wiki' && !url.username && !url.password
            ? url.href
            : null;
    } catch {
        return null;
    }
}

/**
 * Renders a complete tier requirement, keeping alternative groups visible.
 *
 * @param {BlueprintRequirement} requirement source requirement
 * @param {KrtI18nDict} dict page translations
 * @returns {HTMLElement} requirement list item
 */
function renderSourceRequirement(requirement, dict) {
    const row = document.createElement('li');
    const children = requirement.children || [];
    let label = requirement.name || metadataMessage(dict, 'metadataInputs');
    const quantity = requirement.quantityScu ?? requirement.quantity;
    if (quantity != null) {
        const unit =
            requirement.quantityScu != null
                ? metadataMessage(dict, 'unitScu')
                : metadataMessage(dict, 'unitPiece');
        label += ` · ${formatNumber(quantity, requirement.quantityScu != null ? 3 : 0, document.documentElement.lang)} ${unit}`;
    }
    if (requirement.minQuality != null) {
        label += ` · ${metadataMessage(dict, 'minQuality')} ${requirement.minQuality}`;
    }
    if (requirement.requiredCount != null && children.length > 1) {
        label += ` · ${metadataMessage(dict, 'metadataChoices', [
            requirement.requiredCount,
            children.length,
        ])}`;
    }
    row.appendChild(metadataText('span', label));
    if (children.length) {
        const nested = document.createElement('ul');
        children.forEach((child) => nested.appendChild(renderSourceRequirement(child, dict)));
        row.appendChild(nested);
    }
    return row;
}

/**
 * Formats a source reward probability only within its documented fraction interval.
 *
 * @param {number | null | undefined} chance reward probability
 * @param {KrtI18nDict} dict page translations
 * @returns {string | null} formatted probability, or null without a usable value
 */
function sourceChance(chance, dict) {
    return typeof chance === 'number' && Number.isFinite(chance) && chance >= 0 && chance <= 1
        ? metadataMessage(dict, 'metadataChance', [
              formatNumber(chance * 100, 1, document.documentElement.lang),
          ])
        : null;
}

/**
 * Compares mission references without collapsing distinct source engine identities.
 *
 * @param {BlueprintMission} first individual mission
 * @param {BlueprintMission} second grouped mission
 * @returns {boolean} whether both references identify the same mission
 */
function sameMission(first, second) {
    if (first.debugName && second.debugName) {
        return first.debugName === second.debugName;
    }
    const firstUrl = wikiMissionUrl(first.webUrl);
    const secondUrl = wikiMissionUrl(second.webUrl);
    if (firstUrl && secondUrl) {
        return firstUrl === secondUrl;
    }
    return (
        !!first.title &&
        !!second.title &&
        first.title.trim().toLowerCase() === second.title.trim().toLowerCase() &&
        (!first.rewardScope || !second.rewardScope || first.rewardScope === second.rewardScope)
    );
}

/**
 * Renders one mission as source text with an optional validated browser link.
 *
 * @param {BlueprintMission} mission source mission
 * @param {KrtI18nDict} dict page translations
 * @param {number | null | undefined} [groupChance] chance already displayed on the group
 * @returns {HTMLElement} detached mission row
 */
function renderMission(mission, dict, groupChance) {
    const row = document.createElement('li');
    const title = mission.title || mission.debugName || metadataMessage(dict, 'metadataMission');
    const sourceUrl = wikiMissionUrl(mission.webUrl);
    if (sourceUrl) {
        const link = document.createElement('a');
        link.textContent = title;
        link.href = sourceUrl;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        row.appendChild(link);
    } else {
        row.appendChild(metadataText('span', title));
    }
    const chance = sourceChance(mission.chance, dict);
    if (chance && mission.chance !== groupChance) {
        row.appendChild(metadataText('span', ` · ${chance}`));
    }
    return row;
}

/**
 * Distinguishes explicitly loaded empty source sections from sections that were not loaded.
 *
 * @param {HTMLElement} panel parent source panel
 * @param {string} labelKey translated source section name
 * @param {boolean} loaded whether the source explicitly supplied this section
 * @param {boolean} hasData whether the loaded section contains entries
 * @param {KrtI18nDict} dict page translations
 * @returns {void}
 */
function renderSectionState(panel, labelKey, loaded, hasData, dict) {
    if (!loaded || !hasData) {
        panel.appendChild(
            metadataText(
                'p',
                metadataMessage(dict, loaded ? 'metadataEmpty' : 'metadataNotLoaded', [
                    metadataMessage(dict, labelKey),
                ]),
                'text-muted',
            ),
        );
    }
}

/**
 * Renders grouped unlock chances and individual missions without repeating grouped references.
 *
 * @param {HTMLElement} panel parent source panel
 * @param {BlueprintMetadata} metadata retained source evidence
 * @param {Set<BlueprintSection>} loaded explicitly loaded source sections
 * @param {KrtI18nDict} dict page translations
 * @returns {void}
 */
function renderMissionEvidence(panel, metadata, loaded, dict) {
    panel.appendChild(metadataText('h5', metadataMessage(dict, 'metadataMissions')));
    const groups = loaded.has('MISSION_GROUPS') ? metadata.unlockingMissionGroups || [] : [];
    const missions = loaded.has('MISSIONS') ? metadata.unlockingMissions || [] : [];
    const groupedMissions = groups.flatMap((group) => group.missions || []);
    groups.forEach((group) => {
        let label = group.label || metadataMessage(dict, 'metadataMissionGroup');
        const chance = sourceChance(group.chance, dict);
        if (chance) {
            label += ` · ${chance}`;
        }
        panel.appendChild(metadataText('h6', label));
        const list = document.createElement('ul');
        (group.missions || []).forEach((mission) =>
            list.appendChild(renderMission(mission, dict, group.chance)),
        );
        panel.appendChild(list);
    });
    const individual = missions.filter(
        (mission) => !groupedMissions.some((grouped) => sameMission(mission, grouped)),
    );
    if (individual.length) {
        const list = document.createElement('ul');
        individual.forEach((mission) => list.appendChild(renderMission(mission, dict)));
        panel.appendChild(list);
    }
    renderSectionState(
        panel,
        'metadataIndividualMissions',
        loaded.has('MISSIONS'),
        missions.length > 0,
        dict,
    );
    renderSectionState(
        panel,
        'metadataMissionGroups',
        loaded.has('MISSION_GROUPS'),
        groups.length > 0,
        dict,
    );
}

/**
 * Renders source input names and explicit choice constraints with their load state.
 *
 * @param {HTMLElement} panel parent source panel
 * @param {BlueprintMetadata} metadata retained source evidence
 * @param {Set<BlueprintSection>} loaded explicitly loaded source sections
 * @param {KrtI18nDict} dict page translations
 * @returns {void}
 */
function renderAspectEvidence(panel, metadata, loaded, dict) {
    const supplied = loaded.has('ASPECTS');
    const aspects = supplied ? metadata.aspects?.aspects || [] : [];
    const groups = supplied ? metadata.aspects?.aspectGroups || [] : [];
    panel.appendChild(metadataText('h5', metadataMessage(dict, 'metadataAspects')));
    renderSectionState(
        panel,
        'metadataAspects',
        supplied,
        aspects.length > 0 || groups.length > 0,
        dict,
    );
    if (aspects.length) {
        const list = document.createElement('ul');
        aspects.forEach((aspect) =>
            list.appendChild(
                metadataText(
                    'li',
                    aspect.name || aspect.input?.name || metadataMessage(dict, 'metadataInputs'),
                ),
            ),
        );
        panel.appendChild(list);
    }
    const choices = groups.filter(
        (group) => group.choiceGroup && group.requiredCount != null && group.optionCount != null,
    );
    if (choices.length) {
        const list = document.createElement('ul');
        choices.forEach((group) => {
            const name = group.displayName || group.name || metadataMessage(dict, 'metadataInputs');
            const constraint = metadataMessage(dict, 'metadataChoices', [
                group.requiredCount ?? 0,
                group.optionCount ?? 0,
            ]);
            list.appendChild(metadataText('li', `${name} · ${constraint}`));
        });
        panel.appendChild(list);
    }
}

/**
 * Renders complete source tier trees when loaded and preserves their empty or unknown state.
 *
 * @param {HTMLElement} panel parent source panel
 * @param {BlueprintMetadata} metadata retained source evidence
 * @param {Set<BlueprintSection>} loaded explicitly loaded source sections
 * @param {KrtI18nDict} dict page translations
 * @returns {void}
 */
function renderTierEvidence(panel, metadata, loaded, dict) {
    const supplied = loaded.has('TIERS');
    const tiers = supplied ? metadata.tiers || [] : [];
    renderSectionState(panel, 'metadataTiers', supplied, tiers.length > 0, dict);
    if (!tiers.length) {
        return;
    }
    const detail = document.createElement('details');
    detail.appendChild(
        metadataText('summary', metadataMessage(dict, 'metadataTiers'), 'btn btn-ghost'),
    );
    tiers.forEach((tier, index) => {
        const title = metadataMessage(dict, 'metadataTier', [(tier.tierIndex ?? index) + 1]);
        detail.appendChild(metadataText('h5', title));
        if (tier.craftTimeSeconds != null) {
            detail.appendChild(
                metadataText(
                    'p',
                    metadataMessage(dict, 'metadataTime', [
                        formatNumber(tier.craftTimeSeconds, 0, document.documentElement.lang),
                    ]),
                ),
            );
        }
        if (tier.requirements) {
            const requirements = document.createElement('ul');
            requirements.appendChild(renderSourceRequirement(tier.requirements, dict));
            detail.appendChild(requirements);
        }
    });
    panel.appendChild(detail);
}

/**
 * Renders source evidence and explicit load states alongside the canonical recipe.
 *
 * @param {BlueprintMetadata | null | undefined} metadata retained source evidence
 * @param {KrtI18nDict} dict page translations
 * @returns {HTMLElement | null} source panel, or null without evidence
 */
export function renderBlueprintMetadata(metadata, dict) {
    if (!metadata) {
        return null;
    }
    const panel = document.createElement('section');
    panel.className = 'card card--inset';
    panel.appendChild(metadataText('h4', metadataMessage(dict, 'metadataTitle'), 'card-title'));
    const source = metadata.gameVersion
        ? metadataMessage(dict, 'metadataBuild', [metadata.gameVersion])
        : metadataMessage(dict, 'metadataSource');
    panel.appendChild(metadataText('p', source, 'text-muted'));
    const loaded = new Set(metadata.loadedSections || []);
    renderMissionEvidence(panel, metadata, loaded, dict);
    renderAspectEvidence(panel, metadata, loaded, dict);
    renderTierEvidence(panel, metadata, loaded, dict);
    return panel;
}
