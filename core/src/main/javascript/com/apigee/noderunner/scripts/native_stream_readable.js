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
