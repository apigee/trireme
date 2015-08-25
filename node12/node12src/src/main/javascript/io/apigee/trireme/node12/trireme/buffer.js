// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

var smalloc = process.binding('smalloc');
var util = require('util');
var kMaxLength = smalloc.kMaxLength;

/*
 * This is the function that wires up all this good JavaScript code to the Java-based
 * Buffer class. That way we can use all the existing Java-based "BufferImpl" code.
 * It gets called the first time that the "buffer" class is loaded.
 */
exports._setNativePrototype = function(nativeBuf) {
nativeBuf.INSPECT_MAX_BYTES = 50;


nativeBuf.poolSize = 8 * 1024;

// Static methods

nativeBuf.isBuffer = function isBuffer(b) {
  return util.isBuffer(b);
};


nativeBuf.compare = function compare(a, b) {
  if (!(a instanceof Buffer) ||
      !(b instanceof Buffer))
    throw new TypeError('Arguments must be Buffers');

  return Buffer._compare(a, b);
};


nativeBuf.isEncoding = function(encoding) {
  switch ((encoding + '').toLowerCase()) {
    case 'hex':
    case 'utf8':
    case 'utf-8':
    case 'ascii':
    case 'binary':
    case 'base64':
    case 'ucs2':
    case 'ucs-2':
    case 'utf16le':
    case 'utf-16le':
    case 'raw':
      return true;

    default:
      return false;
  }
};


nativeBuf.concat = function(list, length) {
  if (!util.isArray(list))
    throw new TypeError('list argument must be an Array of Buffers.');

  if (util.isUndefined(length)) {
    length = 0;
    for (var i = 0; i < list.length; i++)
      length += list[i].length;
  } else {
    length = length >>> 0;
  }

  if (list.length === 0)
    return new Buffer(0);
  else if (list.length === 1)
    return list[0];

  var buffer = new Buffer(length);
  var pos = 0;
  for (var i = 0; i < list.length; i++) {
    var buf = list[i];
    buf._copy(buffer, pos);
    pos += buf.length;
  }

  return buffer;
};


nativeBuf.byteLength = function(str, enc) {
  var ret;
  str = str + '';
  switch (enc) {
    case 'ascii':
    case 'binary':
    case 'raw':
      ret = str.length;
      break;
    case 'ucs2':
    case 'ucs-2':
    case 'utf16le':
    case 'utf-16le':
      ret = str.length * 2;
      break;
    case 'hex':
      ret = str.length >>> 1;
      break;
    default:
      ret = Buffer._byteLength(str, enc);
  }
  return ret;
};


// toString(encoding, start=0, end=buffer.length)
nativeBuf.prototype.toString = function(encoding, start, end) {
  var loweredCase = false;

  start = start >>> 0;
  end = util.isUndefined(end) || end === Infinity ? this.length : end >>> 0;

  if (!encoding) encoding = 'utf8';
  if (start < 0) start = 0;
  if (end > this.length) end = this.length;
  if (end <= start) return '';

  while (true) {
    switch (encoding) {
      case 'hex':
        return this.hexSlice(start, end);

      case 'utf8':
      case 'utf-8':
        return this.utf8Slice(start, end);

      case 'ascii':
        return this.asciiSlice(start, end);

      case 'binary':
        return this.binarySlice(start, end);

      case 'base64':
        return this.base64Slice(start, end);

      case 'ucs2':
      case 'ucs-2':
      case 'utf16le':
      case 'utf-16le':
        return this.ucs2Slice(start, end);

      default:
        if (loweredCase)
          throw new TypeError('Unknown encoding: ' + encoding);
        encoding = (encoding + '').toLowerCase();
        loweredCase = true;
    }
  }
};


nativeBuf.prototype.equals = function equals(b) {
  if (!(b instanceof Buffer))
    throw new TypeError('Argument must be a Buffer');

  return Buffer._compare(this, b) === 0;
};


// Inspect
nativeBuf.prototype.inspect = function inspect() {
  var str = '';
  var max = exports.INSPECT_MAX_BYTES;
  if (this.length > 0) {
    str = this.toString('hex', 0, max).match(/.{2}/g).join(' ');
    if (this.length > max)
      str += ' ... ';
  }
  return '<' + this.constructor.name + ' ' + str + '>';
};


nativeBuf.prototype.compare = function compare(b) {
  if (!(b instanceof Buffer))
    throw new TypeError('Argument must be a Buffer');

  return Buffer._compare(this, b);
};


nativeBuf.prototype.fill = function fill(val, start, end) {
  start = start >> 0;
  end = (end === undefined) ? this.length : end >> 0;

  if (start < 0 || end > this.length)
    throw new RangeError('out of range index');
  if (end <= start)
    return this;

  if (typeof val !== 'string') {
    val = val >>> 0;
  } else if (val.length === 1) {
    var code = val.charCodeAt(0);
    if (code < 256)
      val = code;
  }

  this._fill(val, start, end);

  return this;
};


// XXX remove in v0.13
nativeBuf.prototype.get = util.deprecate(function get(offset) {
  offset = ~~offset;
  if (offset < 0 || offset >= this.length)
    throw new RangeError('index out of range');
  return this[offset];
}, '.get() is deprecated. Access using array indexes instead.');


// XXX remove in v0.13
nativeBuf.prototype.set = util.deprecate(function set(offset, v) {
  offset = ~~offset;
  if (offset < 0 || offset >= this.length)
    throw new RangeError('index out of range');
  return this[offset] = v;
}, '.set() is deprecated. Set using array indexes instead.');


// TODO(trevnorris): fix these checks to follow new standard
// write(string, offset = 0, length = buffer.length, encoding = 'utf8')
var writeWarned = false;
var writeMsg = '.write(string, encoding, offset, length) is deprecated.' +
               ' Use write(string[, offset[, length]][, encoding]) instead.';
nativeBuf.prototype.write = function(string, offset, length, encoding) {
  // Buffer#write(string);
  if (util.isUndefined(offset)) {
    encoding = 'utf8';
    length = this.length;
    offset = 0;

  // Buffer#write(string, encoding)
  } else if (util.isUndefined(length) && util.isString(offset)) {
    encoding = offset;
    length = this.length;
    offset = 0;

  // Buffer#write(string, offset[, length][, encoding])
  } else if (isFinite(offset)) {
    offset = offset >>> 0;
    if (isFinite(length)) {
      length = length >>> 0;
      if (util.isUndefined(encoding))
        encoding = 'utf8';
    } else {
      encoding = length;
      length = undefined;
    }

  // XXX legacy write(string, encoding, offset, length) - remove in v0.13
  } else {
    if (!writeWarned) {
      if (process.throwDeprecation)
        throw new Error(writeMsg);
      else if (process.traceDeprecation)
        console.trace(writeMsg);
      else
        console.error(writeMsg);
      writeWarned = true;
    }

    var swap = encoding;
    encoding = offset;
    offset = length >>> 0;
    length = swap;
  }

  var remaining = this.length - offset;
  if (util.isUndefined(length) || length > remaining)
    length = remaining;

  encoding = !!encoding ? (encoding + '').toLowerCase() : 'utf8';

  if (string.length > 0 && (length < 0 || offset < 0))
    throw new RangeError('attempt to write outside buffer bounds');

  var ret;
  switch (encoding) {
    case 'hex':
      ret = this.hexWrite(string, offset, length, nativeBuf);
      break;

    case 'utf8':
    case 'utf-8':
      ret = this.utf8Write(string, offset, length, nativeBuf);
      break;

    case 'ascii':
      ret = this.asciiWrite(string, offset, length, nativeBuf);
      break;

    case 'binary':
      ret = this.binaryWrite(string, offset, length, nativeBuf);
      break;

    case 'base64':
      // Warning: maxLength not taken into account in base64Write
      ret = this.base64Write(string, offset, length, nativeBuf);
      break;

    case 'ucs2':
    case 'ucs-2':
    case 'utf16le':
    case 'utf-16le':
      ret = this.ucs2Write(string, offset, length, nativeBuf);
      break;

    default:
      throw new TypeError('Unknown encoding: ' + encoding);
  }

  return ret;
};


nativeBuf.prototype.toJSON = function() {
  return {
    type: 'Buffer',
    data: Array.prototype.slice.call(this, 0)
  };
};


// TODO(trevnorris): currently works like Array.prototype.slice(), which
// doesn't follow the new standard for throwing on out of range indexes.
nativeBuf.prototype.slice = function(start, end) {
  var len = this.length;
  start = ~~start;
  end = util.isUndefined(end) ? len : ~~end;

  if (start < 0) {
    start += len;
    if (start < 0)
      start = 0;
  } else if (start > len) {
    start = len;
  }

  if (end < 0) {
    end += len;
    if (end < 0)
      end = 0;
  } else if (end > len) {
    end = len;
  }

  if (end < start)
    end = start;

  var buf = new NativeBuffer();
  sliceOnto(this, buf, start, end);
  buf.length = end - start;
  if (buf.length > 0)
    buf.parent = util.isUndefined(this.parent) ? this : this.parent;

  return buf;
};


function checkOffset(offset, ext, length) {
  if (offset + ext > length)
    throw new RangeError('index out of range');
}


nativeBuf.prototype.readUIntLE = function(offset, byteLength, noAssert) {
  offset = offset >>> 0;
  byteLength = byteLength >>> 0;
  if (!noAssert)
    checkOffset(offset, byteLength, this.length);

  var val = this[offset];
  var mul = 1;
  var i = 0;
  while (++i < byteLength && (mul *= 0x100))
    val += this[offset + i] * mul;

  return val;
};


nativeBuf.prototype.readUIntBE = function(offset, byteLength, noAssert) {
  offset = offset >>> 0;
  byteLength = byteLength >>> 0;
  if (!noAssert)
    checkOffset(offset, byteLength, this.length);

  var val = this[offset + --byteLength];
  var mul = 1;
  while (byteLength > 0 && (mul *= 0x100))
    val += this[offset + --byteLength] * mul;

  return val;
};


nativeBuf.prototype.readUInt8 = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 1, this.length);
  return this[offset];
};


