package org.allivlisey.tianjitown.integrations.quickshop;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuickShopDirectTaxTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void clearsOnlyMatchingRecipientAndPostsOnlySuccessfulTax(boolean selling) throws Exception {
        var owner = new User(UUID.randomUUID());
        var customer = new User(UUID.randomUUID());
        var receiver = selling ? owner : customer;
        var shop = new Shop(selling, owner);
        UUID townId = UUID.randomUUID();
        List<QuickShopTaxAdapter.SuccessfulTax> posted = new ArrayList<>();
        var adapter = new QuickShopTaxAdapter(mock(Plugin.class), mock(Plugin.class), () -> true,
                id -> id.equals(receiver.id) ? new QuickShopTaxAdapter.TaxPolicy(townId, 500) : null,
                posted::add, 2, (key, values) -> key);
        var type = QuickShopTaxAdapter.class.getDeclaredField("qUserType");
        type.setAccessible(true);
        type.set(adapter, User.class);
        var event = new TradeEvent(shop, customer, new Transaction(receiver));
        invoke(adapter, "onTax", event);
        assertEquals(0.05, selling ? event.shopTax : event.interactorTax);
        var unrelated = new TradeEvent(shop, customer, new Transaction(new User(UUID.randomUUID())));
        invoke(adapter, "onTransaction", unrelated);
        assertNotNull(unrelated.transaction.taxer);
        invoke(adapter, "onTransaction", event);
        assertNull(event.transaction.taxer);
        assertTrue(posted.isEmpty()); // No successful trade event means no town credit.
        invoke(adapter, "onSuccess", event);
        invoke(adapter, "onSuccess", event);
        assertEquals(1, posted.size());
        assertEquals(500, posted.getFirst().taxMinor());
        assertEquals(10000, posted.getFirst().grossMinor());
        assertEquals(townId, posted.getFirst().townId());
    }

    @Test void nonMemberDoesNotRedirectOtherPluginsTaxAccount() throws Exception {
        var owner = new User(UUID.randomUUID());
        var event = new TradeEvent(new Shop(true, owner), new User(UUID.randomUUID()), new Transaction(owner));
        var adapter = new QuickShopTaxAdapter(mock(Plugin.class), mock(Plugin.class), () -> true,
                id -> null, ignored -> fail("non-member tax must not be posted"), 2, (key, values) -> key);
        invoke(adapter, "onTax", event);
        invoke(adapter, "onTransaction", event);
        invoke(adapter, "onSuccess", event);
        assertNotNull(event.transaction.taxer);
        assertEquals(0, event.shopTax);
    }

    private static void invoke(QuickShopTaxAdapter adapter, String name, Event event) throws Exception {
        var method = QuickShopTaxAdapter.class.getDeclaredMethod(name, Event.class);
        method.setAccessible(true);
        method.invoke(adapter, event);
    }

    public static class User {
        final UUID id;
        User(UUID id) { this.id = id; }
        public UUID getUniqueId() { return id; }
        public String getUsername() { return "Member"; }
    }
    public static class Shop {
        final boolean selling;
        final User owner;
        final UUID runtimeId = UUID.randomUUID();
        Shop(boolean selling, User owner) { this.selling = selling; this.owner = owner; }
        public boolean isSelling() { return selling; }
        public User getOwner() { return owner; }
        public UUID getRuntimeRandomUniqueId() { return runtimeId; }
        public long getShopId() { return 1; }
        public Location getLocation() {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            return new Location(world, 0, 64, 0);
        }
    }
    public static class Transaction {
        final User recipient;
        User taxer = new User(UUID.randomUUID());
        Transaction(User recipient) { this.recipient = recipient; }
        public User to() { return recipient; }
        public void taxer(User user) { taxer = user; }
    }
    public static class TradeEvent extends Event {
        final Shop shop;
        final User customer;
        final Transaction transaction;
        double shopTax, interactorTax;
        TradeEvent(Shop shop, User customer, Transaction transaction) {
            this.shop = shop; this.customer = customer; this.transaction = transaction;
        }
        public Shop getShop() { return shop; }
        public User getUser() { return customer; }
        public User getPurchaser() { return customer; }
        public Transaction getTransaction() { return transaction; }
        public void setShopTax(double value) { shopTax = value; }
        public void setInteractorTax(double value) { interactorTax = value; }
        public double getTax() { return 5; }
        public double getBalance() { return 95; }
        public double getBalanceWithoutTax() { return 100; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }
}
