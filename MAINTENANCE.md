* How do to a release:

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
