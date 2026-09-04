package nl.pim16aap2.animatedarchitecture.core.api;

import nl.pim16aap2.animatedarchitecture.core.api.restartable.IRestartable;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureType;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.logging.Level;

/**
 * Represents the configured settings.
 */
public interface IConfig extends IRestartable
{
    /**
     * @return True if debug mode is enabled.
     */
    boolean debug();

    /**
     * @return True if redstone is enabled.
     */
    boolean isRedstoneEnabled();

    /**
     * The amount of time a user gets to specify which structure they meant in case of structureID collisions.
     * <p>
     * This can happen in case they specified a structure by its name when they own more than 1 structure with that
     * name.
     *
     * @return The amount of time (in seconds) to give a user to specify which structure they meant.
     */
    default int specificationTimeout()
    {
        return 20;
    }

    /**
     * @return The movement formula of a flag.
     */
    String flagMovementFormula();

    /**
     * Gets the number of ticks a structure should wait before it can be activated again.
     *
     * @return The number of ticks a structure should wait before it can be activated again.
     */
    int coolDown();

    Locale locale();

    /**
     * Gets the global maximum number of blocks that can be in a structure.
     * <p>
     * Structures exceeding this limit cannot be created or activated.
     *
     * @return The global maximum number of blocks that can be in a structure.
     */
    OptionalInt maxStructureSize();

    /**
     * Gets the amount of time (in minutes) power blocks should be kept in cache.
     *
     * @return The amount of time power blocks should be kept in cache.
     */
    int cacheTimeout();

    /**
     * Gets the global maximum number of structures a player can own.
     *
     * @return The global maximum number of structures a player can own.
     */
    OptionalInt maxStructureCount();

    /**
     * Gets the global maximum distance (in blocks) a powerblock can be from the structure.
     *
     * @return The global maximum distance (in blocks) a powerblock can be from the structure.
     */
    OptionalInt maxPowerBlockDistance();

    /**
     * Gets the global maximum number of blocks a structure can move for applicable types (e.g. sliding door).
     *
     * @return The global maximum number of blocks a structure can move for applicable types (e.g. sliding door).
     */
    OptionalInt maxBlocksToMove();

    /**
     * @return True if we should try to load any unloaded chunks for a toggle.
     */
    boolean loadChunksForToggle();

    /**
     * Whether the inventory wizard is used to guide players through structure creation.
     * <p>
     * When this is disabled, creation runs entirely through the chat-driven flow, exactly as it did before the wizard
     * existed. The wizard is a second front-end over the same procedure, so nothing about the resulting structure
     * depends on which one was used.
     *
     * @return True if the creation wizard should be opened when a creation process starts.
     */
    default boolean isCreatorWizardEnabled()
    {
        return false;
    }

    /**
     * Whether structures may be opened by players walking up to them.
     * <p>
     * When this is disabled, the proximity radius of individual structures is ignored.
     *
     * @return True if proximity opening is enabled on this server.
     */
    default boolean isProximityEnabled()
    {
        return true;
    }

    /**
     * The largest proximity radius players may set for a structure, in blocks.
     * <p>
     * Radii larger than this are clamped to it, both when they are set and when they are used.
     *
     * @return The maximum proximity radius, or an empty optional if it is unlimited.
     */
    default OptionalInt maxProximityRadius()
    {
        return OptionalInt.of(32);
    }

    /**
     * How often the server checks whether players are near a structure with a proximity radius, in ticks.
     *
     * @return The number of ticks between two proximity checks.
     */
    default int proximityCheckInterval()
    {
        return 10;
    }

    /**
     * How long a structure that was opened by proximity stays open after the last player left its radius, in seconds.
     * <p>
     * A delay keeps the structure from flapping open and closed while a player stands on the edge of the radius.
     *
     * @return The delay before a structure opened by proximity closes again.
     */
    default int proximityCloseDelay()
    {
        return 3;
    }

    /**
     * Gets the structure price formula for a specific type of structure.
     *
     * @param type
     *     The structure type.
     * @return The formula for the structure type.
     */
    String getPrice(StructureType type);

    /**
     * Gets the animation time multiplier for a specific type of structure.
     *
     * @param type
     *     The structure type.
     * @return The animation time multiplier for the structure type.
     */
    double getAnimationTimeMultiplier(StructureType type);

    /**
     * @return The global maximum speed of a block.
     */
    double maxBlockSpeed();

    /**
     * Whether to skip all animations by default. If true, toggling a structure will simply teleport the blocks to their
     * destination without any animations. For structures that don't have a destination, any toggle request will be
     * ignored.
     *
     * @return True if all animations should be skipped by default.
     */
    boolean skipAnimationsByDefault();

    /**
     * The log level to use.
     *
     * @return The log level.
     */
    Level logLevel();

    /**
     * Checks if errors should be logged to the console.
     *
     * @return True if errors should be logged to the console.
     */
    boolean consoleLogging();
}
