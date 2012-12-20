# Noderunner

This is a set of libraries for running node.js scripts inside Java.

## Goals

The goal of Noderunner is to provide the substrate necessary to run real, useful Node code inside a Java VM.
This is important because there is a lot of software out there (including our own) that is built in Java and
isn't going to get rewritten in JavaScript now or in the future.

That means that Noderunner has to be highly embeddable. For instance:

* Replaceable HTTP module
* Configurable "firewall" settings on HTTP and network I/O to control what the container will allow users
to connect to
* Configurable "sandbox" settings on file I/O for the same reason
* Configurable thread pools, timers, and logging. I hate libraries that all want to create their own thread
pools and timers and so on...

## node.js Goals

Our goals are to support a very complete subset of node.js. However there are currently a few things that
just might not happen.

* Support for absolutely everything in the "os," "process," and "fs" modules. Java just doesn't expose all of
the low-level OS stuff. Where it does, we'll use it.
* Support for forking child processes -- or at least, we don't think you'll always want to do it that way -- see
below. We may end up implementing this anyway because a lot of tests require it.
* Support for undocumented Node features. It turns out that a lot of code out there relies on undocumented
code, extends internal classes in the HTTP implementation (ahem, express, ahem) and so on. As far as we are
concerned, if it's in the Node docs, then it's supported, and otherwise it's an implementation detail.
Of course we may have to relax this in order to support real stuff.
* Not sure what to do about debugging right now.

## Design

The process mode of Noderunner has the following elements:

### NodeEnvironment

A global environment for running lots of scripts.

### Node script

A script that *may* run in multiple threads.

## Process and Threading Model

Right now, a script runs in a single thread. That is, when the script is executed, it spawns a new thread
and occupies it for all time. Ticks and timers are implemented within that single thread. If the script
exits (has no ticks or timers, is not "pinned" by a library like http, and falls off the bottom of the code)
then the thread exits.

Since we are using Netty, I/O happens in separate thread pools.

We plan to change two things here:

First, when a script has no work to do, it should exit. Once exiting, the thread can be reclaimed. If a timer
was registered, we can use a separate timer thread to wake it up. If it is listening using the http or net
modules, then those modules can also wake it up when there are events to process.

By doing this, we hope to be able to run a large number of scripts inside a single JVM, all in their own
memory-protected "sandboxes," very efficiently.

Second, there should be a way to specify that multiple copies of a script may be launched. Then, when
a network connection is accepted by the "socket" module, or an HTTP request arrives at the "http"
module, those modules can trigger some logic that checks to see if there are free instances of the script
available to run right now, and if not launch a new script if necessary.

By doing this, we also hope to support multi-core CPUs efficiently without spawning subprocesses and without
requiring users to manually "fork" their scripts using the child_process and cluster modules.

However, we will likely need to also support these modules since so many scripts depend on them.

## Dependencies

### Rhino.

This is the most mature framework for running JavaScript under Java. When some of the other efforts are
closer to working, we may look at replacing it if, as anticipated, they are much faster.

### Netty 4

Netty is the most mature and ubiquitous framework for async network I/O in Java. Netty 4 is the bleeding-edge
latest version. It adds important things, especially support for half-closed sockets, which are necessary for
a complete HTTP implementation.

### Slf4j

This is the de facto standard logging API for Java.

### Java SE 6

Java is a great platform with a lot of support for a lot of nice things. We're going to try and do everything
we can without any additional stuff.