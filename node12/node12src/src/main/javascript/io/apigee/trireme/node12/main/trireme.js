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
 * for Trireme. It has many similarities to node.js, but also some differences, which is why it is a copy.
 * For instance, we don't set up stdin and stdout the same way, since Java handles the different ways
 * that we may do file and console I/O.
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

    /*
    process.__proto__ = Object.create(EventEmitter.prototype, {
      constructor: {
        value: process.constructor
      }
    });
    */
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

    startup.processChannel();

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

    } else if (process._eval != null) {
      // User passed '-e' or '--eval' arguments to Node.
      evalScript('[eval]');
    } else if (process.argv[1]) {
      // make process.argv[1] into a full path
      var path = NativeModule.require('path');
      process.argv[1] = path.resolve(process.argv[1]);

      // If this is a worker in cluster mode, start up the communiction
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
        console.error('Trireme does not support the REPL yet.');
        console.error('Use "trireme -h" for usage.');
        process.exit(1);
        /*
        // REPL
        var opts = {
          useGlobal: true,
          ignoreUndefined: false
        };
        if (parseInt(process.env['NODE_NO_READLINE'], 10)) {
          opts.terminal = false;
        }
        if (parseInt(process.env['NODE_DISABLE_COLORS'], 10)) {
          opts.useColors = false;
        }
        var repl = Module.requireRepl().start(opts);
        repl.on('exit', function() {
          process.exit();
        });
        */

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
    //process.binding('buffer').setFastBufferConstructor(global.Buffer);
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
    // call into the active domain, or emit uncaughtException,
    // and exit if there are no listeners.
    process._fatalException = function(er) {
      var caught = false;
      if (process.domain) {
        var domain = process.domain;
        var domainModule = NativeModule.require('domain');
        var domainStack = domainModule._stack;

        // ignore errors on disposed domains.
        //
        // XXX This is a bit stupid.  We should probably get rid of
        // domain.dispose() altogether.  It's almost always a terrible
        // idea.  --isaacs
        if (domain._disposed)
          return true;

        er.domain = domain;
        er.domainThrown = true;
        // wrap this in a try/catch so we don't get infinite throwing
        try {
          // One of three things will happen here.
          //
          // 1. There is a handler, caught = true
          // 2. There is no handler, caught = false
          // 3. It throws, caught = false
          //
          // If caught is false after this, then there's no need to exit()
          // the domain, because we're going to crash the process anyway.
          caught = domain.emit('error', er);

          // Exit all domains on the stack.  Uncaught exceptions end the
          // current tick and no domains should be left on the stack
          // between ticks.
          var domainModule = NativeModule.require('domain');
          domainStack.length = 0;
          domainModule.active = process.domain = null;
        } catch (er2) {
          // The domain error handler threw!  oh no!
          // See if another domain can catch THIS error,
          // or else crash on the original one.
          // If the user already exited it, then don't double-exit.
          if (domain === domainModule.active)
            domainStack.pop();
          if (domainStack.length) {
            var parentDomain = domainStack[domainStack.length - 1];
            process.domain = domainModule.active = parentDomain;
            caught = process._fatalException(er2);
          } else
            caught = false;
        }
      } else {
        caught = process.emit('uncaughtException', er);
      }
      // if someone handled it, then great.  otherwise, die in C++ land
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
      }
      // if we handled an error, then make sure any ticks get processed
      if (caught)
        process._needTickCallback();
      return caught;
    };
  };

  var assert;
  startup.processAssert = function() {
    // Note that calls to assert() are pre-processed out by JS2C for the
    // normal build of node. They persist only in the node_g build.
    // Similarly for debug().
    assert = process.assert = function(x, msg) {
      if (!x) throw new Error(msg || 'assertion error');
    };
  };

  startup.processConfig = function() {
    // used for `process.config`, but not a real module
    var config = NativeModule._source.config;
    delete NativeModule._source.config;

    // strip the gyp comment line at the beginning
    config = config.split('\n').slice(1).join('\n').replace(/'/g, '"');

    process.config = JSON.parse(config, function(key, value) {
      if (value === 'true') return true;
      if (value === 'false') return false;
      return value;
    });
  };

  startup.processNextTick = function() {
    var _needTickCallback = process._needTickCallback.bind(process);
    var nextTickQueue = [];
    var needSpinner = true;
    var inTick = false;

    // this infobox thing is used so that the C++ code in src/node.cc
    // can have easy accesss to our nextTick state, and avoid unnecessary
    // calls into process._tickCallback.
    // order is [length, index, depth]
    // Never write code like this without very good reason!
    var infoBox = process._tickInfoBox;
    var length = 0;
    var index = 1;
    var depth = 2;

    process.nextTick = function nextTick(cb) {
      process._currentTickHandler(cb);
    };

    // needs to be accessible from cc land
    process._currentTickHandler = _nextTick;
    process._nextDomainTick = _nextDomainTick;
    process._tickCallback = _tickCallback;
    process._tickDomainCallback = _tickDomainCallback;
    process._tickFromSpinner = _tickFromSpinner;

    // the maximum number of times it'll process something like
    // nextTick(function f(){nextTick(f)})
    // It's unlikely, but not illegal, to hit this limit.  When
    // that happens, it yields to libuv's tick spinner.
    // This is a loop counter, not a stack depth, so we aren't using
    // up lots of memory here.  I/O can sneak in before nextTick if this
    // limit is hit, which is not ideal, but not terrible.
    process.maxTickDepth = 1000;

    function tickDone(tickDepth_) {
      if (infoBox[length] !== 0) {
        if (infoBox[length] <= infoBox[index]) {
          nextTickQueue = [];
          infoBox[length] = 0;
        } else {
          nextTickQueue.splice(0, infoBox[index]);
          infoBox[length] = nextTickQueue.length;
          if (needSpinner) {
            _needTickCallback();
            needSpinner = false;
          }
        }
      }
      inTick = false;
      infoBox[index] = 0;
      infoBox[depth] = tickDepth_;
    }

    function maxTickWarn() {
      // XXX Remove all this maxTickDepth stuff in 0.11
      var msg = '(node) warning: Recursive process.nextTick detected. ' +
                'This will break in the next version of node. ' +
                'Please use setImmediate for recursive deferral.';
      if (process.throwDeprecation)
        throw new Error(msg);
      else if (process.traceDeprecation)
        console.trace(msg);
      else
        console.error(msg);
    }

    function _tickFromSpinner() {
      needSpinner = true;
      // coming from spinner, reset!
      if (infoBox[depth] !== 0)
        infoBox[depth] = 0;
      // no callbacks to run
      if (infoBox[length] === 0)
        return infoBox[index] = infoBox[depth] = 0;
      process._tickCallback();
    }

    // run callbacks that have no domain
    // using domains will cause this to be overridden
    function _tickCallback() {
      var callback, nextTickLength, threw;

      if (inTick) return;
      if (infoBox[length] === 0) {
        infoBox[index] = 0;
        infoBox[depth] = 0;
        return;
      }
      inTick = true;

      while (infoBox[depth]++ < process.maxTickDepth) {
        nextTickLength = infoBox[length];
        if (infoBox[index] === nextTickLength)
          return tickDone(0);

        while (infoBox[index] < nextTickLength) {
          callback = nextTickQueue[infoBox[index]++].callback;
          threw = true;
          try {
            callback();
            threw = false;
          } finally {
            if (threw) tickDone(infoBox[depth]);
          }
        }
      }

      tickDone(0);
    }

    function _tickDomainCallback() {
      var nextTickLength, tock, callback, threw;

      // if you add a nextTick in a domain's error handler, then
      // it's possible to cycle indefinitely.  Normally, the tickDone
      // in the finally{} block below will prevent this, however if
      // that error handler ALSO triggers multiple MakeCallbacks, then
      // it'll try to keep clearing the queue, since the finally block
      // fires *before* the error hits the top level and is handled.
      if (infoBox[depth] >= process.maxTickDepth)
        return _needTickCallback();

      if (inTick) return;
      inTick = true;

      // always do this at least once.  otherwise if process.maxTickDepth
      // is set to some negative value, or if there were repeated errors
      // preventing depth from being cleared, we'd never process any
      // of them.
      while (infoBox[depth]++ < process.maxTickDepth) {
        nextTickLength = infoBox[length];
        if (infoBox[index] === nextTickLength)
          return tickDone(0);

        while (infoBox[index] < nextTickLength) {
          tock = nextTickQueue[infoBox[index]++];
          callback = tock.callback;
          if (tock.domain) {
            if (tock.domain._disposed) continue;
            tock.domain.enter();
          }
          threw = true;
          try {
            callback();
            threw = false;
          } finally {
            // finally blocks fire before the error hits the top level,
            // so we can't clear the depth at this point.
            if (threw) tickDone(infoBox[depth]);
          }
          if (tock.domain) {
            tock.domain.exit();
          }
        }
      }

      tickDone(0);
    }

    function _nextTick(callback) {
      // on the way out, don't bother. it won't get fired anyway.
      if (process._exiting)
        return;
      if (infoBox[depth] >= process.maxTickDepth)
        maxTickWarn();

      var obj = { callback: callback, domain: null };

      nextTickQueue.push(obj);
      infoBox[length]++;

      if (needSpinner) {
        _needTickCallback();
        needSpinner = false;
      }
    }

    function _nextDomainTick(callback) {
      // on the way out, don't bother. it won't get fired anyway.
      if (process._exiting)
        return;
      if (infoBox[depth] >= process.maxTickDepth)
        maxTickWarn();

      var obj = { callback: callback, domain: process.domain };

      nextTickQueue.push(obj);
      infoBox[length]++;

      if (needSpinner) {
        _needTickCallback();
        needSpinner = false;
      }
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

  function errnoException(errorno, syscall) {
    // TODO make this more compatible with ErrnoException from src/node.cc
    // Once all of Node is using this function the ErrnoException from
    // src/node.cc should be removed.
    var e = new Error(syscall + ' ' + errorno);
    e.errno = e.code = errorno;
    e.syscall = syscall;
    return e;
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
      if (!process._exiting) {
        process._exiting = true;
        process.emit('exit', code || 0);
      }
      process.reallyExit(code || 0);
    };

    process.kill = function(pid, sig) {
      var r;

      // preserve null signal
      if (0 === sig) {
        r = process._kill(pid, 0);
      } else {
        sig = sig || 'SIGTERM';
        if (startup.lazyConstants()[sig] &&
            sig.slice(0, 3) === 'SIG') {
          r = process._kill(pid, startup.lazyConstants()[sig]);
        } else {
          throw new Error('Unknown signal: ' + sig);
        }
      }

      if (r) {
        throw errnoException(process._errno, 'kill');
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

        if (this.listeners(type).length === 0) {
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
  }

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
      process._tickCallback();
    }
    process._submitTick = submitTick;

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
      process._tickCallback();
    }

    // Called by the "domain" module when switching to domains -- we replace a few functions
    // with others that are domain-aware.
    function usingDomains() {
      process._currentTickHandler = process._nextDomainTick;
      process._tickCallback = process._tickDomainCallback;
      process._submitTick = submitDomainTick;
    }
    process._usingDomains = usingDomains;

    if (process.connected) {
      var childRef = 0;

      // Pin and unpin the process depending on whether we are listening for events from the parent
      process.on('disconnect', function() {
        if (childRef > 0) {
          process._unpin();
          childRef = 0;
        }
      });

      // This next logic only works if the previous listener is registered first!
      process.on('newListener', function(event) {
        if (!process.connected) {
          return;
        }
        if ((event === 'message') || (event === 'disconnect')) {
          if (++childRef === 1) {
            process._pin();
          }
        }
      });

      process.on('removeListener', function(event) {
        if (!process.connected) {
          return;
        }
        if ((event === 'message') || (event === 'disconnect')) {
          if (--childRef === 0) {
            process._unpin();
          }
        }
      });
    }
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
