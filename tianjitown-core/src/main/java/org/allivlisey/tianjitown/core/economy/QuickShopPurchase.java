package org.allivlisey.tianjitown.core.economy;

import java.time.Instant;
import java.util.UUID;

/** Purchase evidence; does not identify the account that received the tax. */
public record QuickShopPurchase(long shopId, String shopType, UUID interactingId,
                               long grossMinor, long taxMinor, Instant createdAt) {
}
