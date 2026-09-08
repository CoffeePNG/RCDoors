package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import nl.pim16aap2.animatedarchitecture.core.api.IConfig;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureType;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultManagerSafetyTest
{
    private static void field(VaultManager manager, String name, Object value) throws Exception
    {
        var field = VaultManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    @Test
    void creationPaymentProviderParticipatesInRestartLifecycle()
    {
        var server = mock(org.bukkit.Server.class);
        var services = mock(org.bukkit.plugin.ServicesManager.class);
        when(server.getServicesManager()).thenReturn(services);
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        var permission = mock(net.milkbowl.vault.permission.Permission.class);
        when(services.getRegistration(net.milkbowl.vault.permission.Permission.class)).thenReturn(
            new org.bukkit.plugin.RegisteredServiceProvider<>(net.milkbowl.vault.permission.Permission.class,
                permission,org.bukkit.plugin.ServicePriority.Normal,plugin));
        var lifecycle = new nl.pim16aap2.animatedarchitecture.core.api.restartable.RestartableHolder();
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class))
        {
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(server);
            var manager = new VaultManager(
                mock(nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer.class),
                mock(nl.pim16aap2.animatedarchitecture.core.api.factories.ITextFactory.class),mock(IConfig.class),
                mock(nl.pim16aap2.animatedarchitecture.core.managers.StructureTypeManager.class),
                mock(nl.pim16aap2.animatedarchitecture.core.api.debugging.DebuggableRegistry.class),
                mock(nl.pim16aap2.animatedarchitecture.core.api.IExecutor.class),plugin,lifecycle);
            assertTrue(lifecycle.isRestartableRegistered(manager));
        }
    }

    @Test
    void explicitlyDeniedLimitNodesCannotIncreaseLimits()
    {
        var manager = mock(VaultManager.class, CALLS_REAL_METHODS);
        var player = mock(Player.class);
        var allowed = new PermissionAttachmentInfo(player, "rcdoors.limit.5", null, true);
        var denied = new PermissionAttachmentInfo(player, "rcdoors.limit.1000", null, false);
        when(player.getEffectivePermissions()).thenReturn(Set.of(allowed, denied));
        assertEquals(5, manager.getMaxPermissionSuffix(player, "rcdoors.limit.").orElseThrow());
    }

    @Test
    void providerExceptionAndNullResponseNeverReportPaid() throws Exception
    {
        var manager = mock(VaultManager.class, CALLS_REAL_METHODS);
        var economy = mock(Economy.class);
        field(manager, "economy", economy);
        var player = mock(OfflinePlayer.class);
        when(economy.has(player, 10)).thenReturn(true);
        var method = VaultManager.class.getDeclaredMethod("withdrawPlayer", OfflinePlayer.class, String.class, double.class);
        method.setAccessible(true);
        when(economy.withdrawPlayer(player, "world", 10)).thenThrow(new IllegalStateException("outcome unknown"));
        assertEquals(false, method.invoke(manager, player, "world", 10d));
        doReturn(null).when(economy).withdrawPlayer(player, "world", 10);
        assertEquals(false, method.invoke(manager, player, "world", 10d));
    }

    @Test
    void missingEconomyProviderCannotMakeConfiguredPaidCreationFree() throws Exception
    {
        var manager = mock(VaultManager.class, CALLS_REAL_METHODS);
        field(manager, "flatPrices", new HashMap<StructureType, Double>());
        var config = mock(IConfig.class);
        field(manager, "config", config);
        var type = mock(StructureType.class);
        when(config.getPrice(type)).thenReturn("10");
        assertEquals(10d, manager.getPrice(type, 10).orElseThrow());
        assertFalse(manager.isEconomyEnabled());
    }

    @Test
    void invalidAndSubcentPriceNeverBecomeFreeCreation() throws Exception
    {
        var manager = mock(VaultManager.class, CALLS_REAL_METHODS);
        field(manager, "economy", mock(Economy.class));
        field(manager, "flatPrices", new HashMap<StructureType, Double>());
        var config = mock(IConfig.class);
        field(manager, "config", config);
        var type = mock(StructureType.class);
        for (String formula : new String[]{"nonsense", "0.001", "-1"})
        {
            when(config.getPrice(type)).thenReturn(formula);
            assertThrows(IllegalArgumentException.class, () -> manager.getPrice(type, 10));
        }
    }
}
