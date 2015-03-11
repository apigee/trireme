var http = require('http');

var PORT = process.env.PORT || 8080;

function handleRequest(req, resp) {
  console.log('%s %s', req.method, req.url);

  if (req.method === 'POST') {
    writeEchoResponse(req, resp);
  } else if (req.url === '/test/delay') {
    setTimeout(function() {
      writeResponse(resp);
    }, 1000);
  } else if (req.url === '/test/throw') {
    throw new Error('Oops!');
  } else if (req.url === '/test/exit') {
    process.exit(22);
  } else if (req.url === '/test/swallow') {
    // Do nothing!
  } else {
    writeResponse(resp);
  }
}

function writeResponse(resp) {
  resp.writeHead(200, {
    'CustomHeader': 'foo'
  });
  resp.end('Hello, World!');
}

function writeEchoResponse(req, resp) {
  var msg = '';
  req.setEncoding('utf8');
  req.on('data', function(chunk) {
    msg += chunk;
  });
  req.on('end', function() {
    resp.end(msg);
  });
}

var server = http.createServer(handleRequest);
server.listen(PORT, function() {
  console.log('Listening on %d', PORT);
});
