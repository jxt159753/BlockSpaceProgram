package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import java.io.IOException;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SurfaceMapTransferTest {
    @Test
    public void splitsAndAssemblesAtTheCompressedLimit() throws IOException {
        var data = new byte[SurfaceMapTransfer.MAX_COMPRESSED_BYTES];
        new Random(89).nextBytes(data);
        var fragments = SurfaceMapTransfer.split(data);
        assertEquals(4, fragments.size());
        var assembler = new SurfaceMapTransfer.Assembler();
        for (int index = fragments.size() - 1; index >= 0; index--) {
            assertEquals(SurfaceMapTransfer.FRAGMENT_BYTES, fragments.get(index).length);
            assertEquals(index == 0, assembler.add(index, fragments.size(), fragments.get(index)));
        }
        assertArrayEquals(data, assembler.assemble());
    }

    @Test
    public void rejectsInvalidFragmentSets() throws IOException {
        byte[] part = {1};
        var assembler = new SurfaceMapTransfer.Assembler();
        assertThrows(IOException.class, () -> assembler.add(0, 5, part));
        assertThrows(IOException.class, () -> assembler.add(-1, 2, part));
        assertThrows(IOException.class, () -> assembler.add(0, 2, new byte[SurfaceMapTransfer.FRAGMENT_BYTES + 1]));
        assertFalse(assembler.add(0, 2, part));
        assertThrows(IOException.class, assembler::assemble);
        assertThrows(IOException.class, () -> assembler.add(0, 2, part));
        assertThrows(IOException.class, () -> assembler.add(1, 3, part));
        assertThrows(IOException.class, () -> SurfaceMapTransfer.split(new byte[SurfaceMapTransfer.MAX_COMPRESSED_BYTES + 1]));
    }

    @Test
    public void sendsEachBodyAndHashOncePerConnectionIncludingHashReversions() {
        var first = new SurfaceMapTransfer.SendHistory<String>();
        assertTrue(first.markSent("overworld", "old"));
        for (int attempt = 0; attempt < 9; attempt++) {
            assertFalse(first.markSent("overworld", "old"));
        }
        assertTrue(first.markSent("overworld", "new"));
        assertFalse(first.markSent("overworld", "old"));
        assertTrue(first.markSent("moon", "old"));
        assertTrue(new SurfaceMapTransfer.SendHistory<String>().markSent("overworld", "old"));
    }
}
