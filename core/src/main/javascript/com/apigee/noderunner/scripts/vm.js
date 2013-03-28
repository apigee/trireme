/*
 * The "vm" module is very dependent on Node and V8 internals. It is also simple. This version makes the
 * Java code complexity much lower.
 */

var binding = process.binding('noderunner_evals');

var debug;
if (process.env.NODE_DEBUG && /vm/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('VM: %s', x); };
} else {
  debug = function() { };
}

module.exports = Script;
Script.Script = Script;

function Script(code, ctx, filename) {
  this.compiled = binding.compile(code, filename);
  if (ctx === undefined) {
    this.context = binding.globalContext;
  } else {
    this.context = ctx;
  }
}

Script.createScript = function(code, ctx, name) {
  return new Script(code, ctx, name);
};

Script.runInThisContext = function(code, filename) {
  //var compiled = binding.compile(code, filename);
  //return binding.run(binding.globalContext, compiled);
  return binding.compileAndRun(code, filename, binding.globalContext);
}

Script.runInNewContext = function (code, sandbox, filename) {
  var compiled = binding.compile(code, filename);
  var ctx = binding.createContext();
  copyFromSandbox(sandbox, ctx);
  var result = binding.run(ctx, compiled);
  copyToSandbox(ctx, sandbox);
  return result;
};

Script.runInContext = function(code, context, filename) {
  //var compiled = binding.compile(code, filename);
  //return binding.run(context, compiled);
  return binding.compileAndRun(code, filename, context);
};

Script.createContext = function(sandbox) {
  var ctx = binding.createContext();
  debug('Creating context using sandbox ' + JSON.stringify(sandbox));
  copyFromSandbox(sandbox, ctx);
  debug('Context after sandbox ' + JSON.stringify(ctx));
  return ctx;
};

Script.createScript = function(code, filename) {
  return new Script(code, undefined, filename);
};

Script.prototype.runInThisContext = function() {
  return binding.run(this.context, this.compiled);
};

Script.prototype.runInNewContext = function(sandbox) {
  var ctx = binding.createContext();
  copyFromSandbox(sandbox, ctx);
  var result = binding.run(ctx, this.compiled);
  copyToSandbox(ctx, sandbox);
  return result;
};

Script.prototype.createContext = Script.createContext;

Script.prototype.runInContext = function(ctx) {
  return binding.run(ctx, this.compiled);
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



