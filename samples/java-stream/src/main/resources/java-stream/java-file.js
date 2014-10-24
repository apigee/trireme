/*
 * This file is a Node.js module called "java-file". Since it is embedded inside this Trireme module,
 * "require('java-file');" will always load it regardless of what is in node_modules.
 *
 * The "guts" of this module are implemented in Java. In theory, all the code here can be implemented in
 * Java as well. However, doing things like handling events, checking and adjusting argument types,
 * inheritance, and other things are more easily done in JavaScript, which reduces the amount of Rhino
 * trickery we need to know in order to implement the Java "half" of this module.
 *
 * This module exports two classes -- ReadStream and WriteStream. Each one is a Node.js interface on top
 * of a standard Java stream. (ReadStream wraps FileInputStream and WriteStream wraps FileOutputStream.)
 *
 * ReadStream implements the standard Node.js "readable stream" interface. Once created, it will return
 * the entire contents of the specified file until it is fully read, and then it will close the file.
 * For example:
 *
 * var jf = require('java-file');
 * var in = new jf.ReadStream('/tmp/foo.txt');
 * in.on('data', function(chunk) {
 *   // we will get a buffer here unless we call "in.setEncoding"
 *   console.log('Got a chunk of %d bytes', chunk.length);
 * });
 * in.on('end', function() {
 *   console.log('Reached EOF.');
 * });
 *
 * WriteStream implements WritableStream, on the same file. After "end" is called it will close the
 * underlying file. For example:
 *
 * var jf = require('java-file');
 * var out = new jf.WriteStream('/tmp/foo.txt');
 * out.write('Hello, ');
 * out.end('World!');
 */

var stream = require('stream');
var util = require('util');

// This module is implemented in Java inside JavaFileStream.java.
var fileInternal = require('file-stream-internal');

// This will be a class that implements the "readable stream" contract
function ReadStream(fileName, options) {
  if (!(this instanceof ReadStream)) {
    return new ReadStream(fileName, options);
  }

  // This is basic Node.js inheritance -- this "class" inherits from stream.Readable.
  stream.Readable.call(this, options);

  // Open the file by creating a new "ReadStream" object in Java
  this.file = new fileInternal.ReadStream(fileName);
}
module.exports.ReadStream = ReadStream;

// More standard Node.js inheritance stuff
util.inherits(ReadStream, stream.Readable);

// This function is called internally by stream.Readable when it wants to read more data.
// We are responsible for calling "push" when we have the data.
ReadStream.prototype._read = function(size) {
  var self = this;

  // Call down to Java to read some data. Java will call us back when it has the data.
  self.file.read(size, function(err, buf) {
    if (err) {
      self.file.close();
      self.emit('error', err);
    } else if (buf) {
      // This is how we send the data back to the caller in a readable stream in Node.
      self.push(buf);
    } else {
      self.file.close();
      // This is how we let the reader know that we've reached EOF.
      self.push(null);
    }
  });
};

// This will be a class that implements the "writable stream" contract
function WriteStream(fileName, options) {
  if (!(this instanceof WriteStream)) {
    return new WriteStream(fileName, options);
  }

  stream.Writable.call(this, options);

  // Again, the Java-based stream object
  var file = new fileInternal.WriteStream(fileName);
  this.file = file;

  this.on('finish', function() {
    // stream.Writable will send this event after "end" has been called and any data written.
    file.close();
  });
}
module.exports.WriteStream = WriteStream;

util.inherits(WriteStream, stream.Writable);

WriteStream.prototype._write = function(chunk, encoding, cb) {
  var self = this;

  // Write the Buffer out to the Java code.
  // It will call us back when the write is complete, or had an error.
  // Node.js doc say that chunk will always be a Buffer so we will ignore encoding
  self.file.write(chunk, function(err) {
    if (cb) {
      cb(err);
    }
  });
};


