package nl.pim16aap2.animatedarchitecture.core.util;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.EqualsAndHashCode;
import nl.pim16aap2.animatedarchitecture.core.util.vector.Vector3Di;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An immutable set of individually-selected block positions.
 * <p>
 * A structure always occupies a {@link Cuboid}: that is what determines where it lives in the world and how big it is.
 * A block selection refines that region by describing which blocks inside the bounding cuboid actually belong to the
 * structure. This makes it possible to create structures that are not shaped like a box, e.g. an arch or a circular
 * window, without having to animate the empty blocks around them.
 * <p>
 * The positions are stored as a bitmask over the bounding cuboid: one bit per position inside the cuboid, in
 * x-major/y/z order. This keeps both the in-memory and the serialized representation compact (a selection of 100.000
 * blocks takes ~12kB) and makes {@link #contains(int, int, int)} an O(1) operation, which matters because it is called
 * once for every block of every animation.
 * <p>
 * Instances of this class are immutable and thread-safe. Use {@link BlockSelectionBuilder} to build one up
 * incrementally.
 */
@EqualsAndHashCode
public final class BlockSelection
{
    private final Vector3Di min;
    private final Vector3Di max;

    /**
     * The bitmask over the bounding cuboid. See {@link #bitIndex(int, int, int)} for the layout.
     */
    private final byte[] mask;

    /**
     * The number of blocks in this selection. Cached, as it is used for size limits and price calculations.
     */
    private final int size;

    @JSONCreator
    public BlockSelection(
        @JSONField(name = "min") Vector3Di min,
        @JSONField(name = "max") Vector3Di max,
        @JSONField(name = "mask") byte[] mask)
    {
        this.min = min;
        this.max = max;
        this.mask = mask.clone();

        final int required = requiredMaskSize(min, max);
        if (this.mask.length != required)
            throw new IllegalArgumentException(
                "Mask of size " + this.mask.length + " does not fit cuboid [" + min + "; " + max + "]" +
                    ", which requires a mask of size " + required + "!");

        this.size = countBits(this.mask);
    }

    /**
     * Creates a new selection from a collection of positions.
     *
     * @param positions
     *     The selected positions. May not be empty.
     * @return The new selection.
     *
     * @throws IllegalArgumentException
     *     If the collection of positions is empty.
     */
    public static BlockSelection of(Collection<Vector3Di> positions)
    {
        if (positions.isEmpty())
            throw new IllegalArgumentException("Cannot create a block selection without any positions!");

        int xMin = Integer.MAX_VALUE;
        int yMin = Integer.MAX_VALUE;
        int zMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE;
        int yMax = Integer.MIN_VALUE;
        int zMax = Integer.MIN_VALUE;

        for (final Vector3Di position : positions)
        {
            xMin = Math.min(xMin, position.x());
            yMin = Math.min(yMin, position.y());
            zMin = Math.min(zMin, position.z());
            xMax = Math.max(xMax, position.x());
            yMax = Math.max(yMax, position.y());
            zMax = Math.max(zMax, position.z());
        }

        final Vector3Di min = new Vector3Di(xMin, yMin, zMin);
        final Vector3Di max = new Vector3Di(xMax, yMax, zMax);

        final byte[] mask = new byte[requiredMaskSize(min, max)];
        for (final Vector3Di position : positions)
        {
            final int bit = bitIndex(min, max, position.x(), position.y(), position.z());
            mask[bit >> 3] |= (byte) (1 << (bit & 7));
        }

        return new BlockSelection(min, max, mask);
    }

    /**
     * Creates a selection that contains every block in the given cuboid.
     *
     * @param cuboid
     *     The cuboid to fill.
     * @return The new selection.
     */
    public static BlockSelection ofCuboid(Cuboid cuboid)
    {
        final Vector3Di min = cuboid.getMin();
        final Vector3Di max = cuboid.getMax();

        final int volume = cuboid.getVolume();
        final byte[] mask = new byte[requiredMaskSize(min, max)];

        // Set every bit that is part of the cuboid; the trailing bits of the last byte stay unset.
        java.util.Arrays.fill(mask, 0, volume >> 3, (byte) 0xFF);
        for (int bit = volume & ~7; bit < volume; ++bit)
            mask[bit >> 3] |= (byte) (1 << (bit & 7));

        return new BlockSelection(min, max, mask);
    }

    /**
     * Checks whether the given position is part of this selection.
     *
     * @return True if the position is part of this selection.
     */
    public boolean contains(int x, int y, int z)
    {
        if (x < min.x() || x > max.x() || y < min.y() || y > max.y() || z < min.z() || z > max.z())
            return false;

        final int bit = bitIndex(min, max, x, y, z);
        return (mask[bit >> 3] & (1 << (bit & 7))) != 0;
    }

    /**
     * See {@link #contains(int, int, int)}.
     */
    public boolean contains(Vector3Di position)
    {
        return contains(position.x(), position.y(), position.z());
    }

    /**
     * Checks whether the given selection is a mask at all, i.e. whether it excludes any block of its own bounding
     * cuboid.
     * <p>
     * A selection that contains every block of its bounding cuboid behaves exactly like a regular cuboid structure, so
     * it does not have to be stored.
     *
     * @param selection
     *     The selection to check. May be null.
     * @return True if the selection is null or contains every block of its bounding cuboid.
     */
    public static boolean isFullCuboid(@Nullable BlockSelection selection)
    {
        return selection == null || selection.size == selection.getBoundingCuboid().getVolume();
    }

    /**
     * @return The smallest cuboid that contains every block in this selection.
     */
    public Cuboid getBoundingCuboid()
    {
        return new Cuboid(min, max);
    }

    /**
     * @return The number of blocks in this selection.
     */
    public int size()
    {
        return size;
    }

    /**
     * @return All positions in this selection, in insertion-independent (x, y, z) order.
     */
    public Set<Vector3Di> toSet()
    {
        final Set<Vector3Di> ret = new LinkedHashSet<>(size);
        for (int x = min.x(); x <= max.x(); ++x)
            for (int y = min.y(); y <= max.y(); ++y)
                for (int z = min.z(); z <= max.z(); ++z)
                    if (contains(x, y, z))
                        ret.add(new Vector3Di(x, y, z));
        return ret;
    }

    /**
     * The minimum position of the bounding cuboid. Used for serialization.
     */
    public Vector3Di getMin()
    {
        return min;
    }

    /**
     * The maximum position of the bounding cuboid. Used for serialization.
     */
    public Vector3Di getMax()
    {
        return max;
    }

    /**
     * The raw bitmask. Used for serialization.
     */
    public byte[] getMask()
    {
        return mask.clone();
    }

    private static int requiredMaskSize(Vector3Di min, Vector3Di max)
    {
        final long volume =
            (long) (max.x() - min.x() + 1) *
                (max.y() - min.y() + 1) *
                (max.z() - min.z() + 1);

        if (volume <= 0 || volume > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid selection bounds: [" + min + "; " + max + "]");

        return (int) ((volume + 7) / 8);
    }

    private static int bitIndex(Vector3Di min, Vector3Di max, int x, int y, int z)
    {
        final int sizeY = max.y() - min.y() + 1;
        final int sizeZ = max.z() - min.z() + 1;

        return ((x - min.x()) * sizeY + (y - min.y())) * sizeZ + (z - min.z());
    }

    private static int countBits(byte[] mask)
    {
        int count = 0;
        for (final byte b : mask)
            count += Integer.bitCount(b & 0xFF);
        return count;
    }

    @Override
    public String toString()
    {
        return "BlockSelection(min=" + min + ", max=" + max + ", size=" + size + ")";
    }
}
