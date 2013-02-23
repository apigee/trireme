// This is a version of the interface for the "tls" module that works in the Noderunner environment.
// Why? Because the native node TLS module is a hand-written, in JavaScript, implementation of SSL on top
// of OpenSSL. The SSL functionality available in Java (SSLEngine) is very different, and mapping the two
// is going to be ugly. So, this module uses standard "net" to handle the ciphertext connection, and an internal
// Java module that wraps SSLEngine for the SSL bit.
// This also allows us to use standard Java mechanisms to replace SSLEngine with alternatives in highly
// secure environments.

var assert = require('assert');
var util = require('util');
var net = require('net');
var Stream = require('stream');
var StringDecoder = require('string_decoder').StringDecoder;
var wrap = process.binding('ssl_wrap');
var EventEmitter = require('events').EventEmitter;

var debug;
if (process.env.NODE_DEBUG && /tls/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('TLS:', x); };
} else {
  debug = function() { };
}

function Server() {
  var options, listener;

  if (typeof arguments[0] == 'function') {
    options = {};
    listener = arguments[0];
  } else {
    options = arguments[0] || {};
    listener = arguments[1];
  }
  if (!(this instanceof Server)) return new Server(options, listener);

  var self = this;
  if ((options == undefined) || (options.keystore == undefined)) {
    throw 'keystore in Java JKS format must be included in options';
  }
  if (listener) {
    self.on('secureConnection', listener);
  }

  self.context = wrap.createContext(options);

  self.netServer = net.createServer(options, function(connection) {
    var engine = self.context.createEngine(false);
    var clearStream = new CleartextStream();
    clearStream.init(true, self, connection, engine);
  });
}
util.inherits(Server, net.Server);
exports.Server = Server;

exports.createServer = function () {
  return new Server(arguments[0], arguments[1]);
};

Server.prototype.listen = function() {
  var callback;
  var options;
  var self = this;
  if (typeof arguments[0] == 'function') {
    callback = arguments[0];
  } else if (typeof arguments[1] == 'function') {
    callback = arguments[1];
  } else if (typeof arguments[2] == 'function') {
    callback = arguments[2];
  }

  if (callback !== undefined) {
    self.on('listening', callback);
  }
  this.netServer.on('listening', function() {
    debug('listening');
    self.emit('listening');
  });
  self.netServer.listen(arguments[0], arguments[1], arguments[2]);
};

exports.connect = function() {
  var args = net._normalizeConnectArgs(arguments);
  var options = args[0];
  var callback = args[1];

  if (options.host === undefined) {
    options.host = 'localhost';
  }

  var sslContext;
  if (options.rejectUnauthorized == false) {
    sslContext = wrap.createAllTrustingContext();
  } else {
    sslContext = wrap.createDefaultContext();
  }

  // TODO pass host and port to SSL engine init
  var engine = sslContext.createEngine(true);
  var sslConn = new CleartextStream();
  var netConn = net.connect(options, function() {
    sslConn.engine.beginHandshake();
    writeCleartext(sslConn);
  });
  sslConn.init(false, undefined, netConn, engine);
  writeCleartext(sslConn);
  if (callback) {
    sslConn.on('secureConnect', callback);
  }
  return sslConn;
};

exports.createSecurePair = function() {
  throw 'Not implemented';
};

Server.prototype.close = function() {
  this.netServer.close();
  this.emit('close');
};

Server.prototype.addContext = function(hostname, credentials) {
  // TODO something...
};

function CleartextStream() {
}
util.inherits(CleartextStream, net.Socket);

CleartextStream.prototype.init = function(serverMode, server, connection, engine) {
  var self = this;
  self.serverMode = serverMode;
  self.server = server;
  self.connection = connection;
  self.engine = engine;
  self.closed = false;
  self.closing = false;
  connection.ondata = function(data, offset, end) {
    debug('onData');
    readCiphertext(self, data, offset, end);
  };
  connection.onend = function() {
    debug('onEnd');
    handleEnd(self);
  };
  connection.on('error', function(err) {
    debug('onError');
    self.emit('error', err);
  });
  connection.on('close', function() {
    debug('onClose');
    if (!self.closed) {
      self.connection.destroy();
      self.emit('close');
    }
  });
  connection.on('timeout', function() {
    debug('onTimeout');
    self.emit('timeout');
  });
  connection.on('drain', function() {
    debug('onDrain');
    self.emit('drain');
  });
};

CleartextStream.prototype.getPeerCertificate = function() {
  // TODO
};

CleartextStream.prototype.getCipher = function() {
  // TODO
};

CleartextStream.prototype.address = function() {
  return this.connection.address();
};

