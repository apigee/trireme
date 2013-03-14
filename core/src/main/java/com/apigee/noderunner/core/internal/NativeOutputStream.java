package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.modules.Stream;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class NativeOutputStream
        extends Stream.WritableStream {
    public static final String CLASS_NAME = "_NativeOutputStream";

    private OutputStream out;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void initialize(OutputStream out)
    {
        this.out = out;
        setWritable(true);
    }

    public void setOutput(OutputStream out) {
        this.out = out;
    }

    @Override
    protected boolean write(Context cx, Object[] args) {
        String str = ArgUtils.stringArg(args, 0, "");
        String encoding = ArgUtils.stringArg(args, 1, Charsets.DEFAULT_ENCODING);
        Charset charset = Charsets.get().getCharset(encoding);

        try {
            out.write(str.getBytes(charset));
            out.flush();
        } catch (IOException e) {
            throw new EvaluatorException("Error on write: " + e.toString());
        }
        return true;
    }
}
