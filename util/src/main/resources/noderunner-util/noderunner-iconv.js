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
 * This is a module that implements the same interface as the commonly-used "iconv" module, but it uses
 * native Java capabilities rather than native code to convert character sets. The original "iconv"
 * module that this is based on may be found here:
 * https://github.com/bnoordhuis/node-iconv
 */

var stream = require('stream');
var util = require('util');
var wrap = process.binding('iconv-wrap');

var FLUSH = {};

function Iconv(fromEnc, toEnc) {
  if (!(this instanceof Iconv)) {
    return new Iconv(fromEnc, toEnc);
  }
  stream.Transform.call(this);

  this.converter = new wrap.Converter(fromEnc, toEnc);
}
util.inherits(Iconv, stream.Transform);
exports.Iconv = Iconv;

Iconv.prototype._transform = function(inBuf, encoding, callback) {
  var outBuf = this.convert(inBuf, false);
  if (outBuf) {
    this.push(outBuf);
  }
  callback();
};

Iconv.prototype._flush = function(callback) {
  var outBuf = this.convert(null, true);
  if (outBuf) {
    this.push(outBuf);
  }
  callback();
};

// This is the function that is used directly by most clients.
Iconv.prototype.convert = function(inBuf, lc) {
  if (typeof(inBuf) === 'string') {
    inBuf = new Buffer(inBuf);
  }
  if (inBuf && !(inBuf instanceof Buffer)) {
    throw new Error('Bad argument.');
  }

  var lastChunk = (lc == undefined ? true : lc);
  return this.converter.convert(inBuf, lastChunk);
};

