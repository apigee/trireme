package io.apigee.trireme.kernel.dns;

import io.apigee.trireme.kernel.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * This class encapsulates the DNS wire message format.
 */

public class Wire
{
    public static final int MAX_LABEL_LEN = 63;

    /** Top two bits of a two-byte octet set, for length compression. */
    public static final int POINTER_FLAG = 0xc000;
    public static final int POINTER_BYTE_FLAG = 0xc0;
    /** Flag to mask that two-byte pointer with */
    public static final int POINTER_MASK = 0x3fff;
    public static final int POINTER_BYTE_MASK = 0x3f;

    private static final int INIT_SIZE = 2;

    private final Header header = new Header();
    private Question question;
    private ArrayList<RR> answers = new ArrayList<RR>();

    public Header getHeader() {
        return header;
    }

    public void setQuestion(Question q) {
        this.question = q;
    }

    public Question getQuestion() {
        return question;
    }

    public void addAnswer(RR answer) {
        answers.add(answer);
    }

    public List<RR> getAnswers() {
        return answers;
    }

    public ByteBuffer store()
        throws DNSFormatException
    {
        header.setQuestionCount(question == null ? 0 : 1);
        header.setAnswerCount(answers.size());
        header.setNsCount(0);
        header.setArCount(0);

        Compressor comp = new Compressor();
        ByteBuffer bb = ByteBuffer.allocate(INIT_SIZE);
        bb = header.store(bb);
        if (question != null) {
            bb = question.store(bb, comp);
        }
        for (RR answer : answers) {
            bb = answer.store(bb, comp);
        }
        bb.flip();
        return bb;
    }

    public void load(ByteBuffer bb)
        throws DNSFormatException
    {
        Decompressor dcomp = new Decompressor();

        header.load(bb);
        if (header.questionCount > 0) {
            question = new Question();
            question.load(bb, dcomp);
        }
        for (int i = 0; i < header.answerCount; i++) {
            RR answer = new RR();
            answer.load(bb, dcomp);
            answers.add(answer);
        }
    }

    public static class Header
    {
        private int id;
        private boolean response;
        private int opcode;
        private boolean authoritative;
        private boolean truncated;
        private boolean recursionDesired;
        private boolean recursionAvailable;
        private int rcode;
        private int questionCount;
        private int answerCount;
        private int nsCount;
        private int arCount;

        ByteBuffer store(ByteBuffer b)
        {
            ByteBuffer bb = b;
            while (bb.remaining() < 12) {
                bb = BufferUtils.doubleBuffer(bb);
            }

            bb.putShort((short)id);

            int byte3 = (opcode & 0xf) << 3;
            if (response) {
                byte3 |= (1 << 7);
            }
            if (authoritative) {
                byte3 |= (1 << 2);
            }
            if (truncated) {
                byte3 |= (1 << 1);
            }
            if (recursionDesired) {
                byte3 |= 1;
            }
            bb.put((byte)byte3);

            int byte4 = (rcode & 0xf);
            if (recursionAvailable) {
                byte4 |= (1 << 7);
            }
            bb.put((byte)byte4);

            bb.putShort((short)questionCount);
            bb.putShort((short)answerCount);
            bb.putShort((short)nsCount);
            bb.putShort((short)arCount);

            return bb;
        }

