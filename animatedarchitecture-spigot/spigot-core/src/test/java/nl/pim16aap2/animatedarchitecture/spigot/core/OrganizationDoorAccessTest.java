package nl.pim16aap2.animatedarchitecture.spigot.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.republicraft.platform.api.door.DoorAccessService.Decision;
import net.republicraft.platform.api.lifecycle.LifecycleService;
import nl.pim16aap2.animatedarchitecture.core.api.IPlayer;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionCause;
import nl.pim16aap2.animatedarchitecture.core.events.StructureActionType;
import nl.pim16aap2.animatedarchitecture.core.structures.StructureSnapshot;
import nl.pim16aap2.animatedarchitecture.core.util.Cuboid;
import nl.pim16aap2.animatedarchitecture.spigot.core.events.StructureEventTogglePrepare;
import org.bukkit.Server;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OrganizationDoorAccessTest {
  @TempDir Path directory;
  Plugin owner;
  RCPlatformDoorService doors;
  String organization = UUID.randomUUID().toString();

  @BeforeEach
  void setup() {
    owner = mock(Plugin.class);
    when(owner.getName()).thenReturn("RCBusiness");
    Server server = mock(Server.class);
    ServicesManager services = mock(ServicesManager.class);
    when(owner.getServer()).thenReturn(server);
    when(server.getServicesManager()).thenReturn(services);
    LifecycleService lifecycle = mock(LifecycleService.class);
    when(services.getRegistration(LifecycleService.class))
        .thenReturn(
            new RegisteredServiceProvider<>(
                LifecycleService.class, lifecycle, ServicePriority.Normal, owner));
    doors =
        new RCPlatformDoorService(
            mock(AnimatedArchitectureSpigotPlatform.class), directory.resolve("claims.properties"));
  }

  StructureEventTogglePrepare event() {
    StructureSnapshot structure = mock(StructureSnapshot.class);
    when(structure.getUid()).thenReturn(42L);
    IPlayer player = mock(IPlayer.class);
    when(player.getUUID()).thenReturn(UUID.randomUUID());
    return new StructureEventTogglePrepare(
        structure,
        StructureActionCause.PLAYER,
        StructureActionType.OPEN,
        player,
        1.0,
        false,
        mock(Cuboid.class));
  }

  @Test
  void claimedDoorFailsClosedWithoutItsProviderIncludingAfterRestart() {
    assertTrue(doors.claim(owner, "42", organization));
    var request = event();
    doors.beforeToggle(request);
    assertTrue(request.isCancelled());
    RCPlatformDoorService restarted =
        new RCPlatformDoorService(
            mock(AnimatedArchitectureSpigotPlatform.class), directory.resolve("claims.properties"));
    var afterRestart = event();
    restarted.beforeToggle(afterRestart);
    assertTrue(afterRestart.isCancelled());
    assertEquals(Map.of("42", organization), restarted.claims(owner));
    assertThrows(UnsupportedOperationException.class, () -> restarted.claims(owner).clear());
  }

  @Test
  void everyNativeRequestRechecksPermissionsAndProviderRemovalRevokesAccess() {
    assertTrue(doors.claim(owner, "42", organization));
    AtomicReference<Decision> permission = new AtomicReference<>(Decision.ALLOW);
    var registration = doors.register(owner, (door, player, action) -> permission.get());
    var allowed = event();
    doors.beforeToggle(allowed);
    assertFalse(allowed.isCancelled());
    assertTrue(allowed.isAccessAuthorized());
    permission.set(Decision.DENY);
    var removedMember = event();
    doors.beforeToggle(removedMember);
    assertTrue(removedMember.isCancelled());
    registration.close();
    var providerGone = event();
    doors.beforeToggle(providerGone);
    assertTrue(providerGone.isCancelled());
    assertEquals(Map.of("42", organization), doors.claims(owner));
  }

  @Test
  void otherPluginAndOrganizationCannotReplaceOrReleaseAClaim() {
    Plugin stranger = mock(Plugin.class);
    when(stranger.getName()).thenReturn("OtherFeature");
    assertTrue(doors.claim(owner, "42", organization));
    assertFalse(doors.claim(stranger, "42", organization));
    assertFalse(doors.claim(owner, "42", UUID.randomUUID().toString()));
    assertFalse(doors.releaseClaim(stranger, "42", organization));
    assertFalse(doors.releaseClaim(owner, "42", UUID.randomUUID().toString()));
    assertTrue(doors.releaseClaim(owner, "42", organization));
    var released = event();
    doors.beforeToggle(released);
    assertFalse(released.isCancelled());
    assertFalse(released.isAccessAuthorized());
    assertTrue(doors.claims(owner).isEmpty());
  }

  @Test
  void explicitDenialWinsOverAnAllowance() {
    doors.register(owner, (door, player, action) -> Decision.ALLOW);
    doors.register(owner, (door, player, action) -> Decision.DENY);
    var request = event();
    doors.beforeToggle(request);
    assertTrue(request.isCancelled());
  }

  @Test
  void corruptClaimStorageNeverSilentlyRestoresPersonalAccess() throws Exception {
    Files.writeString(directory.resolve("claims.properties"), "42=invalid-record");
    assertThrows(
        IllegalStateException.class,
        () ->
            new RCPlatformDoorService(
                mock(AnimatedArchitectureSpigotPlatform.class),
                directory.resolve("claims.properties")));
  }

  @Test void automationCannotImpersonatePrimeOwnerForOutsiderOrRemovedMember() {
    assertTrue(doors.claim(owner, "42", organization));
    var primeOwner = UUID.randomUUID();
    doors.register(owner, (door, actor, action) -> actor.equals(primeOwner) ? Decision.ALLOW : Decision.DENY);
    for (var cause : List.of(StructureActionCause.REDSTONE, StructureActionCause.PROXIMITY)) {
      var template = event();
      when(template.getResponsible().getUUID()).thenReturn(primeOwner);
      var automated = new StructureEventTogglePrepare(template.getSnapshot(), cause, StructureActionType.OPEN,
          template.getResponsible(), 1.0, false, mock(Cuboid.class));
      doors.beforeToggle(automated);
      assertTrue(automated.isCancelled(), "Unknown outsider/removed member trigger must not borrow prime-owner identity");
    }
    var removedMember = event(); doors.beforeToggle(removedMember); assertTrue(removedMember.isCancelled());
  }

  @Test void trustedActorlessServerOperationsRemainUsableAndUnclaimedAutomationIsUnchanged() {
    var template = event();
    var redstone = new StructureEventTogglePrepare(template.getSnapshot(), StructureActionCause.REDSTONE,
        StructureActionType.OPEN, template.getResponsible(), 1.0, false, mock(Cuboid.class));
    doors.beforeToggle(redstone); assertFalse(redstone.isCancelled());
    assertTrue(doors.claim(owner, "42", organization));
    var server = new StructureEventTogglePrepare(template.getSnapshot(), StructureActionCause.SERVER,
        StructureActionType.OPEN, null, 1.0, false, mock(Cuboid.class));
    doors.beforeToggle(server); assertFalse(server.isCancelled());
  }

  @Test void nullPolicyResultFailsClosed() {
    doors.register(owner, (door, actor, action) -> null);
    var request = event(); doors.beforeToggle(request); assertTrue(request.isCancelled());
  }
}
