var net = require('net');
var uuid = require('node-uuid');

var socket;

function TraceLog() {
  this.name = null;
  this.requestId = null;
  this.sequenceNumber = null;
  this.timestamp = null;
  this.message = null;
  this.env = {
    request: {
      method: null,
      url: null,
      headers: null,
      body: null
    },
    response: {
      statusCode: null,
      url: null,
      headers: null,
      body: null
    }
  };
  this.extra = null;
};

function buildPrintTrace(env) {
  return function printTrace(name, message, extra) {
    return;
    var request = env.request;
    var response = env.response;
    var target = env.target;

    var log = new TraceLog();
    log.name = name;
    log.requestId = env.requestId;
    log.sequenceNumber = ++env.sequenceNumber;
    log.timestamp = new Date();
    log.message = message;
    log.env.request.method = request.method;
    log.env.request.url = request.url;
    log.env.request.headers = request.headers;
    log.env.request.body = (request.body && request.body.toString) ? request.body.toString() : null;
    log.env.response.statusCode = response.statusCode;
    log.env.response.url = response.url;
    log.env.response.headers = response.headers;
    log.env.response.body = (response.body && response.body.toString) ? response.body.toString() : null;
    log.extra = extra;

    /*var log = {
      name: name,
      requestId: env.requestId,
      sequenceNumber: ++env.sequenceNumber,
      timestamp: new Date(),
      message: message,
      env: {
        request: {
          method: request.method,
          url: request.url,
          headers: request.headers,
          body: (request.body && request.body.toString) ? request.body.toString() : null
        },
        response: {
          statusCode: response.statusCode,
          url: response.url,
          headers: response._headers,
          body: (response.body && response.body.toString) ? response.body.toString() : null
        }
      }
    };

    if (extra) {
      Object.keys(extra).forEach(function(key) {
        if (extra.hasOwnProperty(key)) {
          log[key] = extra[key];
        }
      });
    }*/
    
    socket.write('TRACE: ' + JSON.stringify(log));

    //console.log('TRACE: ', JSON.stringify(log));
  };
}

module.exports = function addTraceHandler(addHandler) {
  socket = new net.Socket({
    fd: 1,
    type: 'pipe',
    readable: false,
    writable: true,
    allowHalfOpen: true });

  addHandler('request', { hoist: true }, function(env, next) {
    env.requestId = uuid.v4();
    env.sequenceNumber = 0;
    env.printTrace = buildPrintTrace(env);
    env.trace = function(name, cb) {
      var start = +Date.now();
      cb();
      var duration = (+Date.now() - start); 
      var message = 'Duration (' + name + '): ' + duration + 'ms';
      env.printTrace(name, message, { duration: duration });
    };

    next(env);
  });
};
