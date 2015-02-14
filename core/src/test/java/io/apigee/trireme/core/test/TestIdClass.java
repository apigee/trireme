package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

public class TestIdClass
    extends AbstractIdObject<TestIdClass>
{
    public static final String CLASS_NAME = "IdObject";

    private static final IdPropertyMap props;

    private static final int m_callFoo = 2,
        m_callBar = 3,
        p_baz = 1,
        p_no = 2;

    static {
        props = new IdPropertyMap(CLASS_NAME);
        props.addMethod("callFoo", m_callFoo, 0);
        props.addMethod("callBar", m_callBar, 1);
        props.addProperty("baz", p_baz, 0);
        props.addProperty("no", p_no, ScriptableObject.READONLY);
    }

    private int baz;
    private int no = 999;

    public TestIdClass()
    {
        super(props);
    }

    @Override
    protected TestIdClass defaultConstructor()
    {
        return new TestIdClass();
    }

    @Override
    protected Object getInstanceIdValue(int id)
    {
        switch (id) {
        case p_baz:
            return baz;
        case p_no:
            return no;
        default:
            return super.getInstanceIdValue(id);
        }
    }

    @Override
    protected void setInstanceIdValue(int id, Object value)
    {
        switch (id) {
        case p_baz:
            baz = (Integer)value;
            break;
        default:
            super.setInstanceIdValue(id, value);
            break;
        }
    }

    @Override
    protected Object prototypeCall(int id, Context cx, Scriptable scope,
                                   Object[] args)
    {
        switch (id) {
        case m_callFoo:
            return "Foo!";
        case m_callBar:
            return "Hello, " + args[0] + '!';
        default:
            return super.prototypeCall(id, cx, scope, args);
        }
    }
}

