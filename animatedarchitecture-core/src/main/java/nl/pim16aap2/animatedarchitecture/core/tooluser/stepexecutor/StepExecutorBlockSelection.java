package nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor;

import lombok.ToString;
import nl.pim16aap2.animatedarchitecture.core.tooluser.BlockSelectionAction;
import nl.pim16aap2.animatedarchitecture.core.tooluser.ToolClick;
import nl.pim16aap2.animatedarchitecture.core.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A {@link StepExecutor} for the block selection step.
 * <p>
 * This step accepts two kinds of input: a {@link ToolClick} for every block the player clicks with the wand, and a
 * {@link BlockSelectionAction} for the actions the player can click in the chat (finishing the selection, undoing a
 * click, and so on).
 */
@ToString
public class StepExecutorBlockSelection extends StepExecutor
{
    @ToString.Exclude
    private final Function<ToolClick, CompletableFuture<Boolean>> clickHandler;

    @ToString.Exclude
    private final Function<BlockSelectionAction, CompletableFuture<Boolean>> actionHandler;

    public StepExecutorBlockSelection(
        Function<ToolClick, CompletableFuture<Boolean>> clickHandler,
        Function<BlockSelectionAction, CompletableFuture<Boolean>> actionHandler)
    {
        this.clickHandler = clickHandler;
        this.actionHandler = actionHandler;
    }

    @Override
    protected CompletableFuture<Boolean> protectedAcceptAsync(@Nullable Object input)
    {
        Util.requireNonNull(input, "Block selection input");

        return switch (input)
        {
            case ToolClick toolClick -> clickHandler.apply(toolClick);
            case BlockSelectionAction action -> actionHandler.apply(action);
            default -> throw new IllegalArgumentException(
                "Invalid input type for the block selection step: " + input.getClass().getName());
        };
    }

    @Override
    public Class<?> getInputClass()
    {
        // Both ToolClicks and BlockSelectionActions are accepted; the exact type is checked when the input is applied.
        return Object.class;
    }

    @Override
    public boolean isAsync()
    {
        return true;
    }
}
