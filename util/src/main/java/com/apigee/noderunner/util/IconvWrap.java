package com.apigee.noderunner.util;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.Utils;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.Constants;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.UnmappableCharacterException;

public class IconvWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "iconv-wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, IconvModuleImpl.class);
        Scriptable exports = cx.newObject(global, IconvModuleImpl.CLASS_NAME);
        ScriptableObject.defineClass(exports, IconvImpl.class);
        return exports;
    }

    public static class IconvModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_iconvModule";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Object encodeString(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            String str = stringArg(args, 0);
            String encoding = stringArg(args, 1);
            IconvModuleImpl self = (IconvModuleImpl)thisObj;

            Charset cs = self.getCharset(cx, funObj, encoding);
            CharsetEncoder enc = cs.newEncoder();
            enc.onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer decoded;
            try {
                decoded = enc.encode(CharBuffer.wrap(str));
            } catch (CharacterCodingException cce) {
                throw Utils.makeError(cx, funObj, cce.toString(), Constants.EINVAL);
            }

            return Buffer.BufferImpl.newBuffer(cx, funObj, decoded, false);
        }

        @JSFunction
        public static Object decodeBuffer(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            String encoding = stringArg(args, 1);
            IconvModuleImpl self = (IconvModuleImpl)thisObj;

            Charset cs = self.getCharset(cx, funObj, encoding);
            CharsetDecoder dec = cs.newDecoder();
            dec.onUnmappableCharacter(CodingErrorAction.REPLACE);
            CharBuffer decoded;
            try {
                decoded = dec.decode(buf.getBuffer());
            } catch (CharacterCodingException cce) {
                throw Utils.makeError(cx, funObj, cce.toString(), Constants.EINVAL);
            }

            return decoded.toString();
        }

        @JSFunction
        public static Object encodingExists(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            String encoding = stringArg(args, 0);

            return Context.javaToJS(Charset.isSupported(encoding), funObj);
        }

        private Charset getCharset(Context cx, Scriptable scope, String n)
        {
            try {
                // Check the alias first, and for charsets like "base64" and "binary"
                Charset cs = Charsets.get().resolveCharset(n);
                if (cs == null) {
                    cs = Charset.forName(n);
                }
                return cs;
            } catch (IllegalArgumentException ie) {
                throw Utils.makeError(cx, scope, "Invalid character set " + n, Constants.EINVAL);
            }
        }
    }

    public static class IconvImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Converter";

        private CharsetConverter converter;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSConstructor
        public static Object init(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            String from = stringArg(args, 0);
            String to = stringArg(args, 1);

            IconvImpl self = new IconvImpl();

            try {
                self.converter = new CharsetConverter(from, to);
            } catch (IllegalArgumentException ia) {
                throw Utils.makeError(cx, ctorObj, "Invalid character set", Constants.EINVAL);
            }

            return self;
        }

        @JSFunction
        public static Object convert(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            ensureArg(args, 0);
            boolean lastChunk = booleanArg(args, 1, true);
            ByteBuffer inBuf;
            IconvImpl self = (IconvImpl)thisObj;

            if (args[0] == null) {
                inBuf = null;
            } else {
                Buffer.BufferImpl b = objArg(args, 0, Buffer.BufferImpl.class, true);
                inBuf = b.getBuffer();
            }

            try {
                ByteBuffer result = self.converter.convert(inBuf, lastChunk);
                if (lastChunk) {
                    self.converter.reset();
                }

                if (result == null) {
                    return null;
                }
                return Buffer.BufferImpl.newBuffer(cx, funObj, result, false);

            } catch (UnmappableCharacterException uce) {
                throw Utils.makeError(cx, funObj, uce.toString(), Constants.EILSEQ);
            } catch (CharacterCodingException cce) {
                throw Utils.makeError(cx, funObj, cce.toString(), Constants.EINVAL);
            }
        }
    }
}
