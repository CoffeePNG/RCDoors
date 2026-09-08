package nl.pim16aap2.animatedarchitecture.spigot.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.commandframework.CommandHelpHandler;
import cloud.commandframework.CommandManager;
import cloud.commandframework.exceptions.*;
import cloud.commandframework.permission.CommandPermission;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.republicraft.rcui.api.ButtonNamespace;
import net.republicraft.rcui.service.MessageServiceImpl;
import nl.pim16aap2.animatedarchitecture.spigot.core.comands.NativeCommandPresentation;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NativeCommandPresentationTest {
  @TempDir Path directory;
  CommandManager<String> manager;
  NativeCommandPresentation<String> handler;
  List<Component> messages = new ArrayList<>();
  Map<Class<?>, BiConsumer<String, Exception>> errors = new HashMap<>();
  List<CommandHelpHandler.VerboseHelpEntry<String>> entries = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    JavaPlugin host = mock(JavaPlugin.class);
    when(host.getDataFolder()).thenReturn(directory.resolve("RCUI").toFile());
    when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
    var service = new MessageServiceImpl(host);
    var plugin = mock(JavaPlugin.class);
    when(plugin.getName()).thenReturn("RCDoors");
    when(plugin.getDataFolder()).thenReturn(directory.resolve("RCDoors").toFile());
    when(plugin.getResource("messages.yml"))
        .thenAnswer(call -> Files.newInputStream(Path.of("src/main/resources/messages.yml")));
    var ui =
        new NativePresentation(
            service.register(plugin, "rcdoors", "messages.yml"),
            mock(ButtonNamespace.class),
            Logger.getAnonymousLogger());
    manager = mock(CommandManager.class, RETURNS_DEEP_STUBS);
    doAnswer(
            call -> {
              errors.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(manager)
        .registerExceptionHandler(any(), any());
    when(manager.getCommandHelpHandler().getAllCommands()).thenReturn(entries);
    when(manager.hasPermission(anyString(), any(CommandPermission.class))).thenReturn(true);
    Audience target = mock(Audience.class);
    doAnswer(
            call -> {
              messages.add(call.getArgument(0));
              return null;
            })
        .when(target)
        .sendMessage(any(Component.class));
    handler = new NativeCommandPresentation<>(manager, () -> ui, sender -> target);
  }

  String text() {
    return messages.stream()
        .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
        .reduce("", (a, b) -> a + "\n" + b);
  }

  @Test
  void allFiveErrorPathsRenderThroughRcuiOnAdventureFiveWithoutExtras() {
    handler.install();
    String unsafe = "<click:run_command:/op>literal</click>";
    errors
        .get(InvalidSyntaxException.class)
        .accept("player", new InvalidSyntaxException("rcdoors " + unsafe, "player", List.of()));
    errors
        .get(InvalidCommandSenderException.class)
        .accept("player", new InvalidCommandSenderException("player", UUID.class, List.of()));
    errors
        .get(NoPermissionException.class)
        .accept(
            "player",
            new NoPermissionException(
                cloud.commandframework.permission.Permission.of("rcdoors.admin"),
                "player",
                List.of()));
    errors
        .get(ArgumentParseException.class)
        .accept(
            "player",
            new ArgumentParseException(new IllegalArgumentException(unsafe), "player", List.of()));
    errors
        .get(CommandExecutionException.class)
        .accept(
            "player", new CommandExecutionException(new IllegalStateException("private details")));
    assertEquals(5, messages.size());
    assertTrue(text().contains(unsafe));
    assertFalse(text().contains("private details"));
    assertTrue(text().contains("RCDoors"));
    assertTrue(messages.stream().allMatch(message -> message.clickEvent() == null));
  }

  @SuppressWarnings("unchecked")
  CommandHelpHandler.VerboseHelpEntry<String> entry(String name) {
    var entry =
        (CommandHelpHandler.VerboseHelpEntry<String>)
            mock(CommandHelpHandler.VerboseHelpEntry.class, RETURNS_DEEP_STUBS);
    when(entry.getSyntaxString()).thenReturn("rcdoors " + name);
    when(entry.getDescription()).thenReturn("Description " + name);
    when(entry.getCommand().getSenderType()).thenReturn(Optional.empty());
    entries.add(entry);
    return entry;
  }

  @Test
  void helpPagesCoverAllAllowedCommandsAndFilterHiddenOrUnauthorizedCommands() {
    for (int i = 1; i <= 10; i++) entry("command" + i);
    var hidden = entry("secret");
    when(hidden.getCommand().isHidden()).thenReturn(true);
    var denied = entry("denied");
    when(manager.hasPermission("player", denied.getCommand().getCommandPermission()))
        .thenReturn(false);
    handler.help("", "player");
    assertTrue(text().contains("command8"));
    assertFalse(text().contains("command9"));
    assertFalse(text().contains("secret"));
    assertFalse(text().contains("denied"));
    messages.clear();
    handler.help("2", "player");
    assertTrue(text().contains("command9"));
    assertTrue(text().contains("command10"));
    assertFalse(text().contains("command1 —"));
    messages.clear();
    handler.help("9999999999999999", "player");
    assertTrue(text().contains("Must be between 1 and 2"));
  }
}
