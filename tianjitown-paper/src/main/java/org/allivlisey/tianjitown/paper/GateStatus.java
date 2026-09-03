package cn.tianji.town.paper;

import java.util.List;

public record GateStatus(State state, List<String> details) {
    public GateStatus {
        details = List.copyOf(details);
    }

    public enum State {
        CHECKING,
        READY,
        LOCKED
    }
}

