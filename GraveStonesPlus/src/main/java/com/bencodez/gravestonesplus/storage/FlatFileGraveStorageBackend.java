package com.bencodez.gravestonesplus.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat-file backend using Graves.yml via GraveLocations.
 */
public class FlatFileGraveStorageBackend implements GraveStorageBackend {

	private final GraveLocations graveLocations;

	/**
	 * Construct a flat-file backend.
	 *
	 * @param graveLocations file handler
	 */
	public FlatFileGraveStorageBackend(GraveLocations graveLocations) {
		this.graveLocations = graveLocations;
	}

	@Override
	public void init() {
		graveLocations.reloadData();
	}

	@Override
	public List<GravesConfig> loadGraves() {
		List<GravesConfig> list = graveLocations.loadGraves();
		return list != null ? list : new ArrayList<GravesConfig>();
	}

	@Override
	public List<GravesConfig> loadBrokenGraves() {
		List<GravesConfig> list = graveLocations.loadBrokenGraves();
		return list != null ? list : new ArrayList<GravesConfig>();
	}

	@Override
	public void saveGraves(List<GravesConfig> graves) {
		graveLocations.setGrave(graves != null ? graves : new ArrayList<GravesConfig>());
		graveLocations.saveData();
	}

	@Override
	public void saveBrokenGraves(List<GravesConfig> graves) {
		graveLocations.setBrokenGrave(graves != null ? graves : new ArrayList<GravesConfig>());
		graveLocations.saveData();
	}

	@Override
	public void close() {
		// no-op
	}
}