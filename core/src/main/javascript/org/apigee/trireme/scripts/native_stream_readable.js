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
 * This class is a subclass of a readable stream that reads from a native source. We use it to read standard
 * input.
 */

var util = require('util');
var Readable = require('stream').Readable;

function NativeReadableStream(options, handle) {
  if (!(this instanceof NativeReadableStream)) {
    return new NativeReadableStream(options, handle);
  }

  Readable.call(this, options);
  this.handle = handle;
}
util.inherits(NativeReadableStream, Readable);
module.exports = NativeReadableStream;

NativeReadableStream.prototype._read = function(n) {
  var self = this;
  self.handle.read(n, function(err, chunk) {
    if (err) {
      self.emit('error', err);
      return false;
    } else {
      return self.push(chunk);
    }
  });
};

NativeReadableStream.prototype.destroy = function() {
  this.handle.close();
  this.emit('closed');
};
