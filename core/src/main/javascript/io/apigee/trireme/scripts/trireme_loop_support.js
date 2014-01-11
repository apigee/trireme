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
 * This code is adapted from the original node.js and it is used for a number of things that the main
 * loop of Trireme (in ScriptRunner) needs to do that are better off being done in JavaScript code.
 */

var debug = false;

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
}
module.exports.submitTick = submitTick;

// This function is called on every fatal error and runs the "uncaughtException" handler.
// It is replaced if domains are enabled with a version below.
function handleFatal(er, debug) {
  if (debug) {
    console.log('Firing error "%s" to process exception handler', er);
  }
  var caught = process.emit('uncaughtException', er);
  if (debug) {
    console.log('Caught = %s', caught);
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
  return caught;
}
module.exports.handleFatal = handleFatal;

// We call this function whenever domains are initialized. We replace functions with versions that are
// sensitive to domains.
function usingDomains() {
  var domainModule = require('domain');
  var domainStack = domainModule._stack;

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
  }
  module.exports.submitTick = submitDomainTick;

  function handleDomainFatal(er, debug) {
    var caught = false;
    if (process.domain) {
      var domain = process.domain;

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
        if (debug) {
          console.log('Firing error "%s" to domain handler', er);
        }
        caught = domain.emit('error', er);
        if (debug) {
          console.log('Caught = %s', caught);
        }

        // Exit all domains on the stack.  Uncaught exceptions end the
        // current tick and no domains should be left on the stack
        // between ticks.
        domainStack.length = 0;
        domainModule.active = process.domain = null;
      } catch (er2) {
        if (debug) {
          console.log('Caught new error "%s" while handling domain error', er2);
          if (er2.stack) {
            console.log(er2.stack);
          }
        }
        // The domain error handler threw!  oh no!
        // See if another domain can catch THIS error,
        // or else crash on the original one.
        // If the user already exited it, then don't double-exit.
        if (domain === domainModule.active) {
          domainStack.pop();
        }
        if (domainStack.length) {
          var parentDomain = domainStack[domainStack.length - 1];
          process.domain = domainModule.active = parentDomain;
          caught = handleFatal(er2, debug);
        } else {
          if (debug) {
            console.log('Domain stack is now empty');
          }
          caught = false;
        }
      }
    } else {
      if (debug) {
        console.log('Firing error "%s" to process exception handler', er);
      }
      caught = process.emit('uncaughtException', er);
      if (debug) {
        console.log('Caught = %s', caught);
      }
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
    return caught;
  }
  module.exports.handleFatal = handleDomainFatal;
}

module.exports.usingDomains = usingDomains;






