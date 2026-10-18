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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

/**
 * Broadcasts changed section keys to a topic's peers; a no-op without a topic or a live channel.
 *
 * @param {string | null | undefined} topic the topic room
 * @param {string | string[]} keys the changed section keys
 */
function publish(topic, keys) {
    if (topic && typeof krtLiveSync.sendChanged === 'function') {
        krtLiveSync.sendChanged(topic, keys);
    }
}

/**
 * Declares a page's sections once and returns the seam that owns the whole live-update
 * choreography: the write with its 409 handling and version propagation, the in-place re-render,
 * the peer broadcast and the receiver for a peer's change (REQ-FE-001…010, ADR-0289).
 *
 * The dict, topic, pageUrl and broadcast callbacks are re-evaluated on every call. The receiver is
 * built here when `config.receiver` is given and the topic (or an explicit event) is known.
 *
 * @param {KrtPageSectionsConfig} config the page's section declaration
 * @returns {KrtPageSections} the page's section seam
 */
function create(config) {
    const sections = config.sections;
    const alsoBroadcast = config.alsoBroadcast || [];

    /** @returns {string | null} the topic room, or null while the page has no entity id */
    function topicOf() {
        const topic = typeof config.topic === 'function' ? config.topic() : config.topic;
        return topic || null;
    }

    /** @param {string[]} keys the changed section keys */
    function broadcast(keys) {
        if (typeof config.broadcast === 'function') {
            config.broadcast(keys);
            return;
        }
        publish(topicOf(), alsoBroadcast.length ? keys.concat(alsoBroadcast) : keys);
    }

    /**
     * @param {string | string[]} sectionKeys one key or a list
     * @returns {string[]} the list
     */
    function asList(sectionKeys) {
        return Array.isArray(sectionKeys) ? sectionKeys : [sectionKeys];
    }

    /**
     * Re-renders one or more sections in place and broadcasts the change once. Sections that share a
     * container and fragment are fetched once.
     *
     * @param {string | string[]} sectionKeys the sections to re-render
     * @param {KrtSectionRefreshOpts} [opts] `broadcast: false` suppresses the peer broadcast
     * @returns {Promise<boolean[]>} one success flag per section
     */
    function refresh(sectionKeys, opts) {
        const list = asList(sectionKeys);
        if (!opts || opts.broadcast !== false) {
            broadcast(list);
        }
        /** @type {Map<string, Promise<boolean>>} */
        const swapped = new Map();
        return Promise.all(
            list.map((sectionKey) => {
                const cfg = sections[sectionKey];
                const url = cfg ? config.pageUrl() : null;
                if (!cfg || !url || !document.querySelector(cfg.container)) {
                    return Promise.resolve(false);
                }
                const swapKey = `${cfg.container}|${cfg.fragmentValue || ''}`;
                const pending = swapped.get(swapKey);
                if (pending) {
                    return pending;
                }
                const dict = /** @type {KrtI18nDict} */ ((config.dict && config.dict()) || {});
                const swap = krtFetch.swap({
                    url,
                    container: cfg.container,
                    fragmentValue: cfg.fragmentValue,
                    history: false,
                    preserveScroll: true,
                    errorMessage: dict[(config.keys || {}).refreshErrorKey] || '',
                });
                swapped.set(swapKey, swap);
                return swap;
            }),
        );
    }

    /**
     * Broadcasts without re-rendering, for handlers that already patched the DOM.
     *
     * @param {string | string[]} sectionKeys the changed sections
     */
    function notify(sectionKeys) {
        broadcast(asList(sectionKeys));
    }

    /**
     * Writes through {@link KrtFetchApi.write} with the localized strings derived from the section
     * key. A 409 shows the conflict confirm and neither re-renders nor broadcasts.
     *
     * @param {KrtWriteOpts} opts the write options; `propagateVersion` writes the response version
     *     to every `[data-version]` of the section, `refresh` re-renders and broadcasts those
     *     sections after a 2xx and before `onSuccess`
     * @returns {Promise<KrtWriteResult>} the outcome
     */
    function write(opts) {
        /** @type {KrtI18nDict} */
        const dict = (config.dict && config.dict()) || {};

        /**
         * @param {string | undefined} key the dictionary key
         * @returns {string | undefined} the localized string, undefined for an unnamed key
         */
        function t(key) {
            if (!key) return undefined;
            return krtI18nText(dict[key], `${config.dictName || 'dict'}[${key}]`);
        }
        const key = opts.sectionKey || '';
        const k = config.keys || {};
        const own = sections[key];
        const refreshKeys = opts.refresh ? asList(opts.refresh) : null;
        const userSuccess = opts.onSuccess;
        return krtFetch.write(
            Object.assign({}, opts, {
                serialize: opts.serialize || (key ? `section:${key}` : undefined),
                containerSelector:
                    opts.containerSelector ||
                    (opts.propagateVersion && own ? own.container : undefined),
                sectionLabel: k.saveSectionPrefix ? t(k.saveSectionPrefix + key) : undefined,
                conflictSectionLabel: k.conflictSectionPrefix
                    ? t(k.conflictSectionPrefix + key)
                    : undefined,
                successMessage: t(k.successKey) || opts.successMessage,
                errorMessage: t(k.errorKey) || opts.errorMessage,
                conflict: {
                    title: t(k.conflictTitleKey),
                    reloadLabel: t(k.reloadLabelKey),
                    dismissLabel: t(k.dismissLabelKey),
                    reloadQuestion: t(k.reloadQuestionKey),
                    reloadDetailFallback: t(k.reloadDetailKey),
                },
                onSuccess: refreshKeys
                    ? async (/** @type {any} */ body) => {
                          await refresh(refreshKeys);
                          if (typeof userSuccess === 'function') {
                              return userSuccess(body);
                          }
                      }
                    : userSuccess,
            }),
        );
    }

    /** @type {KrtReceiver | null} */
    let receiver = null;
    const receiverCfg = config.receiver;
    if (
        receiverCfg &&
        (topicOf() ||
            receiverCfg.retargetable ||
            (receiverCfg.events && receiverCfg.events.changed))
    ) {
        const expand = receiverCfg.expand;
        receiver = krtLiveSync.createReceiver({
            topic: topicOf() || undefined,
            sections,
            coalesceMs: receiverCfg.coalesceMs,
            events: receiverCfg.events,
            pill: receiverCfg.pill,
            busyTest: receiverCfg.busyTest,
            /** @param {string[]} keys the sections a peer changed */
            refresh(keys) {
                refresh(typeof expand === 'function' ? expand(keys) : keys, { broadcast: false });
            },
        });
    }

    return { write, refresh, notify, receiver, sections };
}

/** @type {KrtPageSectionsApi} */
export const krtPageSections = { create, publish };
