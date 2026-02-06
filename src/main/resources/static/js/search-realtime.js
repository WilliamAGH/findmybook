(function(global) {
    'use strict';

    const PLACEHOLDER_COVER = '/images/placeholder-book-cover.svg';

    function computeQueryHash(queryValue) {
        return (queryValue || '').trim().toLowerCase().replace(/[^a-z0-9-_]/g, '_') || 'search';
    }

    function normalizeRealtimeResult(raw) {
        if (!raw || !raw.id) {
            return null;
        }

        const normalized = { ...raw };
        normalized.slug = normalized.slug || normalized.id;
        normalized.cover = normalized.cover || {};
        normalized.cover.preferredUrl = normalized.cover.preferredUrl
            || normalized.cover.s3ImagePath
            || normalized.cover.externalImageUrl
            || normalized.externalImageUrl
            || null;
        normalized.cover.fallbackUrl = normalized.cover.fallbackUrl
            || normalized.cover.externalImageUrl
            || normalized.externalImageUrl
            || PLACEHOLDER_COVER;
        normalized.qualifiers = normalized.qualifiers || {};
        normalized.qualifiers.externalCandidate = { source: normalized.source || 'EXTERNAL' };
        return normalized;
    }

    function mergeRealtimeCandidates(lastSearchResults, realtimeCandidatesById) {
        if (!lastSearchResults || !Array.isArray(lastSearchResults.results) || !realtimeCandidatesById) {
            return lastSearchResults;
        }
        if (realtimeCandidatesById.size === 0) {
            return lastSearchResults;
        }

        const existingIds = new Set(lastSearchResults.results.map(item => item && item.id).filter(Boolean));
        let newlyAdded = 0;
        realtimeCandidatesById.forEach((candidate, id) => {
            if (!existingIds.has(id)) {
                lastSearchResults.results.push(candidate);
                existingIds.add(id);
                newlyAdded++;
            }
        });

        const baselineTotal = Number.isFinite(lastSearchResults.totalResults)
            ? lastSearchResults.totalResults
            : lastSearchResults.results.length - newlyAdded;
        lastSearchResults.totalResults = baselineTotal + newlyAdded;
        return lastSearchResults;
    }

    function updateRealtimeStatus(payload, loadingIndicatorElement) {
        if (!loadingIndicatorElement) {
            return;
        }
        const loadingText = loadingIndicatorElement.querySelector('p');
        if (!loadingText || !payload || !payload.message) {
            return;
        }
        loadingText.textContent = payload.message;
    }

    function applyRealtimePayload(payload,
                                  queryHash,
                                  activeQueryHash,
                                  realtimeCandidatesById,
                                  lastSearchResults,
                                  onUpdated) {
        if (!payload || !Array.isArray(payload.newResults) || payload.newResults.length === 0) {
            return;
        }
        if (!activeQueryHash || activeQueryHash !== queryHash) {
            return;
        }
        if (!realtimeCandidatesById) {
            return;
        }

        payload.newResults
            .map(normalizeRealtimeResult)
            .filter(Boolean)
            .forEach(candidate => {
                realtimeCandidatesById.set(candidate.id, candidate);
            });

        if (!lastSearchResults || !Array.isArray(lastSearchResults.results)) {
            return;
        }

        mergeRealtimeCandidates(lastSearchResults, realtimeCandidatesById);
        if (typeof onUpdated === 'function') {
            onUpdated(lastSearchResults);
        }
    }

    global.SearchRealtimeUtils = {
        applyRealtimePayload: applyRealtimePayload,
        computeQueryHash: computeQueryHash,
        mergeRealtimeCandidates: mergeRealtimeCandidates,
        normalizeRealtimeResult: normalizeRealtimeResult,
        updateRealtimeStatus: updateRealtimeStatus
    };
})(window);
