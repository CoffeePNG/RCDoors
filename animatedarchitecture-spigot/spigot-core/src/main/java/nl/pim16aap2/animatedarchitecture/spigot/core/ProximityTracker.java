package nl.pim16aap2.animatedarchitecture.spigot.core;

import lombok.extern.flogger.Flogger;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.IRestartable;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.RestartableHolder;
import nl.pim16aap2.animatedarchitecture.core.managers.ProximityManager;
import nl.pim16aap2.animatedarchitecture.core.util.FutureUtil;
import nl.pim16aap2.animatedarchitecture.spigot.core.config.ConfigSpigot;
import nl.pim16aap2.animatedarchitecture.spigot.util.SpigotAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Feeds the positions of the online players to the {@link ProximityManager}.
 * <p>
 * Player positions are sampled on a timer rather than from move events: a move event fires several times per second
 * per player, while a structure that opens a fraction of a second later is not noticeable.
 */
@Singleton
@Flogger
public final class ProximityTracker implements IRestartable
{
    private final JavaPlugin plugin;
    private final ConfigSpigot config;
    private final ProximityManager proximityManager;

    private @Nullable BukkitTask task;

    @Inject
    ProximityTracker(
        RestartableHolder restartableHolder,
        JavaPlugin plugin,
        ConfigSpigot config,
        ProximityManager proximityManager)
    {
        this.plugin = plugin;
        this.config = config;
        this.proximityManager = proximityManager;

        restartableHolder.registerRestartable(this);
    }

    @Override
    public void initialize()
    {
        if (!config.isProximityEnabled())
            return;

        final long interval = config.proximityCheckInterval();
        task = Bukkit
            .getScheduler()
            .runTaskTimer(plugin, this::run, interval, interval);
    }

    @Override
    public void shutDown()
    {
        if (task != null)
        {
            task.cancel();
            task = null;
        }
    }

    private void run()
    {
        try
        {
            final Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
            if (onlinePlayers.isEmpty())
                return;

            final List<IPlayer> players = new ArrayList<>(onlinePlayers.size());
            for (final Player player : onlinePlayers)
                players.add(SpigotAdapter.wrapPlayer(player));

            proximityManager
                .update(players)
                .exceptionally(FutureUtil::exceptionally);
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log("Failed to run proximity check!");
        }
    }
}
