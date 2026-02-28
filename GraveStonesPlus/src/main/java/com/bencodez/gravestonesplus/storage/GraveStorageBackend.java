package com.bencodez.gravestonesplus.storage;

import java.util.List;

/**
 * Backend abstraction for grave persistence.
 */
public interface GraveStorageBackend {

	/**
	 * Initialize backend resources (load file, connect DB, create tables).
	 */
	void init();

	/**
	 * Load active (non-broken) graves.
	 *
	 * @return list of grave configs
	 */
	List<GravesConfig> loadGraves();

	/**
	 * Load broken graves.
	 *
	 * @return list of grave configs
	 */
	List<GravesConfig> loadBrokenGraves();

	/**
	 * Save all active graves (bulk write).
	 *
	 * @param graves graves
	 */
	void saveGraves(List<GravesConfig> graves);

	/**
	 * Save all broken graves (bulk write).
	 *
	 * @param graves graves
	 */
	void saveBrokenGraves(List<GravesConfig> graves);

	/**
	 * Insert or update a single grave (incremental write). Default implementation
	 * is no-op (used only by MySQL backend).
	 *
	 * @param grave  grave
	 * @param broken broken flag
	 */
	default void upsertGrave(GravesConfig grave, boolean broken) {
		// no-op
	}

	/**
	 * Update only the broken flag for a single grave (incremental write). Default
	 * implementation is no-op (used only by MySQL backend).
	 *
	 * @param grave  grave
	 * @param broken broken flag
	 */
	default void setBroken(GravesConfig grave, boolean broken) {
		// no-op
	}

	/**
	 * Update an existing grave row (no insert). Intended for state transitions such
	 * as removal. Default implementation is no-op (used only by MySQL backend).
	 *
	 * @param grave  grave config
	 * @param broken whether grave is broken
	 */
	default void updateExistingGrave(GravesConfig grave, boolean broken) {
		// no-op
	}

	/**
	 * Delete a single grave (incremental write). Default implementation is no-op
	 * (used only by MySQL backend).
	 *
	 * @param grave grave
	 */
	default void deleteGrave(GravesConfig grave) {
		// no-op
	}

	/**
	 * Close backend resources.
	 */
	void close();
}