nativeBuf.prototype.readUInt16LE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 2, this.length);
  return this[offset] | (this[offset + 1] << 8);
};


nativeBuf.prototype.readUInt16BE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 2, this.length);
  return (this[offset] << 8) | this[offset + 1];
};


nativeBuf.prototype.readUInt32LE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 4, this.length);

  return ((this[offset]) |
      (this[offset + 1] << 8) |
      (this[offset + 2] << 16)) +
      (this[offset + 3] * 0x1000000);
};


nativeBuf.prototype.readUInt32BE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 4, this.length);

  return (this[offset] * 0x1000000) +
      ((this[offset + 1] << 16) |
      (this[offset + 2] << 8) |
      this[offset + 3]);
};


nativeBuf.prototype.readIntLE = function(offset, byteLength, noAssert) {
  offset = offset >>> 0;
  byteLength = byteLength >>> 0;
  if (!noAssert)
    checkOffset(offset, byteLength, this.length);

  var val = this[offset];
  var mul = 1;
  var i = 0;
  while (++i < byteLength && (mul *= 0x100))
    val += this[offset + i] * mul;
  mul *= 0x80;

  if (val >= mul)
    val -= Math.pow(2, 8 * byteLength);

  return val;
};


nativeBuf.prototype.readIntBE = function(offset, byteLength, noAssert) {
  offset = offset >>> 0;
  byteLength = byteLength >>> 0;
  if (!noAssert)
    checkOffset(offset, byteLength, this.length);

  var i = byteLength;
  var mul = 1;
  var val = this[offset + --i];
  while (i > 0 && (mul *= 0x100))
    val += this[offset + --i] * mul;
  mul *= 0x80;

  if (val >= mul)
    val -= Math.pow(2, 8 * byteLength);

  return val;
};


