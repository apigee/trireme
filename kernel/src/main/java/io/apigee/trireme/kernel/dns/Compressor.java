package io.apigee.trireme.kernel.dns;

import io.apigee.trireme.kernel.util.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 *  This class writes DNS names to byte buffers and stores the result.
 */

public class Compressor
{
    private static final Charset ASCII = Charset.forName("ascii");

    private final HashMap<String, Integer> state = new HashMap<String, Integer>();

    public boolean writeLabel(ByteBuffer bb, String label)
        throws DNSFormatException
    {
        // Label not written yet. Write it.
        byte[] la = label.getBytes(ASCII);
        if (la.length > Wire.MAX_LABEL_LEN) {
            throw new DNSFormatException("Label too long");
        }
        if ((la.length + 1) > bb.remaining()) {
            return false;
        }
        bb.put((byte)la.length);
        bb.put(la);
        return true;
    }

    private boolean writePointer(ByteBuffer bb, int pos)
    {
        // Label already written. Write a pointer.
        if (bb.remaining() < 2) {
            return false;
        }
        int ptr = ((pos & Wire.POINTER_MASK) | Wire.POINTER_FLAG) & 0xffff;
        bb.putShort((short)ptr);
        return true;
    }

    /**
     * Write an entire name to the output. If the output buffer is too small, then resize it and
     * return another one. Leave the position at the end of the output being written.
     */
    public ByteBuffer writeName(ByteBuffer b, String name)
        throws DNSFormatException
    {
        ByteBuffer bb = b;
        // DNS buffer compression -- if the rest of the name is already written, write a pointer
        Integer ptr = state.get(name);
        if (ptr == null) {
            state.put(name, bb.position());
            int dot = name.indexOf('.');
            String label = (dot < 0 ? name : name.substring(0, dot));

            while (!writeLabel(bb, label)) {
                bb = BufferUtils.doubleBuffer(bb);
            }

            if ((dot >= 0) && (name.length() > (dot + 1))) {
                return writeName(bb, name.substring(dot + 1));
            } else {
                // End of name.
                while (!bb.hasRemaining()) {
                    bb = BufferUtils.doubleBuffer(bb);
                }
                bb.put((byte)0);
                return bb;
            }
        }

        while (!writePointer(bb, ptr)) {
            bb = BufferUtils.doubleBuffer(bb);
        }
        return bb;
    }
}
