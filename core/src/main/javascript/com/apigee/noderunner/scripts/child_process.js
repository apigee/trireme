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
var EventEmitter = require('events').EventEmitter;
var net = require('net');
var util = require('util');
var constants; // if (!constants) constants = process.binding('constants');
var ProcessWrap = process.binding('noderunner_process_wrap');

var debug;
var isDebug;
if (process.env.NODE_DEBUG && /child_process/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('child_process: %s', x); };
  isDebug = true;
} else {
  debug = function() { };
}

exports.fork = function(modulePath /*, args, options*/) {

  // Get options and args arguments.
  var options, args, execArgv;
  if (Array.isArray(arguments[1])) {
    args = arguments[1];
    options = util._extend({}, arguments[2]);
  } else {
    args = [];
    options = util._extend({}, arguments[1]);
  }

  // Prepare arguments for fork:
  execArgv = options.execArgv || process.execArgv;
  args = execArgv.concat([modulePath], args);

  // Leave stdin open for the IPC channel. stdout and stderr should be the
  // same as the parent's if silent isn't set.
  options.stdio = options.silent ? ['pipe', 'pipe', 'pipe', 'ipc'] :
      [0, 1, 2, 'ipc'];

  return spawn(process.execPath, args, options);
};

exports.exec = function(command /*, options, callback */) {
  var file, args, options, callback;

  if (typeof arguments[1] === 'function') {
    options = undefined;
    callback = arguments[1];
  } else {
    options = arguments[1];
    callback = arguments[2];
  }

  var splitArgs = command.split(' ');
  if (isDebug) {
    debug('exec(' + JSON.stringify(splitArgs) + ')');
  }

  if (splitArgs[0] === process.execPath) {
    // Trying to exec Node -- catch that and split args into an array
    file = process.execPath;
    args = splitArgs.slice(1);

  } else if (process.platform === 'win32') {
    file = 'cmd.exe';
    args = ['/s', '/c', command];
    // Make a shallow copy before patching so we don't clobber the user's
    // options object.
    options = util._extend({}, options);
    options.windowsVerbatimArguments = true;
  } else {
    file = '/bin/sh';
    args = ['-c', command];
  }

  return exports.execFile(file, args, options, callback);
};

exports.execFile = function(file /* args, options, callback */) {
  var args, optionArg, callback;
  var options = {
    encoding: 'utf8',
    timeout: 0,
    maxBuffer: 200 * 1024,
    killSignal: 'SIGTERM',
    cwd: null,
    env: null
  };

  // Parse the parameters.

  if (typeof arguments[arguments.length - 1] === 'function') {
    callback = arguments[arguments.length - 1];
  }

  if (Array.isArray(arguments[1])) {
    args = arguments[1];
    options = util._extend(options, arguments[2]);
  } else {
    args = [];
    options = util._extend(options, arguments[1]);
  }

  var child = spawn(file, args, {
    cwd: options.cwd,
    env: options.env,
    windowsVerbatimArguments: !!options.windowsVerbatimArguments
  });

  var stdout = '';
  var stderr = '';
  var killed = false;
  var exited = false;
  var timeoutId;

  var err;

  function exithandler(code, signal) {
    if (exited) return;
    exited = true;

    if (timeoutId) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }

    if (!callback) return;

    if (err) {
      callback(err, stdout, stderr);
    } else if (code === 0 && signal === null) {
      callback(null, stdout, stderr);
    } else {
      var e = new Error('Command failed: ' + stderr);
      e.killed = child.killed || killed;
      e.code = code;
      e.signal = signal;
      callback(e, stdout, stderr);
    }
  }

  function errorhandler(e) {
    err = e;
    child.stdout.destroy();
    child.stderr.destroy();
    exithandler();
  }

  function kill() {
    child.stdout.destroy();
    child.stderr.destroy();

    killed = true;
    try {
      child.kill(options.killSignal);
    } catch (e) {
      err = e;
      exithandler();
    }
  }

  if (options.timeout > 0) {
    timeoutId = setTimeout(function() {
      kill();
      timeoutId = null;
    }, options.timeout);
  }

  child.stdout.setEncoding(options.encoding);
  child.stderr.setEncoding(options.encoding);

  child.stdout.addListener('data', function(chunk) {
    stdout += chunk;
    if (stdout.length > options.maxBuffer) {
      err = new Error('maxBuffer exceeded.');
      kill();
    }
  });

  child.stderr.addListener('data', function(chunk) {
    stderr += chunk;
    if (stderr.length > options.maxBuffer) {
      err = new Error('maxBuffer exceeded.');
      kill();
    }
  });

  child.addListener('close', exithandler);
  child.addListener('error', errorhandler);

  return child;
};


