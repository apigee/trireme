package io.apigee.trireme.node12.internal;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * This is the class that makes this thing go. Gets are delegated to the parent, but not puts.
 */
public class ContextifyContext
    extends ScriptableObject
{
    private final Scriptable child;
    private final Scriptable parent;

    public ContextifyContext(Scriptable p, Scriptable c)
    {
        this.parent = p;
        this.child = c;
    }

    @Override
    public String getClassName()
    {
        return child.getClassName();
    }

    @Override
    public Object get(String s, Scriptable scriptable)
    {
        Object r = child.get(s, child);
        if (r == Scriptable.NOT_FOUND) {
            return parent.get(s, parent);
        }
        return r;
    }

    @Override
    public Object get(int i, Scriptable scriptable)
    {
        Object r = child.get(i, child);
        if (r == Scriptable.NOT_FOUND) {
            return parent.get(i, parent);
        }
        return r;
    }

    @Override
    public boolean has(String s, Scriptable scriptable)
    {
        return (child.has(s, child) || parent.has(s, parent));
    }

    @Override
    public boolean has(int i, Scriptable scriptable)
    {
        return (child.has(i, child) || parent.has(i, parent));
    }

    @Override
    public void put(String s, Scriptable scriptable, Object o)
    {
        child.put(s, child, o);
    }

    @Override
    public void put(int i, Scriptable scriptable, Object o)
    {
        child.put(i, child, o);
    }

    @Override
    public void delete(String s)
    {
        child.delete(s);
    }

    @Override
    public void delete(int i)
    {
        child.delete(i);
    }

    @Override
    public Scriptable getPrototype()
    {
        return child.getPrototype();
    }

    @Override
    public void setPrototype(Scriptable scriptable)
    {
        child.setPrototype(scriptable);
    }

    @Override
    public Scriptable getParentScope()
    {
        return null;
    }

    @Override
    public void setParentScope(Scriptable scriptable)
    {
    }

    @Override
    public Object[] getIds()
    {
        return child.getIds();
    }

    @Override
    public Object getDefaultValue(Class<?> aClass)
    {
        return child.getDefaultValue(aClass);
    }

    @Override
    public boolean hasInstance(Scriptable scriptable)
    {
        return child.hasInstance(scriptable);
    }
}