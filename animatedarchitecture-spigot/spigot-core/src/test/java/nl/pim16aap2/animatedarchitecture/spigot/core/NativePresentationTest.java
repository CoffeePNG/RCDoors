package nl.pim16aap2.animatedarchitecture.spigot.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import net.md_5.bungee.api.chat.BaseComponent;
import net.republicraft.rcui.api.ButtonNamespace;
import net.republicraft.rcui.api.ButtonScreen;
import net.republicraft.rcui.service.MessageServiceImpl;
import nl.pim16aap2.animatedarchitecture.core.localization.ILocalizer;
import nl.pim16aap2.animatedarchitecture.core.localization.LocalizationManager;
import nl.pim16aap2.animatedarchitecture.core.text.TextType;
import nl.pim16aap2.animatedarchitecture.spigot.core.implementations.RcuiLocalizer;
import nl.pim16aap2.animatedarchitecture.spigot.core.implementations.TextFactorySpigot;
import nl.pim16aap2.animatedarchitecture.spigot.util.text.TextRendererSpigot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativePresentationTest
{
    @TempDir Path directory;
    private MessageServiceImpl service;
    private NativePresentation presentation;
    private AnimatedArchitecturePlugin plugin;
    private ButtonNamespace buttons;

    @BeforeEach
    void setup() throws Exception
    {
        JavaPlugin host = mock(JavaPlugin.class);
        when(host.getDataFolder()).thenReturn(directory.resolve("RCUI").toFile());
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        service = new MessageServiceImpl(host);
        // Initialize the Surefire-selected Flogger backend before mocking the native plugin initializer.
        com.google.common.flogger.FluentLogger.forEnclosingClass();
        plugin = mock(AnimatedArchitecturePlugin.class);
        when(plugin.getName()).thenReturn("RCDoors");
        when(plugin.getDataFolder()).thenReturn(directory.resolve("RCDoors").toFile());
        when(plugin.getResource("messages.yml")).thenAnswer(call ->
            Files.newInputStream(Path.of("src/main/resources/messages.yml")));
        buttons = mock(ButtonNamespace.class);
        presentation = new NativePresentation(service.register(plugin, "rcdoors", "messages.yml"),
            buttons, Logger.getAnonymousLogger());
        when(plugin.getNativePresentation()).thenReturn(presentation);
    }

    private Path catalog() { return directory.resolve("RCUI/messages/rcdoors.yml"); }

    private void override(String key, String value) throws Exception
    {
        var yaml = new YamlConfiguration();
        yaml.load(catalog().toFile());
        yaml.set("messages." + key + ".text", value);
        yaml.save(catalog().toFile());
        assertTrue(service.reload().valid());
    }

    @Test
    void nativeLocalesAndUnparsedFallbacksRemainAvailable() throws Exception
    {
        var manager = mock(LocalizationManager.class);
        var nativeLocalizer = mock(ILocalizer.class);
        when(manager.getLocalizer()).thenReturn(nativeLocalizer);
        when(nativeLocalizer.getMessage("commands.version.success")).thenReturn("Version {0}");
        when(nativeLocalizer.getMessage("commands.version.success", Locale.GERMAN)).thenReturn("Fassung {0}");
        when(nativeLocalizer.getAvailableLocales()).thenReturn(List.of(Locale.ENGLISH, Locale.GERMAN));
        var localizer = new RcuiLocalizer(plugin, manager);
        assertEquals("Version 4", localizer.getMessage("commands.version.success", "4"));
        assertEquals("Fassung 4", localizer.getMessage("commands.version.success", Locale.GERMAN, "4"));
        assertEquals(List.of(Locale.ENGLISH, Locale.GERMAN), localizer.getAvailableLocales());
        assertEquals("<red>literal fallback</red>", presentation.template(
            "commands.version.success", "default", "<red>literal fallback</red>"));
    }

    @Test
    void centralOverridesPreservePositionalFormattingAndNeverParsePlayerArguments() throws Exception
    {
        override("commands.version.success", "<green>Running {0}</green>");
        var manager = mock(LocalizationManager.class);
        var nativeLocalizer = mock(ILocalizer.class);
        when(manager.getLocalizer()).thenReturn(nativeLocalizer);
        when(nativeLocalizer.getMessage("commands.version.success")).thenReturn("Native {0}");
        var localizer = new RcuiLocalizer(plugin, manager);
        assertEquals("Running {0}", localizer.getMessage("commands.version.success"));
        assertEquals("Running <click:run_command:'/op me'>name</click>", localizer.getMessage(
            "commands.version.success", "<click:run_command:'/op me'>name</click>"));
        assertEquals("Running O'Brien", localizer.getMessage("commands.version.success", "O'Brien"));
    }

    @Test
    void nativeClickableAndHighlightDecoratorsSurviveCatalogOverrides() throws Exception
    {
        override("commands.version.success", "<green>Review {0}: {1}</green>");
        String template = presentation.template("commands.version.success", "default", "{0} {1}");
        var text = new TextFactorySpigot().newText().append(template, TextType.INFO,
            argument -> argument.highlight("<red>literal player</red>"),
            argument -> argument.clickable("Confirm", "/animatedarchitecture confirm"));
        BaseComponent[] rendered = text.render(new TextRendererSpigot());
        assertEquals("Review <red>literal player</red>: Confirm", BaseComponent.toPlainText(rendered));
        assertTrue(Arrays.stream(rendered).anyMatch(component -> component.getClickEvent() != null
            && component.getClickEvent().getValue().equals("/animatedarchitecture confirm")));
        assertTrue(Arrays.stream(rendered).filter(component -> component.toPlainText().contains("literal player"))
            .allMatch(component -> component.getClickEvent() == null));
    }

    @Test
    void invalidYamlReloadAndInvalidPositionalOverrideKeepLastGoodContent() throws Exception
    {
        override("commands.version.success", "Stable {0}");
        assertEquals("Stable {0}", presentation.template("commands.version.success", "default", "Native {0}"));
        String valid = Files.readString(catalog());
        Files.writeString(catalog(), "messages: [broken");
        assertFalse(service.reload().valid());
        assertEquals("Stable {0}", presentation.template("commands.version.success", "default", "Native {0}"));
        Files.writeString(catalog(), valid);
        override("commands.version.success", "Broken {unclosed");
        assertEquals("Stable {0}", presentation.template("commands.version.success", "default", "Native {0}"));
        override("commands.version.success", "Recovered {0}");
        assertEquals("Recovered {0}", presentation.template("commands.version.success", "default", "Native {0}"));
    }

    @Test
    void thirdPartyExtensionKeysRetainTheirNativeText()
    {
        assertEquals("Extension {0}", presentation.template("external.extension.new_key", "default", "Extension {0}"));
    }

    @Test
    void buttonSkinningUsesStableNamespaceScreensAndPreservesProviderItem()
    {
        ButtonScreen screen = mock(ButtonScreen.class);
        ItemStack nativeItem = mock(ItemStack.class);
        ItemStack skinned = mock(ItemStack.class);
        when(buttons.screen("info")).thenReturn(screen);
        when(screen.apply("unlock", nativeItem)).thenReturn(skinned);
        assertSame(skinned, presentation.skin("info", "unlock", nativeItem));
        verify(screen).apply("unlock", nativeItem);
    }

    @Test
    void everyBundledNativeKeyAndAssembledGuiButtonHasACentralDefault() throws Exception
    {
        var defaults = new YamlConfiguration();
        defaults.load(Path.of("src/main/resources/messages.yml").toFile());
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("structures"))) root = root.getParent();
        int nativeKeys = 0;
        try (var paths = Files.walk(root))
        {
            for (Path path : paths.filter(file -> file.toString().replace('\\', '/').contains("/src/main/resources/"))
                .filter(file -> file.getFileName().toString().endsWith(".properties"))
                .filter(file -> !file.getFileName().toString().contains("_"))
                .filter(file -> Character.isUpperCase(file.getFileName().toString().charAt(0))).toList())
            {
                Properties properties = new Properties();
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
                for (String key : properties.stringPropertyNames())
                {
                    assertEquals("<native>", defaults.getString(key + ".text"), key);
                    nativeKeys++;
                }
            }
        }
        assertTrue(nativeKeys >= 300);
        var buttonDefaults = new YamlConfiguration();
        buttonDefaults.load(Path.of("src/main/resources/buttons.yml").toFile());
        var pattern = java.util.regex.Pattern.compile("GuiUtil\\.skin\\([^,]+,\\s*\"([^\"]+)\",\\s*\"([^\"]+)\"");
        Set<String> used = new HashSet<>();
        try (var paths = Files.list(Path.of("src/main/java/nl/pim16aap2/animatedarchitecture/spigot/core/gui")))
        {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList())
            {
                String source = Files.readString(path);
                var matcher = pattern.matcher(source);
                while (matcher.find())
                {
                    String id = matcher.group(1) + "." + matcher.group(2);
                    assertTrue(buttonDefaults.isConfigurationSection(id), id);
                    used.add(id);
                }
                assertFalse(source.contains("setFiller(FILLER)"), "Filler skins must also use RCUI");
            }
        }
        assertEquals(31, used.size());
    }

    @Test
    void cloudMessagesPreserveRichNativeComponentsAndLiteralUntrustedText() throws Exception
    {
        var nativeMessage = net.kyori.adventure.text.Component.text("<red>literal name</red>")
            .append(net.kyori.adventure.text.Component.text(" Confirm")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/rcdoors confirm")));
        var result = presentation.component("cloud.exception.decorated", nativeMessage);
        assertEquals("[RCDoors] <red>literal name</red> Confirm",
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(result));
        assertEquals(1, countConfirmationClicks(result));
        override("cloud.exception.decorated", "<blue>Notice:</blue> <native>");
        assertEquals("Notice: <red>literal name</red> Confirm",
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(presentation.component("cloud.exception.decorated", nativeMessage)));
    }

    private static int countConfirmationClicks(net.kyori.adventure.text.Component component)
    {
        int own = net.kyori.adventure.text.event.ClickEvent.runCommand("/rcdoors confirm")
            .equals(component.clickEvent()) ? 1 : 0;
        return own + component.children().stream().mapToInt(NativePresentationTest::countConfirmationClicks).sum();
    }

    @Test
    void everyCloudHelpAndExceptionMessageHasAReloadableDefault() throws Exception
    {
        var defaults = new YamlConfiguration();
        defaults.load(Path.of("src/main/resources/messages.yml").toFile());
        String source = Files.readString(Path.of("src/main/java/nl/pim16aap2/animatedarchitecture/spigot/core/comands/NativeCommandPresentation.java"));
        var helpKeys = java.util.regex.Pattern.compile("\"(cloud\\.help\\.[a-z_-]+)\"").matcher(source);
        int covered = 0;
        while (helpKeys.find()) { assertTrue(defaults.isString(helpKeys.group(1) + ".text"), helpKeys.group(1)); covered++; }
        assertTrue(covered >= 8);
        for (String key : List.of("invalid-syntax", "invalid-sender", "no-permission", "argument-parsing", "command-execution", "decorated"))
            assertTrue(defaults.isString("cloud.exception." + key + ".text"));
        assertTrue(defaults.isString("system.initialization-error.text"));
        assertTrue(defaults.isString("system.unavailable.text"));
    }

    @Test
    void startupFallbackCommandActuallyInstallsItsHandlerAndUsesCentralMessages()
    {
        var description = mock(org.bukkit.plugin.PluginDescriptionFile.class);
        when(plugin.getDescription()).thenReturn(description);
        when(description.getCommands()).thenReturn(Map.of("rcdoors", Map.of()));
        var command = mock(org.bukkit.command.PluginCommand.class);
        when(plugin.getCommand("rcdoors")).thenReturn(command);
        var listener = new nl.pim16aap2.animatedarchitecture.spigot.core.listeners.BackupCommandListener(plugin, "Detailed failure");
        verify(command).setExecutor(listener);
        var player = mock(org.bukkit.entity.Player.class,
            withSettings().extraInterfaces(net.kyori.adventure.audience.Audience.class));
        assertTrue(listener.onCommand(player, command, "rcdoors", new String[0]));
        var message = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify((net.kyori.adventure.audience.Audience) player).sendMessage(message.capture());
        String content = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(message.getValue());
        assertTrue(content.contains("unavailable"));
        assertFalse(content.contains("Detailed failure"));
    }
}