CleartextStream.prototype.write = function(data, arg1, arg2) {
  debug('write');
  var encoding, cb;

  // parse arguments
  if (arg1) {
    if (typeof arg1 === 'string') {
      encoding = arg1;
      cb = arg2;
    } else if (typeof arg1 === 'function') {
      cb = arg1;
    } else {
      throw new Error('bad arg');
    }
  }

  if (typeof data === 'string') {
    encoding = (encoding || 'utf8').toLowerCase();
    data = new Buffer(data, encoding);
  } else if (!Buffer.isBuffer(data)) {
    throw new TypeError('First argument must be a buffer or a string.');
  }

  return writeCleartext(this, data, cb);
};

CleartextStream.prototype.end = function(data, encoding) {
  debug('end');
  if (data) {
    this.write(data, encoding);
  }
  if (!this.closed && !this.closing) {
    debug('Closing SSL outbound');
    this.closing = true;
    this.engine.closeOutbound();
    while (!this.engine.isOutboundDone()) {
      writeCleartext(this);
    }
  }
};

function handleEnd(self) {
  if (!self.closed && !self.closing) {
    debug('Closing SSL inbound');
    self.closing = true;
    self.engine.closeInbound();
    while (!self.engine.isInboundDone()) {
      writeCleartext(self);
    }
    self.connection.destroy();
    self.emit('closed');
  }
}

CleartextStream.prototype.destroy = function() {
  this.connection.destroy();
}

CleartextStream.prototype.justHandshaked = function() {
  debug('justHandshaked');
  this.readable = this.writable = true;
  if (this.serverMode) {
    this.server.emit('secureConnection', this);
  } else {
    this.emit('secureConnect');
  }
}

CleartextStream.prototype.setEncoding = function(encoding) {
  this.decoder = new StringDecoder(encoding);
}

// TODO offset and end
function readCiphertext(self, data) {
  var sslResult = self.engine.unwrap(data);
  debug('readCiphertext: SSL status ' + sslResult.status + ' read ' + sslResult.consumed);
  if (sslResult.justHandshaked) {
    self.justHandshaked();
  }

  switch (sslResult.status) {
    case self.engine.NEED_WRAP:
      writeCleartext(self);
      break;
    case self.engine.NEED_UNWRAP:
      // Sometimes we need to unwrap while we're unwrapping I guess
      readCiphertext(self);
      break;
    case self.engine.NEED_TASK:
      self.engine.runTask(function() {
        readCiphertext(self);
      });
      break;
    case self.engine.UNDERFLOW:
      // Nothing to do -- wait until we get more data
      break;
    case self.engine.OK:
      if (sslResult.data) {
        emitRawData(self, sslResult.data);
      }
      if ((data !== undefined) && (sslResult.consumed < data.length)) {
        // Once handshaking is done, we might need to unwrap again with the same data
        readCiphertext(self);
      }
      break;
    case self.engine.CLOSED:
      self.closed = true;
      self.connection.destroy();
      self.emit('close');
      break;
    case self.engine.ERROR:
      self.emit('error', 'SSL error');
      break;
    default:
      throw 'Unexpected SSL engine status ' + sslResult.status;
  }
}

function writeCleartext(self, data, cb) {
  var sslResult = self.engine.wrap(data);
  var writeStatus = false;
  debug('writeCiphertext: SSL status ' + sslResult.status);
  if (sslResult.justHandshaked) {
    self.justHandshaked();
  }
  if (cb) {
    if (!self.writeCallbacks) {
      self.writeCallbacks = [];
    }
    self.writeCallbacks.push(cb);
  }
  if (sslResult.data) {
    debug('Writing ' + sslResult.data.length);
    writeStatus = self.connection.write(sslResult.data, function() {
      if (self.writeCallbacks) {
        var popped = self.writeCallbacks.pop();
        while (popped) {
          popped();
          popped = self.writeCallbacks.pop();
        }
      }
    });
  }

  switch (sslResult.status) {
    case self.engine.NEED_WRAP:
      writeCleartext(self);
      break;
    case self.engine.NEED_UNWRAP:
      readCiphertext(self);
      break;
    case self.engine.NEED_TASK:
      self.engine.runTask(function() {
        writeCiphertext(self);
      });
      break;
    case self.engine.UNDERFLOW:
    case self.engine.OK:
      break;
    case self.engine.CLOSED:
      self.closed = true;
      self.connection.destroy();
      self.emit('close');
      break;
    case self.engine.ERROR:
      self.emit('error', 'SSL error');
      break;
    default:
      throw 'Unexpected SSL engine status ' + sslResult.status;
  }
  return writeStatus;
}

function emitRawData(self, data) {
  debug('emitBuffer: ' + data.length);
  if (self.decoder) {
    var decoded = self.decoder.write(data);
    if (decoded) {
      debug('emitBuffer: decoded string ' + decoded.length);
      emitBuffer(self, decoded);
    }
  } else {
    emitBuffer(self, data);
  }
}

function emitBuffer(self, buf) {
  self.emit('data', buf);
  if (self.ondata) {
    self.ondata(buf, 0, buf.length);
  }
}

