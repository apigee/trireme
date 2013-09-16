/*
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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

/*
 * This is a module that implements the same interface as the commonly-used "iconv-lite" module, but it uses
 * native Java capabilities rather than native code to convert character sets. The original "iconv-lite"
 * module that this is based on may be found here:
 * https://github.com/ashtuchkin/iconv-lite
 */

var wrap = process.binding('iconv-wrap');

function toEncoding(s, encoding) {
  var str;
  if (!s) {
    str = "";
  } else if (s instanceof Buffer) {
    str = s.toString('utf8');
  } else {
    str = "" + s;
  }
  return wrap.encodeString(str, encoding);
}
exports.toEncoding = toEncoding;
exports.encode = toEncoding;

function fromEncoding(b, encoding) {
  var buf;
  if (!b) {
    buf = new Buffer(0);
  } else if (b instanceof Buffer) {
    buf = b;
  } else {
    buf = new Buffer("" + b, "binary");
  }
  return wrap.decodeBuffer(buf, encoding);
}
exports.fromEncoding = fromEncoding;
exports.decode = fromEncoding;

function encodingExists(encoding) {

  return wrap.encodingExists(encoding);
}
exports.encodingExists = encodingExists;
