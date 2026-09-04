package org.allivlisey.tianjitown.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownAdminCommandDispatchTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final PluginMessages messages = mock(PluginMessages.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final Command command = mock(Command.class);
    private TownAdminCommand executor;

    @BeforeEach
    void setUp() {
        when(plugin.messages()).thenReturn(messages);
        when(plugin.townRuntime()).thenReturn(runtime);
        executor = new TownAdminCommand(plugin);
    }

    @Test
    void unauthorizedCommandNeverReachesTheExtractedHandler() {
        assertTrue(execute("money", "reconcile"));

        verify(messages).send(sender, "chat.admin.no-permission");
        verifyNoInteractions(runtime);
    }

    @Test
    void authorizedEconomyCommandStillReachesTheRuntime() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);

        assertTrue(execute("money", "reconcile"));

        verify(runtime).reconcileSettlement();
        verify(messages).send(sender, "chat.admin.settlement-reconcile-submitted");
    }

    @Test
    void malformedArgumentsStillUseTheSharedErrorBoundary() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        when(messages.text("chat.admin.usage-money-root")).thenReturn("usage");

        assertTrue(execute("money"));

        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verifyNoInteractions(runtime);
    }

    private boolean execute(String... args) {
        return executor.onCommand(sender, command, "townadmin", args);
    }
}
