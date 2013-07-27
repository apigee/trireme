## Coding tasks:

Support "multitenant" ScriptRunners, so that lots and lots of scripts
can share an event loop.

Pre-process the built-in scripts like Node does to:
* Strip out "assert" calls
* Strip out "debug" calls
* Process the various macros referring to DTrace and the like

Add in hooks for Codahale metrics.

** Node module status:
* Important for completion of various frameworks:

crypto: 
  Partially done. Will be needed by advanced frameworks.
cluster:
  Need to build the pipeline between ScriptRunners in the same JVM.
  child_process supports "fork" within the JVM.

## Less important but needed for compatibility:

repl:
  Partially coded but really not working.
dns:
  Probably won't be used directly. Implemented "lookup" so far.
debugger:
  Perhaps we can use the hooks in Rhino? Unlikely.
