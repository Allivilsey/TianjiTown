package org.allivlisey.tianjitown.paper.station;

import org.allivlisey.tianjitown.storage.station.StationRecord;
import org.allivlisey.tianjitown.storage.station.StationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Immutable read snapshot for event handlers; mutations run on a worker after SQL succeeds. */
public final class StationDirectory {
    private final StationRepository repository;
    private volatile List<StationRecord> records = List.of();

    public StationDirectory(StationRepository repository) {
        this.repository = repository;
    }

    public synchronized void load() {
        records = repository.load();
    }

    public List<StationRecord> records() {
        return records;
    }

    public synchronized boolean insert(StationRecord record) {
        if (!repository.insert(record)) return false;
        List<StationRecord> updated = new ArrayList<>(records);
        updated.add(record);
        records = List.copyOf(updated);
        return true;
    }

    public synchronized void deleteAt(UUID world, int x, int y, int z) {
        repository.deleteAt(world, x, y, z);
        records = records.stream().filter(record -> !record.sameLocation(world, x, y, z)).toList();
    }
}
