package nl.pim16aap2.animatedarchitecture.core.managers;

import nl.pim16aap2.animatedarchitecture.core.api.IConfig;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.api.IWorld;
import nl.pim16aap2.animatedarchitecture.core.api.debugging.DebuggableRegistry;
import nl.pim16aap2.animatedarchitecture.core.api.factories.IPlayerFactory;
import nl.pim16aap2.animatedarchitecture.core.api.restartable.RestartableHolder;
import nl.pim16aap2.animatedarchitecture.core.structures.Structure;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureAnimationRequestBuilder;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.PropertyContainer;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.Property;
import nl.pim16aap2.animatedarchitecture.core.util.Cuboid;
import nl.pim16aap2.animatedarchitecture.core.util.vector.Vector3Di;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

class ProximityManagerTest
{
    private static final Cuboid CUBOID = new Cuboid(new Vector3Di(0, 0, 0), new Vector3Di(4, 4, 4));
    private static final String WORLD_NAME = "world";

    private IConfig config;
    private ProximityManager proximityManager;

    @BeforeEach
    void beforeEach()
    {
        config = Mockito.mock(IConfig.class);
        Mockito.when(config.isProximityEnabled()).thenReturn(true);
        Mockito.when(config.maxProximityRadius()).thenReturn(OptionalInt.of(32));
        Mockito.when(config.maxPowerBlockDistance()).thenReturn(OptionalInt.empty());

        proximityManager = new ProximityManager(
            Mockito.mock(RestartableHolder.class),
            config,
            Mockito.mock(PowerBlockManager.class),
            Mockito.mock(StructureAnimationRequestBuilder.class),
            Mockito.mock(IPlayerFactory.class),
            Mockito.mock(DebuggableRegistry.class)
        );
    }

    private Structure newStructure(@org.jetbrains.annotations.Nullable Integer radius, boolean locked)
    {
        final Structure structure = Mockito.mock(Structure.class);
        final IWorld world = Mockito.mock(IWorld.class);

        Mockito.when(world.worldName()).thenReturn(WORLD_NAME);
        Mockito.when(structure.getWorld()).thenReturn(world);
        Mockito.when(structure.getCuboid()).thenReturn(CUBOID);
        Mockito.when(structure.isLocked()).thenReturn(locked);
        Mockito
            .when(structure.getPropertyValue(Property.PROXIMITY_RADIUS))
            .thenReturn(PropertyContainer.of(Property.PROXIMITY_RADIUS, radius)
                .getPropertyValue(Property.PROXIMITY_RADIUS));

        return structure;
    }

    @Test
    void testRadiusIsClampedToTheConfiguredMaximum()
    {
        Assertions.assertEquals(32, proximityManager.getEffectiveRadius(newStructure(100, false)));
        Assertions.assertEquals(8, proximityManager.getEffectiveRadius(newStructure(8, false)));
    }

    @Test
    void testUnlimitedMaximumRadius()
    {
        Mockito.when(config.maxProximityRadius()).thenReturn(OptionalInt.empty());
        Assertions.assertEquals(100, proximityManager.getEffectiveRadius(newStructure(100, false)));
    }

    @Test
    void testStructuresWithoutARadiusAreIgnored()
    {
        Assertions.assertEquals(0, proximityManager.getEffectiveRadius(newStructure(0, false)));
        Assertions.assertEquals(0, proximityManager.getEffectiveRadius(newStructure(null, false)));
    }

    @Test
    void testLockedStructuresAreIgnored()
    {
        Assertions.assertEquals(0, proximityManager.getEffectiveRadius(newStructure(16, true)));
    }

    @Test
    void testDistanceIsMeasuredFromTheStructureItself()
    {
        final Structure structure = newStructure(5, false);

        // 5 blocks away from the cuboid's max x of 4.
        Assertions.assertTrue(ProximityManager.isPlayerInRange(
            structure,
            List.of(new ProximityManager.PlayerPosition(WORLD_NAME, new Vector3Di(9, 0, 0))),
            5
        ));

        // 6 blocks away, which is outside the radius.
        Assertions.assertFalse(ProximityManager.isPlayerInRange(
            structure,
            List.of(new ProximityManager.PlayerPosition(WORLD_NAME, new Vector3Di(10, 0, 0))),
            5
        ));
    }

    @Test
    void testPlayerInsideTheStructureIsInRange()
    {
        Assertions.assertTrue(ProximityManager.isPlayerInRange(
            newStructure(1, false),
            List.of(new ProximityManager.PlayerPosition(WORLD_NAME, new Vector3Di(2, 2, 2))),
            1
        ));
    }

    @Test
    void testPlayersInOtherWorldsAreIgnored()
    {
        Assertions.assertFalse(ProximityManager.isPlayerInRange(
            newStructure(32, false),
            List.of(new ProximityManager.PlayerPosition("other_world", new Vector3Di(0, 0, 0))),
            32
        ));
    }

    @Test
    void testUpdateDoesNothingWhenProximityIsDisabled()
    {
        Mockito.when(config.isProximityEnabled()).thenReturn(false);

        final CompletableFuture<Void> result = proximityManager.update(List.of(Mockito.mock(IPlayer.class)));

        Assertions.assertTrue(result.isDone());
        Assertions.assertFalse(result.isCompletedExceptionally());
    }
}
