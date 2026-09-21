package org.allivlisey.tianjitown.storage.town;

import java.util.UUID;

/** Durable boundary between an application and non-transactional Vault payments. */
public record ApplicationFeeOperation(UUID applicationId, UUID applicantId, long amountMinor,
        State state, String detail, long version) {
    public enum State {
        UNPAID, COLLECTING, COLLECTION_UNKNOWN, ESCROWED,
        PLAYER_REFUND_PENDING, PLAYER_REFUNDING, PLAYER_REFUND_UNKNOWN,
        REFUND_PENDING, REFUNDING, REFUND_UNKNOWN, REFUNDED
    }

    public enum Outcome { SUCCESS, FAILED, UNKNOWN, PLAYER_REFUND_REQUIRED }

    /** Assertions about verified external balances, never instructions to blindly repeat payment. */
    public enum Resolution {
        COLLECTED, NO_PAYMENT, PLAYER_DEBIT_ONLY, REFUNDED, REFUND_NOT_PAID
    }
}