var spawn = exports.spawn = function(file, args, options) {
  args = args ? args.slice(0) : [];
  args.unshift(file);

  var env = (options ? options.env : null) || process.env;
  var envPairs = [];
  for (var key in env) {
    envPairs.push(key + '=' + env[key]);
  }

  var child = new ChildProcess();
  if (options && options.customFds && !options.stdio) {
    options.stdio = options.customFds.map(function(fd) {
      return fd === -1 ? 'pipe' : fd;
    });
  }

  child.spawn({
    file: file,
    args: args,
    cwd: options ? options.cwd : null,
    windowsVerbatimArguments: !!(options && options.windowsVerbatimArguments),
    detached: !!(options && options.detached),
    envPairs: envPairs,
    stdio: options ? options.stdio : null,
    uid: options ? options.uid : null,
    gid: options ? options.gid : null
  });

  return child;
};



function ChildProcess() {
  EventEmitter.call(this);

  var self = this;

  this._closesNeeded = 1;
  this._closesGot = 0;

  this.signalCode = null;
  this.exitCode = null;
  this.killed = false;

  this._handle = ProcessWrap.createProcess();
  this._handle.owner = this;

  this._handle.onexit = function(exitCode, signalCode) {
    debug('onexit(' + exitCode + ', ' + signalCode + ')');
    //
    // follow 0.4.x behaviour:
    //
    // - normally terminated processes don't touch this.signalCode
    // - signaled processes don't touch this.exitCode
    //
    if (signalCode) {
      self.signalCode = signalCode;
    } else {
      self.exitCode = exitCode;
    }

    if (self.stdin && (self.stdin.destroy)) {
      self.stdin.destroy();
    }

    self._handle.close();
    self._handle = null;

    self.emit('exit', self.exitCode, self.signalCode);

    // if any of the stdio streams have not been touched,
    // then pull all the data through so that it can get the
    // eof and emit a 'close' event.
    // Do it on nextTick so that the user has one last chance
    // to consume the output, if for example they only want to
    // start reading the data once the process exits.
    process.nextTick(function() {
      flushStdio(self);
    });

    // The various background I/O tasks in Noderunner (not necessarily the same in Node)
    // put tasks on the queue to drain the I/O. Don't emit a close until these are done.
    process.nextTick(function() {
      maybeClose(self);
    });
  };
}
util.inherits(ChildProcess, EventEmitter);

function flushStdio(subprocess) {
  subprocess.stdio.forEach(function(stream, fd, stdio) {
    if (!stream || !stream.readable || stream._consuming ||
        stream._readableState.flowing)
      return;
    debug('Resuming stream ' + fd);
    stream.resume();
  });
}

function maybeClose(subprocess) {
  subprocess._closesGot++;

  if (subprocess._closesGot == subprocess._closesNeeded) {
    subprocess.emit('close', subprocess.exitCode, subprocess.signalCode);
  }
}

ChildProcess.prototype.spawn = function(options) {
  var self = this,
      ipc,
      // If no `stdio` option was given - use default
      stdio = options.stdio || 'pipe';

  // Replace shortcut with an array
  if (typeof stdio === 'string') {
    switch (stdio) {
      case 'ignore': stdio = ['ignore', 'ignore', 'ignore']; break;
      case 'pipe': stdio = ['pipe', 'pipe', 'pipe']; break;
      case 'inherit': stdio = [0, 1, 2]; break;
      default: throw new TypeError('Incorrect value of stdio option: ' + stdio);
    }
  } else if (!Array.isArray(stdio)) {
    throw new TypeError('Incorrect value of stdio option: ' + stdio);
  }

  // At least 3 stdio will be created
  // Don't concat() a new Array() because it would be sparse, and
  // stdio.reduce() would skip the sparse elements of stdio.
  // See http://stackoverflow.com/a/5501711/3561
  while (stdio.length < 3) stdio.push(undefined);

  // Translate stdio into C++-readable form
  // (i.e. PipeWraps or fds)
  stdio = stdio.reduce(function(acc, stdio, i) {
    function cleanup() {
      acc.filter(function(stdio) {
        return stdio.type === 'pipe';
      }).forEach(function(stdio) {
        stdio.socket.destroy();
      });
      /* TODO Noderunner repeat for IPC...
      acc.filter(function(stdio) {
        return stdio.type === 'pipe' || stdio.type === 'ipc';
      }).forEach(function(stdio) {
        stdio.handle.close();
      });
      */
    }

    // Defaults
    if (stdio === undefined || stdio === null) {
      stdio = i < 3 ? 'pipe' : 'ignore';
    }

    if (stdio === 'ignore') {
      acc.push({type: 'ignore'});
    } else if (stdio === 'pipe' || typeof stdio === 'number' && stdio < 0) {
      acc.push({type: 'pipe'});
    } else if (stdio === 'ipc') {
      acc.push({type: 'ipc'});
      ipc = true;
    } else if (typeof stdio === 'number' || typeof stdio.fd === 'number') {
      var fd = stdio.fd || stdio;
      if (fd > 2) {
        throw Error('File descriptors > 2 not supported in Noderunner');
      }
      acc.push({ type: 'fd', fd: fd });
    /*
    } else if (getHandleWrapType(stdio) || getHandleWrapType(stdio.handle) ||
               getHandleWrapType(stdio._handle)) {
      var handle = getHandleWrapType(stdio) ?
          stdio :
          getHandleWrapType(stdio.handle) ? stdio.handle : stdio._handle;

      acc.push({
        type: 'wrap',
        wrapType: getHandleWrapType(handle),
        handle: handle
      });
    */
    } else {
      // Cleanup
      cleanup();
      throw new TypeError('Incorrect value for stdio stream: ' + stdio);
    }

    return acc;
  }, []);

  options.stdio = stdio;

  var r = this._handle.spawn(options);

  if (r) {
    // Close all opened fds on error
    stdio.forEach(function(stdio) {
      if (stdio.type === 'pipe') {
        if (stdio.socket) {
          stdio.socket.destroy();
        }
      }
    });

    this._handle.close();
    this._handle = null;
    throw errnoException(errno, 'spawn');
  }

  this.pid = this._handle.pid;

  stdio.forEach(function(stdio, i) {
    if (stdio.type === 'ignore') return;

    if (stdio.ipc) {
      self._closesNeeded++;
      return;
    }

    /* Noderunner don't need this because we don't just use an FD as we do in node.js
    if (stdio.handle) {
      // when i === 0 - we're dealing with stdin
      // (which is the only one writable pipe)
      stdio.socket = createSocket(stdio.handle, i > 0);

      if (i > 0) {
        self._closesNeeded++;
        stdio.socket.on('close', function() {
          maybeClose(self);
        });
      }
    }
    */
  });

  this.stdin = stdio.length >= 1 && stdio[0].socket !== undefined ?
      stdio[0].socket : null;
  this.stdout = stdio.length >= 2 && stdio[1].socket !== undefined ?
      stdio[1].socket : null;
  this.stderr = stdio.length >= 3 && stdio[2].socket !== undefined ?
      stdio[2].socket : null;

  this.stdio = stdio.map(function(stdio) {
    return stdio.socket === undefined ? null : stdio.socket;
  });

  // Add .send() method and start listening for IPC data
  if (ipc !== undefined) setupChannel(this);

  return r;
};

