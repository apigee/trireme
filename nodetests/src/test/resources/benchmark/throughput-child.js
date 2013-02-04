var net = require('net');
var received = 0;
var start = process.hrtime();
var socket = net.connect(8000);

socket.on('data', function(d) {
  received += d.length;
});

var interval = setInterval(function() {
  // After 1 gigabyte shutdown.
  if (received > 10 * 1024 * 1024 * 1024) {
    socket.destroy();
    clearInterval(interval);
    process.exit(0);
  } else {
    // Otherwise print some stats.
    var elapsed = process.hrtime(start);
    var sec = elapsed[0] + elapsed[1]/1E9;
    var gigabytes = received / (1024 * 1024 * 1024);
    var gigabits = gigabytes * 8.0;
    console.log((gigabits / sec) + " gbit/sec")
  }
}, 1000);
