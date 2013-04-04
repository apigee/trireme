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