nativeBuf.prototype.readInt8 = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 1, this.length);
  var val = this[offset];
  return !(val & 0x80) ? val : (0xff - val + 1) * -1;
};


nativeBuf.prototype.readInt16LE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 2, this.length);
  var val = this[offset] | (this[offset + 1] << 8);
  return (val & 0x8000) ? val | 0xFFFF0000 : val;
};


nativeBuf.prototype.readInt16BE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 2, this.length);
  var val = this[offset + 1] | (this[offset] << 8);
  return (val & 0x8000) ? val | 0xFFFF0000 : val;
};


nativeBuf.prototype.readInt32LE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 4, this.length);

  return (this[offset]) |
      (this[offset + 1] << 8) |
      (this[offset + 2] << 16) |
      (this[offset + 3] << 24);
};


nativeBuf.prototype.readInt32BE = function(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 4, this.length);

  return (this[offset] << 24) |
      (this[offset + 1] << 16) |
      (this[offset + 2] << 8) |
      (this[offset + 3]);
};


nativeBuf.prototype.readFloatLE = function readFloatLE(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 4, this.length);
  var i = this.readInt32LE(this, offset);
  return this._toFloat(i);
};


nativeBuf.prototype.readFloatBE = function readFloatBE(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 4, this.length);
  var i = this.readInt32BE(this, offset);
  return this._toFloat(i);
};


