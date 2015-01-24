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
 * The "vm" module is very dependent on Node and V8 internals. It is also simple. This version makes the
 * Java code complexity much lower.
 */

var evals = process.binding('evals').NodeScript;

var debug;
if (process.env.NODE_DEBUG && /vm/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('VM: %s', x); };
} else {
  debug = function() { };
}

module.exports = Script;
Script.Script = Script;

function Script(code, ctx, filename) {
  this.compiled = evals.compile(code, filename);
  if (ctx === undefined) {
    this.context = evals.getGlobalContext();
  } else {
    this.context = ctx;
  }
}

Script.createScript = function(code, ctx, name) {
  return new Script(code, ctx, name);
};

Script.runInThisContext = function(code, filename) {
  return evals.runInNewContext(code, evals.getGlobalContext(), filename);
};

Script.runInNewContext = function (code, sandbox, filename) {
  var ctx = evals.createContext();
  copyFromSandbox(sandbox, ctx);
  var result = evals.runInNewContext(code, ctx, filename);
  copyToSandbox(ctx, sandbox);
  return result;
};

Script.runInContext = function(code, context, filename) {
  return evals.runInNewContext(code, context, filename);
};

Script.createContext = function(sandbox) {
  var ctx = evals.createContext();
  debug('Creating context using sandbox ' + JSON.stringify(sandbox));
  copyFromSandbox(sandbox, ctx);
  debug('Context after sandbox ' + JSON.stringify(ctx));
  return ctx;
};

Script.createScript = function(code, filename) {
  return new Script(code, undefined, filename);
};

Script.prototype.runInThisContext = function() {
  return evals.run(this.context, this.compiled);
};

Script.prototype.runInNewContext = function(sandbox) {
  var ctx = evals.createContext();
  copyFromSandbox(sandbox, ctx);
  var result = evals.run(ctx, this.compiled);
  copyToSandbox(ctx, sandbox);
  return result;
};

Script.prototype.createContext = Script.createContext;

Script.prototype.runInContext = function(ctx) {
  return evals.run(ctx, this.compiled);
};

/*
 * Copy all properties, enumerable or non-enumerable, from the sandbox object to the context.
 */
function copyFromSandbox(sandbox, ctx) {
  if (sandbox != undefined) {
    Object.getOwnPropertyNames(sandbox).forEach(function(n) {
      Object.defineProperty(ctx, n,
                            Object.getOwnPropertyDescriptor(sandbox, n));
    });
  }
}

function copyToSandbox(ctx, sandbox) {
  if (sandbox != undefined) {
    Object.getOwnPropertyNames(ctx).forEach(function(n) {
      Object.defineProperty(sandbox, n,
                            Object.getOwnPropertyDescriptor(ctx, n));
    });
  }
}