function setupChannel(target) {
  target.connected = true;

  target.send = function(message, handle) {
    if (typeof message === 'undefined') {
      throw new TypeError('message cannot be undefined');
    }

    if (!target.connected) {
      this.emit('error', new Error('channel closed'));
      return;
    }

    if (handle) {
      // Do some handle processing here!
      throw new Error('Handles aren\'t supported yet');
    }

    // Use JSON to efficiently copy the object -- in theory we could send the original
    // but we may live to regret it some say
    var messageCopy = JSON.parse(JSON.stringify(message));
    var processTarget = target._handle.childProcess;
    processTarget.nextTick(function() {
      // Make sure that this runs in the right event loop of the right script
      processTarget.emit('message', messageCopy);
    });
  };

  target.disconnect = function() {
    if (!target.connected) {
      this.emit('error', new Error('IPC channel is already disconnected'));
      return;
    }

    target._handle.disconnect();
    target.connected = false;
  };

  target._handle.onMessage = function(message) {
    var messageCopy = JSON.parse(JSON.stringify(message));
    process.nextTick(function() {
      // Again, make sure that this runs in the right script thread
      target.emit('message', messageCopy);
    });
  };
}

function errnoException(errorno, syscall, errmsg) {
  // TODO make this more compatible with ErrnoException from src/node.cc
  // Once all of Node is using this function the ErrnoException from
  // src/node.cc should be removed.
  var message = syscall + ' ' + errorno;
  if (errmsg) {
    message += ' - ' + errmsg;
  }
  var e = new Error(message);
  e.errno = e.code = errorno;
  e.syscall = syscall;
  return e;
}


ChildProcess.prototype.kill = function(sig) {
  var signal;

  if (!constants) {
    constants = process.binding('constants');
  }

  if (sig === 0) {
    signal = 0;
  } else if (!sig) {
    signal = constants['SIGTERM'];
  } else {
    signal = constants[sig];
  }

  if (signal === undefined) {
    throw new Error('Unknown signal: ' + sig);
  }

  if (this._handle) {
    var r = this._handle.kill(signal);
    if (r == 0) {
      /* Success. */
      this.killed = true;
      return true;
    } else if (errno == 'ESRCH') {
      /* Already dead. */
    } else if (errno == 'EINVAL' || errno == 'ENOSYS') {
      /* The underlying platform doesn't support this signal. */
      throw errnoException(errno, 'kill');
    } else {
      /* Other error, almost certainly EPERM. */
      this.emit('error', errnoException(errno, 'kill'));
    }
  }

  /* Kill didn't succeed. */
  return false;
};


ChildProcess.prototype.ref = function() {
  if (this._handle) this._handle.ref();
};


ChildProcess.prototype.unref = function() {
  if (this._handle) this._handle.unref();
};
