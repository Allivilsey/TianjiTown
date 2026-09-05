package org.allivlisey.tianjitown.paper.runtime;

import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;

record TownLandState(TownSnapshot town, List<UUID> members,
                               List<EconomyRepository.TerritoryUnitSnapshot> units) {
}
