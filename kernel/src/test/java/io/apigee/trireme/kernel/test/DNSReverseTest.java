package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.dns.DNSFormatException;
import io.apigee.trireme.kernel.dns.Reverser;
import org.junit.Test;

import static org.junit.Assert.*;

public class DNSReverseTest
{
    @Test
    public void testReverse4()
        throws DNSFormatException
    {
        assertEquals("4.3.2.1.IN-ADDR.ARPA",
                     Reverser.reverse("1.2.3.4"));
        assertEquals("124.133.12.101.IN-ADDR.ARPA",
                     Reverser.reverse("101.12.133.124"));
        assertEquals("3.2.1.IN-ADDR.ARPA",
                     Reverser.reverse("1.2.3"));
        assertEquals("2.1.IN-ADDR.ARPA",
                     Reverser.reverse("1.2"));
        assertEquals("1.IN-ADDR.ARPA",
                     Reverser.reverse("1"));
    }

    @Test
    public void testReverse6()
        throws DNSFormatException
    {
        // From RFC3596
        assertEquals("b.a.9.8.7.6.5.0.4.0.0.0.3.0.0.0.2.0.0.0.1.0.0.0.0.0.0.0.1.2.3.4.IP6.ARPA",
                     Reverser.reverse("4321:0:1:2:3:4:567:89ab"));
    }

    @Test
    public void testReverseInvalid()
    {
        try {
            Reverser.reverse("google.com");
            assertFalse("Expected a format exception", true);
        } catch (DNSFormatException dfe) {
            // This is correct
        }
    }
}
