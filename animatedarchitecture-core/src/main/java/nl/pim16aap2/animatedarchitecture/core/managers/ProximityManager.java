package nl.pim16aap2.animatedarchitecture.core.managers;

import lombok.extern.flogger.Flogger;
import nl.pim16aap2.animatedarchitecture.core.api.IConfig;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.api.debugging.DebuggableRegistry;
import nl.pim16aap2.animatedarchitecture.core.api.debugging.IDebuggable;
import nl.pim16aap2.animatedarchitecture.core.api.factories.IPlayerFactory;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.Restartable;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.RestartableHolder;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionCause;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionType;
import nl.pim16aap2.animatedarchitecture.core.structures.Structure;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureAnimationRequestBuilder;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.IPropertyValue;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.Property;
import nl.pim16aap2.animatedarchitecture.core.util.Cuboid;
import nl.pim16aap2.animatedarchitecture.core.util.FutureUtil;
import nl.pim16aap2.animatedarchitecture.core.util.MathUtil;
import nl.pim16aap2.animatedarchitecture.core.util.vector.Vector3Di;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens structures when a player comes near them, and closes them again once every player has left.
 * <p>
 * A structure takes part in this when its {@link Property#PROXIMITY_RADIUS} is larger than 0. The radius is measured
 * from the structure's own blocks rather than from its power block, so it means the same thing regardless of where the
 * owner hid the power block.
 * <p>
 * This class does not observe players by itself: the platform is expected to call {@link #update(Collection)} with the
 * players that are currently online, at the interval configured by {@link IConfig#proximityCheckInterval()}.
 */
@Singleton
@Flogger
public final class ProximityManager extends Restartable implements IDebuggable
{
    /**
     * The size of a chunk along the x and z axes.
     */
    private static final int CHUNK_SIZE = 16;

    private final IConfig config;
    private final PowerBlockManager powerBlockManager;
    private final StructureAnimationRequestBuilder structureAnimationRequestBuilder;
    private final IPlayerFactory playerFactory;

    /**
     * The structures that this manager opened and has not closed again yet, mapped by their UID.
     */
    private final Map<Long, TrackedStructure> trackedStructures = new ConcurrentHashMap<>();

    /**
     * Whether an update is currently in progress.
     * <p>
     * Updates look structures up asynchronously, so an update can still be running when the next one is due. Running
     * both at the same time would mean acting on the same structure twice, so a new update is skipped instead.
     */
    private volatile boolean updateInProgress = false;

    @Inject
    ProximityManager(
        RestartableHolder restartableHolder,
        IConfig config,
        PowerBlockManager powerBlockManager,
        StructureAnimationRequestBuilder structureAnimationRequestBuilder,
        IPlayerFactory playerFactory,
        DebuggableRegistry debuggableRegistry)
    {
        super(restartableHolder);

        this.config = config;
        this.powerBlockManager = powerBlockManager;
        this.structureAnimationRequestBuilder = structureAnimationRequestBuilder;
        this.playerFactory = playerFactory;

        debuggableRegistry.registerDebuggable(this);
    }

    /**
     * Processes the positions of the given players.
     * <p>
     * Structures near any of these players are opened, and structures that this manager opened earlier are closed once
     * no player has been near them for {@link IConfig#proximityCloseDelay()} seconds.
     *
     * @param players
     *     The players to take into account. Usually every player that is currently online.
     * @return A future that completes once every structure near the given players has been processed.
     */
    public CompletableFuture<Void> update(Collection<IPlayer> players)
    {
        if (!config.isProximityEnabled())
        {
            trackedStructures.clear();
            return CompletableFuture.completedFuture(null);
        }

        if (updateInProgress)
            return CompletableFuture.completedFuture(null);
        updateInProgress = true;

        try
        {
            final List<PlayerPosition> positions = getPlayerPositions(players);

            return findNearbyStructures(positions)
                .thenAccept(structures -> processStructures(structures, positions))
                .exceptionally(FutureUtil::exceptionally)
                .whenComplete((ignored, ex) -> updateInProgress = false);
        }
        catch (Exception e)
        {
            updateInProgress = false;
            throw new RuntimeException("Failed to process proximity update!", e);
        }
    }

    private static List<PlayerPosition> getPlayerPositions(Collection<IPlayer> players)
    {
        final List<PlayerPosition> positions = new ArrayList<>(players.size());
        for (final IPlayer player : players)
            player
                .getLocation()
                .ifPresent(location -> positions.add(new PlayerPosition(
                    location.getWorld().worldName(),
                    location.getPosition()
                )));
        return positions;
    }

    /**
     * Finds every structure whose power block lies in a chunk near one of the given players.
     * <p>
     * The search radius is the largest radius a structure may use, increased by the largest distance a power block may
     * be from its structure, because structures are indexed by the chunk of their power block.
     *
     * @param positions
     *     The positions of the players to search around.
     * @return The structures near the given positions, without duplicates.
     */
    private CompletableFuture<List<Structure>> findNearbyStructures(List<PlayerPosition> positions)
    {
        if (positions.isEmpty())
            return CompletableFuture.completedFuture(List.of());

        final int chunkRadius = getSearchChunkRadius();
        final Set<ChunkKey> chunks = new HashSet<>();
        final List<CompletableFuture<List<Structure>>> futures = new ArrayList<>();

        for (final PlayerPosition position : positions)
        {
            if (!powerBlockManager.isAnimatedArchitectureWorld(position.worldName()))
                continue;

            final int chunkX = Math.floorDiv(position.position().x(), CHUNK_SIZE);
            final int chunkZ = Math.floorDiv(position.position().z(), CHUNK_SIZE);

            for (int dx = -chunkRadius; dx <= chunkRadius; ++dx)
                for (int dz = -chunkRadius; dz <= chunkRadius; ++dz)
                {
                    final ChunkKey key = new ChunkKey(position.worldName(), chunkX + dx, chunkZ + dz);
                    if (!chunks.add(key))
                        continue;

                    futures.add(powerBlockManager.structuresInChunk(
                        new Vector3Di(key.chunkX() * CHUNK_SIZE, position.position().y(), key.chunkZ() * CHUNK_SIZE),
                        key.worldName()
                    ));
                }
        }

        return FutureUtil
            .getAllCompletableFutureResults(futures)
            .thenApply(lists ->
            {
                final Map<Long, Structure> structures = new HashMap<>();
                lists.forEach(list -> list.forEach(structure -> structures.put(structure.getUid(), structure)));
                return List.copyOf(structures.values());
            });
    }

    /**
     * The radius, in chunks, to search around each player.
     */
    private int getSearchChunkRadius()
    {
        final int maxRadius = config.maxProximityRadius().orElse(CHUNK_SIZE * 8);
        final int powerBlockDistance = config.maxPowerBlockDistance().orElse(0);
        return MathUtil.ceil((maxRadius + powerBlockDistance) / (double) CHUNK_SIZE);
    }

    /**
     * Opens the structures that a player is near, and closes the ones that nobody has been near for long enough.
     *
     * @param structures
     *     The structures that were found near the players.
     * @param positions
     *     The positions of the players.
     */
    private void processStructures(List<Structure> structures, List<PlayerPosition> positions)
    {
        final long now = System.currentTimeMillis();
        final Set<Long> handled = new HashSet<>(structures.size());

        for (final Structure structure : structures)
        {
            handled.add(structure.getUid());

            final int radius = getEffectiveRadius(structure);
            if (radius <= 0)
            {
                // The radius may have been set to 0 while the structure was open.
                closeIfTracked(structure, now, true);
                continue;
            }

            if (isPlayerInRange(structure, positions, radius))
                open(structure, now);
            else
                closeIfTracked(structure, now, false);
        }

        // Structures that are no longer found near any player, e.g. because every player left the area entirely.
        trackedStructures.values().removeIf(tracked ->
        {
            if (handled.contains(tracked.structure().getUid()))
                return false;
            return closeIfExpired(tracked, now, false);
        });
    }

    /**
     * The proximity radius of a structure, clamped to the maximum radius allowed by the configuration.
     *
     * @param structure
     *     The structure to get the radius of.
     * @return The radius in blocks, or 0 if this structure does not use proximity opening.
     */
    int getEffectiveRadius(Structure structure)
    {
        if (structure.isLocked())
            return 0;

        final IPropertyValue<Integer> value = structure.getPropertyValue(Property.PROXIMITY_RADIUS);
        if (!value.isSet() || value.value() == null)
            return 0;

        final int radius = value.value();
        if (radius <= 0)
            return 0;

        final OptionalInt maxRadius = config.maxProximityRadius();
        return maxRadius.isPresent() ? Math.min(radius, maxRadius.getAsInt()) : radius;
    }

    static boolean isPlayerInRange(Structure structure, List<PlayerPosition> positions, int radius)
    {
        final String worldName = structure.getWorld().worldName();
        final Cuboid cuboid = structure.getCuboid();

        for (final PlayerPosition position : positions)
        {
            if (!worldName.equals(position.worldName()))
                continue;

            // The distance is -1 when the player stands inside the structure itself.
            final int distance = cuboid.getDistanceToPoint(position.position());
            if (distance <= radius)
                return true;
        }
        return false;
    }

    /**
     * Opens a structure because a player is near it.
     * <p>
     * Structures that are already open are not opened again; their timestamp is refreshed so they stay open while the
     * player remains in range.
     */
    private void open(Structure structure, long now)
    {
        final TrackedStructure tracked = trackedStructures.computeIfAbsent(
            structure.getUid(),
            uid -> new TrackedStructure(structure, now));
        tracked.setLastInRange(now);

        if (isOpen(structure))
            return;

        toggle(structure, StructureActionType.OPEN);
    }

    /**
     * Closes a structure that this manager opened, once no player has been near it for long enough.
     *
     * @param force
     *     True to close the structure without waiting for the delay to expire.
     */
    private void closeIfTracked(Structure structure, long now, boolean force)
    {
        final TrackedStructure tracked = trackedStructures.get(structure.getUid());
        if (tracked != null && closeIfExpired(tracked, now, force))
            trackedStructures.remove(structure.getUid());
    }

    /**
     * @return True if the structure was closed and should no longer be tracked.
     */
    private boolean closeIfExpired(TrackedStructure tracked, long now, boolean force)
    {
        if (!force && now - tracked.getLastInRange() < config.proximityCloseDelay() * 1000L)
            return false;

        final Structure structure = tracked.structure();
        if (isOpen(structure))
            toggle(structure, StructureActionType.CLOSE);

        return true;
    }

    private static boolean isOpen(Structure structure)
    {
        return Boolean.TRUE.equals(structure.getPropertyValue(Property.OPEN_STATUS).value());
    }

    private void toggle(Structure structure, StructureActionType actionType)
    {
        structureAnimationRequestBuilder
            .builder()
            .structure(structure)
            .structureActionCause(StructureActionCause.PROXIMITY)
            .structureActionType(actionType)
            .messageReceiverServer()
            .responsible(playerFactory.create(structure.getPrimeOwner().playerData()))
            .build()
            .execute()
            .exceptionally(FutureUtil::exceptionally);
    }

    @Override
    public void shutDown()
    {
        trackedStructures.clear();
        updateInProgress = false;
    }

    @Override
    public String getDebugInformation()
    {
        return "Proximity enabled: " + config.isProximityEnabled() +
            "\nOpened by proximity: " + trackedStructures.size() +
            "\nSearch radius (chunks): " + getSearchChunkRadius();
    }

    /**
     * The position of a player at the time of an update.
     *
     * @param worldName
     *     The name of the world the player is in.
     * @param position
     *     The position of the player in that world.
     */
    record PlayerPosition(String worldName, Vector3Di position) {}

    /**
     * The coordinates of a chunk, used to avoid looking the same chunk up twice in a single update.
     */
    private record ChunkKey(String worldName, int chunkX, int chunkZ) {}

    /**
     * A structure that was opened because a player came near it.
     */
    private static final class TrackedStructure
    {
        private final Structure structure;

        /**
         * The last time, in milliseconds, that a player was in range of this structure.
         */
        private volatile long lastInRange;

        TrackedStructure(Structure structure, long lastInRange)
        {
            this.structure = structure;
            this.lastInRange = lastInRange;
        }

        Structure structure()
        {
            return structure;
        }

        long getLastInRange()
        {
            return lastInRange;
        }

        void setLastInRange(long lastInRange)
        {
            this.lastInRange = lastInRange;
        }
    }
}
