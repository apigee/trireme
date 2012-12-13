var net = require('net');
var server = net.createServer(function(c) { //'connection' listener
    console.log('server connected');
    c.on('end', function() {
        console.log('server disconnected');
    });
    c.setEncoding('ascii');
    c.on('data', function(msg) {
      console.log('Data: ' + msg);
    });
    c.write('hello\r\n');
    //c.pipe(c);
});

server.listen(8124, function() { //'listening' listener
    console.log('server bound');
});
