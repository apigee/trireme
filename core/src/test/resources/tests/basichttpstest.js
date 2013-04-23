var https = require('https');

var options = {
  keystore: 'agent1.jks',
  passphrase: 'secure'
};

var svr = https.createServer(options, function(req, resp) {
  console.log('Got an HTTP request');
  req.on('end', function() {
    resp.end('Hello, World!');
  });
});

svr.listen(33333, function() {
  console.log('listening on 33333');
});

/*
console.log('Server starting to listen');
svr.listen(33333, function() {
  console.log('Server listening');
  http.get('http://localhost:33333/', function(resp) {
    console.log('Got a response with status code ' + resp.statusCode);
    if (resp.statusCode != 200) {
      process.exit(1);
    }
    svr.close();
  });
});
*/
