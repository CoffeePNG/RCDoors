package nl.pim16aap2.animatedarchitecture.core.tooluser;

import nl.pim16aap2.animatedarchitecture.core.api.ILocation;

/**
 * Represents a click on a block with the AnimatedArchitecture tool.
 * <p>
 * Most steps only care about the location that was clicked, but steps like the block selection step distinguish between
 * the two mouse buttons: left-click selects a block and right-click deselects it. Steps that do not accept a
 * {@link ToolClick} are given the {@link #location()} of a left-click instead, which is the behavior every step had
 * before the selection wand was introduced.
 *
 * @param location
 *     The location of the block that was clicked.
 * @param button
 *     The mouse button that was used.
 */
public record ToolClick(ILocation location, Button button)
{
    /**
     * @return True if this click was made with the left mouse button.
     */
    public boolean isLeftClick()
    {
        return button == Button.LEFT;
    }

    /**
     * The mouse button used for a click.
     */
    public enum Button
    {
        LEFT,
        RIGHT
    }
}
