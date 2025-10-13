package com.williamcallahan.book_recommendation_engine.service.image;

/**
 * Marker interface for the Longitood cover provider. Historically exposed bespoke
 * caching primitives; those responsibilities now live in CoverPersistenceService.
 */
public interface LongitoodService extends ExternalCoverService {
}
