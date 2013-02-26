var https = require('https');

var options = {
  keystore: 'src/test/resources/test/fixtures/keys/agent1.jks',
  passphrase: 'secure'
};

https.createServer(options, function (request, response) {
  console.log('Got HTTP request');
  response.writeHead(200, {'Content-Type': 'text/plain'});
  response.end('Hello World\n');
}).listen(8124);

console.log('Server running at http://127.0.0.1:8124/');
