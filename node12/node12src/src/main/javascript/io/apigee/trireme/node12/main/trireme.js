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

// Hello, and welcome to hacking node.js!
//
// This file is invoked by node::Load in src/node.cc, and responsible for
// bootstrapping the node.js core. Special caution is given to the performance
// of the startup process, so many dependencies are invoked lazily.

/*
 * Trireme: This is an adaptation of node.js, which sets up the global and "process" variables
 * for Trireme. It is a copy of "node.js" with modifications to support Trireme's own event loop,
 * and covers over some differences between V8 and Rhino.
 */
(function(process) {
  var NativeModule = process.binding('native_module');

  this.global = this;

  function startup() {
    var EventEmitter = NativeModule.require('events').EventEmitter;

    // Use a different and slightly-hacky way to get the functions from EventEmitter on to this object,
    // which was already constructed. The usual methods don't work with Rhino if the object has already been
    // created.
    for (p in EventEmitter.prototype) {
      process[p] = EventEmitter.prototype[p];
    }
    EventEmitter.call(process);

    process.EventEmitter = EventEmitter; // process.EventEmitter is deprecated

    // do this good and early, since it handles errors.
    startup.processFatal();

    startup.globalVariables();
    startup.globalTimeouts();
    startup.globalConsole();

    startup.processAssert();
    // TODO
    //startup.processConfig();
    startup.processNextTick();
    startup.processStdio();
    startup.processKillAndExit();
    startup.processSignalHandlers();

    startup.processTrireme();
    startup.processJavaScriptCompatibility();

    // Do not initialize channel in debugger agent, it deletes env variable
    // and the main thread won't see it.
    if (process.argv[1] !== '--debug-agent')
      startup.processChannel();

    startup.processRawDebug();

    // Don't do this -- we rely on "node" being the process name in some cases.
    //startup.resolveArgv0();

    // There are various modes that Node can run in. The most common two
    // are running from a script and running the REPL - but there are a few
    // others like the debugger or running --eval arguments. Here we decide
    // which mode we run in.

    if (NativeModule.exists('_third_party_main')) {
      // To allow people to extend Node in different ways, this hook allows
      // one to drop a file lib/_third_party_main.js into the build
      // directory which will be executed instead of Node's normal loading.
      process.nextTick(function() {
        NativeModule.require('_third_party_main');
      });

    } else if (process.argv[1] == 'debug') {
      // Start the debugger agent
      var d = NativeModule.require('_debugger');
      d.start();

    } else if (process.argv[1] == '--debug-agent') {
      // Start the debugger agent
      var d = NativeModule.require('_debugger_agent');
      d.start();

    } else if (process._eval != null) {
      // User passed '-e' or '--eval' arguments to Node.
      evalScript('[eval]');
    } else if (process.argv[1]) {
      // make process.argv[1] into a full path
      var path = NativeModule.require('path');
      process.argv[1] = path.resolve(process.argv[1]);

      // If this is a worker in cluster mode, start up the communication
      // channel.
      if (process.env.NODE_UNIQUE_ID) {
        var cluster = NativeModule.require('cluster');
        cluster._setupWorker();

        // Make sure it's not accidentally inherited by child processes.
        delete process.env.NODE_UNIQUE_ID;
      }

      var Module = NativeModule.require('module');

      if (global.v8debug &&
          process.execArgv.some(function(arg) {
            return arg.match(/^--debug-brk(=[0-9]*)?$/);
          })) {

        // XXX Fix this terrible hack!
        //
        // Give the client program a few ticks to connect.
        // Otherwise, there's a race condition where `node debug foo.js`
        // will not be able to connect in time to catch the first
        // breakpoint message on line 1.
        //
        // A better fix would be to somehow get a message from the
        // global.v8debug object about a connection, and runMain when
        // that occurs.  --isaacs

        var debugTimeout = +process.env.NODE_DEBUG_TIMEOUT || 50;
        setTimeout(Module.runMain, debugTimeout);

      } else {
        // Main entry point into most programs:
        Module.runMain();
      }

    } else {
      var Module = NativeModule.require('module');

      // If -i or --interactive were passed, or stdin is a TTY.
      if (process._forceRepl || NativeModule.require('tty').isatty(0)) {
        // trireme-shell contains an alternate REPL that works better on Java.
        console.error('Trireme does not support the REPL yet in this mode.');
        process.exit(1);

      } else {
        // Read all of stdin - execute it.
        process.stdin.setEncoding('utf8');

        var code = '';
        process.stdin.on('data', function(d) {
          code += d;
        });

        process.stdin.on('end', function() {
          process._eval = code;
          evalScript('[stdin]');
        });
      }
    }
  }

  startup.globalVariables = function() {
    global.process = process;
    global.global = global;
    global.GLOBAL = global;
    global.root = global;
    global.Buffer = NativeModule.require('buffer').Buffer;
    process.domain = null;
    process._exiting = false;
  };

  startup.globalTimeouts = function() {
    global.setTimeout = function() {
      var t = NativeModule.require('timers');
      return t.setTimeout.apply(this, arguments);
    };

    global.setInterval = function() {
      var t = NativeModule.require('timers');
      return t.setInterval.apply(this, arguments);
    };

    global.clearTimeout = function() {
      var t = NativeModule.require('timers');
      return t.clearTimeout.apply(this, arguments);
    };

    global.clearInterval = function() {
      var t = NativeModule.require('timers');
      return t.clearInterval.apply(this, arguments);
    };

    global.setImmediate = function() {
      var t = NativeModule.require('timers');
      return t.setImmediate.apply(this, arguments);
    };

    global.clearImmediate = function() {
      var t = NativeModule.require('timers');
      return t.clearImmediate.apply(this, arguments);
    };
  };

  startup.globalConsole = function() {
    global.__defineGetter__('console', function() {
      return NativeModule.require('console');
    });
  };


  startup._lazyConstants = null;

  startup.lazyConstants = function() {
    if (!startup._lazyConstants) {
      startup._lazyConstants = process.binding('constants');
    }
    return startup._lazyConstants;
  };

  startup.processFatal = function() {
    process._fatalException = function(er) {
      var caught;

      if (process.domain && process.domain._errorHandler)
        caught = process.domain._errorHandler(er) || caught;

      if (!caught)
        caught = process.emit('uncaughtException', er);

      // If someone handled it, then great.  otherwise, die in C++ land
      // since that means that we'll exit the process, emit the 'exit' event
      if (!caught) {
        try {
          if (!process._exiting) {
            process._exiting = true;
            process.emit('exit', 1);
          }
        } catch (er) {
          // nothing to be done about it at this point.
        }

      // if we handled an error, then make sure any ticks get processed
      } else {
        NativeModule.require('timers').setImmediate(process._tickCallback);
      }

      return caught;
    };
  };

  var assert;
  startup.processAssert = function() {
    assert = process.assert = function(x, msg) {
      if (!x) throw new Error(msg || 'assertion error');
    };
  };

  startup.processConfig = function() {
    // used for `process.config`, but not a real module
    var config = NativeModule._source.config;
    delete NativeModule._source.config;

    // strip the gyp comment line at the beginning
    config = config.split('\n')
                   .slice(1)
                   .join('\n')
                   .replace(/"/g, '\\"')
                   .replace(/'/g, '"');

    process.config = JSON.parse(config, function(key, value) {
      if (value === 'true') return true;
      if (value === 'false') return false;
      return value;
    });
  };

  /*
   * Set up tick handling. Trireme does not have "microtasks" so we simplified this a bit.
   */
  startup.processNextTick = function() {
    var nextTickQueue = [];

    // *Must* match Environment::TickInfo::Fields in src/env.h.
    var kIndex = 0;
    var kLength = 1;

    process.nextTick = nextTick;
    // Needs to be accessible from beyond this scope.

    process._tickCallback = _tickCallback;
    process._tickDomainCallback = _tickDomainCallback;

    // Return an object with efficient access to the integers that track tick counts
    var tickInfo =
      process._setupNextTick(_tickCallback);

    function tickDone() {
      if (tickInfo[kLength] !== 0) {
        if (tickInfo[kLength] <= tickInfo[kIndex]) {
          nextTickQueue = [];
          tickInfo[kLength] = 0;
        } else {
          nextTickQueue.splice(0, tickInfo[kIndex]);
          tickInfo[kLength] = nextTickQueue.length;
        }
      }
      tickInfo[kIndex] = 0;
    }

    // Run callbacks that have no domain.
    // Using domains will cause this to be overridden.
    function _tickCallback() {
      var callback, threw, tock;

      while (tickInfo[kIndex] < tickInfo[kLength]) {
        tock = nextTickQueue[tickInfo[kIndex]++];
        callback = tock.callback;
        threw = true;
        try {
          callback();
          threw = false;
        } finally {
          if (threw)
            tickDone();
        }
        if (1e4 < tickInfo[kIndex])
          tickDone();
      }

      tickDone();
    }

    function _tickDomainCallback() {
      var callback, domain, threw, tock;

      while (tickInfo[kIndex] < tickInfo[kLength]) {
        tock = nextTickQueue[tickInfo[kIndex]++];
        callback = tock.callback;
        domain = tock.domain;
        if (domain)
          domain.enter();
        threw = true;
        try {
          callback();
          threw = false;
        } finally {
          if (threw)
            tickDone();
        }
        if (1e4 < tickInfo[kIndex])
          tickDone();
        if (domain)
          domain.exit();
      }

      tickDone();
    }

    function nextTick(callback) {
      // on the way out, don't bother. it won't get fired anyway.
      if (process._exiting)
        return;

      var obj = {
        callback: callback,
        domain: process.domain || null
      };

      nextTickQueue.push(obj);
      tickInfo[kLength]++;
    }
  };

  function evalScript(name) {
    var Module = NativeModule.require('module');
    var path = NativeModule.require('path');
    var cwd = process.cwd();

    var module = new Module(name);
    module.filename = path.join(cwd, name);
    module.paths = Module._nodeModulePaths(cwd);
    var script = process._eval;
    if (!Module._contextLoad) {
      var body = script;
      script = 'global.__filename = ' + JSON.stringify(name) + ';\n' +
               'global.exports = exports;\n' +
               'global.module = module;\n' +
               'global.__dirname = __dirname;\n' +
               'global.require = require;\n' +
               'return require("vm").runInThisContext(' +
               JSON.stringify(body) + ', ' +
               JSON.stringify(name) + ', true);\n';
    }
    var result = module._compile(script, name + '-wrapper');
    if (process._print_eval) console.log(result);
  }

  startup.processStdio = function() {
    var stdin, stdout, stderr;

    process.__defineGetter__('stdout', function() {
      if (stdout) return stdout;

      // Create a handle that could be the TTY
      var handle = process._stdoutHandle;
      if (handle.isTTY) {
        var tty = NativeModule.require('tty');
        stdout = new tty.WriteStream(handle);
      } else {
        var net = NativeModule.require('net');
        stdout = new net.Socket({
          handle: handle,
          readable: false,
          writable: true
        });
      }

      stdout.fd = 1;
      if (!process._childProcess) {
        stdout.destroy = stdout.destroySoon = function(er) {
          er = er || new Error('process.stdout cannot be closed.');
          stdout.emit('error', er);
        };
      }
      if (stdout.isTTY) {
        process.on('SIGWINCH', function() {
          stdout._refreshSize();
        });
      }
      return stdout;
    });

    process.__defineGetter__('stderr', function() {
      if (stderr) return stderr;

      var net = NativeModule.require('net');
      stderr = new net.Socket({
        handle: process._stderrHandle,
        readable: false,
        writable: true
      });

      stderr.fd = 2;
      if (!process._childProcess) {
        stderr.destroy = stderr.destroySoon = function(er) {
          er = er || new Error('process.stderr cannot be closed.');
          stderr.emit('error', er);
        };
      }
      return stderr;
    });

    process.__defineGetter__('stdin', function() {
      if (stdin) return stdin;
      stdin = process._stdinStream;

      // If we get here we have to create a new stdin. It could be a tty.
      var handle = process._stdinHandle;
      if (handle.isTTY) {
        var tty = NativeModule.require('tty');
        stdin = new tty.ReadStream(handle);
      } else {
        var net = NativeModule.require('net');
        stdin = new net.Socket({
          handle: handle,
          readable: true,
          writable: false
        });
      }

      // For supporting legacy API we put the FD here.
      stdin.fd = 0;

      // stdin starts out life in a paused state, but node doesn't
      // know yet.  Explicitly to readStop() it to put it in the
      // not-reading state.
      if (stdin._handle && stdin._handle.readStop) {
        stdin._handle.reading = false;
        stdin._readableState.reading = false;
        stdin._handle.readStop();
      }

      // if the user calls stdin.pause(), then we need to stop reading
      // immediately, so that the process can close down.
      stdin.on('pause', function() {
        if (!stdin._handle)
          return;
        stdin._readableState.reading = false;
        stdin._handle.reading = false;
        stdin._handle.readStop();
      });

      return stdin;
    });

    process.openStdin = function() {
      process.stdin.resume();
      return process.stdin;
    };
  };

  startup.processKillAndExit = function() {

    process.exit = function(code) {
      if (code || code === 0)
        process.exitCode = code;

      if (!process._exiting) {
        process._exiting = true;
        process.emit('exit', process.exitCode || 0);
      }
      process.reallyExit(process.exitCode || 0);
    };

    process.kill = function(pid, sig) {
      var err;

      if (pid != (pid | 0)) {
        throw new TypeError('invalid pid');
      }

      // preserve null signal
      if (0 === sig) {
        err = process._kill(pid, 0);
      } else {
        sig = sig || 'SIGTERM';
        if (startup.lazyConstants()[sig] &&
            sig.slice(0, 3) === 'SIG') {
          err = process._kill(pid, startup.lazyConstants()[sig]);
        } else {
          throw new Error('Unknown signal: ' + sig);
        }
      }

      if (err) {
        var errnoException = NativeModule.require('util')._errnoException;
        throw errnoException(err, 'kill');
      }

      return true;
    };
  };

  startup.processSignalHandlers = function() {
    // Load events module in order to access prototype elements on process like
    // process.addListener.
    var signalWraps = {};
    var addListener = process.addListener;
    var removeListener = process.removeListener;

    function isSignal(event) {
      return event.slice(0, 3) === 'SIG' &&
             startup.lazyConstants().hasOwnProperty(event);
    }

    // Wrap addListener for the special signal types
    process.on = process.addListener = function(type, listener) {
      /* TODO Trireme
      if (isSignal(type) &&
          !signalWraps.hasOwnProperty(type)) {
        var Signal = process.binding('signal_wrap').Signal;
        var wrap = new Signal();

        wrap.unref();

        wrap.onsignal = function() { process.emit(type); };

        var signum = startup.lazyConstants()[type];
        var r = wrap.start(signum);
        if (r) {
          wrap.close();
          throw errnoException(process._errno, 'uv_signal_start');
        }

        signalWraps[type] = wrap;
      }
      */

      return addListener.apply(this, arguments);
    };

    process.removeListener = function(type, listener) {
      var ret = removeListener.apply(this, arguments);
      if (isSignal(type)) {
        assert(signalWraps.hasOwnProperty(type));

        if (NativeModule.require('events').listenerCount(this, type) === 0) {
          signalWraps[type].close();
          delete signalWraps[type];
        }
      }

      return ret;
    };
  };


  startup.processChannel = function() {
    // If we were spawned with env NODE_CHANNEL_FD then load that up and
    // start parsing data from that stream.
    if (process.env.NODE_CHANNEL_FD) {
      var fd = parseInt(process.env.NODE_CHANNEL_FD, 10);
      assert(fd >= 0);

      // Make sure it's not accidentally inherited by child processes.
      delete process.env.NODE_CHANNEL_FD;

      var cp = NativeModule.require('child_process');

      // Load tcp_wrap to avoid situation where we might immediately receive
      // a message.
      // FIXME is this really necessary?
      process.binding('tcp_wrap');

      cp._forkChild(fd);
      assert(process.send);
    }
  };


  startup.processRawDebug = function() {
    var format = NativeModule.require('util').format;
    var rawDebug = process._rawDebug;
    process._rawDebug = function() {
      rawDebug(format.apply(null, arguments));
    };
  };


  startup.resolveArgv0 = function() {
    var cwd = process.cwd();
    var isWindows = process.platform === 'win32';

    // Make process.argv[0] into a full path, but only touch argv[0] if it's
    // not a system $PATH lookup.
    // TODO: Make this work on Windows as well.  Note that "node" might
    // execute cwd\node.exe, or some %PATH%\node.exe on Windows,
    // and that every directory has its own cwd, so d:node.exe is valid.
    var argv0 = process.argv[0];
    if (!isWindows && argv0.indexOf('/') !== -1 && argv0.charAt(0) !== '/') {
      var path = NativeModule.require('path');
      process.argv[0] = path.join(cwd, process.argv[0]);
    }
  };

  // TODO Do we need to include the "contextify" binding now?

  // Do Trireme-specific things, which we do a little bit differently than node.js
  startup.processTrireme = function() {
    function copyArgs(args, skipCount) {
      var a = [];
      for (var i = skipCount; i < args.length; i++) {
        a.push(args[i]);
      }
      return a;
    }

    // This function is called every time we want to submit a tick from process.nextTick or any other callback.
    // It ensures that the script can run since Rhino barfs when certain types of anonymous functions are
    // called directly from Java code. It also handles the domain stuff.
    function submitTick(func, thisObj, domain) {
      var fargs = copyArgs(arguments, 3);
      func.apply(thisObj, fargs);
      // node.cc always re-calls the ticks after executing a callback.
      // but we won't...
      //process._tickCallback();
    }
    process._submitTickCallback = submitTick;

    function submitDomainTick(func, thisObj, domain) {
      var fargs = copyArgs(arguments, 3);
      if (domain) {
        domain.enter();
      }
      func.apply(thisObj, fargs);
      if (domain) {
        // Don't use a finally here -- domain module will clean up in "handleFatal" below
        domain.exit();
      }
      // node.cc always re-calls the ticks after executing a callback.
      //process._tickCallback();
    }

    // Called by the "domain" module when switching to domains -- we replace a few functions
    // with others that are domain-aware.
    function usingDomains() {
      process._currentTickHandler = process._nextDomainTick;
      process._tickCallback = process._tickDomainCallback;
      process._submitTickCallback = submitDomainTick;
    }
    process._usingDomains = usingDomains;
  };

  /*
   * Do things to make our version of JavaScript more compatible with V8.
   */
  startup.processJavaScriptCompatibility = function() {
    // Replace "escape" with a function that ignores all but the first parameter.
    var rhinoEscape = escape;
    escape = function(s) {
      return rhinoEscape(s);
    };

    // Correct but not-as-efficient-as-possible implementations of trimLeft() and trimRight()
    if (!String.prototype.trimLeft) {
      var wsLeft = /^\s.*/;
      String.prototype.trimLeft = function() {
        var s = this;
        while (wsLeft.test(s)) {
          s = s.substring(1);
        }
        return s;
      };
    }

    if (!String.prototype.trimRight) {
      var wsRight = /.*\s$/;
      String.prototype.trimRight = function() {
        var s = this;
        while (wsRight.test(s)) {
          s = s.substring(0, s.length - 1);
        }
        return s;
      };
    }

    Number.isFinite = Number.isFinite || function(value) {
      return typeof value === "number" && isFinite(value);
    };

    Number.isNaN = Number.isNaN || function(value) {
      return typeof value === "number" && isNaN(value);
    };
  };

  // "Minimal module system" from node.js replaced here with the "NativeModule" Java module.

  startup();
});
