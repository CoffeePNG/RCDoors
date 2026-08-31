package nl.pim16aap2.animatedarchitecture.spigot.core;

import net.republicraft.platform.api.door.DoorAction;
import net.republicraft.platform.api.door.DoorId;
import net.republicraft.platform.api.door.DoorRequest;
import net.republicraft.platform.api.door.DoorResult;
import net.republicraft.platform.api.door.DoorState;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureToggleResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class RCPlatformDoorServiceTest
{
    @Test
    void acceptsOnlyPositiveDecimalStructureUids()
    {
        Assertions.assertEquals(
            42L,
            RCPlatformDoorService.parseStructureUid(new DoorId("42")).orElseThrow());
        Assertions.assertTrue(RCPlatformDoorService.parseStructureUid(new DoorId("front-door")).isEmpty());
        Assertions.assertTrue(RCPlatformDoorService.parseStructureUid(new DoorId("0")).isEmpty());
        Assertions.assertTrue(RCPlatformDoorService.parseStructureUid(new DoorId("-1")).isEmpty());
        Assertions.assertTrue(
            RCPlatformDoorService.parseStructureUid(new DoorId("999999999999999999999999")).isEmpty());
    }

    @Test
    void movingStateTakesPrecedenceOverStoredOpenState()
    {
        Assertions.assertEquals(DoorState.MOVING, RCPlatformDoorService.state(true, true));
        Assertions.assertEquals(DoorState.MOVING, RCPlatformDoorService.state(true, false));
        Assertions.assertEquals(DoorState.OPEN, RCPlatformDoorService.state(false, true));
        Assertions.assertEquals(DoorState.CLOSED, RCPlatformDoorService.state(false, false));
        Assertions.assertEquals(DoorState.UNKNOWN, RCPlatformDoorService.state(false, null));
    }

    @Test
    void mapsNativeOutcomesToExplicitPlatformStatuses()
    {
        final DoorRequest request = DoorRequest.system(new DoorId("7"), DoorAction.OPEN);

        Assertions.assertEquals(
            DoorResult.Status.SUCCESS,
            RCPlatformDoorService.result(StructureToggleResult.ALREADY_OPEN, request, DoorState.OPEN).status());
        Assertions.assertEquals(
            DoorResult.Status.BUSY,
            RCPlatformDoorService.result(StructureToggleResult.BUSY, request, DoorState.CLOSED).status());
        Assertions.assertEquals(
            DoorResult.Status.DENIED,
            RCPlatformDoorService.result(StructureToggleResult.LOCKED, request, DoorState.CLOSED).status());
        Assertions.assertEquals(
            DoorResult.Status.DENIED,
            RCPlatformDoorService.result(StructureToggleResult.NO_PERMISSION, request, DoorState.CLOSED).status());
        Assertions.assertEquals(
            DoorResult.Status.UNAVAILABLE,
            RCPlatformDoorService.result(StructureToggleResult.TYPE_DISABLED, request, DoorState.CLOSED).status());
        Assertions.assertEquals(
            DoorResult.Status.FAILED,
            RCPlatformDoorService.result(StructureToggleResult.OBSTRUCTED, request, DoorState.CLOSED).status());
    }

    @Test
    void acceptedAnimationReportsMovementAndInstantToggleReportsTargetState()
    {
        final DoorRequest animated = new DoorRequest(
            new DoorId("7"), DoorAction.CLOSE, null, Duration.ofSeconds(2), false);
        final DoorRequest instantToggle = new DoorRequest(
            new DoorId("7"), DoorAction.TOGGLE, null, Duration.ZERO, true);

        Assertions.assertEquals(
            DoorState.MOVING,
            RCPlatformDoorService.result(StructureToggleResult.SUCCESS, animated, DoorState.OPEN).state());
        Assertions.assertEquals(
            DoorState.OPEN,
            RCPlatformDoorService.result(StructureToggleResult.SUCCESS, instantToggle, DoorState.CLOSED).state());
    }
}
