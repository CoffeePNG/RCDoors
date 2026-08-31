package nl.pim16aap2.animatedarchitecture.core.tooluser;

import java.util.Locale;
import java.util.Optional;

/**
 * The actions a player can perform during the block selection step of a creation process.
 * <p>
 * Clicking blocks with the wand is handled by {@link ToolClick}; these are the actions that go with it and that are
 * offered as clickable options in the instructions of the step.
 */
public enum BlockSelectionAction
{
    /**
     * Finishes the selection and moves on to the next step.
     */
    DONE,

    /**
     * Selects every block in the region that was selected with the two corners, i.e. the behavior of structures that do
     * not use a block selection.
     */
    FILL,

    /**
     * Reverts the last click.
     */
    UNDO,

    /**
     * Removes every selected block.
     */
    CLEAR;

    /**
     * Parses an action from user input.
     *
     * @param input
     *     The name of the action. Case-insensitive.
     * @return The action, if the input describes one.
     */
    public static Optional<BlockSelectionAction> parse(String input)
    {
        for (final BlockSelectionAction action : values())
            if (action.name().equalsIgnoreCase(input))
                return Optional.of(action);
        return Optional.empty();
    }

    /**
     * @return The name of this action as it is used in commands.
     */
    public String getCommandName()
    {
        return name().toLowerCase(Locale.ROOT);
    }
}
