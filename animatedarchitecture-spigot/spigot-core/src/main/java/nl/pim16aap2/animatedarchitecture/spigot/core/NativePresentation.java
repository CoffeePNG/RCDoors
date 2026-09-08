package nl.pim16aap2.animatedarchitecture.spigot.core;

import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.republicraft.rcui.api.ButtonNamespace;
import net.republicraft.rcui.api.MessageBundle;
import net.republicraft.rcui.api.MissingMessageException;
import net.republicraft.rcui.api.RCUI;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** RCUI content and button compatibility boundary for the upstream native Text API. */
public final class NativePresentation
{
    private static final TagResolver POSITIONAL = new TagResolver()
    {
        @Override public boolean has(String name) { return name.matches("[0-9]+"); }
        @Override public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context context)
        {
            return has(name) ? Tag.inserting(Component.text("{" + name + "}")) : null;
        }
    };

    private final MessageBundle messages;
    private final ButtonNamespace buttons;
    private final Logger logger;
    private final Map<String, String> lastGood = new ConcurrentHashMap<>();
    private final Map<String, String> rejected = new ConcurrentHashMap<>();

    NativePresentation(AnimatedArchitecturePlugin plugin)
    {
        this(RCUI.messages(plugin).register(plugin, "rcdoors", "messages.yml"),
            RCUI.buttons(plugin, "rcdoors"), plugin.getLogger());
        buttons.registerDefaults("buttons.yml");
    }

    NativePresentation(MessageBundle messages, ButtonNamespace buttons, Logger logger)
    {
        this.messages = messages;
        this.buttons = buttons;
        this.logger = logger;
    }

    public ItemStack skin(String screen, String button, ItemStack original)
    {
        return buttons.screen(screen).apply(button, original);
    }

    /** Rich components can cross Cloud's Adventure boundary without parsing native player text. */
    public Component component(String key, Component nativeComponent)
    {
        return component(key, nativeComponent, new TagResolver[0]);
    }

    public Component component(String key, Component nativeComponent, TagResolver... arguments)
    {
        TagResolver[] all = new TagResolver[arguments.length + 1];
        all[0] = Placeholder.component("native", nativeComponent);
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return messages.component(key + ".text", all);
    }

    /**
     * Preserve native positional tokens for Text's styled/clickable argument processing. RCUI
     * converts legacy {0} placeholders to tags, so the numeric resolver restores those tokens.
     * Native fallback strings are inserted as literal text, never parsed as MiniMessage.
     */
    public String template(String key, String locale, String nativeText)
    {
        final String cacheKey = locale + ":" + key;
        final String rendered;
        try
        {
            rendered = PlainTextComponentSerializer.plainText().serialize(messages.component(
                key + ".text", Placeholder.unparsed("native", nativeText), POSITIONAL));
        }
        catch (MissingMessageException extensionKey)
        {
            // Third-party structure types may add keys after this JAR was built.
            return nativeText;
        }
        try
        {
            new MessageFormat(rendered);
            lastGood.put(cacheKey, rendered);
            rejected.remove(cacheKey);
            return rendered;
        }
        catch (IllegalArgumentException invalidPattern)
        {
            if (!rendered.equals(rejected.put(cacheKey, rendered)))
                logger.warning("RCUI rcdoors key '" + key
                    + "' has an invalid positional pattern; retaining its last valid value.");
            return lastGood.getOrDefault(cacheKey, nativeText);
        }
    }
}