nativeBuf.prototype.readDoubleLE = function readDoubleLE(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 8, this.length);
  return this._readDoubleLE(this, offset);
};


nativeBuf.prototype.readDoubleBE = function readDoubleBE(offset, noAssert) {
  offset = offset >>> 0;
  if (!noAssert)
    checkOffset(offset, 8, this.length);
  return this._readDoubleBE(this, offset);
};


function checkInt(buffer, value, offset, ext, max, min) {
  if (!(buffer instanceof Buffer))
    throw new TypeError('buffer must be a Buffer instance');
  if (value > max || value < min)
    throw new TypeError('value is out of bounds');
  if (offset + ext > buffer.length)
    throw new RangeError('index out of range');
}


nativeBuf.prototype.writeUIntLE = function(value, offset, byteLength, noAssert) {
  value = +value;
  offset = offset >>> 0;
  byteLength = byteLength >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, byteLength, Math.pow(2, 8 * byteLength), 0);

  var mul = 1;
  var i = 0;
  this[offset] = value;
  while (++i < byteLength && (mul *= 0x100))
    this[offset + i] = (value / mul) >>> 0;

  return offset + byteLength;
};


nativeBuf.prototype.writeUIntBE = function(value, offset, byteLength, noAssert) {
  value = +value;
  offset = offset >>> 0;
  byteLength = byteLength >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, byteLength, Math.pow(2, 8 * byteLength), 0);

  var i = byteLength - 1;
  var mul = 1;
  this[offset + i] = value;
  while (--i >= 0 && (mul *= 0x100))
    this[offset + i] = (value / mul) >>> 0;

  return offset + byteLength;
};


nativeBuf.prototype.writeUInt8 = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 1, 0xff, 0);
  this[offset] = value;
  return offset + 1;
};


nativeBuf.prototype.writeUInt16LE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 2, 0xffff, 0);
  this[offset] = value;
  this[offset + 1] = (value >>> 8);
  return offset + 2;
};


nativeBuf.prototype.writeUInt16BE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 2, 0xffff, 0);
  this[offset] = (value >>> 8);
  this[offset + 1] = value;
  return offset + 2;
};


nativeBuf.prototype.writeUInt32LE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 4, 0xffffffff, 0);
  this[offset + 3] = (value >>> 24);
  this[offset + 2] = (value >>> 16);
  this[offset + 1] = (value >>> 8);
  this[offset] = value;
  return offset + 4;
};


nativeBuf.prototype.writeUInt32BE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 4, 0xffffffff, 0);
  this[offset] = (value >>> 24);
  this[offset + 1] = (value >>> 16);
  this[offset + 2] = (value >>> 8);
  this[offset + 3] = value;
  return offset + 4;
};


