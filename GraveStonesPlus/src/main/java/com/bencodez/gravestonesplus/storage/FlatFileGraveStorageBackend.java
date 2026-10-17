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
	public void saveSnapshot(List<GravesConfig> active, List<GravesConfig> broken) {
		graveLocations.saveSnapshot(active, broken);
	}

    @Override
    public void saveStartupSnapshot(List<GravesConfig> active, List<GravesConfig> broken) {
        // Only detached, owner-prepared records enter this worker-only path.
        var activeRecords = active.stream().map(GravesConfig::preparedRecordForStorage).toList();
        var brokenRecords = broken.stream().map(GravesConfig::preparedRecordForStorage).toList();
        var path = graveLocations.getdFile().toPath();
        try {
            String baseline = java.nio.file.Files.exists(path) ? java.nio.file.Files.readString(path) : "";
            String document = StartupGraveDocument.merge(baseline, activeRecords, brokenRecords);
            com.bencodez.gravestonesplus.graves.DurableFeatureState.publishDocument(path, document);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("Failed to preserve startup grave state", failure);
        }
    }

	@Override
	public void close() {
		// no-op
	}
}
