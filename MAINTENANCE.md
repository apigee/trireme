* How do to a release:

Be sure that JAVA_HOME is set. On the Mac:

        export JAVA_HOME=`/usr/libexec/java_home -v 1.7`

	mvn -DautoVersionSubmodules=true release:prepare
	mvn release:perform
	mvn release:clean

What this does:

1) Updates all the versions in the POM to a non-snapshot release.
2) Commits the changes and sets a tag on that commit
3) Updates all the versions to a snapshot release.
4) Commits those changes
5) Pushes to git
6) Checks out the tag in a temporary directory
7) Builds there (without running tests)
8) Uploads the artifacts to the repo

** If it doesn't work

You have to have permissions to push to git -- git ref is defined
in the top-level pom.xml and shouldn't change if you have perms.

If you see a "403" error (but not a "409") while doing release:perform:

You have to have permissions to push to the repo (currently
labdt1 on our internal network). You will need to have
~/.m2/settings.xml set up in order to do this like follows:

	<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
	  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      http://maven.apache.org/xsd/settings-1.0.0.xsd">
	 <servers>
	 <server>
	    <id>labdt</id>
	    <username>USERNAME</username>
	    <password>PW</password>
	  </server>
	  </servers>
	</settings>

If the "deploy" task fails part way, then some deployment tasks will return
a "409" (conflict) status. You can fix this by using the Archiva web app to
manually delete the artifacts and try again. I'm not sure of a better way
to do this.

* What to do next.

You need to log in to "oss.sonatype.org". Look for "Staging Repositories"
and find the one named "io.apigee-something".

Check it and press the "Close" button on top. A bunch of validation happens
and you have to wait for it to complete. Eventually, it will be done
and you can press "Promote."

Apparently this can be automated, but it has not been automated yet.

* How to update the license:

mvn -Dyear=2013 license:format
