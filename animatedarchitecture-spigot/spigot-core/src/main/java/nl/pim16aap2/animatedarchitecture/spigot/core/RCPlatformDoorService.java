package nl.pim16aap2.animatedarchitecture.spigot.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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

/**
 * Adapts RCPlatform's stable door contract to AnimatedArchitecture's native structure API.
 *
 * <p>RCPlatform door identifiers may be AnimatedArchitecture structure UIDs or exact structure
 * names. Names are accepted only when they resolve to exactly one structure; ambiguous names fail
 * with guidance to use the UID instead.
 */
final class RCPlatformDoorService
    implements DoorService,
        net.republicraft.platform.api.door.DoorAccessService,
        org.bukkit.event.Listener {
  private final AnimatedArchitectureSpigotPlatform platform;
  private volatile boolean active = true;
  private final java.nio.file.Path claimsPath;
  private final java.util.Map<String, Claim> doorClaims =
      new java.util.concurrent.ConcurrentHashMap<>();

  private record Claim(String plugin, String organization) {}

  private final java.util.Map<java.util.UUID, Policy> policies =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public net.republicraft.platform.api.service.Registration register(
      org.bukkit.plugin.Plugin owner, Policy policy) {
    java.util.UUID id = java.util.UUID.randomUUID();
    policies.put(id, java.util.Objects.requireNonNull(policy));
    var registration =
        new net.republicraft.platform.api.service.Registration() {
          public boolean isActive() {
            return policies.containsKey(id);
          }

          public void close() {
            policies.remove(id);
          }
        };
    net.republicraft.platform.api.service.Services.require(
            owner, net.republicraft.platform.api.lifecycle.LifecycleService.class)
        .track(owner, registration);
    return registration;
  }

  @Override
  public CompletionStage<Boolean> canManage(DoorId id, java.util.UUID actor) {
    return platform
        .getStructureRetrieverFactory()
        .of(id.value())
        .getStructures()
        .thenApply(
            rows -> rows.size() == 1 && rows.getFirst().isOwner(actor, PermissionLevel.CREATOR));
  }

  private Decision access(DoorId id, java.util.UUID actor, DoorAction action) {
    Decision result = Decision.ABSTAIN;
    for (Policy policy : policies.values()) {
      Decision decision;
      try {
        decision = policy.check(id, actor, action);
      } catch (RuntimeException failure) {
        return Decision.DENY;
      }
      if (decision == Decision.DENY) return decision;
      if (decision == Decision.ALLOW) result = decision;
    }
    return result == Decision.ABSTAIN && doorClaims.containsKey(id.value())
        ? Decision.DENY
        : result;
  }

  @Override
  public synchronized boolean claim(
      org.bukkit.plugin.Plugin owner, String doorId, String organizationId) {
    if (!active || parseStructureUid(new DoorId(doorId)).isEmpty()) return false;
    String canonical = Long.toString(parseStructureUid(new DoorId(doorId)).orElseThrow());
    try {
      java.util.UUID.fromString(organizationId);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
    Claim proposed = new Claim(owner.getName(), organizationId);
    Claim prior = doorClaims.get(canonical);
    if (prior != null) return prior.equals(proposed);
    var updated = new java.util.HashMap<>(doorClaims);
    updated.put(canonical, proposed);
    if (!persistClaims(updated)) return false;
    doorClaims.put(canonical, proposed);
    return true;
  }

  @Override
  public synchronized boolean releaseClaim(
      org.bukkit.plugin.Plugin owner, String doorId, String organizationId) {
    Claim expected = new Claim(owner.getName(), organizationId);
    Claim prior = doorClaims.get(doorId);
    if (prior == null) return true;
    if (!prior.equals(expected)) return false;
    var updated = new java.util.HashMap<>(doorClaims);
    updated.remove(doorId);
    if (!persistClaims(updated)) return false;
    doorClaims.remove(doorId, prior);
    return true;
  }

  @Override
  public java.util.Map<String, String> claims(org.bukkit.plugin.Plugin owner) {
    var result = new java.util.HashMap<String, String>();
    doorClaims.forEach(
        (door, claim) -> {
          if (claim.plugin.equals(owner.getName())) result.put(door, claim.organization);
        });
    return java.util.Map.copyOf(result);
  }

  private boolean persistClaims(java.util.Map<String, Claim> values) {
    java.util.Properties properties = new java.util.Properties();
    values.forEach(
        (door, claim) -> properties.setProperty(door, claim.plugin + ":" + claim.organization));
    try {
      java.nio.file.Files.createDirectories(claimsPath.getParent());
      java.nio.file.Path temporary = claimsPath.resolveSibling(claimsPath.getFileName() + ".tmp");
      try (var output =
          java.nio.channels.FileChannel.open(
              temporary,
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
              java.nio.file.StandardOpenOption.WRITE)) {
        properties.store(
            java.nio.channels.Channels.newOutputStream(output),
            "Organization door ownership; preserved when providers unload");
        output.force(true);
      }
      try {
        java.nio.file.Files.move(
            temporary,
            claimsPath,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
        java.nio.file.Files.move(
            temporary, claimsPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } catch (java.io.IOException error) {
      java.util.logging.Logger.getLogger("RCDoors")
          .log(java.util.logging.Level.SEVERE, "Could not persist organization door claims", error);
      return false;
    }
  }

  @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
  public void beforeToggle(
      nl.pim16aap2.animatedarchitecture.spigot.core.events.StructureEventTogglePrepare event) {
    if (event.getResponsible() == null) return;
    Decision decision =
        access(
            new DoorId(Long.toString(event.getSnapshot().getUid())),
            event.getResponsible().getUUID(),
            switch (event.getActionType()) {
              case OPEN -> DoorAction.OPEN;
              case CLOSE -> DoorAction.CLOSE;
              default -> DoorAction.TOGGLE;
            });
    if (decision == Decision.DENY) event.setCancelled(true);
    else if (decision == Decision.ALLOW) event.setAccessAuthorized(true);
  }

  RCPlatformDoorService(
      AnimatedArchitectureSpigotPlatform platform, java.nio.file.Path claimsPath) {
    this.platform = Objects.requireNonNull(platform, "platform");
    this.claimsPath = Objects.requireNonNull(claimsPath, "claimsPath");
    if (java.nio.file.Files.exists(claimsPath)) {
      try (var input = java.nio.file.Files.newInputStream(claimsPath)) {
        java.util.Properties properties = new java.util.Properties();
        properties.load(input);
        for (String door : properties.stringPropertyNames()) {
          String[] parts = properties.getProperty(door).split(":", 2);
          if (parts.length != 2
              || parts[0].isBlank()
              || parseStructureUid(new DoorId(door)).isEmpty()
              || !door.equals(Long.toString(parseStructureUid(new DoorId(door)).orElseThrow())))
            throw new java.io.IOException("Invalid organization door claim");
          java.util.UUID.fromString(parts[1]);
          doorClaims.put(door, new Claim(parts[0], parts[1]));
        }
      } catch (java.io.IOException | IllegalArgumentException error) {
        throw new IllegalStateException(
            "Organization door claims could not be loaded; refusing unprotected door access",
            error);
      }
    }
  }

  @Override
  public CompletionStage<DoorResult> execute(DoorRequest request) {
    Objects.requireNonNull(request, "request");
    if (!active)
      return completed(DoorResult.Status.UNAVAILABLE, DoorState.UNKNOWN, "RCDoors is not active");

    final String identifier = request.doorId().value().trim();
    if (isNumericIdentifier(identifier) && parseStructureUid(request.doorId()).isEmpty())
      return completed(
          DoorResult.Status.NOT_FOUND,
          DoorState.UNKNOWN,
          "Numeric door IDs must be positive AnimatedArchitecture structure UIDs");

    return platform
        .getStructureRetrieverFactory()
        .of(identifier)
        .getStructures()
        .thenCompose(
            structures -> {
              if (structures.isEmpty())
                return completed(
                    DoorResult.Status.NOT_FOUND,
                    DoorState.UNKNOWN,
                    "No structure exists with ID or exact name '" + identifier + "'");
              if (structures.size() > 1)
                return completed(
                    DoorResult.Status.FAILED,
                    DoorState.UNKNOWN,
                    "Structure name '"
                        + identifier
                        + "' matches "
                        + structures.size()
                        + " doors; configure the unique numeric UID instead");
              return execute(request, structures.getFirst());
            })
        .exceptionally(this::failed);
  }

  private CompletableFuture<DoorResult> execute(DoorRequest request, Structure structure) {
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

    if (request.actorId() == null) return submit(request, structure, stateBefore, null);

    final Decision decision =
        access(new DoorId(Long.toString(structure.getUid())), request.actorId(), request.action());
    if (decision == Decision.DENY)
      return completed(DoorResult.Status.DENIED, stateBefore, "Organization access denied");
    if (decision != Decision.ALLOW && !structure.isOwner(request.actorId(), PermissionLevel.USER))
      return completed(
          DoorResult.Status.DENIED, stateBefore, "The actor is not an owner of this structure");

    return platform
        .getPlayerFactory()
        .create(request.actorId())
        .thenCompose(
            player ->
                player
                    .map(value -> submit(request, structure, stateBefore, value))
                    .orElseGet(
                        () ->
                            completed(
                                DoorResult.Status.UNAVAILABLE,
                                stateBefore,
                                "The actor profile is not available to RCDoors")));
  }

  private CompletableFuture<DoorResult> submit(
      DoorRequest request,
      Structure structure,
      DoorState stateBefore,
      @Nullable IPlayer responsible) {
    if (!active)
      return completed(DoorResult.Status.UNAVAILABLE, stateBefore, "RCDoors is not active");

    final StructureActionCause cause = actionCause(request);
    final StructureAnimationRequestBuilder.IBuilder builder =
        platform
            .getStructureAnimationRequestBuilder()
            .structure(platform.getStructureRetrieverFactory().of(structure))
            .structureActionCause(cause)
            .structureActionType(action(request.action()))
            .responsible(responsible)
            .messageReceiver(IMessageable.NULL)
            .skipAnimation(request.instant());

    if (!request.animationDuration().isZero()) builder.time(seconds(request.animationDuration()));

    return builder
        .build()
        .execute()
        .thenApply(result -> result(result, request, stateBefore))
        .exceptionally(throwable -> failed(throwable, stateBefore));
  }

  /**
   * System contract requests are server actions and must not inherit a structure owner's region
   * permissions.
   */
  static StructureActionCause actionCause(DoorRequest request) {
    return request.actorId() == null ? StructureActionCause.SERVER : StructureActionCause.PLAYER;
  }

  @Override
  public Optional<DoorState> state(DoorId doorId) {
    Objects.requireNonNull(doorId, "doorId");
    if (!active) return Optional.empty();

    final OptionalLong structureUid = parseStructureUid(doorId);
    if (structureUid.isEmpty()) return Optional.empty();

    return platform
        .getStructureRegistry()
        .getRegisteredStructure(structureUid.getAsLong())
        .map(this::state);
  }

  private DoorState state(Structure structure) {
    final boolean moving =
        platform
            .getStructureActivityManager()
            .getBlockMovers()
            .anyMatch(animator -> animator.getStructureUID() == structure.getUid());
    final @Nullable Boolean open =
        structure.hasProperty(Property.OPEN_STATUS)
            ? structure.getPropertyValue(Property.OPEN_STATUS).value()
            : null;
    return state(moving, open);
  }

  void disable() {
    active = false;
    policies.clear();
  }

  boolean isActive() {
    return active;
  }

  static OptionalLong parseStructureUid(DoorId doorId) {
    try {
      final long uid = Long.parseLong(doorId.value().trim());
      return uid > 0 ? OptionalLong.of(uid) : OptionalLong.empty();
    } catch (NumberFormatException ignored) {
      return OptionalLong.empty();
    }
  }

  static boolean isNumericIdentifier(String identifier) {
    if (identifier == null || identifier.isEmpty()) return false;
    int index = identifier.charAt(0) == '+' || identifier.charAt(0) == '-' ? 1 : 0;
    if (index == identifier.length()) return false;
    for (; index < identifier.length(); ++index)
      if (!Character.isDigit(identifier.charAt(index))) return false;
    return true;
  }

  static DoorState state(boolean moving, @Nullable Boolean open) {
    if (moving) return DoorState.MOVING;
    if (open == null) return DoorState.UNKNOWN;
    return open ? DoorState.OPEN : DoorState.CLOSED;
  }

  static DoorResult result(
      StructureToggleResult result, DoorRequest request, DoorState stateBefore) {
    return switch (result) {
      case SUCCESS ->
          new DoorResult(
              DoorResult.Status.SUCCESS,
              request.instant() ? targetState(request.action(), stateBefore) : DoorState.MOVING,
              "Structure action accepted");
      case ALREADY_OPEN ->
          new DoorResult(DoorResult.Status.SUCCESS, DoorState.OPEN, "Structure is already open");
      case ALREADY_CLOSED ->
          new DoorResult(
              DoorResult.Status.SUCCESS, DoorState.CLOSED, "Structure is already closed");
      case BUSY ->
          new DoorResult(DoorResult.Status.BUSY, DoorState.MOVING, "Structure is already moving");
      case LOCKED -> new DoorResult(DoorResult.Status.DENIED, stateBefore, "Structure is locked");
      case NO_PERMISSION ->
          new DoorResult(
              DoorResult.Status.DENIED, stateBefore, "The actor cannot modify the target area");
      case CANCELLED ->
          new DoorResult(
              DoorResult.Status.DENIED,
              stateBefore,
              "Structure action was cancelled by an event listener");
      case TYPE_DISABLED ->
          new DoorResult(
              DoorResult.Status.UNAVAILABLE, stateBefore, "This structure type is disabled");
      case NO_STRUCTURES_FOUND ->
          new DoorResult(DoorResult.Status.NOT_FOUND, DoorState.UNKNOWN, "No structure was found");
      default ->
          new DoorResult(
              DoorResult.Status.FAILED,
              stateBefore,
              "AnimatedArchitecture result: " + result.name());
    };
  }

  private static StructureActionType action(DoorAction action) {
    return switch (action) {
      case OPEN -> StructureActionType.OPEN;
      case CLOSE -> StructureActionType.CLOSE;
      case TOGGLE -> StructureActionType.TOGGLE;
    };
  }

  private static DoorState targetState(DoorAction action, DoorState stateBefore) {
    return switch (action) {
      case OPEN -> DoorState.OPEN;
      case CLOSE -> DoorState.CLOSED;
      case TOGGLE ->
          switch (stateBefore) {
            case OPEN -> DoorState.CLOSED;
            case CLOSED -> DoorState.OPEN;
            default -> DoorState.UNKNOWN;
          };
    };
  }

  private static double seconds(Duration duration) {
    return duration.getSeconds() + duration.getNano() / 1_000_000_000.0D;
  }

  private static CompletableFuture<DoorResult> completed(
      DoorResult.Status status, DoorState state, String detail) {
    return CompletableFuture.completedFuture(new DoorResult(status, state, detail));
  }

  private DoorResult failed(Throwable throwable) {
    return failed(throwable, DoorState.UNKNOWN);
  }

  private DoorResult failed(Throwable throwable, DoorState state) {
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
