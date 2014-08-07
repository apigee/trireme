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
 * This is an implementation of "readline" that is designed for Trireme. Java does not have
 * raw terminal output, so all of what readline does is not possible. However, the main purpose
 * is to read individual lines from the terminal and readline does that fine.
 */

var binding = process.binding('console-wrap');
var util = require('util');

var EventEmitter = require('events').EventEmitter;
var StringDecoder = require('string_decoder').StringDecoder;

exports.createInterface = function(input, output, completer, terminal) {
  var rl;
  if (arguments.length === 1) {
    rl = new Interface(input);
  } else {
    rl = new Interface(input, output, completer, terminal);
  }
  return rl;
};

function Interface(input, output, completer, terminal) {
  if (!(this instanceof Interface)) {
    return new Interface(input, output, completer, terminal);
  }

  this._sawReturn = false;

  EventEmitter.call(this);

  if (arguments.length === 1) {
    // an options object was given
    output = input.output;
    completer = input.completer;
    terminal = input.terminal;
    input = input.input;
  }

  completer = completer || function() { return []; };

  if (typeof completer !== 'function') {
    throw new TypeError('Argument \'completer\' must be a function');
  }

  // If terminal is not specified, check whether we can really support it
  if (typeof terminal == 'undefined') {
    terminal = binding.isSupported();
  }

  var self = this;

  this.output = output;
  this.input = input;

  // Check arity, 2 - for async, 1 for sync
  this.completer = completer.length === 2 ? completer : function(v, callback) {
    callback(null, completer(v));
  };

  this.setPrompt('> ');

  this.terminal = !!terminal;

  function ondata(data) {
    self._normalWrite(data);
  }

  function onend() {
    self.close();
  }

  function online(line) {
    self._onLine(line);
  }

  this._decoder = new StringDecoder('utf8');

  if (!this.terminal) {
    // Non-terminal mode. Read from the specified stream as usual.
    input.on('data', ondata);
    input.on('end', onend);
    self.once('close', function() {
      input.removeListener('data', ondata);
      input.removeListener('end', onend);
    });

    input.resume();

  } else {

    if (!binding.isSupported()) {
      // Check that we are not reading from a fake TTY, which won't work in Java
      throw new Error('Terminal input is only supported on standard input');

    }
    this.terminal = true;

    // Pause whatever regular thing we have going on that might read from stdin
    input.pause();

    binding.onLine = online;
  }
}

util.inherits(Interface, EventEmitter);
exports.Interface = Interface;

Interface.prototype.__defineGetter__('columns', function() {
  return this.output.columns || Infinity;
});

Interface.prototype.setPrompt = function(prompt, length) {
  this._prompt = prompt;
  if (this.terminal) {
    binding.setPrompt(prompt);
  }
};

Interface.prototype.prompt = function(preserveCursor) {
  if (this.paused) this.resume();
  if (this.terminal) {
    // Re-start the reading, possibly with a new prompt
    binding.stopReading();
    binding.startReading();
  } else {
    this.output.write(this._prompt);
  }
};


Interface.prototype.question = function(query, cb) {
  if (typeof cb === 'function') {
    if (this._questionCallback) {
      this.prompt();
    } else {
      this._oldPrompt = this._prompt;
      this.setPrompt(query);
      this._questionCallback = cb;
      this.prompt();
    }
  }
};

Interface.prototype.close = function() {
  if (this.closed) return;
  this.pause();
  this.closed = true;
  this.emit('close');
};


Interface.prototype.pause = function() {
  if (this.paused) return;
  if (this.terminal) {
    binding.stopReading();
  } else {
    this.input.pause();
  }
  this.paused = true;
  this.emit('pause');
};


Interface.prototype.resume = function() {
  if (!this.paused) return;
  if (this.terminal) {
    binding.startReading();
  } else {
    this.input.resume();
  }
  this.paused = false;
  this.emit('resume');
};

// Docs say that we call this every time we want to write to output, but actually we seem
// to call this whenever there is new input!
Interface.prototype.write = function(d, key) {
  if (this.paused) this.resume();
  // For "tty" terminals this would only be called for "artifical" input creation.
  // For regular streams it is called on all new data.
  this._normalWrite(d);
  //this.terminal ? this._ttyWrite(d, key) : this._normalWrite(d);
};

// Called on every line that we get
Interface.prototype._onLine = function(line) {
  if (this._questionCallback) {
    var cb = this._questionCallback;
    this._questionCallback = null;
    this.setPrompt(this._oldPrompt);
    cb(line);
  } else {
    this.emit('line', line);
  }
};

// Code from Node.js which reads from a standard stream and breaks them into lines
// \r\n, \n, or \r followed by something other than \n
var lineEnding = /\r?\n|\r(?!\n)/;
Interface.prototype._normalWrite = function(b) {
  if (b === undefined) {
    return;
  }
  var string = this._decoder.write(b);
  if (this._sawReturn) {
    string = string.replace(/^\n/, '');
    this._sawReturn = false;
  }

  if (this._line_buffer) {
    string = this._line_buffer + string;
    this._line_buffer = null;
  }
  if (lineEnding.test(string)) {
    this._sawReturn = /\r$/.test(string);

    // got one or more newlines; process into "line" events
    var lines = string.split(lineEnding);
    // either '' or (concievably) the unfinished portion of the next line
    string = lines.pop();
    this._line_buffer = string;
    lines.forEach(function(line) {
      this._onLine(line);
    }, this);
  } else if (string) {
    // no newlines this time, save what we have for next time
    this._line_buffer = string;
  }
};

/*
 * Code from Node.js that manipulates the cursor on the screen using VT100 escape
 * sequences.
 */

/**
 * moves the cursor to the x and y coordinate on the given stream
 */

function cursorTo(stream, x, y) {
  if (typeof x !== 'number' && typeof y !== 'number')
    return;

  if (typeof x !== 'number')
    throw new Error("Can't set cursor row without also setting it's column");

  if (typeof y !== 'number') {
    stream.write('\x1b[' + (x + 1) + 'G');
  } else {
    stream.write('\x1b[' + (y + 1) + ';' + (x + 1) + 'H');
  }
}
exports.cursorTo = cursorTo;


/**
 * moves the cursor relative to its current location
 */

function moveCursor(stream, dx, dy) {
  if (dx < 0) {
    stream.write('\x1b[' + (-dx) + 'D');
  } else if (dx > 0) {
    stream.write('\x1b[' + dx + 'C');
  }

  if (dy < 0) {
    stream.write('\x1b[' + (-dy) + 'A');
  } else if (dy > 0) {
    stream.write('\x1b[' + dy + 'B');
  }
}
exports.moveCursor = moveCursor;

/**
 * clears the current line the cursor is on:
 *   -1 for left of the cursor
 *   +1 for right of the cursor
 *    0 for the entire line
 */

function clearLine(stream, dir) {
  if (dir < 0) {
    // to the beginning
    stream.write('\x1b[1K');
  } else if (dir > 0) {
    // to the end
    stream.write('\x1b[0K');
  } else {
    // entire line
    stream.write('\x1b[2K');
  }
}
exports.clearLine = clearLine;


/**
 * clears the screen from the current position of the cursor down
 */

function clearScreenDown(stream) {
  stream.write('\x1b[0J');
}
exports.clearScreenDown = clearScreenDown;