nativeBuf.prototype.writeIntLE = function(value, offset, byteLength, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert) {
    checkInt(this,
             value,
             offset,
             byteLength,
             Math.pow(2, 8 * byteLength - 1) - 1,
             -Math.pow(2, 8 * byteLength - 1));
  }

  var i = 0;
  var mul = 1;
  var sub = value < 0 ? 1 : 0;
  this[offset] = value;
  while (++i < byteLength && (mul *= 0x100))
    this[offset + i] = ((value / mul) >> 0) - sub;

  return offset + byteLength;
};


nativeBuf.prototype.writeIntBE = function(value, offset, byteLength, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert) {
    checkInt(this,
             value,
             offset,
             byteLength,
             Math.pow(2, 8 * byteLength - 1) - 1,
             -Math.pow(2, 8 * byteLength - 1));
  }

  var i = byteLength - 1;
  var mul = 1;
  var sub = value < 0 ? 1 : 0;
  this[offset + i] = value;
  while (--i >= 0 && (mul *= 0x100))
    this[offset + i] = ((value / mul) >> 0) - sub;

  return offset + byteLength;
};


nativeBuf.prototype.writeInt8 = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 1, 0x7f, -0x80);
  this[offset] = value;
  return offset + 1;
};


nativeBuf.prototype.writeInt16LE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 2, 0x7fff, -0x8000);
  this[offset] = value;
  this[offset + 1] = (value >>> 8);
  return offset + 2;
};


nativeBuf.prototype.writeInt16BE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 2, 0x7fff, -0x8000);
  this[offset] = (value >>> 8);
  this[offset + 1] = value;
  return offset + 2;
};


nativeBuf.prototype.writeInt32LE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 4, 0x7fffffff, -0x80000000);
  this[offset] = value;
  this[offset + 1] = (value >>> 8);
  this[offset + 2] = (value >>> 16);
  this[offset + 3] = (value >>> 24);
  return offset + 4;
};


nativeBuf.prototype.writeInt32BE = function(value, offset, noAssert) {
  value = +value;
  offset = offset >>> 0;
  if (!noAssert)
    checkInt(this, value, offset, 4, 0x7fffffff, -0x80000000);
  this[offset] = (value >>> 24);
  this[offset + 1] = (value >>> 16);
  this[offset + 2] = (value >>> 8);
  this[offset + 3] = value;
  return offset + 4;
};


function checkFloat(buffer, value, offset, ext) {
  if (!(buffer instanceof Buffer))
    throw new TypeError('buffer must be a Buffer instance');
  if (offset + ext > buffer.length)
    throw new RangeError('index out of range');
}


nativeBuf.prototype.writeFloatLE = function writeFloatLE(val, offset, noAssert) {
  val = +val;
  offset = offset >>> 0;
  if (!noAssert)
    checkFloat(this, val, offset, 4);
  var i = this._toFloat(val);
  this.writeInt32LE(i, offset);
  return offset + 4;
};


nativeBuf.prototype.writeFloatBE = function writeFloatBE(val, offset, noAssert) {
  val = +val;
  offset = offset >>> 0;
  if (!noAssert)
    checkFloat(this, val, offset, 4);
  var i = this._toFloat(val);
  this.writeInt32BE(i, offset);
  return offset + 4;
};


nativeBuf.prototype.writeDoubleLE = function writeDoubleLE(val, offset, noAssert) {
  val = +val;
  offset = offset >>> 0;
  if (!noAssert)
    checkFloat(this, val, offset, 8);
  this._writeDoubleLE(val, offset);
  return offset + 8;
};


nativeBuf.prototype.writeDoubleBE = function writeDoubleBE(val, offset, noAssert) {
  val = +val;
  offset = offset >>> 0;
  if (!noAssert)
    checkFloat(this, val, offset, 8);
  this._writeDoubleBE(val, offset);
  return offset + 8;
};
};

