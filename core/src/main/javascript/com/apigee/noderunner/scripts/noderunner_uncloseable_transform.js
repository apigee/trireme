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
