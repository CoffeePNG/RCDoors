package nl.pim16aap2.animatedarchitecture.spigot.core;

import net.republicraft.platform.api.door.DoorAction;
import net.republicraft.platform.api.door.DoorId;
import net.republicraft.platform.api.door.DoorRequest;
import net.republicraft.platform.api.door.DoorResult;
import net.republicraft.platform.api.door.DoorService;
import net.republicraft.platform.api.door.DoorState;
import nl.pim16aap2.animatedarchitecture.core.api.IMessageable;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionCause;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionType;
import nl.pim16aap2.animatedarchitecture.core.structures.PermissionLevel;
import nl.pim16aap2.animatedarchitecture.core.structures.Structure;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureAnimationRequestBuilder;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureToggleResult;
import nl.pim16aap2.animatedarchitecture.core.structures.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Adapts RCPlatform's stable door contract to AnimatedArchitecture's native structure API.
 * <p>
 * RCPlatform door identifiers are decimal AnimatedArchitecture structure UIDs. Structure names are deliberately not
 * accepted because they are not unique.
 */
final class RCPlatformDoorService implements DoorService
{
    private final AnimatedArchitectureSpigotPlatform platform;
    private volatile boolean active = true;

    RCPlatformDoorService(AnimatedArchitectureSpigotPlatform platform)
    {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    public CompletionStage<DoorResult> execute(DoorRequest request)
    {
        Objects.requireNonNull(request, "request");
        if (!active)
            return completed(DoorResult.Status.UNAVAILABLE, DoorState.UNKNOWN, "RCDoors is not active");

        final OptionalLong structureUid = parseStructureUid(request.doorId());
        if (structureUid.isEmpty())
            return completed(
                DoorResult.Status.NOT_FOUND,
                DoorState.UNKNOWN,
                "Door IDs must be positive decimal AnimatedArchitecture structure UIDs");

        final long uid = structureUid.getAsLong();
        return platform
            .getStructureRetrieverFactory()
            .of(uid)
            .getStructure()
            .thenCompose(structure -> structure
                .map(value -> execute(request, value))
                .orElseGet(() -> completed(
                    DoorResult.Status.NOT_FOUND,
                    DoorState.UNKNOWN,
                    "No structure exists with UID " + uid)))
            .exceptionally(this::failed);
    }

    private CompletableFuture<DoorResult> execute(DoorRequest request, Structure structure)
    {
        if (!active)
            return completed(DoorResult.Status.UNAVAILABLE, DoorState.UNKNOWN, "RCDoors is not active");

        if (!structure.hasProperty(Property.OPEN_STATUS))
            return completed(
                DoorResult.Status.FAILED,
                DoorState.UNKNOWN,
                "Structure " + structure.getUid() + " does not expose an open state");

        final DoorState stateBefore = state(structure);
        if (stateBefore == DoorState.MOVING)
            return completed(DoorResult.Status.BUSY, DoorState.MOVING, "Structure is already moving");

        if (request.actorId() == null)
            return submit(request, structure, stateBefore, null);

        if (!structure.isOwner(request.actorId(), PermissionLevel.USER))
            return completed(
                DoorResult.Status.DENIED,
                stateBefore,
                "The actor is not an owner of this structure");

        return platform
            .getPlayerFactory()
            .create(request.actorId())
            .thenCompose(player -> player
                .map(value -> submit(request, structure, stateBefore, value))
                .orElseGet(() -> completed(
                    DoorResult.Status.UNAVAILABLE,
                    stateBefore,
                    "The actor profile is not available to RCDoors")));
    }

    private CompletableFuture<DoorResult> submit(
        DoorRequest request,
        Structure structure,
        DoorState stateBefore,
        @Nullable IPlayer responsible)
    {
        if (!active)
            return completed(DoorResult.Status.UNAVAILABLE, stateBefore, "RCDoors is not active");

        final StructureActionCause cause = responsible == null ?
            StructureActionCause.PLUGIN : StructureActionCause.PLAYER;
        final StructureAnimationRequestBuilder.IBuilder builder = platform
            .getStructureAnimationRequestBuilder()
            .structure(platform.getStructureRetrieverFactory().of(structure))
            .structureActionCause(cause)
            .structureActionType(action(request.action()))
            .responsible(responsible)
            .messageReceiver(IMessageable.NULL)
            .skipAnimation(request.instant());

        if (!request.animationDuration().isZero())
            builder.time(seconds(request.animationDuration()));

        return builder
            .build()
            .execute()
            .thenApply(result -> result(result, request, stateBefore))
            .exceptionally(throwable -> failed(throwable, stateBefore));
    }

    @Override
    public Optional<DoorState> state(DoorId doorId)
    {
        Objects.requireNonNull(doorId, "doorId");
        if (!active)
            return Optional.empty();

        final OptionalLong structureUid = parseStructureUid(doorId);
        if (structureUid.isEmpty())
            return Optional.empty();

        return platform
            .getStructureRegistry()
            .getRegisteredStructure(structureUid.getAsLong())
            .map(this::state);
    }

    private DoorState state(Structure structure)
    {
        final boolean moving = platform
            .getStructureActivityManager()
            .getBlockMovers()
            .anyMatch(animator -> animator.getStructureUID() == structure.getUid());
        final @Nullable Boolean open = structure.hasProperty(Property.OPEN_STATUS) ?
            structure.getPropertyValue(Property.OPEN_STATUS).value() : null;
        return state(moving, open);
    }

    void disable()
    {
        active = false;
    }

    boolean isActive()
    {
        return active;
    }

    static OptionalLong parseStructureUid(DoorId doorId)
    {
        try
        {
            final long uid = Long.parseLong(doorId.value());
            return uid > 0 ? OptionalLong.of(uid) : OptionalLong.empty();
        }
        catch (NumberFormatException ignored)
        {
            return OptionalLong.empty();
        }
    }

    static DoorState state(boolean moving, @Nullable Boolean open)
    {
        if (moving)
            return DoorState.MOVING;
        if (open == null)
            return DoorState.UNKNOWN;
        return open ? DoorState.OPEN : DoorState.CLOSED;
    }

    static DoorResult result(StructureToggleResult result, DoorRequest request, DoorState stateBefore)
    {
        return switch (result)
        {
            case SUCCESS -> new DoorResult(
                DoorResult.Status.SUCCESS,
                request.instant() ? targetState(request.action(), stateBefore) : DoorState.MOVING,
                "Structure action accepted");
            case ALREADY_OPEN -> new DoorResult(
                DoorResult.Status.SUCCESS, DoorState.OPEN, "Structure is already open");
            case ALREADY_CLOSED -> new DoorResult(
                DoorResult.Status.SUCCESS, DoorState.CLOSED, "Structure is already closed");
            case BUSY -> new DoorResult(DoorResult.Status.BUSY, DoorState.MOVING, "Structure is already moving");
            case LOCKED -> new DoorResult(DoorResult.Status.DENIED, stateBefore, "Structure is locked");
            case NO_PERMISSION -> new DoorResult(
                DoorResult.Status.DENIED, stateBefore, "The actor cannot modify the target area");
            case CANCELLED -> new DoorResult(
                DoorResult.Status.DENIED, stateBefore, "Structure action was cancelled by an event listener");
            case TYPE_DISABLED -> new DoorResult(
                DoorResult.Status.UNAVAILABLE, stateBefore, "This structure type is disabled");
            case NO_STRUCTURES_FOUND -> new DoorResult(
                DoorResult.Status.NOT_FOUND, DoorState.UNKNOWN, "No structure was found");
            default -> new DoorResult(
                DoorResult.Status.FAILED,
                stateBefore,
                "AnimatedArchitecture result: " + result.name());
        };
    }

    private static StructureActionType action(DoorAction action)
    {
        return switch (action)
        {
            case OPEN -> StructureActionType.OPEN;
            case CLOSE -> StructureActionType.CLOSE;
            case TOGGLE -> StructureActionType.TOGGLE;
        };
    }

    private static DoorState targetState(DoorAction action, DoorState stateBefore)
    {
        return switch (action)
        {
            case OPEN -> DoorState.OPEN;
            case CLOSE -> DoorState.CLOSED;
            case TOGGLE -> switch (stateBefore)
            {
                case OPEN -> DoorState.CLOSED;
                case CLOSED -> DoorState.OPEN;
                default -> DoorState.UNKNOWN;
            };
        };
    }

    private static double seconds(Duration duration)
    {
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0D;
    }

    private static CompletableFuture<DoorResult> completed(
        DoorResult.Status status,
        DoorState state,
        String detail)
    {
        return CompletableFuture.completedFuture(new DoorResult(status, state, detail));
    }

    private DoorResult failed(Throwable throwable)
    {
        return failed(throwable, DoorState.UNKNOWN);
    }

    private DoorResult failed(Throwable throwable, DoorState state)
    {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null)
            cause = cause.getCause();
        final String message = cause.getMessage();
        return new DoorResult(
            DoorResult.Status.FAILED,
            state,
            message == null || message.isBlank() ? cause.getClass().getSimpleName() : message);
    }
}
