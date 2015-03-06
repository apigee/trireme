# Samples

This directory contains sample code for Trireme. For the most part these samples show how to use Trireme
to embed Java code and make it look like Node.js code.

## war

This sample builds a WAR file that uses the "trireme-servlet" module to run a Node.js application
inside a Java servlet container.

## java-hello

This is the simplest possible example of Java code running inside Trireme. It consists of a module that
exposes a single function called "hello".

It demonstrates:

* Using Rhino annotations to create a JavaScript "class" from Java code
* Wiring up the new class to Trireme

## java-stream

This is a much more complex example that creates a module that can create streams that can read and
write a file using the "java.io" stream classes. (Of course, the "fs" module works just fine in Trireme,
but file I/O makes a simple and easy-to-test example.)

On top of the previous example, this example demonstrates:

* Embedding JavaScript code into the module along with the Java code
* Translating between Node.js Buffer objects and byte arrays in Java
* Creating "readable streams" and "writable streams" in JavaScript from the "streams2" module
* Creating classes in Java that can be constructed from JavaScript

## hadoop

This is more of a proof of concept than a complete example. It shows how Trireme could be used to
run Node.js code as part of a Hadoop job.

# Running the Samples

The "java-hello" samples are jar files that contain modules. If they are in the classpath when Trireme is launched,
then the modules that they create will be loaded by "require".

An easy way to run them is to use the "trireme" command-line launcher from the "npm-package" module.
If you don't want to build all that, then you can just build these samples and use the TRIREME_CLASSPATH
environment variable to add them to Trireme's class path, like this:

    sudo npm install -g trireme
    export TRIREME_CLASSPATH=/path/to/module.jar
    trireme <your test script goes here>
