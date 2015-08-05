package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;

import java.nio.ByteBuffer;

public class TestCommand
{
    private String command;
    private byte[] data;

    public boolean read(ByteBuffer buf)
    {
        if (buf.remaining() < 8) {
            return false;
        }

        ByteBuffer tmp = buf.duplicate();
        byte[] cmdBuf = new byte[4];
        tmp.get(cmdBuf);
        command = new String(cmdBuf, Charsets.ASCII);
        int dataLen = tmp.getInt();

        if (buf.remaining() < (dataLen + 8)) {
            return false;
        }

        buf.position(buf.position() + 8);
        data = new byte[dataLen];
        buf.get(data);
        return true;
    }

    public String getCommand() {
        return command;
    }

    public byte[] getData() {
        return data;
    }

    public static ByteBuffer makeCommand(String cmd, byte[] data)
    {
        assert(cmd.length() == 4);
        int dataLen = (data == null ? 0 : data.length);
        ByteBuffer buf = ByteBuffer.allocate(dataLen + 8);
        buf.put(cmd.getBytes(Charsets.ASCII));
        buf.putInt(dataLen);
        if (dataLen > 0) {
            buf.put(data);
        }
        buf.flip();
        return buf;
    }
}
