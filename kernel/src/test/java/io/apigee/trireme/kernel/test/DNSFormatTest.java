package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.dns.DNSFormatException;
import io.apigee.trireme.kernel.dns.Wire;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class DNSFormatTest
{
    @Test
    public void testBasicQuestion()
        throws DNSFormatException
    {
        Wire w = new Wire();
        w.getHeader().setId(1234);
        w.getHeader().setRecursionDesired(true);
        w.getHeader().setOpcode(0);

        Wire.Question q = new Wire.Question();
        q.setName("foo.bar.com");
        q.setType(1);
        q.setKlass(1);
        w.setQuestion(q);

        ByteBuffer buf = w.store();

        Wire r = new Wire();
        r.load(buf);
        assertEquals(1234, r.getHeader().getId());
        assertEquals(true, r.getHeader().isRecursionDesired());
        assertEquals(0, r.getHeader().getOpcode());
        assertEquals(1, r.getHeader().getQuestionCount());
        assertEquals(0, r.getHeader().getAnswerCount());

        assertEquals("foo.bar.com", r.getQuestion().getName());
        assertEquals(1, r.getQuestion().getType());
        assertEquals(1, r.getQuestion().getKlass());
    }

    @Test
    public void testBasicAnswer()
        throws DNSFormatException, UnsupportedEncodingException
    {
        Wire w = new Wire();
        w.getHeader().setId(1234);
        w.getHeader().setRecursionDesired(true);
        w.getHeader().setOpcode(0);

        ByteBuffer testData = ByteBuffer.wrap("Hello, World!".getBytes("ascii"));

        Wire.RR a = new Wire.RR();
        a.setName("foo.bar.com");
        a.setType(1);
        a.setKlass(1);
        a.setTtl(999);
        a.setData(testData);
        w.addAnswer(a);

        ByteBuffer buf = w.store();

        Wire r = new Wire();
        r.load(buf);
        assertEquals(1234, r.getHeader().getId());
        assertEquals(true, r.getHeader().isRecursionDesired());
        assertEquals(0, r.getHeader().getOpcode());
        assertEquals(0, r.getHeader().getQuestionCount());
        assertEquals(1, r.getHeader().getAnswerCount());

        Wire.RR ar = r.getAnswers().get(0);
        assertEquals("foo.bar.com", ar.getName());
        assertEquals(1, ar.getType());
        assertEquals(1, ar.getKlass());
        assertEquals(999, ar.getTtl());
        assertEquals(testData, ar.getData());
    }
}
