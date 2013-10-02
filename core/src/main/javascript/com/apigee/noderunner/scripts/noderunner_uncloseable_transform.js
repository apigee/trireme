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
 * This is a script that inherts from TransformStream, but it just makes sure that you can't close it.
 * We use this when we spawn a script in the same process and it shares stdin and stdout with its parent,
 * to keep a child from closing a paren't stdin or stdout.
 */

var Transform = require('stream').Transform;
var util = require('util');

function UncloseableTransform(options) {
  if (!(this instanceof UncloseableTransform)) {
    return new UncloseableTransform(options);
  }
  Transform.call(this, options);
}
util.inherits(UncloseableTransform, Transform);
module.exports.UncloseableTransform = UncloseableTransform;

UncloseableTransform.prototype.end = function(chunk, encoding, cb) {
  this.write(chunk, encoding, function() {
    cb('This stream may not be closed');
  });
};

UncloseableTransform.prototype.destroy = function() {
  // We still need this stream to support destroy -- just don't pass it down to the other stream
};
