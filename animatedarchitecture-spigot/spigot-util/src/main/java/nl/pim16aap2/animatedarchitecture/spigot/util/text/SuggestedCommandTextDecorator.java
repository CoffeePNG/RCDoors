package nl.pim16aap2.animatedarchitecture.spigot.util.text;

import lombok.EqualsAndHashCode;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a text decorator for the Spigot platform that decorates text with links that put a command in the
 * player's chat box when clicked, without running it.
 * <p>
 * This is used for commands that the player still has to complete themselves.
 */
@EqualsAndHashCode
public final class SuggestedCommandTextDecorator implements ITextDecoratorSpigot
{
    /**
     * The command to suggest when the text is clicked.
     */
    private final String command;

    /**
     * The optional message to show when hovering over the text.
     */
    private final @Nullable String hoverMessage;

    public SuggestedCommandTextDecorator(String command, @Nullable String hoverMessage)
    {
        this.command = command;
        this.hoverMessage = hoverMessage;
    }

    @Override
    public void decorateComponent(BaseComponent component)
    {
        component.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command));
        if (hoverMessage != null)
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverMessage)));
    }
}
