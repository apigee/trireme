package io.apigee.trireme.testjars.dependency;

public class Dependency
{
    private static volatile int sharedVal;

    private String val;

    public static void setSharedVal(int s) {
        sharedVal = s;
    }

    public static int getSharedVal() {
        return sharedVal;
    }

    public void setValue(String v) {
        this.val = v;
    }

    public String getValue() {
        return val;
    }
}
