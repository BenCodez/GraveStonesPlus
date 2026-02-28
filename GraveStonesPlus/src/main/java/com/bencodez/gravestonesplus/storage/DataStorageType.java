package com.bencodez.gravestonesplus.storage;

/**
 * Supported storage types for grave persistence.
 */
public enum DataStorageType {
	FLATFILE,
	MYSQL;

	/**
	 * Parse a storage type from config.
	 *
	 * @param value string value
	 * @return parsed type, defaults to FLATFILE
	 */
	public static DataStorageType parse(String value) {
		if (value == null) {
			return FLATFILE;
		}
		try {
			return DataStorageType.valueOf(value.trim().toUpperCase());
		} catch (Exception e) {
			return FLATFILE;
		}
	}
}