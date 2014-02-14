if (process.argv.length != 4) {
  console.log('npmupdate: <working dir> <NPM cache dir>');
}

var workingDir = process.argv[2];
var cacheDir = process.argv[3];

console.log('Running NPM update in %s', workingDir);
process.chdir(workingDir);

console.log('Cache is %s', cacheDir);

var npm = require('npm');

var config = { cache: cacheDir };

npm.load(config, function(err, n) {
  if (err) {
    console.log('Error initializing NPM: %s', er);
    return;
  }

  npm.commands.update([], function(err) {
    if (err) {
      console.log('Error running NPM: %s', err);
      process.exit(2);
    }
  });
});
