var tls = require('tls');

if (process.argv.length !== 4) {
  console.error('Usage: node s_client.js <host> <port>');
  process.exit(2);
}

var port = parseInt(process.argv[3]);
var host = process.argv[2];

var conn = tls.connect({
  host: host,
  port: port,
  rejectUnauthorized: false
}, function(err) {
  if (err) {
    console.error('TLS connection error: %s', err);
    process.exit(3);
  }

  conn.setEncoding('utf8');
  conn.on('data', function(msg) {
    console.log('Server said: "%s"', msg);
  });
  conn.on('error', function(err) {
    console.log('Error on connection: %s', err);
  });
  conn.on('end', function() {
    console.log('Connection ended');
    conn.destroy();
    process.exit(4);
  });

  processConn(conn);
});

var NumWrites = 10;
var writeCount = 0;
var sleep = 1000;

function processConn(conn) {
  if (writeCount >= NumWrites) {
    conn.destroy();

  } else {
    conn.write('Hello, Server!', function(err) {
      if (err) {
        console.error('Error writing to server: %s', err);
        conn.destroy();
        process.exit(5);
      } else {
        writeCount++;
        setTimeout(function() {
          processConn(conn);
        }, sleep);
      }
    });
  }
}
