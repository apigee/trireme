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

var net = require('net');
var util = require('util');

exports.isatty = function(fd) {
  switch (fd) {
    case 0:
      return process.stdin.isTTY;
    case 1:
      return process.stdout.isTTY;
    default:
      return false;
  }
};

// backwards-compat
exports.setRawMode = util.deprecate(function(flag) {
  if (!process.stdin.isTTY) {
    throw new Error('can\'t set raw mode on non-tty');
  }
  process.stdin.setRawMode(flag);
}, 'tty.setRawMode: Use `process.stdin.setRawMode()` instead.');

function ReadStream(handle) {
  if (!(this instanceof ReadStream))
    return new ReadStream(handle);

  net.Socket.call(this, {
    highWaterMark: 0,
    readable: true,
    writable: false,
    handle: handle
  });

  this.isTTY = true;
}
util.inherits(ReadStream, net.Socket);

exports.ReadStream = ReadStream;

ReadStream.prototype.setRawMode = function(flag) {
  flag = !!flag;
  this._handle.setRawMode(flag);
};

function WriteStream(handle) {
  if (!(this instanceof WriteStream))
    return new WriteStream(handle);
  net.Socket.call(this, {
    handle: handle,
    readable: false,
    writable: true
  });

  var winSize = [];
  var err = this._handle.getWindowSize(winSize);
  if (!err) {
    this.columns = winSize[0];
    this.rows = winSize[1];
  }
}
util.inherits(WriteStream, net.Socket);
exports.WriteStream = WriteStream;


WriteStream.prototype.isTTY = true;

WriteStream.prototype._refreshSize = function() {
  var oldCols = this.columns;
  var oldRows = this.rows;
  var winSize = [];
  var err = this._handle.getWindowSize(winSize);
  if (err) {
    this.emit('error', errnoException(err, 'getWindowSize'));
    return;
  }
  var newCols = winSize[0];
  var newRows = winSize[1];
  if (oldCols !== newCols || oldRows !== newRows) {
    this.columns = newCols;
    this.rows = newRows;
    this.emit('resize');
  }
};


// backwards-compat
WriteStream.prototype.cursorTo = function(x, y) {
  require('readline').cursorTo(this, x, y);
};
WriteStream.prototype.moveCursor = function(dx, dy) {
  require('readline').moveCursor(this, dx, dy);
};
WriteStream.prototype.clearLine = function(dir) {
  require('readline').clearLine(this, dir);
};
WriteStream.prototype.clearScreenDown = function() {
  require('readline').clearScreenDown(this);
};
WriteStream.prototype.getWindowSize = function() {
  return [this.columns, this.rows];
};
