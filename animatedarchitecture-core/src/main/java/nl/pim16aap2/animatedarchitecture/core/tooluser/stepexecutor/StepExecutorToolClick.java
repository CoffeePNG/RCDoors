package nl.pim16aap2.animatedarchitecture.core.tooluser.stepexecutor;

import lombok.ToString;
import nl.pim16aap2.animatedarchitecture.core.tooluser.ToolClick;
import nl.pim16aap2.animatedarchitecture.core.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A {@link StepExecutor} for steps that handle both mouse buttons of the tool.
 * <p>
 * See {@link ToolClick}.
 */
@ToString
public class StepExecutorToolClick extends StepExecutor
{
    @ToString.Exclude
    private final Function<ToolClick, CompletableFuture<Boolean>> fun;

    public StepExecutorToolClick(Function<ToolClick, CompletableFuture<Boolean>> fun)
    {
        this.fun = fun;
    }

    @Override
    protected CompletableFuture<Boolean> protectedAcceptAsync(@Nullable Object input)
    {
        Util.requireNonNull(input, "ToolClick input");
        return fun.apply((ToolClick) input);
    }

    @Override
    public Class<?> getInputClass()
    {
        return ToolClick.class;
    }

    @Override
    public boolean isAsync()
    {
        return true;
    }
}
