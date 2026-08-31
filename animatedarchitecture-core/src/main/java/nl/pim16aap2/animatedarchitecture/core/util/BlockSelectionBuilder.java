package nl.pim16aap2.animatedarchitecture.core.util;

import nl.pim16aap2.animatedarchitecture.core.util.vector.Vector3Di;
import org.jetbrains.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Builds up a {@link BlockSelection} one block at a time.
 * <p>
 * This is what backs the selection wand: every click either adds or removes a single position, and the player can undo
 * their clicks one by one or clear the whole selection. The builder keeps the positions in a set, so clicking the same
 * block twice does not select it twice.
 */
@ThreadSafe
public final class BlockSelectionBuilder
{
    private final Set<Vector3Di> positions = new LinkedHashSet<>();

    /**
     * The history of changes, used by {@link #undo()}. Each entry describes one accepted click.
     */
    private final Deque<Change> history = new ArrayDeque<>();

    /**
     * Adds a position to the selection.
     *
     * @param position
     *     The position to add.
     * @return True if the position was added, or false if it was already part of the selection.
     */
    public synchronized boolean add(Vector3Di position)
    {
        if (!positions.add(position))
            return false;
        history.push(new Change(position, true));
        return true;
    }

    /**
     * Adds all provided positions to the selection as a single undoable action.
     *
     * @param newPositions
     *     The positions to add.
     * @return The number of positions that were added.
     */
    public synchronized int addAll(Collection<Vector3Di> newPositions)
    {
        int added = 0;
        for (final Vector3Di position : newPositions)
            if (add(position))
                ++added;
        return added;
    }

    /**
     * Removes a position from the selection.
     *
     * @param position
     *     The position to remove.
     * @return True if the position was removed, or false if it was not part of the selection.
     */
    public synchronized boolean remove(Vector3Di position)
    {
        if (!positions.remove(position))
            return false;
        history.push(new Change(position, false));
        return true;
    }

    /**
     * Reverts the last accepted change.
     *
     * @return The position that was affected by the undone change, if there was anything left to undo.
     */
    public synchronized Optional<Vector3Di> undo()
    {
        final @Nullable Change change = history.poll();
        if (change == null)
            return Optional.empty();

        if (change.added())
            positions.remove(change.position());
        else
            positions.add(change.position());

        return Optional.of(change.position());
    }

    /**
     * Removes every position from the selection. This cannot be undone.
     */
    public synchronized void clear()
    {
        positions.clear();
        history.clear();
    }

    /**
     * @return True if the given position is currently selected.
     */
    public synchronized boolean contains(Vector3Di position)
    {
        return positions.contains(position);
    }

    /**
     * @return The number of currently selected positions.
     */
    public synchronized int size()
    {
        return positions.size();
    }

    /**
     * @return True if nothing is selected.
     */
    public synchronized boolean isEmpty()
    {
        return positions.isEmpty();
    }

    /**
     * @return An immutable copy of the currently selected positions.
     */
    public synchronized Set<Vector3Di> getPositions()
    {
        return Set.copyOf(positions);
    }

    /**
     * @return The smallest cuboid containing every selected position, or an empty optional when nothing is selected.
     */
    public synchronized Optional<Cuboid> getBoundingCuboid()
    {
        if (positions.isEmpty())
            return Optional.empty();
        return Optional.of(build().getBoundingCuboid());
    }

    /**
     * Builds the immutable selection.
     *
     * @return The new selection.
     *
     * @throws IllegalArgumentException
     *     If nothing is selected.
     */
    public synchronized BlockSelection build()
    {
        return BlockSelection.of(positions);
    }

    @Override
    public synchronized String toString()
    {
        return "BlockSelectionBuilder(size=" + positions.size() + ")";
    }

    /**
     * A single accepted click: the position it affected and whether it added (true) or removed (false) that position.
     */
    private record Change(Vector3Di position, boolean added) {}
}
