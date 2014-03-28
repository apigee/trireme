/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.net;

import io.apigee.trireme.core.internal.Charsets;

import java.util.regex.Pattern;

/**
 * Constants for the HTTP parser.
 */
public interface HTTPGrammar
{
    public static final String HTTP_1_1 = "HTTP/1.1";
    public static final String HTTP_1_0 = "HTTP/1.0";

    public static final String CHAR_CL    = "[\\x00-\\x7f]";
    public static final String UPALPHA_CL = "A-Z";
    public static final String LOALPHA_CL =     "a-z";
    public static final String ALPHA_CL =       UPALPHA_CL + LOALPHA_CL;
    public static final String DIGITS_CL =      "[0-9]";
    public static final String CTL_CL =         "\\x00-\\x1f\\x7f";
    public static final String NOT_CTLS_CL =    "[^" + CTL_CL + ']';
    public static final String SEPARATOR_CL =   "\\(\\)<>@,;:\"/\\[\\]?+{} \t\\\\";
    public static final String TEXTS_CL =       "[[ \t][^" + CTL_CL + "]]";
    public static final String TOKENS_CL =      "[^" + SEPARATOR_CL + CTL_CL + ']';
    public static final String LWS_CL =         "[ \\t]";
    public static final String CR =             "\r";
    public static final String LF =             "\n";
    public static final String SP =             " ";
    public static final String HT =             "\t";
    public static final String CRLF =           "\r\n";
    public static final byte[] CRLF_BYTES =     CRLF.getBytes(Charsets.ASCII);

    public static final String HEX =            "[A-Fa-f0-9]+";

    public static final String COMMENT =        "\\([[ \t][^" + CTL_CL + "\\(\\)]]*\\)";
    public static final String QUOTEDSTRING =   "\"[[ \t][^" + CTL_CL + "\"]]*\"";

    public static final String HEADER =         "^(" + TOKENS_CL + "+):" + LWS_CL + "*(" + NOT_CTLS_CL + "*)" + LWS_CL + "*$";
    public static final String HEADER_CONTINUATION = '^' + LWS_CL + "+(" + NOT_CTLS_CL + "*)" + LWS_CL + "*$";
    public static final String REQUEST_LINE =   "^(" + TOKENS_CL + "+) (" + TEXTS_CL + "+) HTTP/(" + DIGITS_CL + ").(" + DIGITS_CL + ")" + LWS_CL + "*$";
    public static final String STATUS_LINE =    "^HTTP/(" + DIGITS_CL + ").(" + DIGITS_CL + ") (" + DIGITS_CL + "+)( " + TEXTS_CL + "+)?" + LWS_CL + "*$";

    public static final Pattern HEADER_PATTERN =   Pattern.compile(HEADER);
    public static final Pattern HEADER_CONTINUATION_PATTERN = Pattern.compile(HEADER_CONTINUATION);
    public static final Pattern REQUEST_LINE_PATTERN = Pattern.compile(REQUEST_LINE);
    public static final Pattern STATUS_LINE_PATTERN = Pattern.compile(STATUS_LINE);
}
