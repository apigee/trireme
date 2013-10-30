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
 * This class implements a writable stream based on an internal resource. It's used to pass events
 * to standard output, among other things. It delegates the real I/O to a native helper object
 * that is passed to the constructor.
 */

var util = require('util');
var Writable = require('stream').Writable;

function NativeWritableStream(options, handle) {
  if (!(this instanceof NativeWritableStream)) {
    return new NativeWritableStream(options, handle);
  }

  var superOpts;
  if (options) {
    superOpts = options;
  } else {
    superOpts = {};
  }
  superOpts.decodeStrings = true;
  Writable.call(this, superOpts);

  this.handle = handle;
}
util.inherits(NativeWritableStream, Writable);
module.exports = NativeWritableStream;

NativeWritableStream.prototype._write = function(chunk, encoding, callback) {
  this.handle.write(chunk, callback);
};

NativeWritableStream.prototype.end = function(chunk, encoding) {
  var self = this;
  Writable.prototype.end.call(this, chunk, encoding, function() {
    doClose(self);
  });
};

NativeWritableStream.prototype.destroy = function() {
  if (!this._writableState.ended) {
    this.end();
  }
  doClose(this);
}

function doClose(self) {
  if (!self.closed) {
    self.handle.close();
    self.emit('close');
    self.closed = true;
  }
}


