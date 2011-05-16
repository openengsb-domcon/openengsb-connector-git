openengsb-connector-git-1.2.1 2011-05-16
---------------------------------------------------------------------

Add bundle.info file and allow to change repository urls now

** Bug
    * [OPENENGSB-1222] - git-connector does not allow to change repository URL
    * [OPENENGSB-1573] - bundle.info uses wrong resource-filtering

** Library Upgrade
    * [OPENENGSB-1508] - Push connectors and domains to latest openengsb-framework-1.3.0.M1

** New Feature
    * [OPENENGSB-948] - Add OSGI-INF/bundle.info as used in Karaf to the openengsb bundles

** Task
    * [OPENENGSB-1450] - Release openengsb-connector-git-1.2.1


openengsb-connector-git-1.2.0 2011-04-27
---------------------------------------------------------------------

Initial release of the OpenEngSB Git Connector as standalone package

** Bug
    * [OPENENGSB-1401] - Domains in connctors are referenced by the wrong version
    * [OPENENGSB-1409] - Range missformed

** Library Upgrade
    * [OPENENGSB-1394] - Upgrade to openengsb-1.2.0.RC1
    * [OPENENGSB-1449] - Upgrade to openengsb-domain-scm-1.2.0

** Task
    * [OPENENGSB-1277] - Use slf4j instead of commons-logging in git connector
    * [OPENENGSB-1319] - Adjust all connectors to new ServiceManager-API
    * [OPENENGSB-1378] - Release openengsb-connector-git-1.2.0
    * [OPENENGSB-1396] - Add infrastructure for notice file generation
    * [OPENENGSB-1397] - Add ASF2 license file

