package nl.pim16aap2.animatedarchitecture.core.util;

import com.alibaba.fastjson2.JSON;
import nl.pim16aap2.animatedarchitecture.core.util.vector.Vector3Di;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class BlockSelectionTest
{
    @Test
    void testContainsOnlySelectedPositions()
    {
        final BlockSelection selection = BlockSelection.of(List.of(
            new Vector3Di(0, 0, 0),
            new Vector3Di(2, 3, 4)
        ));

        Assertions.assertTrue(selection.contains(0, 0, 0));
        Assertions.assertTrue(selection.contains(2, 3, 4));
        Assertions.assertFalse(selection.contains(1, 1, 1));
        Assertions.assertFalse(selection.contains(-1, 0, 0));
        Assertions.assertEquals(2, selection.size());
    }

    @Test
    void testBoundingCuboid()
    {
        final BlockSelection selection = BlockSelection.of(List.of(
            new Vector3Di(5, 1, -3),
            new Vector3Di(-2, 7, 4)
        ));

        Assertions.assertEquals(
            new Cuboid(new Vector3Di(-2, 1, -3), new Vector3Di(5, 7, 4)),
            selection.getBoundingCuboid()
        );
    }

    @Test
    void testFullCuboid()
    {
        final Cuboid cuboid = new Cuboid(new Vector3Di(0, 0, 0), new Vector3Di(2, 2, 2));
        final BlockSelection full = BlockSelection.ofCuboid(cuboid);

        Assertions.assertEquals(cuboid.getVolume(), full.size());
        Assertions.assertEquals(cuboid, full.getBoundingCuboid());
        Assertions.assertTrue(BlockSelection.isFullCuboid(full));
        Assertions.assertTrue(BlockSelection.isFullCuboid(null));

        for (int x = 0; x <= 2; ++x)
            for (int y = 0; y <= 2; ++y)
                for (int z = 0; z <= 2; ++z)
                    Assertions.assertTrue(full.contains(x, y, z));
    }

    @Test
    void testPartialSelectionIsNotAFullCuboid()
    {
        final BlockSelection selection = BlockSelection.of(List.of(
            new Vector3Di(0, 0, 0),
            new Vector3Di(1, 1, 1)
        ));

        Assertions.assertFalse(BlockSelection.isFullCuboid(selection));
    }

    @Test
    void testToSetReturnsEverySelectedPosition()
    {
        final Set<Vector3Di> positions = Set.of(
            new Vector3Di(1, 2, 3),
            new Vector3Di(1, 2, 4),
            new Vector3Di(4, 2, 3)
        );

        Assertions.assertEquals(positions, BlockSelection.of(positions).toSet());
    }

    @Test
    void testSerializationRoundTrip()
    {
        final BlockSelection selection = BlockSelection.of(List.of(
            new Vector3Di(10, 20, 30),
            new Vector3Di(12, 20, 30),
            new Vector3Di(11, 22, 31)
        ));

        final BlockSelection deserialized = JSON.parseObject(JSON.toJSONString(selection), BlockSelection.class);

        Assertions.assertEquals(selection, deserialized);
        Assertions.assertEquals(selection.toSet(), deserialized.toSet());
    }

    @Test
    void testEmptySelectionIsRejected()
    {
        Assertions.assertThrows(IllegalArgumentException.class, () -> BlockSelection.of(List.of()));
    }

    @Test
    void testBuilderAddsRemovesAndUndoes()
    {
        final BlockSelectionBuilder builder = new BlockSelectionBuilder();
        final Vector3Di position = new Vector3Di(1, 1, 1);

        Assertions.assertTrue(builder.isEmpty());
        Assertions.assertTrue(builder.add(position));
        // Adding the same position twice does not select it twice.
        Assertions.assertFalse(builder.add(position));
        Assertions.assertEquals(1, builder.size());

        Assertions.assertTrue(builder.remove(position));
        Assertions.assertFalse(builder.remove(position));
        Assertions.assertTrue(builder.isEmpty());

        // Undo the removal, then the addition.
        Assertions.assertEquals(position, builder.undo().orElseThrow());
        Assertions.assertTrue(builder.contains(position));
        Assertions.assertEquals(position, builder.undo().orElseThrow());
        Assertions.assertTrue(builder.isEmpty());
        Assertions.assertTrue(builder.undo().isEmpty());
    }

    @Test
    void testBuilderClear()
    {
        final BlockSelectionBuilder builder = new BlockSelectionBuilder();
        builder.addAll(List.of(new Vector3Di(0, 0, 0), new Vector3Di(1, 0, 0)));
        Assertions.assertEquals(2, builder.size());

        builder.clear();

        Assertions.assertTrue(builder.isEmpty());
        Assertions.assertTrue(builder.undo().isEmpty());
        Assertions.assertTrue(builder.getBoundingCuboid().isEmpty());
    }
}