        void load(ByteBuffer bb)
            throws DNSFormatException
        {
            if (bb.remaining() < 12) {
                throw new DNSFormatException("Incomplete DNS header");
            }

            id = bb.getShort() & 0xffff;

            int byte3 = bb.get() & 0xff;
            if ((byte3 & (1 << 7)) != 0) {
                response = true;
            }
            opcode = (byte3 >> 3) & 0xf;
            if ((byte3 & (1 << 2)) != 0) {
                authoritative = true;
            }
            if ((byte3 & (1 << 1)) != 0) {
                truncated = true;
            }
            if ((byte3 & 1) != 0) {
                recursionDesired = true;
            }

            int byte4 = bb.get() & 0xff;
            if ((byte4 & (1 << 7)) != 0) {
                recursionAvailable = true;
            }
            rcode = byte4 & 0xf;

            questionCount = bb.getShort() & 0xffff;
            answerCount = bb.getShort() & 0xffff;
            nsCount = bb.getShort() & 0xffff;
            arCount = bb.getShort() & 0xffff;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public boolean isResponse() {
            return response;
        }

        public void setResponse(boolean response) {
            this.response = response;
        }

        public int getOpcode() {
            return opcode;
        }

        public void setOpcode(int opcode) {
            this.opcode = opcode;
        }

        public boolean isAuthoritative() {
            return authoritative;
        }

        public void setAuthoritative(boolean authoritative) {
            this.authoritative = authoritative;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean truncated) {
            this.truncated = truncated;
        }

        public boolean isRecursionDesired() {
            return recursionDesired;
        }

        public void setRecursionDesired(boolean recursionDesired) {
            this.recursionDesired = recursionDesired;
        }

        public boolean isRecursionAvailable() {
            return recursionAvailable;
        }

        public void setRecursionAvailable(boolean recursionAvailable) {
            this.recursionAvailable = recursionAvailable;
        }

        public int getRcode() {
            return rcode;
        }

        public void setRcode(int rcode) {
            this.rcode = rcode;
        }

        public int getQuestionCount() {
            return questionCount;
        }

        public void setQuestionCount(int questionCount) {
            this.questionCount = questionCount;
        }

        public int getAnswerCount() {
            return answerCount;
        }

        public void setAnswerCount(int answerCount) {
            this.answerCount = answerCount;
        }

        public int getNsCount() {
            return nsCount;
        }

        public void setNsCount(int nsCount) {
            this.nsCount = nsCount;
        }

        public int getArCount() {
            return arCount;
        }

        public void setArCount(int arCount) {
            this.arCount = arCount;
        }
    }

    public static class Question
    {
        private String name;
        private int klass;
        private int type;

        ByteBuffer store(ByteBuffer b, Compressor comp)
            throws DNSFormatException
        {
            ByteBuffer bb = b;

            bb = comp.writeName(bb, name);
            while (bb.remaining() < 4) {
                bb = BufferUtils.doubleBuffer(bb);
            }
            bb.putShort((short)type);
            bb.putShort((short)klass);
            return bb;
        }

        void load(ByteBuffer bb, Decompressor dcomp)
            throws DNSFormatException
        {
            name = dcomp.readName(bb);
            type = bb.getShort() & 0xffff;
            klass = bb.getShort() & 0xffff;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getKlass() {
            return klass;
        }

        public void setKlass(int klass) {
            this.klass = klass;
        }

        public int getType(){
            return type;
        }

        public void setType(int type) {
            this.type = type;
        }
    }

    public static class RR
    {
        private String name;
        private int type;
        private int klass;
        private long ttl;
        private int length;
        private ByteBuffer data;
        private Object result;

        ByteBuffer store(ByteBuffer b, Compressor comp)
            throws DNSFormatException
        {
            ByteBuffer bb = comp.writeName(b, name);
            while (bb.remaining() < 10) {
                bb = BufferUtils.doubleBuffer(bb);
            }

            bb.putShort((short)type);
            bb.putShort((short)klass);
            bb.putInt((int)ttl);
            bb.putShort((short)length);

            while (bb.remaining() < data.remaining()) {
                bb = BufferUtils.doubleBuffer(bb);
            }
            ByteBuffer tmpData = data.duplicate();
            bb.put(tmpData);

            return bb;
        }

        void load(ByteBuffer bb, Decompressor dcomp)
            throws DNSFormatException
        {
            name = dcomp.readName(bb);
            type = bb.getShort() & 0xffff;
            klass = bb.getShort() & 0xffff;
            ttl = bb.getInt() & 0xffffffffL;
            length = bb.getShort() & 0xffff;

            if (bb.remaining() < length) {
                throw new DNSFormatException("Invalid DNS record.");
            }

            // Return a buffer positioned at the start, but leave the original buffer intact
            // so we can go backwards when decompressing names
            data = bb.duplicate();
            data.limit(data.position() + length);
            bb.position(bb.position() + length);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getType() {
            return type;
        }

        public void setType(int type) {
            this.type = type;
        }

        public int getKlass() {
            return klass;
        }

        public void setKlass(int klass) {
            this.klass = klass;
        }

        public long getTtl() {
            return ttl;
        }

        public void setTtl(long ttl) {
            this.ttl = ttl;
        }

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public ByteBuffer getData() {
            return data;
        }

        public void setData(ByteBuffer data) {
            this.data = data;
            this.length = data.remaining();
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object r) {
            this.result = r;
        }
    }
}
