/*
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
/*
 * This is a version of string_decoder that works with native Java code. This is a bit of performance-
 * critical code in Node and this version should be much more efficient and also simpler.
 */

var decoderWrap = process.binding('decoder_wrap');

var StringDecoder = exports.StringDecoder = function(enc) {
  var encoding = (enc || 'utf8');
  if (!decoderWrap.isEncoding(encoding)) {
    throw new Error('Unknown encoding: ' + encoding);
  }

  this.decoder = new decoderWrap.Decoder(encoding);
  this.encoding = encoding;
};

function toBuf(o) {
  if (typeof o === 'string') {
    return new Buffer(o);
  }
  return o;
}

StringDecoder.prototype.write = function(buffer) {
  return this.decoder.decode(toBuf(buffer));
};

StringDecoder.prototype.end = function(buf) {
  var buffer = (buf ? buf : null);
  return this.decoder.end(toBuf(buf));
};
