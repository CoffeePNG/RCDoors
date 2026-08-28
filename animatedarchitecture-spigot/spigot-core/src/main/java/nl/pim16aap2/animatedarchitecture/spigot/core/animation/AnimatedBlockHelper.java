package nl.pim16aap2.animatedarchitecture.spigot.core.animation;

import com.google.common.flogger.LazyArgs;
import lombok.extern.flogger.Flogger;
import nl.pim16aap2.animatedarchitecture.core.util.Constants;
import nl.pim16aap2.animatedarchitecture.spigot.core.animation.recovery.AnimatedBlockRecoveryDataType;
import nl.pim16aap2.animatedarchitecture.spigot.core.animation.recovery.IAnimatedBlockRecoveryData;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.Objects;

/**
 * Helper class for animated blocks.
 */
@Flogger
@Singleton
public final class AnimatedBlockHelper
{
    /**
     * The key used to store the recovery data in the entity's persistent data container.
     */
    private final NamespacedKey recoveryKey;
    private final NamespacedKey legacyRecoveryKey;

    @Inject
    AnimatedBlockHelper(JavaPlugin plugin)
    {
        recoveryKey = new NamespacedKey(plugin, Constants.ANIMATED_ARCHITECTURE_ENTITY_RECOVERY_KEY);
        legacyRecoveryKey = new NamespacedKey(
            "animatedarchitecture",
            Constants.ANIMATED_ARCHITECTURE_ENTITY_RECOVERY_KEY.toLowerCase(Locale.ROOT));
    }

    /**
     * Attempts to recover an animated block from an entity.
     * <p>
     * If the entity is not an animated block (or null), this method does nothing.
     * <p>
     * If the entity is an animated block, this method will attempt to perform a recovery action by calling
     * {@link IAnimatedBlockRecoveryData#recover()}. If the recovery action is successful, the entity will be removed.
     *
     * @param entity
     *     The entity for which to attempt recovery.
     */
    public void recoverAnimatedBlock(@Nullable Entity entity)
    {
        if (entity == null)
            return;

        final PersistentDataContainer persistentData = entity.getPersistentDataContainer();
        @Nullable IAnimatedBlockRecoveryData recoveryData =
            persistentData.get(recoveryKey, AnimatedBlockRecoveryDataType.INSTANCE);
        NamespacedKey sourceKey = recoveryKey;

        if (recoveryData == null)
        {
            recoveryData = persistentData.get(legacyRecoveryKey, AnimatedBlockRecoveryDataType.INSTANCE);
            sourceKey = legacyRecoveryKey;
        }

        if (recoveryData == null)
            return;

        final IAnimatedBlockRecoveryData finalRecoveryData = recoveryData;
        final NamespacedKey finalSourceKey = sourceKey;

        log.atFinest().log(
            "Attempting to recover animated block with recovery data '%s'",
            LazyArgs.lazy(
                () -> persistentData.get(finalSourceKey, AnimatedBlockRecoveryDataType.STRING))
        );

        try
        {
            if (finalRecoveryData.recover())
                log.atWarning().log(
                    "Recovered animated block with recovery data '%s'! " +
                        "This is not intended behavior, please contact the author(s) of this plugin!",
                    finalRecoveryData
                );
            else
                log.atFine().log("No recovery action required for data '%s'", finalRecoveryData);

            entity.remove();
        }
        catch (Exception e)
        {
            log.atSevere().withCause(e).log(
                "Failed to recover animated block '%s' from recovery: '%s'",
                entity,
                finalRecoveryData
            );
        }
    }

    /**
     * Sets the recovery data for an animated block entity.
     *
     * @param entity
     *     The entity to set the recovery data for.
     * @param recoveryData
     *     The recovery data to set for the entity.
     */
    public void setRecoveryData(BlockDisplay entity, @Nullable IAnimatedBlockRecoveryData recoveryData)
    {
        entity.getPersistentDataContainer().set(
            recoveryKey,
            AnimatedBlockRecoveryDataType.INSTANCE,
            Objects.requireNonNullElse(recoveryData, IAnimatedBlockRecoveryData.EMPTY)
        );
    }
}
