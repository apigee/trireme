var tls = require('tls');

if (process.argv.length < 3) {
  console.error('Usage: node getcert.js <hostname>');
  return;
}

var conn = tls.connect(443, process.argv[2],
  function(err) {
    if (err) {
      console.error('%j', err);
    } else {
      console.log('%j', conn.getPeerCertificate());
      conn.destroy();
    }
  });
